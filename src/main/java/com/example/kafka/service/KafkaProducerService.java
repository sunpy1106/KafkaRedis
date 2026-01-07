package com.example.kafka.service;

import com.alibaba.fastjson.JSON;
import com.example.kafka.model.Message;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Kafka生产者服务类
 */
public class KafkaProducerService {
    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerService.class);

    private KafkaProducer<String, String> producer;
    private String topic;

    public KafkaProducerService() {
        initProducer();
    }

    /**
     * 初始化Kafka生产者
     */
    private void initProducer() {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                logger.error("无法找到配置文件 application.properties");
                throw new RuntimeException("配置文件不存在");
            }

            Properties fileProps = new Properties();
            fileProps.load(input);

            // 设置Kafka生产者配置
            props.put("bootstrap.servers", fileProps.getProperty("kafka.bootstrap.servers"));
            props.put("acks", fileProps.getProperty("kafka.acks", "all"));
            props.put("retries", fileProps.getProperty("kafka.retries", "3"));
            props.put("batch.size", fileProps.getProperty("kafka.batch.size", "16384"));
            props.put("linger.ms", fileProps.getProperty("kafka.linger.ms", "1"));
            props.put("buffer.memory", fileProps.getProperty("kafka.buffer.memory", "33554432"));
            props.put("key.serializer", fileProps.getProperty("kafka.key.serializer"));
            props.put("value.serializer", fileProps.getProperty("kafka.value.serializer"));

            this.topic = fileProps.getProperty("kafka.topic");
            this.producer = new KafkaProducer<>(props);

            logger.info("Kafka生产者初始化成功, topic: {}", topic);
        } catch (IOException e) {
            logger.error("加载配置文件失败", e);
            throw new RuntimeException("加载配置文件失败", e);
        }
    }

    /**
     * 发送消息到Kafka
     *
     * @param message 消息对象
     * @return true-发送成功，false-发送失败
     */
    public boolean sendMessage(Message message) {
        try {
            String messageJson = JSON.toJSONString(message);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.getUuid(), messageJson);

            // 同步发送消息
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get();

            logger.info("消息发送成功 - UUID: {}, Topic: {}, Partition: {}, Offset: {}",
                    message.getUuid(), metadata.topic(), metadata.partition(), metadata.offset());
            return true;
        } catch (Exception e) {
            logger.error("发送消息失败 - UUID: {}, Content: {}", message.getUuid(), message.getContent(), e);
            return false;
        }
    }

    /**
     * 异步发送消息到Kafka（带回调）
     *
     * @param message 消息对象
     */
    public void sendMessageAsync(Message message) {
        String messageJson = JSON.toJSONString(message);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.getUuid(), messageJson);

        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                logger.info("消息异步发送成功 - UUID: {}, Topic: {}, Partition: {}, Offset: {}",
                        message.getUuid(), metadata.topic(), metadata.partition(), metadata.offset());
            } else {
                logger.error("消息异步发送失败 - UUID: {}", message.getUuid(), exception);
            }
        });
    }

    /**
     * 关闭Kafka生产者
     */
    public void close() {
        if (producer != null) {
            producer.close();
            logger.info("Kafka生产者已关闭");
        }
    }
}
