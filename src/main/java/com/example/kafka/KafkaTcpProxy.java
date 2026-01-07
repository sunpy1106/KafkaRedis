package com.example.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka TCP 代理 - 可控制的网络中间层
 *
 * 用途：
 *  - 在 Producer 和 Kafka broker 之间建立代理
 *  - 初始时正常转发流量
 *  - 在需要时停止转发（模拟网络挂起）
 *  - 触发 "batch creation" 超时
 */
public class KafkaTcpProxy {
    private static final Logger logger = LoggerFactory.getLogger(KafkaTcpProxy.class);
    private static final AtomicBoolean shouldForwardRequests = new AtomicBoolean(true);
    private static final AtomicBoolean shouldForwardResponses = new AtomicBoolean(true);
    private static final AtomicBoolean isRunning = new AtomicBoolean(true);

    private final int proxyPort;
    private final String kafkaHost;
    private final int kafkaPort;

    public KafkaTcpProxy(int proxyPort, String kafkaHost, int kafkaPort) {
        this.proxyPort = proxyPort;
        this.kafkaHost = kafkaHost;
        this.kafkaPort = kafkaPort;
    }

    public void start() {
        logger.info("启动 Kafka TCP 代理:");
        logger.info("  监听端口: {}", proxyPort);
        logger.info("  转发到: {}:{}", kafkaHost, kafkaPort);
        logger.info("");

        try (ServerSocket serverSocket = new ServerSocket(proxyPort)) {
            logger.info("✅ 代理已启动，等待连接...");
            logger.info("   Producer 应连接到: localhost:{}", proxyPort);
            logger.info("");

            while (isRunning.get()) {
                Socket clientSocket = serverSocket.accept();
                logger.info("✅ 接受客户端连接: {}", clientSocket.getRemoteSocketAddress());

                // 为每个连接创建新线程处理
                Thread handlerThread = new Thread(() -> handleConnection(clientSocket));
                handlerThread.setDaemon(true);
                handlerThread.start();
            }

        } catch (IOException e) {
            logger.error("代理服务器错误: {}", e.getMessage(), e);
        }
    }

    private void handleConnection(Socket clientSocket) {
        Socket kafkaSocket = null;
        try {
            // 连接到真实的 Kafka broker
            kafkaSocket = new Socket(kafkaHost, kafkaPort);
            logger.info("✅ 已连接到 Kafka broker: {}:{}", kafkaHost, kafkaPort);

            final Socket finalKafkaSocket = kafkaSocket;

            // 客户端 -> Kafka 的转发线程（请求）
            Thread clientToKafka = new Thread(() -> {
                try {
                    forwardData(clientSocket.getInputStream(),
                               finalKafkaSocket.getOutputStream(),
                               "Client->Kafka", shouldForwardRequests);
                } catch (IOException e) {
                    logger.debug("Client->Kafka 转发结束");
                }
            });
            clientToKafka.setDaemon(true);
            clientToKafka.start();

            // Kafka -> 客户端的转发线程（响应）
            Thread kafkaToClient = new Thread(() -> {
                try {
                    forwardData(finalKafkaSocket.getInputStream(),
                               clientSocket.getOutputStream(),
                               "Kafka->Client", shouldForwardResponses);
                } catch (IOException e) {
                    logger.debug("Kafka->Client 转发结束");
                }
            });
            kafkaToClient.setDaemon(true);
            kafkaToClient.start();

            // 等待转发线程结束
            clientToKafka.join();
            kafkaToClient.join();

        } catch (Exception e) {
            logger.error("连接处理错误: {}", e.getMessage());
        } finally {
            try {
                if (kafkaSocket != null) kafkaSocket.close();
                clientSocket.close();
                logger.info("连接已关闭");
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void forwardData(InputStream input, OutputStream output, String direction, AtomicBoolean shouldForward) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
            // 检查是否应该继续转发
            if (!shouldForward.get()) {
                logger.warn("⚠️  停止转发 {} 的数据（模拟网络挂起）", direction);
                // 不再转发数据，但保持连接
                // 这会导致发送方等待超时
                while (shouldForward.get() == false && isRunning.get()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!isRunning.get()) break;
            }

            output.write(buffer, 0, bytesRead);
            output.flush();
        }
    }

    /**
     * 停止转发数据（但保持连接） - 停止所有方向
     */
    public static void stopForwarding() {
        shouldForwardRequests.set(false);
        shouldForwardResponses.set(false);
        logger.warn("");
        logger.warn("🛑 已停止所有数据转发");
        logger.warn("   TCP 连接保持，但不转发数据");
        logger.warn("   这将导致 Producer 等待超时");
        logger.warn("");
    }

    /**
     * 仅停止响应转发（Kafka->Client），保持请求转发（Client->Kafka）
     * 这样 Producer 的请求能到达 Broker，但收不到响应
     */
    public static void stopResponseForwarding() {
        shouldForwardResponses.set(false);
        logger.warn("");
        logger.warn("🛑 已停止响应转发（Kafka->Client）");
        logger.warn("   请求仍会转发（Client->Kafka）");
        logger.warn("   Producer 发送的消息会到达 Broker");
        logger.warn("   但 Producer 收不到 ACK 响应");
        logger.warn("   这将导致 delivery.timeout.ms 超时");
        logger.warn("");
    }

    /**
     * 恢复转发数据
     */
    public static void resumeForwarding() {
        shouldForwardRequests.set(true);
        shouldForwardResponses.set(true);
        logger.info("✅ 已恢复所有数据转发");
    }

    /**
     * 停止代理服务器
     */
    public static void shutdown() {
        isRunning.set(false);
        logger.info("代理服务器已停止");
    }

    public static void main(String[] args) {
        logger.info("========== Kafka TCP 代理服务器 ==========\n");

        // 代理监听 19092，转发到 localhost:9092
        KafkaTcpProxy proxy = new KafkaTcpProxy(19092, "localhost", 9092);

        // 在单独的线程启动代理
        Thread proxyThread = new Thread(() -> proxy.start());
        proxyThread.setDaemon(true);
        proxyThread.start();

        // 等待代理启动
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            // ignore
        }

        logger.info("代理已就绪！");
        logger.info("");
        logger.info("使用说明:");
        logger.info("1. Producer 连接到 localhost:19092");
        logger.info("2. 代理会转发到真实的 Kafka (localhost:9092)");
        logger.info("3. 在其他测试类中调用:");
        logger.info("   - KafkaTcpProxy.stopResponseForwarding() - 仅阻止响应（推荐）");
        logger.info("   - KafkaTcpProxy.stopForwarding() - 阻止所有流量");
        logger.info("4. 这将导致 Producer 等待超时");
        logger.info("");

        // 保持运行
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // ignore
        }
    }
}
