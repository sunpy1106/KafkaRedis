# Redis异常模拟分析文档

## 目录
1. [概述](#概述)
2. [连接池耗尽异常](#连接池耗尽异常)
3. [连接超时异常](#连接超时异常)
4. [网络异常](#网络异常)
5. [关键配置参数说明](#关键配置参数说明)
6. [异常类型对照表](#异常类型对照表)

---

## 概述

本文档详细说明如何基于当前代码和配置模拟各种Redis异常。所有测试类都位于 `src/main/java/com/example/kafka/` 目录下。

### 测试环境要求
- JDK 1.8+
- Redis 服务器运行在 localhost:6379
- Jedis 客户端版本: 3.1.0
- Maven 项目已编译

---

## 连接池耗尽异常

### 异常类型
```
redis.clients.jedis.exceptions.JedisExhaustedPoolException:
Could not get a resource since the pool is exhausted
```

### 测试类
`TestRedisPoolException.java`

### 触发机制详解

#### 1. 核心原理
**连接池耗尽的数学模型：**
```
并发线程数 > maxTotal 且 maxWaitMillis 超时
→ JedisExhaustedPoolException
```

#### 2. 关键配置 (application.properties)
```properties
# 连接池最大连接数
redis.pool.maxTotal=16

# 连接池最大空闲连接数
redis.pool.maxIdle=16

# 连接池最小空闲连接数
redis.pool.minIdle=4

# 等待可用连接的最大时间(毫秒)
redis.pool.maxWaitMillis=20

# 从池中获取连接时是否测试
redis.pool.testOnBorrow=true
```

#### 3. 代码实现机制

**步骤1: 创建线程栅栏**
```java
int threadCount = 25;  // 25个线程竞争16个连接
CountDownLatch startLatch = new CountDownLatch(1);  // 控制同时开始
CountDownLatch endLatch = new CountDownLatch(threadCount);  // 等待全部结束
```

**步骤2: 启动并发线程**
```java
for (int i = 0; i < threadCount; i++) {
    final int threadId = i + 1;
    new Thread(() -> {
        try {
            startLatch.await();  // 等待启动信号，确保同时开始

            // 获取连接（前16个成功，后9个等待20ms后超时）
            Jedis jedis = jedisPool.getResource();

            // 占用连接5秒（模拟长时间操作）
            Thread.sleep(5000);

            // 释放连接
            jedis.close();
        } catch (JedisConnectionException e) {
            // 捕获连接池耗尽异常
        }
    }).start();
}
```

**步骤3: 触发并发**
```java
startLatch.countDown();  // 释放所有线程，同时开始竞争连接
```

#### 4. 异常触发流程

```
时间轴视图:

T0: 25个线程同时调用 jedisPool.getResource()
    ├─ 前16个线程: 立即获得连接 ✅
    └─ 后9个线程: 进入等待队列 ⏰

T0+20ms: maxWaitMillis 超时
    └─ 等待队列中的9个线程抛出异常 ❌

T0+5000ms: 前16个线程释放连接
    └─ 连接归还到池中（但已无线程等待）
```

#### 5. 预期结果
```
成功获取连接的线程数: 16
获取连接失败的线程数: 9
异常类型: JedisExhaustedPoolException
异常原因: java.util.NoSuchElementException: Timeout waiting for idle object
```

#### 6. 如何调整配置来改变行为

| 配置修改 | 结果 | 说明 |
|---------|------|------|
| `maxTotal=25` | 0个失败 | 连接数足够，所有线程都能获取 |
| `maxWaitMillis=6000` | 0个失败 | 等待时间足够长，前16个线程释放后后9个可获取 |
| `maxWaitMillis=10` | 9个失败更快 | 缩短超时时间，异常更快触发 |
| `threadCount=10` | 0个失败 | 线程数小于连接数，无竞争 |

---

## 连接超时异常

### 异常类型
```
redis.clients.jedis.exceptions.JedisConnectionException:
Failed connecting to host
```

### 测试类
`TestRedisConnectionTimeout.java`

### 触发机制详解

#### 场景1: 连接到不存在的主机

**配置:**
```java
JedisPool jedisPool = new JedisPool(
    poolConfig,
    "192.0.2.1",  // 不可达的IP地址（文档用途保留IP）
    6379,
    2000  // 连接超时: 2秒
);
```

**触发流程:**
```
1. 客户端尝试建立TCP连接到 192.0.2.1:6379
2. 由于主机不可达，TCP握手失败
3. 等待2秒后超时
4. 抛出 JedisConnectionException
5. 根本原因: ConnectException: Operation timed out (或 No route to host)
```

**预期输出:**
```
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Failed connecting to host 192.0.2.1:6379
根本原因: java.net.ConnectException: Operation timed out
```

#### 场景2: 连接到错误的端口

**配置:**
```java
JedisPool jedisPool = new JedisPool(
    poolConfig,
    "localhost",
    16379,  // Redis未监听的端口
    1000  // 连接超时: 1秒
);
```

**触发流程:**
```
1. 客户端尝试建立TCP连接到 localhost:16379
2. 端口未监听，立即返回拒绝连接
3. 抛出 JedisConnectionException
4. 根本原因: ConnectException: Connection refused
```

**预期输出:**
```
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Failed connecting to host localhost:16379
根本原因: java.net.ConnectException: Connection refused
```

#### 场景3: 设置极短的连接超时时间

**配置:**
```java
JedisPool jedisPool = new JedisPool(
    poolConfig,
    "localhost",
    6379,
    1  // 极短超时: 1毫秒
);
```

**触发流程:**
```
1. 客户端尝试建立连接，但超时时间只有1ms
2. TCP握手或Redis握手无法在1ms内完成
3. 抛出 JedisConnectionException
4. 根本原因: SocketTimeoutException: Read timed out
```

**注意:** 这个场景不稳定，因为本地连接可能在1ms内完成

---

## 网络异常

### 异常类型
```
redis.clients.jedis.exceptions.JedisConnectionException:
Unexpected end of stream / Broken pipe / Connection reset
```

### 测试类
`TestRedisNetworkException.java`

### 触发机制详解

#### 场景: 连接建立后网络中断

**步骤:**
```
1. 程序建立Redis连接
2. 执行PING命令验证连接正常
3. 程序等待30秒（给操作员时间停止Redis）
4. 操作员执行: docker stop kafka-redis
5. 程序尝试执行Redis操作
6. 由于连接已断开，抛出异常
```

**代码关键点:**
```java
// 关闭testOnBorrow，让连接在使用时才检测
poolConfig.setTestOnBorrow(false);

// 获取连接
Jedis jedis = jedisPool.getResource();

// 验证连接正常
jedis.ping();  // 成功

// ... 此时手动停止Redis ...

// 尝试操作（触发异常）
jedis.set("test:network", "value");  // ❌ 抛出异常
```

**可能的异常消息:**
- `Broken pipe` - 写入数据到已关闭的socket
- `Connection reset` - 连接被对端重置
- `Unexpected end of stream` - socket意外关闭

**操作步骤:**
```bash
# 1. 启动测试程序
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisNetworkException"

# 2. 看到倒计时提示后，在另一个终端执行:
docker stop kafka-redis

# 3. 观察异常输出

# 4. 测试完成后重启Redis
docker start kafka-redis
```

---

## 关键配置参数说明

### JedisPoolConfig 参数详解

| 参数 | 类型 | 默认值 | 说明 | 影响 |
|------|------|--------|------|------|
| **maxTotal** | int | 8 | 连接池最大连接数 | 决定最多能同时使用多少个连接 |
| **maxIdle** | int | 8 | 最大空闲连接数 | 决定连接池保持多少空闲连接 |
| **minIdle** | int | 0 | 最小空闲连接数 | 决定连接池至少保持多少空闲连接 |
| **maxWaitMillis** | long | -1 | 等待连接最大时间(ms) | **关键参数**：等待超时后抛出异常 |
| **testOnBorrow** | boolean | false | 获取连接时是否测试 | true会在获取时执行PING，增加延迟 |
| **testOnReturn** | boolean | false | 归还连接时是否测试 | true会在归还时执行PING |
| **testWhileIdle** | boolean | false | 空闲时是否测试连接 | true会定期检查空闲连接 |
| **blockWhenExhausted** | boolean | true | 连接耗尽时是否阻塞 | false会立即抛异常，true会等待 |

### JedisPool 构造参数

```java
public JedisPool(
    JedisPoolConfig poolConfig,  // 连接池配置
    String host,                 // Redis主机
    int port,                    // Redis端口
    int timeout,                 // 连接超时(ms)
    String password,             // Redis密码
    int database                 // Redis数据库索引
)
```

| 参数 | 说明 | 异常影响 |
|------|------|---------|
| **timeout** | Socket超时和连接超时 | 过小会导致 SocketTimeoutException |
| **host** | Redis服务器地址 | 错误会导致 ConnectException |
| **port** | Redis服务器端口 | 错误会导致 Connection refused |
| **password** | Redis密码 | 错误会导致 JedisDataException: NOAUTH |

---

## 异常类型对照表

### Jedis 3.1.0 异常层次结构

```
JedisException (抽象基类)
├─ JedisConnectionException (连接相关异常)
│  ├─ JedisExhaustedPoolException (连接池耗尽)
│  └─ 其他连接异常 (网络、超时等)
├─ JedisDataException (数据/命令异常)
│  ├─ NOAUTH Authentication required
│  ├─ WRONGTYPE Operation against a key...
│  └─ 其他Redis命令错误
└─ JedisNoScriptException (Lua脚本不存在)
```

### 常见异常场景对照

| 异常类型 | 异常消息 | 触发条件 | 测试类 |
|---------|---------|---------|--------|
| **JedisExhaustedPoolException** | Could not get a resource since the pool is exhausted | 并发数 > maxTotal 且等待超时 | TestRedisPoolException |
| **JedisConnectionException** | Failed connecting to host | 主机不可达或端口错误 | TestRedisConnectionTimeout |
| **JedisConnectionException** | Connection refused | Redis未启动或端口错误 | TestRedisConnectionTimeout |
| **JedisConnectionException** | Read timed out | Socket超时时间过短 | TestRedisConnectionTimeout |
| **JedisConnectionException** | Broken pipe | 连接后服务器关闭 | TestRedisNetworkException |
| **JedisConnectionException** | Unexpected end of stream | 连接意外中断 | TestRedisNetworkException |
| **JedisDataException** | NOAUTH Authentication required | Redis需要密码但未提供 | (需手动配置) |

---

## 如何运行测试

### 编译项目
```bash
cd /Users/sunpy/javaworkspace/kafkaRedis
mvn clean compile
```

### 运行各个测试

#### 1. 连接池耗尽异常测试
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisPoolException"
```

**前置条件:**
- Redis运行在 localhost:6379
- application.properties 配置正确

**预期输出:**
```
成功获取连接的线程数: 16
获取连接失败的线程数: 9
异常类型: JedisExhaustedPoolException
```

#### 2. 连接超时异常测试
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisConnectionTimeout"
```

**前置条件:**
- 无需Redis运行（测试会尝试连接不存在的主机）

**预期输出:**
```
场景1: 连接到不存在的主机 → ConnectException: Operation timed out
场景2: 连接到错误的端口 → ConnectException: Connection refused
场景3: 极短超时时间 → SocketTimeoutException: Read timed out
```

#### 3. 网络异常测试
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestRedisNetworkException"
```

**前置条件:**
- Redis运行在 localhost:6379

**操作步骤:**
1. 启动测试程序
2. 看到倒计时提示后，执行: `docker stop kafka-redis`
3. 观察异常输出
4. 重启Redis: `docker start kafka-redis`

**预期输出:**
```
异常类型: JedisConnectionException
异常消息: Unexpected end of stream (或 Broken pipe)
```

---

## 总结

### 异常模拟的关键要素

1. **连接池耗尽异常**
   - 关键参数: `maxTotal`, `maxWaitMillis`
   - 触发条件: 并发线程数 > maxTotal
   - 模拟方法: 多线程并发 + 长时间占用连接

2. **连接超时异常**
   - 关键参数: `timeout` (构造参数)
   - 触发条件: 主机不可达或超时时间过短
   - 模拟方法: 连接到错误的主机/端口

3. **网络异常**
   - 关键参数: `testOnBorrow=false`
   - 触发条件: 连接建立后网络中断
   - 模拟方法: 手动停止Redis服务器

### 生产环境建议配置

```properties
# 生产环境推荐配置
redis.pool.maxTotal=50          # 根据并发量调整
redis.pool.maxIdle=20           # 保持足够的空闲连接
redis.pool.minIdle=5            # 最小空闲连接
redis.pool.maxWaitMillis=3000   # 3秒超时（不要太短）
redis.pool.testOnBorrow=true    # 获取时测试连接
redis.pool.testWhileIdle=true   # 定期检查空闲连接
redis.timeout=3000              # Socket超时3秒
```

### 异常处理建议

```java
try {
    // Redis操作
    redisService.saveUuid(uuid);
} catch (JedisExhaustedPoolException e) {
    // 连接池耗尽：可能需要增加连接数或优化代码
    logger.error("连接池耗尽，考虑增加maxTotal", e);
} catch (JedisConnectionException e) {
    // 连接异常：可能是网络问题或Redis宕机
    logger.error("Redis连接异常，检查网络和服务状态", e);
    // 降级处理：使用本地缓存或跳过去重检查
} catch (Exception e) {
    // 其他异常
    logger.error("未知异常", e);
}
```

---

## 附录：完整配置文件

### application.properties (生产环境)
```properties
# Kafka配置
kafka.bootstrap.servers=localhost:9092
kafka.topic=message-topic
kafka.acks=all
kafka.retries=3

# Redis配置
redis.host=localhost
redis.port=6379
redis.timeout=3000
redis.database=0
redis.password=
redis.uuid.expire.seconds=604800

# Redis连接池配置（生产环境）
redis.pool.maxTotal=50
redis.pool.maxIdle=20
redis.pool.minIdle=5
redis.pool.maxWaitMillis=3000
redis.pool.testOnBorrow=true
redis.pool.testWhileIdle=true
redis.pool.timeBetweenEvictionRunsMillis=30000
```

### application.properties (测试环境 - 连接池耗尽)
```properties
# Redis连接池配置（用于模拟连接池耗尽）
redis.pool.maxTotal=16
redis.pool.maxIdle=16
redis.pool.minIdle=4
redis.pool.maxWaitMillis=20
redis.pool.testOnBorrow=true
```

---

*文档版本: 1.0*
*最后更新: 2026-01-05*
*Jedis版本: 3.1.0*
