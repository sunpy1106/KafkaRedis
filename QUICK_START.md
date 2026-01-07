# 快速开始：触发 JedisConnectionException

## 目标异常

```
redis.clients.jedis.exceptions.JedisConnectionException: Could not get a resource from the pool
```

---

## 🚀 最快方式（推荐）

### 方案：testOnBorrow=false + 错误端口

```java
// 1. 配置
JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setTestOnBorrow(false);
poolConfig.setMinIdle(0);  // 关键配置

// 2. 连接到错误端口
JedisPool jedisPool = new JedisPool(poolConfig, "localhost", 16379, 1000);

// 3. 触发异常
Jedis jedis = jedisPool.getResource();  // ❌ 抛出异常
```

### 运行测试

```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionWithoutTestOnBorrow"
```

### 预期结果

```
✅ 异常类型: JedisConnectionException
✅ 异常消息: Could not get a resource from the pool
根本原因: Failed connecting to host localhost:16379
耗时: 1ms
```

---

## 📋 所有实现方案

### 方案对比

| 方案 | 自动化 | 耗时 | testOnBorrow | 适用场景 |
|------|-------|------|-------------|---------|
| 🥇 **错误端口** | ✅ | 1ms | false | **快速验证、生产模拟** |
| 🥈 认证失败 | ✅ | 28ms | false | 配置错误模拟 |
| 🥉 不可达主机 | ✅ | 2000ms | false | 网络故障模拟 |
| testOnBorrow + 错误端口 | ✅ | 22ms | true | 验证机制测试 |
| Redis 停止 | ❌ 手动 | 30s+ | true | 运行时故障 |

---

## 💡 核心要点

### 1. testOnBorrow=false 也能触发异常 ⚠️

```
即使 testOnBorrow=false（默认配置）
makeObject() 创建连接时仍会失败
→ 触发 JedisConnectionException
```

### 2. 触发机制

```
getResource()
  → borrowObject()
    → makeObject() 创建连接  ❌ 失败
      → 抛出异常
        → 包装为 JedisConnectionException
```

### 3. 三个关键配置

```properties
testOnBorrow=false  # 默认配置即可
minIdle=0          # 避免连接池创建时失败
port=16379         # 错误端口触发异常
```

---

## 🔍 源码分析（精简版）

```java
// Pool.java
public T getResource() {
    try {
        return internalPool.borrowObject();
    } catch (Exception e) {
        // ⭐ 关键：所有 Exception 都会触发这里
        throw new JedisConnectionException(
            "Could not get a resource from the pool", e);
    }
}

// borrowObject() 内部
borrowObject() {
    makeObject()      // ⬅️ 可能失败（不需要 testOnBorrow）
    validateObject()  // ⬅️ 可能失败（需要 testOnBorrow=true）
    activateObject()  // ⬅️ 可能失败（不需要 testOnBorrow）
}
```

---

## 🎯 选择建议

### 根据需求选择

```
需要快速验证？
  → 使用方案1（错误端口，1ms）

模拟配置错误？
  → 使用方案2（认证失败，28ms）

模拟网络故障？
  → 使用方案3（不可达主机，2000ms）

测试 testOnBorrow 机制？
  → 使用方案4（testOnBorrow=true + 错误端口）
```

---

## 📝 完整测试命令

```bash
# 推荐测试（testOnBorrow=false，3个场景）
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionWithoutTestOnBorrow"

# testOnBorrow=true 测试（2个场景）
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionAuto"

# 手动测试（需停止 Redis）
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionException"
# 看到倒计时后执行: docker stop kafka-redis
```

---

## 🛠️ 生产环境排查

当遇到此异常时：

```java
catch (JedisConnectionException e) {
    // 1. 查看根本原因
    String cause = e.getCause().getMessage();

    // 2. 分析原因
    if (cause.contains("AUTH")) {
        // → Redis 密码配置错误
    } else if (cause.contains("Connection refused")) {
        // → 端口或 Redis 未启动
    } else if (cause.contains("timeout")) {
        // → 网络超时
    } else if (cause.contains("maxclients")) {
        // → Redis 连接数已满
    }
}
```

---

## 📚 详细文档

- **IMPLEMENTATION_SUMMARY.md** - 完整实现思路总结
- **PRODUCTION_SCENARIO_GUIDE.md** - 生产环境排查指南
- **JEDIS_CONNECTION_EXCEPTION_GUIDE.md** - 异常触发完整指南

---

## ✅ 验证结果

所有方案均已验证成功：

```
✅ testOnBorrow=false + 错误端口        → 成功触发 (1ms)
✅ testOnBorrow=false + 认证失败        → 成功触发 (28ms)
✅ testOnBorrow=false + 不可达主机      → 成功触发 (2000ms)
✅ testOnBorrow=true + 错误端口         → 成功触发 (22ms)
✅ testOnBorrow=true + 不可达主机       → 成功触发 (2002ms)
✅ testOnBorrow=true + Redis停止        → 成功触发 (手动)
```

---

**推荐**: 使用方案1（错误端口）- 最快最稳定 🏆
