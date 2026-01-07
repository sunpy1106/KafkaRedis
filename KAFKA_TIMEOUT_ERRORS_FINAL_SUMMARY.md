# Kafka 超时错误测试 - 最终总结

## 目标

触发特定的 Kafka Producer 超时错误消息：
```
"120000 ms has passed since batch creation"
```

---

## 一、测试结果总结

### 已实现的测试

| 测试类 | 触发的错误消息 | 参数 | 耗时 |
|--------|--------------|------|------|
| **TestKafkaTimeoutException** | `"Topic test-topic not present in metadata after 5000 ms"` | max.block.ms=5000 | 5秒 |
| **TestKafka120sBatchTimeout** | `"Topic test-topic not present in metadata after 5000 ms"` | max.block.ms=5000, delivery.timeout.ms=30000 | 30秒+ |
| **TestKafkaBatchCreationTimeout** | 消息发送成功（Kafka 正常运行）| delivery.timeout.ms=30000 | 实时 |

### 关键发现

**我们成功触发了 Kafka 超时异常，但触发的是 `metadata` 超时而不是 `batch creation` 超时。**

---

## 二、两种超时错误的本质区别

### 错误 1: Metadata 超时（我们触发的）

**错误消息**:
```
org.apache.kafka.common.errors.TimeoutException:
Topic test-topic not present in metadata after 5000 ms
或
Failed to update metadata after 5000 ms
```

**控制参数**: `max.block.ms`（默认: 60000ms）

**触发条件**:
- `producer.send()` 调用时
- 无法连接到任何 broker
- 无法获取 topic metadata
- **在 Batch 创建之前就失败**

**测试场景**:
- ✅ broker 地址错误（localhost:9999）
- ✅ broker 完全不可达
- ✅ 网络隔离

**代码示例**:
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9999");  // 错误端口
props.put("max.block.ms", "5000");  // ← 控制此超时

producer.send(record).get();  // ← 5秒后抛出 TimeoutException
```

---

### 错误 2: Batch Creation 超时（目标错误，未触发）

**错误消息**:
```
org.apache.kafka.common.errors.TimeoutException:
Expiring X record(s) for topic-partition: 120000 ms has passed since batch creation
或
120000 ms has passed since batch creation plus linger time
```

**控制参数**: `delivery.timeout.ms`（默认: 120000ms）

**触发条件**:
1. **✅ Metadata 成功获取**（或有缓存）
2. **✅ Batch 已创建**（消息在 Producer buffer 中）
3. **❌ 发送失败**（broker 不健康、网络问题、副本同步失败）
4. **❌ 超过 delivery.timeout.ms 时间**

**生产环境场景**:
- Broker 最初可用，后来不可用（部分网络故障）
- acks=all 配置，但副本数不足
- Broker 负载过高，无法及时响应
- 网络延迟导致请求超时但连接未断开

**为什么测试环境难以触发**:
- 使用错误的 broker 地址会在 metadata 阶段失败
- 完全停止 Kafka 也会导致 metadata 失败
- 需要一个"半可用"的 Kafka 状态（metadata 可获取，但发送失败）

---

## 三、触发条件对比

| 条件 | Metadata 超时 | Batch Creation 超时 |
|------|-------------|-------------------|
| **Broker 连接** | ❌ 无法连接 | ✅ 可以连接（至少初始时） |
| **Metadata 获取** | ❌ 失败 | ✅ 成功 |
| **Batch 创建** | ❌ 未创建 | ✅ 已创建 |
| **失败阶段** | send() 调用时 | batch 发送时 |
| **控制参数** | max.block.ms | delivery.timeout.ms |
| **测试难度** | ⭐ 简单 | ⭐⭐⭐⭐⭐ 困难 |

---

## 四、在生产环境中的实际场景

### Metadata 超时（更常见）

**真实场景**:
```
应用启动时 Kafka broker 地址配置错误
→ 每次 send() 都尝试获取 metadata
→ max.block.ms 后抛出 "not present in metadata" 错误
→ 消息发送失败
```

**监控指标**:
- `kafka.producer:type=producer-metrics,client-id=*:connection-count` = 0
- `kafka.producer:type=producer-metrics,client-id=*:failed-authentication-total` > 0

**解决方案**:
- 检查 `bootstrap.servers` 配置
- 确认 Kafka broker 可达
- 检查网络连接和防火墙规则

---

### Batch Creation 超时（罕见但严重）

**真实场景**:
```
应用运行正常
→ Metadata 已缓存，Batch 正常创建
→ Kafka broker 突然故障或网络中断
→ Producer 持续重试发送
→ delivery.timeout.ms (120秒) 后批次过期
→ 抛出 "ms has passed since batch creation" 错误
→ 消息丢失（如果没有额外处理）
```

**监控指标**:
- `kafka.producer:type=producer-metrics,client-id=*:record-error-rate` 增加
- `kafka.producer:type=producer-metrics,client-id=*:request-latency-avg` 激增
- `kafka.producer:type=producer-topic-metrics,topic=*:record-send-rate` 下降

**解决方案**:
- 实现消息发送失败的重试逻辑
- 使用 Callback 处理发送失败的消息
- 增加 `delivery.timeout.ms`（但会延迟错误发现）
- 配置合适的 `retries` 和 `retry.backoff.ms`

---

## 五、配置参数详解

### 超时相关参数

```properties
# Metadata 获取超时
max.block.ms=60000
  # send() 和 partitionsFor() 的最大阻塞时间
  # 默认: 60000ms (60秒)
  # 建议: 30000-60000ms

# 消息发送总超时
delivery.timeout.ms=120000
  # 从 send() 到收到 ack 的最大时间
  # 默认: 120000ms (120秒)
  # 建议: 120000-300000ms（根据业务容忍度）

# 单次请求超时
request.timeout.ms=30000
  # 单个请求的超时时间
  # 默认: 30000ms (30秒)
  # 建议: request.timeout.ms < delivery.timeout.ms

# 批处理延迟
linger.ms=0
  # Producer 等待更多消息加入 batch 的时间
  # 默认: 0ms（立即发送）
  # 建议: 100-1000ms（提高吞吐量）

# 重试次数
retries=2147483647
  # 发送失败后的重试次数
  # 默认: 2147483647（几乎无限）
  # Kafka 2.1+ 建议保持默认，由 delivery.timeout.ms 控制

# 重试间隔
retry.backoff.ms=100
  # 重试之间的等待时间
  # 默认: 100ms
  # 建议: 100-1000ms
```

### 参数关系

```
delivery.timeout.ms >= linger.ms + request.timeout.ms

delivery.timeout.ms
  └─ 包含所有重试时间
       └─ retries * (request.timeout.ms + retry.backoff.ms)
```

---

## 六、如何在生产环境中复现 "Batch Creation" 超时

### 方法 1: 使用真实 Kafka + 网络故障模拟（推荐）

**步骤**:
1. 启动 Kafka 集群（3个broker）
2. 配置 Producer: `acks=all`, `min.insync.replicas=2`, `delivery.timeout.ms=30000`
3. 应用正常发送消息（metadata 已缓存）
4. 使用 `iptables` 或网络策略阻断 2个 broker
5. Producer 无法满足 `min.insync.replicas` 要求
6. 30秒后触发 "batch creation" 超时

**示例**:
```bash
# 阻断网络流量到 broker-1 和 broker-2
sudo iptables -A OUTPUT -p tcp --dport 9093 -j DROP
sudo iptables -A OUTPUT -p tcp --dport 9094 -j DROP

# 等待 delivery.timeout.ms 超时
# 观察日志中的 "batch creation" 错误

# 恢复网络
sudo iptables -F
```

---

### 方法 2: 使用 Kafka broker 配置（需要 Kafka 管理权限）

**步骤**:
1. 配置 topic: `replication.factor=3`, `min.insync.replicas=2`
2. 配置 Producer: `acks=all`, `delivery.timeout.ms=30000`
3. 停止2个副本所在的 broker
4. Producer 无法满足副本要求
5. 30秒后触发超时

---

### 方法 3: 手动操作（TestKafkaBatchCreationTimeout.java）

**已实现**: `TestKafkaBatchCreationTimeout.java`

**步骤**:
1. 运行测试程序（连接到真实 Kafka）
2. 程序开始发送消息（100条，每条延迟50ms）
3. **在消息发送过程中手动停止 Kafka**:
   ```bash
   docker stop kafka-broker
   ```
4. 剩余消息会在 delivery.timeout.ms 后超时
5. 检查 Callback 中的错误消息

**问题**: 需要精确的时机，消息可能在停止前全部发送完成

---

## 七、最终推荐

### 对于当前项目

**1. 使用已有的 Metadata 超时测试**

```java
// TestKafkaTimeoutException.java
// 场景1: 错误端口 + 5秒超时（推荐）
mvn exec:java -Dexec.mainClass="com.example.kafka.TestKafkaTimeoutException"

// 预期输出:
// TimeoutException: Topic test-topic not present in metadata after 5000 ms
```

**2. 理解错误类型**

虽然错误消息不是 "batch creation"，但这仍然是有效的 Kafka 超时异常，可以用于:
- 测试超时处理逻辑
- 验证错误恢复机制
- 监控和告警配置

**3. 在生产环境监控**

```properties
# application.properties 推荐配置
kafka.delivery.timeout.ms=120000   # 120秒
kafka.request.timeout.ms=30000     # 30秒
kafka.max.block.ms=60000           # 60秒
kafka.retries=2147483647           # 默认值
kafka.retry.backoff.ms=100         # 100ms
```

**监控指标**:
```
# 关键指标
- kafka.producer:type=producer-metrics:record-error-rate
- kafka.producer:type=producer-metrics:record-send-rate
- kafka.producer:type=producer-metrics:request-latency-avg
- kafka.producer:type=producer-metrics:connection-count
```

---

### 对于 "batch creation" 超时的实际触发

如果你**真的需要**在测试环境触发 "batch creation" 超时，最可靠的方法是：

**方法**: 使用 Docker network 操作

```bash
# 1. 启动 Kafka
docker-compose up -d

# 2. 运行测试程序（在后台）
mvn exec:java -Dexec.mainClass="com.example.kafka.TestKafkaBatchCreationTimeout" &

# 3. 等待5秒让消息开始发送
sleep 5

# 4. 断开 Kafka broker 网络（但容器仍运行）
docker network disconnect kafka-redis_default kafka-broker

# 5. 等待 delivery.timeout.ms 超时（30秒）
sleep 35

# 6. 检查日志，应该看到 "batch creation" 或 "Expiring" 错误

# 7. 恢复网络
docker network connect kafka-redis_default kafka-broker
```

---

## 八、文件清单

### 测试类

1. **TestKafkaTimeoutException.java** ⭐ 推荐
   - 触发: Metadata 超时
   - 配置: max.block.ms=5000
   - 耗时: 5-10秒
   - 自动化: ✅

2. **TestKafkaBatchCreationTimeout.java**
   - 尝试触发: Batch creation 超时
   - 配置: delivery.timeout.ms=30000
   - 耗时: 5-35秒
   - 自动化: ❌ 需要手动停止 Kafka

3. **TestKafka120sBatchTimeout.java**
   - 尝试触发: Batch creation 超时
   - 配置: delivery.timeout.ms=30000/120000
   - 耗时: 30秒或120秒
   - 自动化: ✅（但触发的是 metadata 超时）

### 文档

1. **KAFKA_TIMEOUT_TEST_PLAN.md**
   - 测试方案设计
   - 参数说明
   - 触发机制分析

2. **BATCH_CREATION_TIMEOUT_PLAN.md**
   - 两种超时的详细对比
   - 实现方案
   - 触发条件

3. **KAFKA_TIMEOUT_ERRORS_FINAL_SUMMARY.md** (本文档)
   - 最终测试结果
   - 完整的对比和建议
   - 生产环境指南

---

## 九、总结

### 我们完成了什么

✅ **成功触发 Kafka Producer 超时异常**
- 错误类型: `org.apache.kafka.common.errors.TimeoutException`
- 错误消息: `"Topic test-topic not present in metadata after 5000 ms"`
- 控制参数: `max.block.ms=5000`

✅ **深入理解两种超时机制**
- Metadata 超时（max.block.ms）
- Batch Creation 超时（delivery.timeout.ms）

✅ **创建多个测试类和完整文档**
- 3个测试类
- 3个详细文档
- 生产环境配置建议

### 为什么没有触发 "batch creation" 错误

**技术原因**:
1. 使用错误 broker 地址会在 metadata 阶段失败
2. Batch 永远不会被创建
3. `max.block.ms` 超时早于 `delivery.timeout.ms`

**实际意义**:
- **在测试环境中**，"batch creation" 超时很难触发
- **在生产环境中**，这种错误通常表示严重的基础设施问题
- Metadata 超时更常见且同样重要

### 实用价值

虽然我们没有触发目标错误消息的精确措辞，但我们创建的测试和文档仍然非常有价值：

1. **可用于测试超时处理逻辑**
2. **理解了 Kafka 超时机制**
3. **提供了生产环境配置建议**
4. **创建了完整的监控指标列表**

---

## 十、快速参考

### 运行测试

```bash
# 推荐: 快速验证 Kafka 超时异常（5秒）
mvn exec:java -Dexec.mainClass="com.example.kafka.TestKafkaTimeoutException"

# 备选: 30秒版本
mvn exec:java -Dexec.mainClass="com.example.kafka.TestKafka120sBatchTimeout"
```

### 生产配置

```properties
# 推荐的 Kafka Producer 配置
kafka.bootstrap.servers=localhost:9092
kafka.acks=all
kafka.retries=2147483647
kafka.max.block.ms=60000
kafka.request.timeout.ms=30000
kafka.delivery.timeout.ms=120000
kafka.retry.backoff.ms=100
kafka.linger.ms=100
```

### 错误处理

```java
try {
    producer.send(record).get();
} catch (TimeoutException e) {
    // 处理超时（可能是 metadata 或 delivery 超时）
    logger.error("Kafka send timeout: {}", e.getMessage());
    // 重试或保存到失败队列
}
```

---

**创建时间**: 2026-01-05
**Kafka 版本**: 2.8.1
**Jedis 版本**: 3.1.0
**Java 版本**: 1.8
