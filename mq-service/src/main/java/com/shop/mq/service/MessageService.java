package com.shop.mq.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息服务
 * 基于 Redis Stream 实现消息队列
 * 负责消息的发送和消费
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;

    // Stream 配置
    private static final String STREAM_KEY = "payment-events";
    private static final String GROUP_NAME = "order-processor";
    private static final String CONSUMER_NAME = "consumer-1";
    private static final String DEAD_LETTER_KEY = "payment-events:dead-letter";

    @Value("${order.service.url:http://localhost:8081}")
    private String orderServiceUrl;

    public MessageService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.restTemplate = new RestTemplate();

        // 初始化消费者组
        initConsumerGroup();
    }

    /**
     * 初始化消费者组
     */
    private void initConsumerGroup() {
        try {
            // 创建 Stream（如果不存在）
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM_KEY))) {
                MapRecord<String, String, String> record = StreamRecords.newRecord()
                        .ofMap(Map.of("init", "true"))
                        .withStreamKey(STREAM_KEY);
                redisTemplate.opsForStream().add(record);
            }

            // 创建消费者组
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP_NAME);
            log.info("消费者组已创建: group={}", GROUP_NAME);
        } catch (Exception e) {
            // 消费者组可能已存在
            log.debug("消费者组初始化: {}", e.getMessage());
        }
    }

    /**
     * 消费支付成功消息，通知订单服务更新状态
     * 
     * 问题：先 ACK 后处理，如果处理失败消息已丢失！
     */
    @Scheduled(fixedDelay = 1000)
    public void consume() {
        try {
            // 从 Stream 读取消息
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                    );

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                // 问题：先确认消息再处理
                // 如果 processMessage 抛异常，消息已经被确认，无法重试
                redisTemplate.opsForStream().acknowledge(GROUP_NAME, record);

                try {
                    processMessage(record);
                } catch (Exception e) {
                    log.error("消息处理失败，但已 ACK，消息丢失: id={}, error={}",
                            record.getId(), e.getMessage());
                    // 消息已 ACK，无法重新消费！
                    // 应该将失败消息写入死信队列，但这里没有做
                }
            }
        } catch (Exception e) {
            log.error("消费消息异常: {}", e.getMessage());
        }
    }

    /**
     * 处理支付成功消息
     * 调用 order-service 更新订单状态
     */
    private void processMessage(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        String orderId = (String) body.get("orderId");

        log.info("处理支付成功消息: orderId={}", orderId);

        // 调用订单服务更新状态
        String url = orderServiceUrl + "/api/orders/" + orderId + "/status";

        Map<String, String> request = new HashMap<>();
        request.put("status", "PAID");

        // 如果订单服务不可用，这里会抛异常
        // 但消息已经被 ACK 了！
        restTemplate.postForEntity(url, request, String.class);

        log.info("订单 {} 状态已更新为 PAID", orderId);
    }

    /**
     * 发送消息到 Stream
     *
     * @param topic   消息主题
     * @param payload 消息内容
     * @return 消息ID
     */
    public String send(String topic, Map<String, Object> payload) {
        // 将 payload 转换为 String 类型
        Map<String, String> stringPayload = new HashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            stringPayload.put(entry.getKey(), String.valueOf(entry.getValue()));
        }

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .ofMap(stringPayload)
                .withStreamKey(STREAM_KEY);

        RecordId id = redisTemplate.opsForStream().add(record);
        log.info("消息已发送: stream={}, id={}, payload={}", STREAM_KEY, id, payload);

        return id.getValue();
    }

    /**
     * 查看待处理的消息数量
     */
    public long getPendingCount() {
        try {
            PendingInfo pending = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP_NAME);
            return pending.getTotalCount();
        } catch (Exception e) {
            log.error("查询待处理消息失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 查看死信队列中的消息
     */
    public List<MapRecord<String, Object, Object>> getDeadLetters() {
        try {
            return redisTemplate.opsForStream().read(
                    StreamReadOptions.empty().count(100),
                    StreamOffset.fromStart(DEAD_LETTER_KEY)
            );
        } catch (Exception e) {
            log.error("查询死信队列失败: {}", e.getMessage());
            return List.of();
        }
    }
}
