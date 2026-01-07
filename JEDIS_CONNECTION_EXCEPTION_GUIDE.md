# JedisConnectionException 触发指南

## 目标异常

```
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Could not get a resource from the pool
```

---

## 源码分析

### Pool.java getResource() 方法（Jedis 3.1.0）

```java
public T getResource() {
    try {
        return internalPool.borrowObject();
    } catch (NoSuchElementException e) {
        if (e.getCause() == null) {
            // 连接池耗尽，无其他原因
            throw new JedisExhaustedPoolException(
                "Could not get a resource since the pool is exhausted", e);
        } else {
            // 连接池耗尽，但有其他根本原因
            throw new JedisException(
                "Could not get a resource from the pool", e);
        }
    } catch (Exception e) {
        // 连接创建/验证失败 ✅ 这里抛出 JedisConnectionException
        throw new JedisConnectionException(
            "Could not get a resource from the pool", e);
    }
}
```

### 异常类型对照

| 异常类型 | 异常消息 | 触发条件 |
|---------|---------|---------|
| `JedisExhaustedPoolException` | "Could not get a resource **since** the pool is exhausted" | 连接池耗尽（无根本原因） |
| `JedisException` | "Could not get a resource **from** the pool" | 连接池耗尽（有根本原因） |
| **`JedisConnectionException`** ✅ | "Could not get a resource **from** the pool" | **连接创建/验证失败** |

---

## 触发机制

要触发 `JedisConnectionException`，需要让 `borrowObject()` 抛出 `Exception`（非 `NoSuchElementException`）。

### 触发条件

1. **makeObject() 失败** - 创建新连接时失败
2. **validateObject() 失败** - 验证连接时抛出异常
3. **activateObject() 失败** - 激活对象时失败

### 关键配置

```properties
# 关键配置：从池中获取连接时验证
redis.pool.testOnBorrow=true

# 其他配置
redis.pool.minIdle=0  # 避免创建连接池时就失败
redis.pool.maxTotal=8
redis.pool.maxWaitMillis=3000
```

---

## 测试方案

### 方案1: testOnBorrow + 错误端口（自动化，推荐） ✅

**原理**:
- `testOnBorrow=true` → 获取连接时执行 PING 验证
- 连接到错误端口 → 连接创建失败
- 抛出 `JedisConnectionException`

**配置**:
```java
JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setTestOnBorrow(true);  // ⚠️ 关键
poolConfig.setMinIdle(0);          // 避免连接池创建时失败

JedisPool jedisPool = new JedisPool(poolConfig, "localhost", 16379, 1000);
Jedis jedis = jedisPool.getResource();  // ❌ 抛出异常
```

**运行测试**:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionAuto"
```

**预期结果**:
```
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Could not get a resource from the pool
根本原因: Failed connecting to host localhost:16379
耗时: ~20ms (快速失败)
```

---

### 方案2: testOnBorrow + 不可达主机（自动化）

**原理**: 类似方案1，但使用不可达的IP地址

**配置**:
```java
JedisPool jedisPool = new JedisPool(poolConfig, "192.0.2.1", 6379, 2000);
```

**预期结果**:
```
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Could not get a resource from the pool
根本原因: Failed connecting to host 192.0.2.1:6379
耗时: ~2000ms (超时失败)
```

---

### 方案3: testOnBorrow + Redis关闭（需手动操作）

**原理**:
- 连接池创建时 Redis 正常
- 获取连接时 Redis 已关闭
- 验证失败抛出异常

**步骤**:
1. 运行测试程序
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionException"
```

2. 看到倒计时后，停止 Redis
```bash
docker stop kafka-redis
```

3. 观察异常输出

4. 重启 Redis
```bash
docker start kafka-redis
```

**预期结果**:
```
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Could not get a resource from the pool
根本原因: Connection refused / Broken pipe
```

---

## 测试验证结果 ✅

### 测试环境
- Jedis版本: 3.1.0
- JDK版本: 1.8
- 测试日期: 2026-01-05

### 测试结果

#### 场景1: testOnBorrow + 错误端口
```
✅ 成功触发
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Could not get a resource from the pool
耗时: 22ms
```

#### 场景2: testOnBorrow + 不可达主机
```
✅ 成功触发
异常类型: redis.clients.jedis.exceptions.JedisConnectionException
异常消息: Could not get a resource from the pool
耗时: 2002ms
```

---

## 关键配置对比

### 触发 JedisConnectionException

```properties
# 必须配置
redis.pool.testOnBorrow=true

# 避免提前失败
redis.pool.minIdle=0

# 连接到错误的目标
redis.host=localhost
redis.port=16379  # 错误端口
```

### 触发 JedisExhaustedPoolException

```properties
# 必须配置
redis.pool.maxTotal=16
redis.pool.maxWaitMillis=20

# 正常连接
redis.host=localhost
redis.port=6379  # 正确端口
```

---

## 完整测试代码

### 自动化测试（推荐）

**文件**: `TestJedisConnectionExceptionAuto.java`

```bash
# 运行测试
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionAuto"

# 预期输出
🎯 成功触发目标异常！
✅ 异常类型: JedisConnectionException
✅ 异常消息: Could not get a resource from the pool
```

### 手动测试

**文件**: `TestJedisConnectionException.java`

```bash
# 运行测试
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionException"

# 看到倒计时后执行
docker stop kafka-redis

# 观察异常

# 测试完成后重启
docker start kafka-redis
```

---

## 异常处理建议

```java
try {
    Jedis jedis = jedisPool.getResource();
    // 执行Redis操作
    jedis.close();

} catch (JedisConnectionException e) {
    if ("Could not get a resource from the pool".equals(e.getMessage())) {
        logger.error("连接创建/验证失败，检查:");
        logger.error("1. Redis服务器是否运行");
        logger.error("2. 网络连接是否正常");
        logger.error("3. 主机和端口是否正确");

        // 降级处理
        // 1. 切换到备用Redis实例
        // 2. 使用本地缓存
        // 3. 返回默认值
    }

} catch (JedisExhaustedPoolException e) {
    logger.error("连接池耗尽，考虑:");
    logger.error("1. 增加 maxTotal");
    logger.error("2. 减少连接占用时间");
    logger.error("3. 优化业务逻辑");
}
```

---

## 常见问题

### Q1: 为什么需要 testOnBorrow=true？

**A**:
- `testOnBorrow=false` (默认): 获取连接时不验证，直接返回
- `testOnBorrow=true`: 获取连接时执行 PING，验证失败会抛出异常
- 只有设置为 `true`，才会在 `getResource()` 时触发连接验证

### Q2: 为什么需要 minIdle=0？

**A**:
- `minIdle > 0`: 连接池创建时会预先创建连接
- 如果此时 Redis 不可达，连接池创建就会失败
- 设置为 0 可以延迟到 `getResource()` 时才创建连接

### Q3: JedisConnectionException 和 JedisExhaustedPoolException 的区别？

**A**:
| 特征 | JedisConnectionException | JedisExhaustedPoolException |
|------|-------------------------|----------------------------|
| 触发原因 | 连接创建/验证失败 | 连接池资源耗尽 |
| 异常消息 | "from the pool" | "since the pool is exhausted" |
| 解决方案 | 检查 Redis 服务器 | 增加连接数或优化代码 |
| testOnBorrow | 通常为 true | 无关 |

### Q4: 如何区分是哪种连接失败？

**A**: 查看根本原因（Cause）
```java
if (e.getCause() != null) {
    String cause = e.getCause().getMessage();
    if (cause.contains("Connection refused")) {
        // 端口未监听
    } else if (cause.contains("timeout")) {
        // 连接超时
    } else if (cause.contains("Broken pipe")) {
        // 连接中断
    }
}
```

---

## 总结

### 触发 JedisConnectionException 的关键

1. ✅ **testOnBorrow=true** - 开启连接验证
2. ✅ **Redis 不可达** - 连接失败（错误端口/主机/服务停止）
3. ✅ **minIdle=0** - 避免连接池创建时失败

### 推荐测试方案

**自动化测试**（无需手动操作）:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionAuto"
```

**手动测试**（需停止 Redis）:
```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionException"
```

---

**文档版本**: 1.0
**创建日期**: 2026-01-05
**Jedis版本**: 3.1.0
