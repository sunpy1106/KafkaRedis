package com.example.kafka;

import redis.clients.jedis.JedisPoolConfig;

/**
 * 检查 JedisPoolConfig 的默认配置
 */
public class CheckDefaultConfig {

    public static void main(String[] args) {
        System.out.println("========== JedisPoolConfig 默认配置 ==========\n");

        // 创建默认配置（不设置任何参数）
        JedisPoolConfig config = new JedisPoolConfig();

        System.out.println("【连接测试相关】");
        System.out.println("  testOnBorrow: " + config.getTestOnBorrow());
        System.out.println("  testOnReturn: " + config.getTestOnReturn());
        System.out.println("  testOnCreate: " + config.getTestOnCreate());
        System.out.println("  testWhileIdle: " + config.getTestWhileIdle());

        System.out.println("\n【连接池大小】");
        System.out.println("  maxTotal: " + config.getMaxTotal());
        System.out.println("  maxIdle: " + config.getMaxIdle());
        System.out.println("  minIdle: " + config.getMinIdle());

        System.out.println("\n【等待和超时】");
        System.out.println("  maxWaitMillis: " + config.getMaxWaitMillis() + " ms");
        System.out.println("  minEvictableIdleTimeMillis: " + config.getMinEvictableIdleTimeMillis() + " ms");
        System.out.println("  timeBetweenEvictionRunsMillis: " + config.getTimeBetweenEvictionRunsMillis() + " ms");

        System.out.println("\n【其他配置】");
        System.out.println("  blockWhenExhausted: " + config.getBlockWhenExhausted());
        System.out.println("  lifo: " + config.getLifo());
        System.out.println("  fairness: " + config.getFairness());
        System.out.println("  numTestsPerEvictionRun: " + config.getNumTestsPerEvictionRun());

        System.out.println("\n========== 关键发现 ==========");
        if (!config.getTestOnBorrow()) {
            System.out.println("✅ testOnBorrow 默认值是 FALSE");
            System.out.println("   意味着：获取连接时不会执行 validateObject()");
            System.out.println("   但是：makeObject() 创建新连接时仍会连接到 Redis");
        } else {
            System.out.println("⚠️  testOnBorrow 默认值是 TRUE");
        }

        System.out.println("\n========== 完成 ==========");
    }
}
