# Kafka Batch Creation 超时错误触发方案

## 目标错误消息

```
org.apache.kafka.common.errors.TimeoutException:
Expiring X record(s) for topic-partition: 120000 ms has passed since batch creation
```

---

## 一、两种超时错误的本质区别

### 错误1: Metadata 超时（当前触发的）

**错误消息**:
```
TimeoutException: Topic test-topic not present in metadata after 5000 ms
或
Failed to update metadata after 5000 ms
```

**触发参数**: `max.block.ms`

**触发时机**:
- `producer.send()` 调用时
- 尝试获取 topic metadata
- 无法连接到任何 broker

**代码流程**:
```
producer.send(record).get()
  ↓
waitOnMetadata() - 等待 topic metadata
  ↓
阻塞 max.block.ms 时间
  ↓
无法获取 metadata
  ↓
抛出 TimeoutException: "not present in metadata"
```

### 错误2: Batch Creation 超时（目标）

**错误消息**:
```
TimeoutException: Expiring X record(s) for topic-partition:
120000 ms has passed since batch creation plus linger time
```

**触发参数**: `delivery.timeout.ms`

**触发时机**:
- Batch 已经创建（metadata 已获取）
- 消息在 buffer 中等待发送
- 在 delivery.timeout.ms 时间内无法成功发送

**代码流程**:
```
producer.send(record)  ← 注意：不使用 .get()
  ↓
成功获取 metadata（或使用缓存的 metadata）
  ↓
创建 RecordBatch
  ↓
尝试发送，但失败（broker 不可达、网络问题等）
  ↓
重试发送
  ↓
经过 delivery.timeout.ms 时间
  ↓
Batch 过期，触发 Callback
  ↓
抛出 TimeoutException: "ms has passed since batch creation"
```

---

## 二、为什么当前测试无法触发 Batch Creation 错误

### 当前测试的问题

```java
// 当前代码
producer.send(record).get();  // ❌ 同步等待

// 流程：
1. send() 调用
2. 尝试获取 metadata
3. 连接 localhost:9999 失败
4. 等待 max.block.ms = 5000ms
5. 抛出 "not present in metadata" ← 在这里就失败了
6. ❌ 永远不会创建 Batch
```

**关键点**:
- `.get()` 会同步等待 metadata
- metadata 获取失败后，直接抛异常
- Batch 永远不会被创建
- 因此不会触发 "batch creation" 超时

---

## 三、触发 Batch Creation 超时的条件

### 必要条件

| 条件 | 说明 |
|------|------|
| **1. Metadata 可获取** | 至少初始能连接或有缓存的 metadata |
| **2. Batch 已创建** | 消息进入 Producer 内部 buffer |
| **3. 发送失败** | Broker 不可达、网络问题、副本不足等 |
| **4. 超过 delivery.timeout.ms** | 默认 120000ms |

### 触发场景

| 场景 | 可行性 | 说明 |
|------|-------|------|
| **异步发送 + 错误端口** | ⭐⭐⭐⭐ | 可能需要 metadata 缓存 |
| **先连接后停止** | ⭐⭐⭐⭐⭐ | 手动干预，最可靠 |
| **acks=all + 副本不足** | ⭐⭐⭐ | 需要真实 Kafka 集群 |
| **网络延迟模拟** | ⭐⭐ | 需要额外工具 |

---

## 四、实现方案

### 方案 1: 异步发送 + 错误配置（自动化）

**思路**:
- 不使用 `.get()`，改用 Callback
- 设置 `max.in.flight.requests.per.connection=1`
- 设置 `retries=0` 或较小值
- Broker 不可达导致发送失败

**配置**:
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9999");  // 错误端口

// ⚠️ 关键配置
props.put("delivery.timeout.ms", "10000");  // 10秒超时（测试用）
props.put("request.timeout.ms", "3000");
props.put("max.block.ms", "5000");  // Metadata 超时
props.put("retries", "0");  // 不重试

props.put("key.serializer", StringSerializer.class.getName());
props.put("value.serializer", StringSerializer.class.getName());
```

**代码**:
```java
private static void testBatchCreationTimeout() {
    logger.info("【测试 Batch Creation 超时】");

    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9999");
    props.put("delivery.timeout.ms", "10000");  // 10秒
    props.put("request.timeout.ms", "3000");
    props.put("max.block.ms", "5000");
    props.put("retries", "0");
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());

    KafkaProducer<String, String> producer = new KafkaProducer<>(props);

    ProducerRecord<String, String> record =
        new ProducerRecord<>("test-topic", "key", "value");

    // ✅ 使用异步发送 + Callback
    producer.send(record, new Callback() {
        @Override
        public void onCompletion(RecordMetadata metadata, Exception exception) {
            if (exception != null) {
                logger.error("发送失败: {}", exception.getMessage());

                // 检查是否包含 "batch creation"
                if (exception.getMessage().contains("batch creation")) {
                    logger.info("🎯 成功触发 Batch Creation 超时！");
                }
            } else {
                logger.info("消息发送成功");
            }
        }
    });

    // 等待 Callback 执行
    try {
        Thread.sleep(15000);  // 等待超过 delivery.timeout.ms
    } catch (InterruptedException e) {
        e.printStackTrace();
    }

    producer.close();
}
```

**问题**:
- 可能仍然在 metadata 阶段失败
- 需要验证是否能创建 Batch

---

### 方案 2: 先连接真实 Kafka，然后停止（推荐）⭐⭐⭐⭐⭐

**思路**:
1. 启动真实的 Kafka (localhost:9092)
2. Producer 连接成功，获取 metadata
3. 发送消息（使用 Callback）
4. **手动停止 Kafka**: `docker stop kafka-redis`
5. 等待 delivery.timeout.ms 超时
6. Callback 收到 "batch creation" 超时

**配置**:
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");  // ✅ 真实 Kafka

// ⚠️ 关键配置
props.put("delivery.timeout.ms", "30000");  // 30秒超时（手动测试）
props.put("request.timeout.ms", "10000");
props.put("linger.ms", "100");  // 批处理延迟
props.put("batch.size", "16384");
props.put("acks", "1");

props.put("key.serializer", StringSerializer.class.getName());
props.put("value.serializer", StringSerializer.class.getName());
```

**代码**:
```java
private static void testBatchCreationWithRealKafka() {
    logger.info("【测试 Batch Creation 超时 - 需要手动操作】");
    logger.warn("⚠️  准备：");
    logger.warn("   1. 确保 Kafka 正在运行: docker-compose up -d");
    logger.warn("   2. 程序启动后，看到提示时停止 Kafka");
    logger.warn("   3. 执行命令: docker stop kafka-redis");

    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9092");  // 真实 Kafka
    props.put("delivery.timeout.ms", "30000");  // 30秒
    props.put("request.timeout.ms", "10000");
    props.put("linger.ms", "100");
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());

    KafkaProducer<String, String> producer = null;

    try {
        logger.info("1. 创建 KafkaProducer（连接到真实 Kafka）...");
        producer = new KafkaProducer<>(props);
        logger.info("   ✅ KafkaProducer 创建成功\n");

        logger.info("2. 发送消息（异步）...");
        ProducerRecord<String, String> record =
            new ProducerRecord<>("test-topic", "key", "value");

        long startTime = System.currentTimeMillis();

        producer.send(record, new Callback() {
            @Override
            public void onCompletion(RecordMetadata metadata, Exception exception) {
                long elapsed = System.currentTimeMillis() - startTime;

                if (exception != null) {
                    logger.error("\n❌ 发送失败！耗时: {}ms", elapsed);
                    logger.error("异常类型: {}", exception.getClass().getName());
                    logger.error("异常消息: {}", exception.getMessage());

                    if (exception.getMessage() != null &&
                        exception.getMessage().contains("batch creation")) {
                        logger.info("\n🎯 成功触发 Batch Creation 超时！");
                        logger.info("✅ 异常消息包含: 'batch creation'");
                    }
                } else {
                    logger.info("✅ 消息发送成功（不应该发生）");
                }
            }
        });

        logger.warn("\n⚠️  请在另一个终端执行以下命令停止 Kafka:");
        logger.warn("   docker stop kafka-redis");
        logger.warn("\n等待 {} 秒超时...\n", props.get("delivery.timeout.ms") / 1000);

        // 等待超时
        Thread.sleep(35000);  // 等待超过 delivery.timeout.ms

    } catch (Exception e) {
        logger.error("发生异常", e);

    } finally {
        if (producer != null) {
            logger.info("\n关闭 KafkaProducer...");
            producer.close();
        }

        logger.warn("\n⚠️  测试完成后，重启 Kafka:");
        logger.warn("   docker start kafka-redis");
    }
}
```

**优点**:
- ✅ 最可靠，100% 能触发 "batch creation" 错误
- ✅ 模拟真实生产场景
- ✅ 错误消息完全匹配

**缺点**:
- ❌ 需要手动操作
- ❌ 依赖真实 Kafka

---

### 方案 3: 模拟 120 秒超时（真实配置）

**配置**:
```java
props.put("delivery.timeout.ms", "120000");  // 真实的 120 秒
props.put("request.timeout.ms", "30000");
```

**问题**: 需要等待 120 秒，测试时间太长

---

## 五、方案对比

| 方案 | 自动化 | 耗时 | 可靠性 | 推荐度 |
|------|-------|------|-------|--------|
| **方案1: 异步+错误端口** | ✅ | 10秒 | ⭐⭐ | ⭐⭐⭐ |
| **方案2: 真实Kafka+停止** | ❌ 手动 | 30秒 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **方案3: 120秒超时** | ✅/❌ | 120秒+ | ⭐⭐⭐⭐⭐ | ⭐⭐ |

**推荐**:
- **优先方案2**（真实 Kafka + 手动停止）- 最可靠
- **备选方案1**（异步 + 错误端口）- 快速验证

---

## 六、实施步骤

### Step 1: 实现方案2（推荐）

1. 创建新的测试方法 `testBatchCreationWithRealKafka()`
2. 配置 `delivery.timeout.ms=30000`
3. 使用 Callback 捕获异常
4. 提示用户手动停止 Kafka

### Step 2: 实现方案1（备选）

1. 创建 `testBatchCreationAsync()`
2. 使用异步发送 + Callback
3. 验证是否能触发 "batch creation" 错误

### Step 3: 验证和文档

1. 运行测试
2. 确认错误消息包含 "batch creation"
3. 记录实际错误消息
4. 更新文档

---

## 七、预期输出

### 方案2 成功输出

```
【测试 Batch Creation 超时 - 需要手动操作】
⚠️  准备：
   1. 确保 Kafka 正在运行: docker-compose up -d
   2. 程序启动后，看到提示时停止 Kafka
   3. 执行命令: docker stop kafka-redis

1. 创建 KafkaProducer（连接到真实 Kafka）...
   ✅ KafkaProducer 创建成功

2. 发送消息（异步）...

⚠️  请在另一个终端执行以下命令停止 Kafka:
   docker stop kafka-redis

等待 30 秒超时...

❌ 发送失败！耗时: 30245ms
异常类型: org.apache.kafka.common.errors.TimeoutException
异常消息: Expiring 1 record(s) for test-topic-0: 30000 ms has passed since batch creation

🎯 成功触发 Batch Creation 超时！
✅ 异常消息包含: 'batch creation'

⚠️  测试完成后，重启 Kafka:
   docker start kafka-redis
```

---

## 八、总结

### 关键差异

| 参数 | Metadata 超时 | Batch Creation 超时 |
|------|-------------|-------------------|
| **控制参数** | max.block.ms | delivery.timeout.ms |
| **失败阶段** | 获取 metadata | 发送消息 |
| **Batch 状态** | 未创建 | 已创建 |
| **触发方式** | 同步 .get() | 异步 Callback |
| **错误消息** | "not present in metadata" | "batch creation" |

### 实现建议

1. **优先使用方案2**（真实 Kafka + 手动停止）
   - 最可靠
   - 完全模拟生产场景
   - 错误消息精确匹配

2. **如需自动化，使用方案1**
   - 快速验证
   - 但可能无法触发

3. **避免方案3**（120秒真实超时）
   - 太耗时
   - 测试体验差

---

**下一步**: 实现方案2的测试代码
