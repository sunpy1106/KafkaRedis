package com.example.kafka;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisExhaustedPoolException;

import java.io.InputStream;
import java.util.Properties;

/**
 * 验证Jedis异常类的包路径和继承关系
 */
public class VerifyJedisException {

    public static void main(String[] args) {
        System.out.println("========== Jedis 异常类验证 ==========\n");

        // 1. 检查异常类的包路径
        System.out.println("【1】异常类的完整路径:");
        System.out.println("  JedisException: " + JedisException.class.getName());
        System.out.println("  JedisConnectionException: " + JedisConnectionException.class.getName());
        System.out.println("  JedisExhaustedPoolException: " + JedisExhaustedPoolException.class.getName());

        // 2. 检查继承关系
        System.out.println("\n【2】异常类的继承关系:");
        printInheritance("JedisException", JedisException.class);
        printInheritance("JedisConnectionException", JedisConnectionException.class);
        printInheritance("JedisExhaustedPoolException", JedisExhaustedPoolException.class);

        // 3. 检查Jedis版本
        System.out.println("\n【3】Jedis 版本信息:");
        try {
            Package pkg = Jedis.class.getPackage();
            System.out.println("  Package: " + pkg.getName());
            System.out.println("  Implementation Title: " + pkg.getImplementationTitle());
            System.out.println("  Implementation Version: " + pkg.getImplementationVersion());
            System.out.println("  Implementation Vendor: " + pkg.getImplementationVendor());
        } catch (Exception e) {
            System.out.println("  无法获取版本信息: " + e.getMessage());
        }

        // 4. 实际触发异常并检查
        System.out.println("\n【4】实际触发异常测试:");
        testPoolExhaustedException();

        System.out.println("\n========== 验证完成 ==========");
    }

    /**
     * 打印类的继承关系
     */
    private static void printInheritance(String name, Class<?> clazz) {
        System.out.println("  " + name + ":");
        Class<?> current = clazz;
        int level = 0;
        while (current != null) {
            StringBuilder indent = new StringBuilder("    ");
            for (int i = 0; i < level; i++) {
                indent.append("  ");
            }
            System.out.println(indent.toString() + "└─ " + current.getName());
            current = current.getSuperclass();
            level++;
        }
    }

    /**
     * 测试连接池耗尽异常
     */
    private static void testPoolExhaustedException() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(1);
        poolConfig.setMaxWaitMillis(10);

        JedisPool jedisPool = null;
        Jedis jedis1 = null;

        try {
            jedisPool = new JedisPool(poolConfig, "localhost", 6379, 1000);

            // 占用唯一的连接
            jedis1 = jedisPool.getResource();
            System.out.println("  第1个连接获取成功");

            // 尝试获取第2个连接（应该失败）
            try {
                Jedis jedis2 = jedisPool.getResource();
                System.out.println("  第2个连接获取成功（不应该发生）");
                jedis2.close();
            } catch (Exception e) {
                System.out.println("  第2个连接获取失败（符合预期）");
                System.out.println("    异常类型: " + e.getClass().getName());
                System.out.println("    异常包路径: " + e.getClass().getPackage().getName());
                System.out.println("    异常消息: " + e.getMessage());

                // 检查异常类型
                if (e instanceof JedisExhaustedPoolException) {
                    System.out.println("    ✅ 确认为 JedisExhaustedPoolException");
                } else if (e instanceof JedisConnectionException) {
                    System.out.println("    ⚠️  是 JedisConnectionException (不是 JedisExhaustedPoolException)");
                } else {
                    System.out.println("    ❌ 其他类型异常");
                }
            }

        } catch (Exception e) {
            System.out.println("  测试过程发生异常: " + e.getMessage());
        } finally {
            if (jedis1 != null) {
                jedis1.close();
            }
            if (jedisPool != null) {
                jedisPool.close();
            }
        }
    }
}
