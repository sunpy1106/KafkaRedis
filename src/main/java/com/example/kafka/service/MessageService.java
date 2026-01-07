package com.example.kafka.service;

import com.example.kafka.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息发送主服务类
 * 实现消息去重逻辑：发送前检查Redis，发送后写入Redis
 */
public class MessageService {
    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);

    private RedisService redisService;
    private KafkaProducerService kafkaProducerService;

    public MessageService() {
        this.redisService = new RedisService();
        this.kafkaProducerService = new KafkaProducerService();
    }

    /**
     * 发送消息（带去重检查）
     * 1. 先查询Redis确认消息UUID是否已存在
     * 2. 如果不存在，发送到Kafka
     * 3. 发送成功后，将UUID写入Redis
     *
     * @param message 消息对象
     * @return true-发送成功，false-发送失败或消息已存在
     */
    public boolean sendMessage(Message message) {
        if (message == null || message.getUuid() == null) {
            logger.error("消息对象或UUID为空");
            return false;
        }

        String uuid = message.getUuid();
        logger.info("准备发送消息 - UUID: {}, Content: {}", uuid, message.getContent());

        try {
            // 1. 检查Redis中是否已存在该UUID
            if (redisService.isUuidExists(uuid)) {
                logger.warn("消息已存在，跳过发送 - UUID: {}", uuid);
                return false;
            }

            // 2. 发送消息到Kafka
            boolean sendSuccess = kafkaProducerService.sendMessage(message);
            if (!sendSuccess) {
                logger.error("Kafka发送失败 - UUID: {}", uuid);
                return false;
            }

            // 3. Kafka发送成功后，将UUID写入Redis
            boolean saveSuccess = redisService.saveUuid(uuid);
            if (!saveSuccess) {
                logger.error("UUID写入Redis失败 - UUID: {}", uuid);
                // 注意：此时消息已发送到Kafka，但Redis记录失败
                // 根据业务需求决定是否需要补偿机制
                return false;
            }

            logger.info("消息发送完成 - UUID: {}", uuid);
            return true;

        } catch (Exception e) {
            logger.error("发送消息过程中发生异常 - UUID: {}", uuid, e);
            return false;
        }
    }

    /**
     * 发送消息（强制发送，不检查去重）
     * 仅用于特殊场景
     *
     * @param message 消息对象
     * @return true-发送成功，false-发送失败
     */
    public boolean sendMessageForce(Message message) {
        if (message == null || message.getUuid() == null) {
            logger.error("消息对象或UUID为空");
            return false;
        }

        String uuid = message.getUuid();
        logger.info("强制发送消息（不检查去重） - UUID: {}", uuid);

        try {
            // 直接发送到Kafka
            boolean sendSuccess = kafkaProducerService.sendMessage(message);
            if (!sendSuccess) {
                logger.error("Kafka发送失败 - UUID: {}", uuid);
                return false;
            }

            // 写入Redis
            redisService.saveUuid(uuid);
            return true;

        } catch (Exception e) {
            logger.error("强制发送消息失败 - UUID: {}", uuid, e);
            return false;
        }
    }

    /**
     * 检查消息是否已发送
     *
     * @param uuid 消息UUID
     * @return true-已发送，false-未发送
     */
    public boolean isMessageSent(String uuid) {
        return redisService.isUuidExists(uuid);
    }

    /**
     * 关闭服务
     */
    public void close() {
        if (kafkaProducerService != null) {
            kafkaProducerService.close();
        }
        if (redisService != null) {
            redisService.close();
        }
        logger.info("MessageService已关闭");
    }
}
