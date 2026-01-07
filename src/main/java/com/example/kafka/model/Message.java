package com.example.kafka.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * 消息实体类
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private String uuid;
    private String content;
    private long timestamp;

    public Message() {
        this.uuid = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    public Message(String content) {
        this();
        this.content = content;
    }

    public Message(String uuid, String content) {
        this.uuid = uuid;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Message{" +
                "uuid='" + uuid + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
