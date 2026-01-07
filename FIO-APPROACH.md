# fio-Based Slow Disk Simulation for Kafka Batch Creation Timeout

## 概述 (Overview)

这是一个基于 **fio (Flexible I/O Tester)** 的磁盘慢速模拟方案，用于触发 Kafka Producer 的 "batch creation" 超时错误。

本方案基于华为 ALM-12033 告警的 svctm (service time) 检测标准设计，比之前的 dd 命令方案更加精确和可控。

## 为什么选择 fio？(Why fio?)

### 相比之前方法的优势

| 方法 | 问题 | fio 的改进 |
|------|------|-----------|
| **20个dd进程** | I/O压力过大，Kafka无法响应连接 | 精确控制 iodepth、numjobs 参数，可调节压力 |
| **磁盘填满** | Kafka预分配日志段，写入仍成功 | 直接制造 I/O 延迟，而非空间问题 |
| **TCP代理** | 应用层控制，无法模拟磁盘慢 | 真实的底层 I/O 压力 |
| **Docker pause** | 完全暂停进程，触发连接错误 | 仅减慢磁盘，Kafka 仍在运行 |

### 核心优势

1. **可测量的成功标准**: 监控 svctm 指标，明确知道是否达到目标
2. **精确控制**: 通过 fio 参数精细调节 I/O 压力强度
3. **专业工具**: fio 是行业标准的 I/O 性能测试工具
4. **可重复性**: 配置文件化，测试结果可重现

## ALM-12033 慢盘检测标准

### svctm 计算公式

```
svctm = (total_ticks_spent_on_io) / (total_io_operations_completed)
```

从 `/proc/diskstats` 读取:
```
svctm = (tot_ticks_new - tot_ticks_old) / (rd_ios_new + wr_ios_new - rd_ios_old - wr_ios_old)
```

### 告警触发条件

**HDD (机械硬盘):**
- **严重**: svctm > 1000ms 持续30秒，在300秒窗口内出现7次
- **警告**: svctm > 150ms 持续30秒，占50%以上的检测周期

**SSD (固态硬盘):**
- **严重**: svctm > 1000ms 持续30秒，在300秒窗口内出现7次
- **警告**: svctm > 20ms 持续30秒，占50%以上的检测周期

## fio 参数与 svctm 的关系

### 关键参数说明

| fio 参数 | 对 svctm 的影响 | 推荐值 |
|----------|----------------|--------|
| `iodepth` | I/O 队列深度，越大排队时间越长 | 64-128 |
| `numjobs` | 并发作业数，增加 I/O 竞争 | 4-8 |
| `bs` (block size) | 小块产生更多 I/O 操作 | 4K-16K |
| `rw` | 随机 I/O 比顺序慢 | randrw, randwrite |
| `fsync` | 每 N 次操作强制刷盘 | 1-10 |
| `direct=1` | 绕过缓存，真实磁盘性能 | 1 |
| `sync=1` | 同步 I/O，等待完成 | 1 |
| `size` | 测试文件大小 | 2G-4G |

### 配置策略

**moderate 配置 (目标 svctm 150-300ms):**
```ini
[slow-disk-hdd]
bs=4k
iodepth=64
numjobs=4
rw=randrw
fsync=5
```

**extreme 配置 (目标 svctm > 1000ms):**
```ini
[slow-disk-extreme]
bs=4k
iodepth=128
numjobs=8
rw=randwrite
fsync=1
```

## 文件说明

### 1. fio 配置文件

- **fio-slow-disk-hdd.fio**: 模拟 HDD 慢盘 (svctm 150-300ms)
- **fio-slow-disk-extreme.fio**: 模拟极端慢盘 (svctm > 1000ms)

### 2. 监控脚本

- **monitor-svctm.sh**: 实时监控 svctm 和其他 I/O 指标
  ```bash
  ./monitor-svctm.sh [device] [interval_seconds]
  ```

### 3. 自动化测试脚本

- **run-fio-kafka-test.sh**: 一键运行完整测试流程
  ```bash
  ./run-fio-kafka-test.sh [fio-config-file]
  ```

## 使用指南

### 快速开始

```bash
# 方法1: 使用自动化脚本（推荐）
./run-fio-kafka-test.sh fio-slow-disk-hdd.fio

# 方法2: 手动步骤
# 1. 确保 Kafka 容器运行
docker-compose up -d

# 2. 在容器中安装 fio
docker exec -u root kafka-broker bash -c "apt-get update && apt-get install -y fio"

# 3. 复制 fio 配置到容器
docker cp fio-slow-disk-hdd.fio kafka-broker:/tmp/fio-test.fio

# 4. 在后台运行 fio
docker exec -d kafka-broker fio /tmp/fio-test.fio

# 5. 等待30秒让 I/O 压力建立
sleep 30

# 6. 运行 Kafka 测试
mvn compile exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationProgressiveIO"

# 7. 清理
docker exec kafka-broker pkill fio
docker exec kafka-broker rm -f /var/lib/kafka/data/fio-test*.dat
```

### 监控 svctm 指标

**Linux 系统:**
```bash
# 方法1: 使用提供的监控脚本
./monitor-svctm.sh sda 5

# 方法2: 使用 iostat
iostat -x 5

# 方法3: 直接读取 /proc/diskstats
watch -n 5 'cat /proc/diskstats | head -10'
```

**macOS 系统:**
```bash
# macOS 的 iostat 不提供 svctm，需要在容器内监控
docker exec kafka-broker iostat -x 5
```

### 调整测试强度

如果第一次测试结果不理想：

**情况1: 所有消息成功，未触发超时**
- 原因: I/O 压力不够
- 解决: 使用更激进的配置
  ```bash
  ./run-fio-kafka-test.sh fio-slow-disk-extreme.fio
  ```
- 或者手动调整参数: 增加 `iodepth` 和 `numjobs`

**情况2: Kafka 无法连接**
- 原因: I/O 压力过大
- 解决: 减小压力参数
  ```bash
  # 编辑 fio-slow-disk-hdd.fio
  iodepth=32  # 从 64 减到 32
  numjobs=2   # 从 4 减到 2
  ```

**情况3: 触发了其他错误(非 batch creation)**
- 原因: 可能是连接超时、网络错误等
- 解决: 检查 Kafka 配置，增加超时时间

## 测试原理

### 工作流程

```
1. Kafka 正常运行
   ↓
2. fio 在后台制造 I/O 压力
   ↓
3. svctm 指标升高 (150ms - 1000ms+)
   ↓
4. Kafka Producer 发送消息
   ↓
5. Broker fsync() 操作被延迟
   ↓
6. Producer 等待 ACK 超时
   ↓
7. 触发 "batch creation" timeout
```

### 为什么 fio 能成功？

**关键点**: 创建"灰色故障"(Gray Failure)状态

- ✅ **网络正常**: Producer 能连接到 Broker
- ✅ **Broker 运行**: Kafka 进程正常，能接收消息
- ❌ **磁盘缓慢**: fsync() 操作极慢，但不是完全失败
- ❌ **ACK 延迟**: Broker 无法及时返回确认

这正是 batch creation timeout 发生的典型场景！

## 预期结果

### 成功触发的标志

在 Kafka Producer 日志中看到:

```
❌ 消息 #X 发送失败！
异常消息: X ms has passed since batch creation plus linger time
```

或者相关的超时消息:
```
Expiring 1 record(s) for test-topic-0: X ms has passed since batch creation
```

### 监控指标

在 fio 运行时应观察到:

```
svctm(ms)  await(ms)  avgqu-sz  %util
---------------------------------------
  250.00     280.50      8.50    95.00  ⚠️  WARNING: svctm > 150ms (HDD threshold)
  1200.00    1350.20    32.10    99.50  🚨 CRITICAL: svctm > 1000ms
```

## 故障排查

### fio 无法启动

```bash
# 检查容器是否运行
docker ps | grep kafka-broker

# 检查 fio 是否安装
docker exec kafka-broker which fio

# 手动安装 fio
docker exec -u root kafka-broker apt-get update
docker exec -u root kafka-broker apt-get install -y fio
```

### 测试未触发超时

1. **检查 svctm 是否升高**
   ```bash
   docker exec kafka-broker iostat -x 5
   ```

2. **验证 fio 正在运行**
   ```bash
   docker exec kafka-broker ps aux | grep fio
   ```

3. **增加 fio 压力**
   - 编辑 fio 配置文件
   - 增加 `iodepth` 和 `numjobs`
   - 减小 `fsync` 值 (更频繁的刷盘)

4. **检查 Kafka 配置**
   ```bash
   # docker-compose.yml 中应该有这些配置
   KAFKA_LOG_FLUSH_INTERVAL_MESSAGES: 1
   KAFKA_LOG_FLUSH_INTERVAL_MS: 100
   ```

### Kafka 完全无法连接

- 原因: fio 压力过大
- 解决:
  ```bash
  # 立即停止 fio
  docker exec kafka-broker pkill fio

  # 等待几秒让 Kafka 恢复
  sleep 10

  # 使用更温和的配置重试
  ```

## 进阶技巧

### 自定义 fio 配置

创建你自己的配置文件:

```ini
[global]
ioengine=libaio
direct=1
sync=1
time_based=1
runtime=120

[custom-test]
filename=/var/lib/kafka/data/custom-test.dat
size=3G
bs=8k
iodepth=96
numjobs=6
rw=randrw
rwmixread=60
fsync=3
```

### 组合监控

同时监控多个指标:

```bash
# 终端1: 监控 I/O
docker exec kafka-broker iostat -x 5

# 终端2: 监控 Kafka 日志
docker logs -f kafka-broker

# 终端3: 运行测试
mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationProgressiveIO"
```

### 使用 cgroup 限制 I/O

Docker 提供的 I/O 限制:

```bash
# 限制写入速度为 1MB/s
docker run --device-write-bps /dev/sda:1mb kafka-image

# 或在 docker-compose.yml 中配置
services:
  kafka:
    device_write_bps:
      - /dev/sda:1mb
```

## 总结

fio-based 方案是目前最有希望成功复现 Kafka batch creation timeout 的方法，因为:

1. ✅ **精确控制**: 可调节的 I/O 压力
2. ✅ **可测量**: svctm 指标明确
3. ✅ **可重复**: 配置文件化
4. ✅ **真实性**: 模拟真实的慢盘场景
5. ✅ **灰色故障**: 创建部分失败状态，而非完全失败

如果这个方案仍然无法触发错误，可能说明 batch creation timeout 确实极难在测试环境中复现，建议转向其他测试策略（如 mock、生产环境监控等）。
