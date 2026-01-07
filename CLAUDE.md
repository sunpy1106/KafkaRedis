# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java 8 project demonstrating Kafka producer with Redis-based message deduplication. Primary focus is on testing and documenting production failure scenarios including connection timeouts, pool exhaustion, and exception handling patterns.

## Build & Run Commands

```bash
# Build
mvn clean install

# Run main demo
mvn exec:java -Dexec.mainClass="com.example.kafka.Main"

# Start infrastructure (Redis, Zookeeper, Kafka)
docker-compose up -d

# Stop infrastructure
docker-compose down

# Run specific test scenario (32 test classes available)
mvn exec:java -Dexec.mainClass="com.example.kafka.TestJedisConnectionExceptionWithoutTestOnBorrow"
mvn exec:java -Dexec.mainClass="com.example.kafka.TestBatchCreationDockerPause"
```

## Architecture

**Core Message Flow:**
1. `MessageService` - Orchestration layer that checks Redis for duplicate UUIDs before sending to Kafka
2. `KafkaProducerService` - Kafka producer wrapper with configurable sync/async sending
3. `RedisService` - Jedis connection pool management with configurable pool settings and 7-day UUID expiration

**Package Structure:**
- `com.example.kafka.model` - Message entity
- `com.example.kafka.service` - Core services (MessageService, KafkaProducerService, RedisService)
- Root package contains 32 test/scenario classes for failure reproduction

## Test Classes

This project uses executable main classes (not JUnit) to simulate production scenarios:

- **Redis Exception Tests**: `TestRedisPoolException`, `TestJedisConnectionException*`, `TestRedisConnectionFailure`
- **Kafka Timeout Tests**: `TestBatchCreation*` (12 variants for different timeout scenarios)
- **Verification Utilities**: `CheckDefaultConfig`, `VerifyJedisException`

## Configuration

All settings in `src/main/resources/application.properties`:
- Kafka: bootstrap.servers, topic, acks, retries
- Redis: host, port, timeout, pool settings (maxTotal, maxIdle, minIdle, maxWaitMillis, testOnBorrow)

## Key Dependencies

- kafka-clients 2.8.1
- jedis 3.1.0
- fastjson 1.2.78
- slf4j 1.7.32

## Documentation

Extensive docs in `/Documentation/` folder covering:
- `QUICK_START.md` - Fast-track setup guide
- `PRODUCTION_SCENARIO_GUIDE.md` - Debugging production failures
- `IMPLEMENTATION_SUMMARY.md` - JedisConnectionException trigger mechanisms
- `TEST_CLASSES_EXPLANATION.md` - Complete test class overview
