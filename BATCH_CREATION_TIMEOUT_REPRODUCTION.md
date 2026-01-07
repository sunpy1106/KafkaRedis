# Kafka Batch Creation 超时复现指南

**日期:** 2026-01-07
**目标错误:** `TimeoutException: Expiring X record(s) for test-topic-0: X ms has passed since batch creation`

---

## 一、背景

### 1.1 问题描述

Kafka Producer 在生产环境中偶尔会出现以下错误：

```
org.apache.kafka.common.errors.TimeoutException:
Expiring 20 record(s) for test-topic-0: 30002 ms has passed since batch creation
```

这个错误表示消息在 Producer 的 batch 缓冲区中等待了超过 `delivery.timeout.ms` 时间仍未成功发送。

### 1.2 触发条件

要触发此错误，需要同时满足：

1. **Metadata 已缓存** - 不能在 metadata 阶段就失败
2. **Batch 已创建** - 消息已进入 Producer 缓冲区
3. **无法收到 ACK** - Broker 无法响应（网络问题/磁盘慢/容器暂停等）
4. **超过 delivery.timeout.ms** - 等待时间超过配置的超时时间

### 1.3 之前失败的尝试

| 方法 | 结果 | 原因 |
|------|------|------|
| 错误的 broker 地址 | 触发 metadata 超时 | Batch 未创建 |
| 完全停止 Kafka | 触发连接错误 | 不是 batch 超时 |
| dd 制造 I/O 压力 | 消息仍成功/Kafka 完全无响应 | 窗口太窄 |
| iptables DROP | 触发网络错误 | 不是 batch 超时 |

---

## 二、成功复现的三种方法

### 2.1 方法一：Docker Pause（推荐）

**原理：** 暂停容器但保持 TCP 连接，模拟"灰色故障"状态。

**测试类：** `TestBatchCreationDockerPauseV2.java`

**运行命令：**
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationDockerPauseV2"
```

**核心流程：**
```
1. 创建 KafkaProducer
2. 发送预热消息 → 缓存 metadata
3. docker pause kafka-broker → 容器暂停
4. 发送测试消息 → 消息进入 batch
5. 等待 30 秒 → delivery.timeout.ms 超时
6. 触发错误: "X ms has passed since batch creation"
7. docker unpause kafka-broker → 恢复容器
```

**关键代码：**
```java
// 暂停容器
Runtime.getRuntime().exec("docker pause kafka-broker");

// 发送消息（会进入 batch 缓冲区）
producer.send(record, callback);

// 恢复容器
Runtime.getRuntime().exec("docker unpause kafka-broker");
```

**优点：**
- 简单可靠，100% 复现
- 无需额外工具
- 自动化程度高

---

### 2.2 方法二：Cgroup I/O 限速

**原理：** 使用 cgroup v2 的 `io.max` 限制容器磁盘写入速度，模拟慢盘。

**测试类：** `TestBatchCreationIOThrottle.java`

**运行命令：**
```bash
# 需要 root 权限
mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationIOThrottle"
```

**核心流程：**
```
1. 创建 KafkaProducer
2. 发送预热消息 → 缓存 metadata
3. 设置 I/O 限速 → echo "8:0 wbps=1024" > io.max
4. 发送测试消息 → fsync 被阻塞
5. 等待 30 秒 → 超时
6. 触发错误
7. 清理限速 → echo "" > io.max
```

**关键命令：**
```bash
# 找到容器的 cgroup 路径
CGROUP_PATH=/sys/fs/cgroup/system.slice/docker-<container_id>.scope

# 设置写入速度限制为 1KB/s (8:0 是 sda 设备号)
echo "8:0 wbps=1024" > $CGROUP_PATH/io.max

# 清理限速
echo "" > $CGROUP_PATH/io.max
```

**优点：**
- 精确控制 I/O 速度
- 真实模拟慢盘场景
- 可调节限速参数

---

### 2.3 方法三：FIO 磁盘压力

**原理：** 使用 fio 在 Kafka 数据目录制造大量同步写入，使磁盘 I/O 饱和。

**测试类：** `TestBatchCreationFioSlowDisk.java`

**运行命令：**
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationFioSlowDisk"
```

**核心流程：**
```
1. 创建 KafkaProducer
2. 发送预热消息 → 缓存 metadata
3. 启动 fio 压力 → 磁盘 I/O 饱和
4. 等待 5 秒让压力生效
5. 发送测试消息 → Kafka fsync 阻塞
6. 等待超时 → 触发错误
7. 停止 fio，清理测试文件
```

**FIO 参数：**
```bash
fio --name=kafka-stress \
    --directory=/var/lib/docker/volumes/kafkaredis_kafka_data/_data \
    --rw=randwrite \        # 随机写
    --bs=4k \               # 4K 块大小
    --size=500M \           # 每个作业写 500MB
    --numjobs=8 \           # 8 个并发作业
    --iodepth=64 \          # I/O 队列深度
    --direct=1 \            # 绕过页面缓存
    --fsync=1 \             # 每次写入都 fsync
    --time_based \
    --runtime=120           # 运行 120 秒
```

**优点：**
- 最接近真实慢盘场景
- 可模拟不同程度的磁盘压力
- 行业标准的 I/O 测试工具

---

## 三、关键配置

### 3.1 Producer 配置

```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:19092");
props.put("key.serializer", StringSerializer.class.getName());
props.put("value.serializer", StringSerializer.class.getName());

// 关键超时配置
props.put("delivery.timeout.ms", "30000");  // 30秒，控制 batch 超时
props.put("request.timeout.ms", "10000");   // 10秒，单次请求超时
props.put("max.block.ms", "10000");         // 10秒，metadata 获取超时

// ACK 配置
props.put("acks", "all");  // 需要所有 ISR 副本确认

// Batch 配置
props.put("linger.ms", "500");      // 500ms 延迟，让消息积累成 batch
props.put("batch.size", "16384");   // 16KB batch 大小

// 重试配置
props.put("retries", "3");
props.put("retry.backoff.ms", "1000");
```

### 3.2 Docker Compose 配置

```yaml
kafka:
  image: confluentinc/cp-kafka:7.5.0
  container_name: kafka-broker
  ports:
    - "19092:9092"
  environment:
    KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,PLAINTEXT_INTERNAL://0.0.0.0:9093
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:19092,PLAINTEXT_INTERNAL://kafka:9093
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_INTERNAL:PLAINTEXT
    # 强制频繁 flush（增加触发概率）
    KAFKA_LOG_FLUSH_INTERVAL_MESSAGES: 1
    KAFKA_LOG_FLUSH_INTERVAL_MS: 100
```

---

## 四、测试结果

### 4.1 Docker Pause 方法

```
╔════ 消息 #1 发送失败 ════╗
║ 耗时: 30004ms (30.0秒)
║ 类型: TimeoutException
║ 消息: Expiring 20 record(s) for test-topic-0:30002 ms has passed since batch creation
╠════════════════════════════════════════╣
║ 🎯🎯🎯 成功触发 Batch Creation 超时！  ║
╚════════════════════════════════════════╝
```

### 4.2 Cgroup I/O 限速方法

```
╔════ 消息 #1 发送失败 ════╗
║ 耗时: 30007ms (30.0秒)
║ 类型: TimeoutException
║ 消息: Expiring 10 record(s) for test-topic-0:30004 ms has passed since batch creation
╠════════════════════════════════════════╣
║ 🎯🎯🎯 成功触发 Batch Creation 超时！  ║
╚════════════════════════════════════════╝
```

### 4.3 FIO 压力方法

```
╔════ 消息 #2 发送失败 ════╗
║ 耗时: 46849ms (46.8秒)
║ 类型: TimeoutException
║ 消息: Expiring 19 record(s) for test-topic-0:30008 ms has passed since batch creation
╠════════════════════════════════════════╣
║ 🎯🎯🎯 成功触发 Batch Creation 超时！  ║
╚════════════════════════════════════════╝
```

---

## 五、方法选择建议

| 场景 | 推荐方法 | 原因 |
|------|---------|------|
| 快速验证 | Docker Pause | 简单、可靠、无依赖 |
| CI/CD 集成 | Docker Pause | 易于自动化 |
| 模拟真实慢盘 | FIO 压力 | 最接近生产环境 |
| 精确控制测试 | Cgroup I/O 限速 | 可精确调节参数 |
| 无 root 权限 | Docker Pause | 不需要系统权限 |

---

## 六、文件清单

### 测试类

| 文件 | 说明 |
|------|------|
| `TestBatchCreationDockerPauseV2.java` | Docker Pause 方法 |
| `TestBatchCreationIOThrottle.java` | Cgroup I/O 限速方法 |
| `TestBatchCreationFioSlowDisk.java` | FIO 磁盘压力方法 |
| `TestBatchCreationAutoFault.java` | 自动化故障注入（tc/iptables） |

### 配置文件

| 文件 | 说明 |
|------|------|
| `docker-compose.yml` | Docker 环境配置 |
| `application.properties` | Kafka/Redis 连接配置 |

---

## 七、总结

### 7.1 关键发现

1. **"灰色故障"是关键** - 需要让 Kafka 能接受连接但无法响应
2. **Metadata 必须先成功** - 否则会触发 metadata 超时
3. **Docker Pause 最可靠** - 精确模拟连接保持但不响应的状态
4. **慢盘可以复现** - 使用 cgroup 或 fio 限制/压满 I/O

### 7.2 错误消息解读

```
Expiring 20 record(s) for test-topic-0: 30002 ms has passed since batch creation
         ↑                    ↑              ↑
    过期的消息数        topic-partition   自 batch 创建以来的时间
```

- `delivery.timeout.ms` 控制这个超时时间
- 当消息在 batch 中等待超过此时间未收到 ACK，就会触发此错误

### 7.3 生产环境建议

1. **监控** - 关注 `record-error-rate` 指标
2. **告警** - 对 "batch creation" 错误设置告警
3. **配置** - 根据业务容忍度调整 `delivery.timeout.ms`
4. **重试** - 在 Callback 中实现消息重试逻辑

---

**文档版本:** 1.0
**最后更新:** 2026-01-07
