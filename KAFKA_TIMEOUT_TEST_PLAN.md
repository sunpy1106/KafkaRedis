# Kafka 超时错误测试方案

## 目标错误

```
"120000 ms has passed since batch creation"
或
TimeoutException: Failed to update metadata after 120000 ms
```

---

## 一、错误分析

### 错误来源

这个错误来自 **Kafka Producer** 的 `max.block.ms` 配置参数。

```java
// KafkaProducer 配置
props.put("max.block.ms", "120000");  // 120秒超时
```

### 触发条件

| 条件 | 说明 |
|------|------|
| **Kafka broker 不可用** | 无法连接到 broker |
| **Metadata 更新失败** | 无法获取 topic metadata |
| **Buffer 满** | Producer 内存缓冲区已满 |
| **网络问题** | 网络延迟或中断 |

### 关键配置参数

```properties
# Kafka Producer 超时相关配置
max.block.ms=120000              # ⚠️ 关键：等待 metadata/buffer 的最大时间
request.timeout.ms=30000         # 单次请求超时
delivery.timeout.ms=120000       # 消息发送总超时
```

---

## 二、当前项目配置分析

### application.properties

```properties
kafka.bootstrap.servers=localhost:9092
kafka.topic=message-topic
kafka.acks=all
kafka.retries=3
kafka.batch.size=16384
kafka.linger.ms=1
kafka.buffer.memory=33554432

# ⚠️ 缺少 max.block.ms 配置
# 默认值: 60000ms (60秒)
```

### KafkaProducerService.java

```java
// 当前实现没有设置 max.block.ms
props.put("bootstrap.servers", fileProps.getProperty("kafka.bootstrap.servers"));
props.put("acks", fileProps.getProperty("kafka.acks", "all"));
props.put("retries", fileProps.getProperty("kafka.retries", "3"));
// ... 其他配置

// ⚠️ 缺少:
// props.put("max.block.ms", "120000");
```

---

## 三、测试方案设计

### 方案对比

| 方案 | 触发方式 | 耗时 | 自动化 | 推荐度 |
|------|---------|------|-------|--------|
| **1. 错误端口** | bootstrap.servers=localhost:9999 | 5-10秒 | ✅ | ⭐⭐⭐⭐⭐ |
| 2. 不可达主机 | bootstrap.servers=192.0.2.1:9092 | 120秒+ | ✅ | ⭐⭐⭐ |
| 3. Kafka 停止 | 先启动后停止 | 120秒+ | ❌ 手动 | ⭐⭐ |

### 推荐方案：错误端口（方案1）

**配置**:
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9999");  // 错误端口
props.put("max.block.ms", "5000");                 // 5秒超时（快速测试）
props.put("request.timeout.ms", "3000");
```

**流程**:
```
1. 创建 KafkaProducer，连接到 localhost:9999
2. 尝试发送消息
3. Producer 尝试获取 metadata
4. 连接失败，无法获取 metadata
5. 等待 max.block.ms (5000ms)
6. 抛出 TimeoutException
```

**预期输出**:
```
org.apache.kafka.common.errors.TimeoutException:
Failed to update metadata after 5000 ms
```

---

## 四、实现方案

### 测试类 1: TestKafkaTimeoutException（推荐）

**场景**: 错误端口 + 短超时时间

```java
/**
 * 场景1: 错误端口，快速触发超时
 */
private static void testInvalidPort() {
    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9999");  // 错误端口
    props.put("max.block.ms", "5000");                 // 5秒超时
    props.put("request.timeout.ms", "3000");
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());

    KafkaProducer<String, String> producer = new KafkaProducer<>(props);

    try {
        // 尝试发送消息
        ProducerRecord<String, String> record =
            new ProducerRecord<>("test-topic", "key", "value");

        producer.send(record).get();  // ❌ 超时

    } catch (TimeoutException e) {
        // ✅ 成功触发
        System.out.println("异常类型: " + e.getClass().getName());
        System.out.println("异常消息: " + e.getMessage());
    }
}
```

### 测试类 2: TestKafkaTimeout120s

**场景**: 模拟真实的 120 秒超时

```java
/**
 * 场景2: 模拟真实的 120 秒超时（不推荐，太慢）
 */
private static void testRealTimeout() {
    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9999");
    props.put("max.block.ms", "120000");  // 120秒（真实配置）

    // ... 同上
    // 耗时: 120+ 秒
}
```

---

## 五、详细实现步骤

### Step 1: 创建测试类骨架

```java
package com.example.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class TestKafkaTimeoutException {
    private static final Logger logger = LoggerFactory.getLogger(TestKafkaTimeoutException.class);

    public static void main(String[] args) {
        // 场景1: 错误端口 (5秒)
        testInvalidPortQuick();

        // 场景2: 不可达主机 (5秒)
        testUnreachableHost();

        // 场景3: 真实 120 秒超时 (可选)
        // testRealTimeout120s();
    }
}
```

### Step 2: 实现场景1 - 错误端口（5秒）

```java
private static void testInvalidPortQuick() {
    logger.info("【场景1】错误端口 + 5秒超时");

    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9999");
    props.put("max.block.ms", "5000");
    props.put("request.timeout.ms", "3000");
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());

    KafkaProducer<String, String> producer = null;

    try {
        producer = new KafkaProducer<>(props);
        logger.info("KafkaProducer 创建成功");

        ProducerRecord<String, String> record =
            new ProducerRecord<>("test-topic", "test-key", "test-value");

        long startTime = System.currentTimeMillis();
        logger.info("开始发送消息...");

        // 同步发送（会阻塞直到超时）
        producer.send(record).get();

        logger.info("消息发送成功（不应该发生）");

    } catch (TimeoutException e) {
        long elapsed = System.currentTimeMillis() - startTime;
        logger.error("❌ 超时异常！耗时: {}ms", elapsed);
        logger.error("异常类型: {}", e.getClass().getName());
        logger.error("异常消息: {}", e.getMessage());

        if (e.getMessage().contains("metadata") ||
            e.getMessage().contains("batch creation")) {
            logger.info("🎯 成功触发 Kafka 超时异常！");
        }

    } catch (Exception e) {
        logger.error("其他异常", e);

    } finally {
        if (producer != null) {
            producer.close();
        }
    }
}
```

### Step 3: 实现场景2 - 不可达主机

```java
private static void testUnreachableHost() {
    logger.info("\n【场景2】不可达主机 + 5秒超时");

    Properties props = new Properties();
    props.put("bootstrap.servers", "192.0.2.1:9092");  // 不可达IP
    props.put("max.block.ms", "5000");
    props.put("request.timeout.ms", "3000");
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());

    // ... 同场景1
}
```

### Step 4: 实现场景3 - 真实 120 秒超时（可选）

```java
private static void testRealTimeout120s() {
    logger.info("\n【场景3】真实 120 秒超时");
    logger.warn("⚠️  此测试需要 120+ 秒，请耐心等待");

    Properties props = new Properties();
    props.put("bootstrap.servers", "localhost:9999");
    props.put("max.block.ms", "120000");  // 120秒
    props.put("request.timeout.ms", "30000");
    props.put("key.serializer", StringSerializer.class.getName());
    props.put("value.serializer", StringSerializer.class.getName());

    // ... 同场景1
}
```

---

## 六、预期输出

### 场景1: 错误端口（5秒）

```
【场景1】错误端口 + 5秒超时
KafkaProducer 创建成功
开始发送消息...
❌ 超时异常！耗时: 5023ms
异常类型: org.apache.kafka.common.errors.TimeoutException
异常消息: Failed to update metadata after 5000 ms
🎯 成功触发 Kafka 超时异常！
```

### 场景2: 不可达主机（5秒）

```
【场景2】不可达主机 + 5秒超时
KafkaProducer 创建成功
开始发送消息...
❌ 超时异常！耗时: 5018ms
异常类型: org.apache.kafka.common.errors.TimeoutException
异常消息: Failed to update metadata after 5000 ms
🎯 成功触发 Kafka 超时异常！
```

### 场景3: 真实 120 秒超时

```
【场景3】真实 120 秒超时
⚠️  此测试需要 120+ 秒，请耐心等待
KafkaProducer 创建成功
开始发送消息...
... 等待 120 秒 ...
❌ 超时异常！耗时: 120045ms
异常类型: org.apache.kafka.common.errors.TimeoutException
异常消息: Failed to update metadata after 120000 ms
或
异常消息: 120000 ms has passed since batch creation
🎯 成功触发 Kafka 超时异常！
```

---

## 七、关键配置参数说明

### max.block.ms

**作用**: Producer 等待以下操作的最大时间
- 获取 topic metadata
- 等待 buffer 空间
- 序列化 key/value

**默认值**: 60000ms (60秒)

**推荐值**:
- 测试: 5000ms (5秒) - 快速验证
- 生产: 30000-60000ms - 根据网络情况

### request.timeout.ms

**作用**: 单次请求的最大等待时间

**默认值**: 30000ms (30秒)

**关系**:
```
max.block.ms >= request.timeout.ms
```

### delivery.timeout.ms

**作用**: 消息发送的总超时时间（包括重试）

**默认值**: 120000ms (120秒)

**关系**:
```
delivery.timeout.ms >= linger.ms + request.timeout.ms
```

---

## 八、触发机制流程图

```
Producer.send(record)
  ↓
需要获取 topic metadata
  ↓
尝试连接 bootstrap.servers
  ↓
连接失败（端口错误/主机不可达）
  ↓
等待 max.block.ms
  ↓
超时
  ↓
抛出 TimeoutException:
  "Failed to update metadata after {max.block.ms} ms"
  或
  "{max.block.ms} ms has passed since batch creation"
```

---

## 九、运行测试

### 编译

```bash
mvn clean compile
```

### 运行测试

```bash
# 场景1 + 场景2 (推荐，约 10 秒)
mvn exec:java -Dexec.mainClass="com.example.kafka.TestKafkaTimeoutException"

# 如果包含场景3（120秒），需要等待约 130 秒
```

---

## 十、对比：不同超时配置

| max.block.ms | 适用场景 | 优点 | 缺点 |
|-------------|---------|------|------|
| **5000** (5秒) | 测试验证 | 快速触发，节省时间 | 不真实 |
| 60000 (60秒) | 生产环境默认 | 平衡性能和可靠性 | 较长等待 |
| **120000** (120秒) | 高延迟网络 | 更宽容，减少假超时 | 故障发现慢 |

---

## 十一、总结

### 实现方案选择

| 方案 | 推荐度 | 理由 |
|------|-------|------|
| **场景1: 错误端口 + 5秒** | ⭐⭐⭐⭐⭐ | 最快、自动化、稳定 |
| 场景2: 不可达主机 + 5秒 | ⭐⭐⭐⭐ | 补充场景 |
| 场景3: 真实 120 秒 | ⭐⭐ | 太慢，不推荐 |

### 关键要点

1. **max.block.ms** 是触发超时的关键参数

2. **推荐测试配置**: 5000ms（5秒）
   - 快速验证
   - 减少等待时间

3. **触发方式**:
   - 错误端口（最简单）
   - 不可达主机
   - Kafka 停止（需手动）

4. **实际异常消息可能**:
   - "Failed to update metadata after {time} ms"
   - "{time} ms has passed since batch creation"

---

**下一步**: 实现 `TestKafkaTimeoutException.java`
