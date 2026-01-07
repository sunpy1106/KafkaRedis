# testOnBorrow 配置分析

## 问题

**testOnBorrow 的默认值是什么？当前项目的配置是什么？**

---

## 一、JedisPoolConfig 默认配置

### 验证结果 ✅

运行 `CheckDefaultConfig.java` 的输出：

```
【连接测试相关】
  testOnBorrow: false   ⬅️ 默认值
  testOnReturn: false
  testOnCreate: false
  testWhileIdle: true   ⬅️ 默认开启
```

### 默认值总结

| 参数 | 默认值 | 说明 |
|------|-------|------|
| **testOnBorrow** | **false** | 从池中获取连接时**不验证** |
| testOnReturn | false | 归还连接到池中时不验证 |
| testOnCreate | false | 创建连接时不验证 |
| testWhileIdle | true | 空闲时验证连接 |
| maxTotal | 8 | 最大连接数 |
| maxIdle | 8 | 最大空闲连接数 |
| minIdle | 0 | 最小空闲连接数 |
| maxWaitMillis | -1 | 无限等待 |

### 关键发现 ⚠️

```
✅ testOnBorrow 默认值是 FALSE

意味着：
  1. 获取连接时不会执行 validateObject()
  2. 不会执行 PING 命令验证连接
  3. 直接返回连接给应用使用

但是：
  makeObject() 创建新连接时仍会连接到 Redis
  如果连接失败，仍然会抛出异常
```

---

## 二、当前项目的实际配置

### RedisService.java 配置

**文件位置**: `src/main/java/com/example/kafka/service/RedisService.java`

**第 52 行**:
```java
poolConfig.setTestOnBorrow(
    Boolean.parseBoolean(props.getProperty("redis.pool.testOnBorrow", "true"))
);
```

**关键点**:
- 从配置文件读取 `redis.pool.testOnBorrow`
- 如果配置文件没有这个属性，**默认值是 "true"**
- ⚠️ 注意：这里的默认值覆盖了 JedisPoolConfig 的默认值 false

### application.properties 配置

**文件位置**: `src/main/resources/application.properties`

**第 26 行**:
```properties
redis.pool.testOnBorrow=true
```

**结论**: 当前项目明确设置为 `true`

---

## 三、配置层级对比

### 配置优先级

```
1. application.properties 中的显式配置  ← 当前项目使用
   redis.pool.testOnBorrow=true
   ↓
2. RedisService.java 中的代码默认值
   getProperty("redis.pool.testOnBorrow", "true")
   ↓
3. JedisPoolConfig 的库默认值
   testOnBorrow = false
```

### 当前项目的实际值

| 层级 | 配置位置 | testOnBorrow 值 | 是否生效 |
|------|---------|----------------|---------|
| **配置文件** | application.properties | **true** | ✅ **生效** |
| 代码默认 | RedisService.java | true | ❌ 被配置文件覆盖 |
| 库默认 | JedisPoolConfig | false | ❌ 被代码覆盖 |

---

## 四、testOnBorrow 对异常触发的影响

### testOnBorrow=false（库默认）

```
getResource() 流程:
  1. borrowObject()
  2. makeObject()        ← 可能抛异常
  3. (跳过 validateObject)
  4. activateObject()     ← 可能抛异常
  5. 返回连接

可能抛出 JedisConnectionException 的场景:
  ✅ 连接创建失败（端口错误、主机不可达）
  ✅ 认证失败
  ✅ 数据库选择失败
  ❌ 连接验证失败（不会验证）
```

### testOnBorrow=true（当前项目配置）

```
getResource() 流程:
  1. borrowObject()
  2. makeObject()        ← 可能抛异常
  3. validateObject()    ← 可能抛异常（额外检查）
  4. activateObject()    ← 可能抛异常
  5. 返回连接

可能抛出 JedisConnectionException 的场景:
  ✅ 连接创建失败（端口错误、主机不可达）
  ✅ 认证失败
  ✅ 数据库选择失败
  ✅ 连接验证失败（执行 PING 失败）
```

### 对比总结

| 场景 | testOnBorrow=false | testOnBorrow=true |
|------|-------------------|------------------|
| **连接创建失败** | ✅ 会抛异常 | ✅ 会抛异常 |
| **认证失败** | ✅ 会抛异常 | ✅ 会抛异常 |
| **从池中获取，但 Redis 已停止** | ❌ 不会立即发现 | ✅ 立即抛异常 |
| **从池中获取，Redis 正常** | ✅ 直接使用 | ✅ 先 PING 后使用 |

**关键区别**:
- `testOnBorrow=true` 会在每次从池中获取连接时执行 PING 验证
- 这样可以更早发现连接失效的问题
- 但会增加轻微的性能开销（每次 getResource 都执行 PING）

---

## 五、验证当前项目配置

### 创建验证程序

```java
// CheckProjectConfig.java
JedisPoolConfig poolConfig = new JedisPoolConfig();
// 模拟 RedisService.java 的配置读取
poolConfig.setTestOnBorrow(
    Boolean.parseBoolean("true")  // 从配置文件读取的值
);

System.out.println("当前项目实际使用:");
System.out.println("  testOnBorrow: " + poolConfig.getTestOnBorrow());
```

### 运行验证

```bash
# 检查默认配置
mvn exec:java -Dexec.mainClass="com.example.kafka.CheckDefaultConfig"

# 输出:
# testOnBorrow: false  ← JedisPoolConfig 库默认值
```

### 检查实际使用

```bash
# 查看配置文件
grep testOnBorrow src/main/resources/application.properties

# 输出:
# redis.pool.testOnBorrow=true  ← 当前项目实际使用
```

---

## 六、配置建议

### 生产环境推荐配置

```properties
# 推荐配置
redis.pool.testOnBorrow=true   # 建议开启，确保连接可用
redis.pool.testWhileIdle=true  # 默认开启，定期检查空闲连接
redis.pool.maxTotal=50         # 根据并发量调整
redis.pool.maxIdle=20
redis.pool.minIdle=5
redis.pool.maxWaitMillis=3000  # 3秒超时
```

### 不同场景的选择

| 场景 | testOnBorrow 建议 | 原因 |
|------|-----------------|------|
| **生产环境** | true | 确保每次获取的连接都可用 |
| **高并发场景** | false | 减少 PING 开销，依赖 testWhileIdle |
| **开发测试** | true | 更早发现连接问题 |
| **不稳定网络** | true | 及时发现连接断开 |

### testOnBorrow 的性能影响

```
testOnBorrow=true 的开销:
  - 每次 getResource() 都执行 PING
  - 单次 PING 延迟: 0.1-1ms（本地）
  - 高并发时累积影响较大

优化建议:
  1. 如果 Redis 稳定，可以设为 false
  2. 依赖 testWhileIdle=true 定期检查
  3. 合理设置连接池大小减少创建新连接的频率
```

---

## 七、当前项目配置总结

### 配置文件 (application.properties)

```properties
# Redis连接池配置（当前配置）
redis.pool.maxTotal=16
redis.pool.maxIdle=16
redis.pool.minIdle=4
redis.pool.maxWaitMillis=20
redis.pool.testOnBorrow=true  ⬅️ 明确设置为 true
```

### 实际行为

```
当前项目使用 testOnBorrow=true，意味着:

1. 每次 jedisPool.getResource() 都会:
   - 从池中获取连接
   - 执行 PING 命令验证
   - 如果 PING 失败，抛出 JedisConnectionException
   - 如果 PING 成功，返回连接

2. 可以检测到的问题:
   ✅ Redis 服务器停止
   ✅ 网络中断
   ✅ 连接已关闭
   ✅ 连接创建失败

3. 额外开销:
   - 每次 getResource 增加 ~0.1-1ms (PING 延迟)
   - maxWaitMillis=20ms 可能受影响
```

---

## 八、常见误解澄清

### 误解1: testOnBorrow=false 就不会抛异常 ❌

**错误理解**:
```
testOnBorrow=false → 不验证 → 不会抛异常
```

**正确理解**:
```
testOnBorrow=false:
  - 不执行 validateObject()（不执行 PING）
  - 但 makeObject() 仍会执行（创建新连接）
  - makeObject() 失败仍会抛 JedisConnectionException

只是少了一个检查点，不是完全不检查
```

### 误解2: testOnBorrow=true 才能触发异常 ❌

**错误理解**:
```
必须设置 testOnBorrow=true 才能触发 JedisConnectionException
```

**正确理解**:
```
testOnBorrow=false 也能触发:
  ✅ 连接创建失败（端口错误、主机不可达）
  ✅ 认证失败
  ✅ 数据库选择失败

testOnBorrow=true 额外能触发:
  ✅ 从池中获取连接，但连接已失效
```

### 误解3: 库默认值就是项目实际值 ❌

**错误理解**:
```
JedisPoolConfig 默认 testOnBorrow=false
→ 当前项目使用 false
```

**正确理解**:
```
层级优先级:
1. application.properties: true  ← 当前生效
2. 代码默认值: true
3. 库默认值: false
```

---

## 九、验证命令

### 检查默认配置

```bash
mvn exec:java -Dexec.mainClass="com.example.kafka.CheckDefaultConfig"
```

**输出**:
```
testOnBorrow: false  ← JedisPoolConfig 库默认值
```

### 检查项目配置

```bash
# 查看配置文件
cat src/main/resources/application.properties | grep testOnBorrow

# 输出:
redis.pool.testOnBorrow=true

# 查看代码配置
grep -n "testOnBorrow" src/main/java/com/example/kafka/service/RedisService.java

# 输出:
52: poolConfig.setTestOnBorrow(Boolean.parseBoolean(props.getProperty("redis.pool.testOnBorrow", "true")));
```

---

## 十、总结

### 核心要点

1. **JedisPoolConfig 库默认值**: `testOnBorrow = false`

2. **当前项目实际值**: `testOnBorrow = true`
   - 配置文件明确设置
   - 代码默认值也是 true
   - 覆盖了库默认值

3. **testOnBorrow=false 仍可触发异常**
   - makeObject() 失败时
   - 不需要 validateObject() 也能检测创建失败

4. **两种配置的差异**
   - false: 只在创建新连接时检测
   - true: 每次获取连接都检测（更安全，轻微性能开销）

### 推荐配置

```properties
# 生产环境推荐
redis.pool.testOnBorrow=true        # 确保连接可用
redis.pool.testWhileIdle=true       # 默认已开启
redis.pool.timeBetweenEvictionRunsMillis=30000  # 30秒检查一次
```

---

**文档创建日期**: 2026-01-05
**Jedis 版本**: 3.1.0
**验证状态**: ✅ 已验证
