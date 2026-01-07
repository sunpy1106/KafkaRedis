# 测试类详细说明

本项目共有 8 个测试/验证类，用于模拟和验证不同的 Redis 异常场景。

---

## 一、测试类总览

| # | 测试类 | 目标异常 | testOnBorrow | 自动化 | 主要用途 |
|---|--------|---------|-------------|-------|---------|
| 1 | **TestRedisPoolException** | JedisExhaustedPoolException | false | ✅ | 连接池耗尽 |
| 2 | **TestJedisConnectionExceptionWithoutTestOnBorrow** | JedisConnectionException | false | ✅ | **生产场景模拟（推荐）** |
| 3 | **TestJedisConnectionExceptionAuto** | JedisConnectionException | true | ✅ | testOnBorrow=true 场景 |
| 4 | **TestJedisConnectionException** | JedisConnectionException | true | ❌ 手动 | 运行时故障模拟 |
| 5 | **TestRedisConnectionTimeout** | JedisConnectionException | - | ✅ | 连接超时场景 |
| 6 | **TestRedisNetworkException** | JedisConnectionException | - | ❌ 手动 | 网络中断模拟 |
| 7 | **VerifyJedisException** | - | - | ✅ | 验证异常类结构 |
| 8 | **CheckDefaultConfig** | - | - | ✅ | 检查配置默认值 |

---

## 二、详细说明

### 1️⃣ TestRedisPoolException

**目标异常**: `JedisExhaustedPoolException: Could not get a resource since the pool is exhausted`

**核心功能**:
- 模拟连接池耗尽场景
- 25 个线程竞争 16 个连接
- 20ms 超时触发异常

**关键配置**:
```java
maxTotal = 16
maxWaitMillis = 20ms
线程数 = 25
```

**测试场景**:
```
场景: 并发请求数超过连接池容量
结果: 前 16 个线程成功，后 9 个线程超时失败
```

**运行命令**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisPoolException"
```

**预期输出**:
```
✅ 成功获取连接的线程数: 16
❌ 获取连接失败的线程数: 9
异常类型: JedisExhaustedPoolException
异常消息: Could not get a resource since the pool is exhausted
```

**适用场景**:
- ✅ 压力测试
- ✅ 连接池容量规划
- ✅ 验证 maxTotal 和 maxWaitMillis 配置

---

### 2️⃣ TestJedisConnectionExceptionWithoutTestOnBorrow ⭐ 推荐

**目标异常**: `JedisConnectionException: Could not get a resource from the pool`

**核心功能**:
- 模拟 **testOnBorrow=false** (默认配置) 下的异常
- 包含 3 个自动化场景
- **最贴近生产环境故障**

**测试场景**:
```
场景1: Redis 认证失败
  - 提供错误密码，Redis 未配置密码
  - 触发: makeObject() 认证失败
  - 耗时: ~28ms

场景2: 连接到错误端口
  - port = 16379 (未监听)
  - 触发: makeObject() 连接失败
  - 耗时: ~1ms (最快)

场景3: 连接超时
  - host = 192.0.2.1 (不可达)
  - 触发: makeObject() 超时
  - 耗时: ~2000ms
```

**关键配置**:
```java
testOnBorrow = false  // 明确设置为 false
minIdle = 0           // 避免连接池创建时失败
```

**运行命令**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionWithoutTestOnBorrow"
```

**预期输出**:
```
🎯 成功触发目标异常！
✅ 异常类型: JedisConnectionException
✅ 异常消息: Could not get a resource from the pool
✅ testOnBorrow: false
```

**适用场景**:
- ✅ **生产环境故障模拟（最推荐）**
- ✅ 配置错误排查
- ✅ 网络故障测试
- ✅ Redis 正常但仍失败的场景

**为什么推荐**:
1. testOnBorrow=false 是库默认值
2. 3 个场景全自动，无需手动操作
3. 覆盖最常见的生产故障
4. 快速验证（1ms）

---

### 3️⃣ TestJedisConnectionExceptionAuto

**目标异常**: `JedisConnectionException: Could not get a resource from the pool`

**核心功能**:
- 模拟 **testOnBorrow=true** 下的异常
- 包含 2 个自动化场景
- 验证连接验证机制

**测试场景**:
```
场景1: testOnBorrow=true + 错误端口
  - port = 16379
  - 触发: makeObject() 失败
  - 耗时: ~22ms

场景2: testOnBorrow=true + 不可达主机
  - host = 192.0.2.1
  - 触发: makeObject() 超时
  - 耗时: ~2002ms
```

**关键配置**:
```java
testOnBorrow = true   // ⚠️ 明确设置为 true
minIdle = 0
```

**运行命令**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionAuto"
```

**预期输出**:
```
🎯 成功触发目标异常！
✅ 异常类型: JedisConnectionException
✅ 异常消息: Could not get a resource from the pool
✅ testOnBorrow: true
```

**适用场景**:
- ✅ 测试 testOnBorrow=true 配置
- ✅ 验证连接验证机制
- ✅ 对比 testOnBorrow true/false 的差异

**与测试类2的区别**:
- 测试类2: testOnBorrow=false（默认配置）
- 测试类3: testOnBorrow=true（验证机制测试）

---

### 4️⃣ TestJedisConnectionException

**目标异常**: `JedisConnectionException: Could not get a resource from the pool`

**核心功能**:
- 模拟 Redis 运行时故障
- **需要手动停止 Redis**
- 更真实的故障场景

**测试场景**:
```
场景: testOnBorrow=true + Redis 停止
  1. 程序启动，连接 Redis 正常
  2. 执行 PING 测试成功
  3. 倒计时 30 秒
  4. 手动停止 Redis: docker stop kafka-redis
  5. 程序尝试获取连接
  6. validateObject() 执行 PING 失败
  7. 抛出 JedisConnectionException
```

**关键配置**:
```java
testOnBorrow = true   // 必须为 true
```

**运行步骤**:
```bash
# 1. 启动测试
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionException"

# 2. 看到倒计时后，在另一个终端执行:
docker stop kafka-redis

# 3. 观察异常输出

# 4. 测试完成后重启 Redis
docker start kafka-redis
```

**预期输出**:
```
🎯 成功触发目标异常！
✅ 异常类型: JedisConnectionException
✅ 异常消息: Could not get a resource from the pool
✅ testOnBorrow: true
触发原因: 连接验证失败（Redis 已停止）
```

**适用场景**:
- ✅ 模拟 Redis 运行时宕机
- ✅ 测试应用容错能力
- ✅ 验证 testOnBorrow 机制

**缺点**:
- ❌ 需要手动操作
- ❌ 不适合自动化测试

---

### 5️⃣ TestRedisConnectionTimeout

**目标异常**: `JedisConnectionException: Failed connecting to host`

**核心功能**:
- 专注于连接超时场景
- 测试不同的连接失败原因

**测试场景**:
```
场景1: 连接到不存在的主机
  - host = 192.0.2.1 (保留IP，不可达)
  - timeout = 2000ms
  - 耗时: ~2019ms

场景2: 连接到错误的端口
  - port = 16379 (未监听)
  - timeout = 1000ms
  - 耗时: ~3ms (快速失败)

场景3: 设置极短的超时时间
  - timeout = 1ms
  - 结果: 本地连接可能成功（1ms 够用）
```

**运行命令**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisConnectionTimeout"
```

**预期输出**:
```
场景1: ✅ JedisConnectionException (超时)
场景2: ✅ JedisConnectionException (拒绝)
场景3: ⚠️  可能成功（本地太快）
```

**适用场景**:
- ✅ 测试连接超时配置
- ✅ 验证不同的连接失败场景
- ✅ 网络故障排查

**与测试类2/3的区别**:
- 测试类2/3: 作为 JedisConnectionException 的一部分场景
- 测试类5: 专门测试连接超时，更细致

---

### 6️⃣ TestRedisNetworkException

**目标异常**: `JedisConnectionException: Unexpected end of stream / Broken pipe`

**核心功能**:
- 模拟网络中断
- **需要手动停止 Redis**

**测试场景**:
```
场景: 连接建立后网络中断
  1. 获取连接成功
  2. 执行 PING 成功
  3. 倒计时 30 秒
  4. 手动停止 Redis: docker stop kafka-redis
  5. 尝试执行 SET 操作
  6. 抛出 Broken Pipe 或 Connection reset
```

**关键配置**:
```java
testOnBorrow = false  // 关闭，让连接在使用时才检测
```

**运行步骤**:
```bash
# 1. 启动测试
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisNetworkException"

# 2. 看到倒计时后执行:
docker stop kafka-redis

# 3. 测试完成后重启:
docker start kafka-redis
```

**预期输出**:
```
异常消息可能是:
  - Broken pipe
  - Connection reset
  - Unexpected end of stream
```

**适用场景**:
- ✅ 测试网络中断场景
- ✅ 模拟 Redis 宕机
- ✅ 验证异常处理

**与测试类4的区别**:
- 测试类4: testOnBorrow=true，获取连接时检测
- 测试类6: testOnBorrow=false，使用连接时检测

---

### 7️⃣ VerifyJedisException

**目标**: 验证 Jedis 异常类的包路径和继承关系

**核心功能**:
- 检查异常类的完整路径
- 验证继承关系
- 实际触发异常验证

**测试内容**:
```
1. 异常类的完整路径
   - JedisException
   - JedisConnectionException
   - JedisExhaustedPoolException

2. 继承关系
   - JedisConnectionException → JedisException → RuntimeException

3. 实际触发测试
   - 连接池耗尽场景
   - 验证异常类型
```

**运行命令**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.VerifyJedisException"
```

**预期输出**:
```
【1】异常类的完整路径:
  JedisException: redis.clients.jedis.exceptions.JedisException
  JedisConnectionException: redis.clients.jedis.exceptions.JedisConnectionException
  JedisExhaustedPoolException: redis.clients.jedis.exceptions.JedisExhaustedPoolException

【2】异常类的继承关系:
  JedisExhaustedPoolException
    └─ JedisException
      └─ RuntimeException

✅ 确认为 JedisExhaustedPoolException
```

**适用场景**:
- ✅ 验证 Jedis 版本
- ✅ 检查异常类结构
- ✅ 排查包路径问题

---

### 8️⃣ CheckDefaultConfig

**目标**: 检查 JedisPoolConfig 的默认配置

**核心功能**:
- 显示所有默认配置值
- 验证 testOnBorrow 默认值

**测试内容**:
```
【连接测试相关】
  testOnBorrow: false
  testOnReturn: false
  testOnCreate: false
  testWhileIdle: true

【连接池大小】
  maxTotal: 8
  maxIdle: 8
  minIdle: 0

【等待和超时】
  maxWaitMillis: -1
```

**运行命令**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.CheckDefaultConfig"
```

**预期输出**:
```
✅ testOnBorrow 默认值是 FALSE
   意味着：获取连接时不会执行 validateObject()
   但是：makeObject() 创建新连接时仍会连接到 Redis
```

**适用场景**:
- ✅ 检查配置默认值
- ✅ 对比项目配置
- ✅ 配置规划参考

---

## 三、测试类分类

### 按异常类型分类

#### JedisExhaustedPoolException
```
1. TestRedisPoolException
   - 连接池耗尽异常
```

#### JedisConnectionException
```
2. TestJedisConnectionExceptionWithoutTestOnBorrow ⭐
   - testOnBorrow=false（生产场景）

3. TestJedisConnectionExceptionAuto
   - testOnBorrow=true（验证机制）

4. TestJedisConnectionException
   - 运行时故障（手动）

5. TestRedisConnectionTimeout
   - 连接超时场景

6. TestRedisNetworkException
   - 网络中断（手动）
```

#### 辅助验证
```
7. VerifyJedisException
   - 验证异常类结构

8. CheckDefaultConfig
   - 检查默认配置
```

### 按自动化程度分类

#### 全自动（推荐用于 CI/CD）
```
✅ TestRedisPoolException
✅ TestJedisConnectionExceptionWithoutTestOnBorrow ⭐
✅ TestJedisConnectionExceptionAuto
✅ TestRedisConnectionTimeout
✅ VerifyJedisException
✅ CheckDefaultConfig
```

#### 需要手动操作
```
❌ TestJedisConnectionException
❌ TestRedisNetworkException
```

### 按 testOnBorrow 分类

#### testOnBorrow=false
```
1. TestRedisPoolException
2. TestJedisConnectionExceptionWithoutTestOnBorrow ⭐
6. TestRedisNetworkException
```

#### testOnBorrow=true
```
3. TestJedisConnectionExceptionAuto
4. TestJedisConnectionException
```

#### 无关
```
5. TestRedisConnectionTimeout
7. VerifyJedisException
8. CheckDefaultConfig
```

---

## 四、使用建议

### 快速验证异常

```bash
# 推荐：最快最全面
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionWithoutTestOnBorrow"
```

**优点**:
- ✅ 3 个场景全自动
- ✅ 1ms 快速验证
- ✅ 最贴近生产环境

### 测试连接池耗尽

```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisPoolException"
```

**适用场景**:
- 压力测试
- 容量规划
- 验证 maxWaitMillis 配置

### 验证配置

```bash
# 检查默认配置
mvn exec:java -Dexec.mainClass="com.example.kafka.CheckDefaultConfig"

# 验证异常类结构
mvn exec:java -Dexec.mainClass="com.example.kafka.VerifyJedisException"
```

### 模拟运行时故障（需手动）

```bash
# 方式1: testOnBorrow=true + Redis 停止
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionException"

# 方式2: 网络中断
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisNetworkException"
```

---

## 五、对比总结表

### 核心对比

| 测试类 | 异常类型 | testOnBorrow | 自动化 | 场景数 | 耗时 | 推荐度 |
|--------|---------|-------------|-------|-------|------|--------|
| TestRedisPoolException | Exhausted | false | ✅ | 1 | ~5s | ⭐⭐⭐ |
| **TestJedisConnectionExceptionWithoutTestOnBorrow** | **Connection** | **false** | ✅ | **3** | **1ms** | **⭐⭐⭐⭐⭐** |
| TestJedisConnectionExceptionAuto | Connection | true | ✅ | 2 | 22ms | ⭐⭐⭐⭐ |
| TestJedisConnectionException | Connection | true | ❌ | 1 | 30s+ | ⭐⭐⭐ |
| TestRedisConnectionTimeout | Connection | - | ✅ | 3 | 2s | ⭐⭐⭐ |
| TestRedisNetworkException | Connection | false | ❌ | 1 | 30s+ | ⭐⭐ |
| VerifyJedisException | - | - | ✅ | - | <1s | ⭐⭐⭐⭐ |
| CheckDefaultConfig | - | - | ✅ | - | <1s | ⭐⭐⭐⭐ |

### 推荐使用顺序

```
1. CheckDefaultConfig
   → 了解默认配置

2. TestJedisConnectionExceptionWithoutTestOnBorrow ⭐
   → 快速验证最常见异常

3. TestRedisPoolException
   → 测试连接池容量

4. VerifyJedisException
   → 验证异常类结构

5. 其他测试类
   → 根据具体需求选择
```

---

## 六、常见问题

### Q1: 应该用哪个测试类？

**A**: 根据需求选择

```
快速验证异常？
  → TestJedisConnectionExceptionWithoutTestOnBorrow ⭐

测试连接池容量？
  → TestRedisPoolException

测试 testOnBorrow 机制？
  → TestJedisConnectionExceptionAuto

模拟真实故障？
  → TestJedisConnectionException（手动）
```

### Q2: 为什么有这么多测试类？

**A**: 每个测试类关注不同的场景

```
1. 异常类型不同:
   - Exhausted vs Connection

2. testOnBorrow 配置不同:
   - false (默认) vs true (验证)

3. 触发方式不同:
   - makeObject() vs validateObject()

4. 自动化程度不同:
   - 自动 vs 手动操作
```

### Q3: 测试类 2 和 3 有什么区别？

**A**: testOnBorrow 配置不同

```
测试类2 (WithoutTestOnBorrow):
  - testOnBorrow = false (库默认值)
  - 模拟默认配置下的异常
  - 更贴近大多数生产环境

测试类3 (Auto):
  - testOnBorrow = true
  - 测试连接验证机制
  - 验证 validateObject() 失败场景
```

### Q4: 哪些测试类适合自动化测试？

**A**: 以下 6 个

```
✅ TestRedisPoolException
✅ TestJedisConnectionExceptionWithoutTestOnBorrow ⭐
✅ TestJedisConnectionExceptionAuto
✅ TestRedisConnectionTimeout
✅ VerifyJedisException
✅ CheckDefaultConfig
```

---

**文档创建日期**: 2026-01-05
**测试类总数**: 8
**推荐测试**: TestJedisConnectionExceptionWithoutTestOnBorrow ⭐
