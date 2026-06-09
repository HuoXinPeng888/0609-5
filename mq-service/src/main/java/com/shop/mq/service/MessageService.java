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

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;

    private static final String STREAM_KEY = "payment-events";
    private static final String GROUP_NAME = "order-processor";
    private static final String CONSUMER_NAME = "consumer-1";
    private static final String DEAD_LETTER_KEY = "payment-events:dead-letter";
    private static final int MAX_RETRIES = 3;

    @Value("${order.service.url:http://localhost:8081}")
    private String orderServiceUrl;

    public MessageService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.restTemplate = new RestTemplate();
        initConsumerGroup();
    }

    private void initConsumerGroup() {
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM_KEY))) {
                MapRecord<String, String, String> record = StreamRecords.newRecord()
                        .ofMap(Map.of("init", "true"))
                        .withStreamKey(STREAM_KEY);
                redisTemplate.opsForStream().add(record);
            }
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP_NAME);
            log.info("消费者组已创建: group={}", GROUP_NAME);
        } catch (Exception e) {
            log.debug("消费者组初始化: {}", e.getMessage());
        }
    }

    /**
     * 消费消息：先处理，后ACK
     * 处理失败的消息保留在 pending list 中等待重试
     */
    @Scheduled(fixedDelay = 1000)
    public void consume() {
        try {
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
                try {
                    // 先处理消息
                    processMessage(record);
                    // 处理成功后才ACK
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
                    log.info("消息处理成功并已ACK: id={}", record.getId());
                } catch (Exception e) {
                    // 处理失败，不ACK，消息留在 pending list
                    log.error("消息处理失败，将等待重试: id={}, error={}", record.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("消费消息异常: {}", e.getMessage());
        }
    }

    /**
     * 定时扫描 pending list 中长时间未 ACK 的消息进行重试
     * 超过最大重试次数的消息转入死信队列
     */
    @Scheduled(fixedDelay = 30000) // 每30秒检查一次
    public void retryPendingMessages() {
        try {
            PendingMessages pendingMessages = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, GROUP_NAME, org.springframework.data.domain.Range.unbounded(), 50);

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return;
            }

            for (PendingMessage pending : pendingMessages) {
                // 只处理闲置超过10秒的消息
                if (pending.getElapsedTimeSinceLastDelivery().toMillis() < 10000) {
                    continue;
                }

                RecordId messageId = pending.getId();

                if (pending.getTotalDeliveryCount() > MAX_RETRIES) {
                    // 超过最大重试次数，转入死信队列
                    moveToDeadLetter(messageId);
                    // ACK 原消息，移出 pending list
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, messageId);
                    log.warn("消息超过最大重试次数，已移入死信队列: id={}, retries={}",
                            messageId, pending.getTotalDeliveryCount());
                } else {
                    // 重新投递（claim）消息进行重试
                    try {
                        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                                .claim(STREAM_KEY, GROUP_NAME, CONSUMER_NAME,
                                        Duration.ofSeconds(10), messageId);

                        if (claimed != null) {
                            for (MapRecord<String, Object, Object> record : claimed) {
                                try {
                                    processMessage(record);
                                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
                                    log.info("重试消息处理成功: id={}", record.getId());
                                } catch (Exception e) {
                                    log.error("重试消息处理失败: id={}, retries={}, error={}",
                                            record.getId(), pending.getTotalDeliveryCount(), e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("Claim 消息失败: id={}, error={}", messageId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("重试 pending 消息异常: {}", e.getMessage());
        }
    }

    /**
     * 将失败消息写入死信队列
     */
    private void moveToDeadLetter(RecordId messageId) {
        try {
            // 读取原始消息
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .range(STREAM_KEY, org.springframework.data.domain.Range.closed(
                            messageId.getValue(), messageId.getValue()));

            if (records != null && !records.isEmpty()) {
                MapRecord<String, Object, Object> original = records.get(0);
                Map<String, String> deadLetterPayload = new HashMap<>();
                for (Map.Entry<Object, Object> entry : original.getValue().entrySet()) {
                    deadLetterPayload.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                deadLetterPayload.put("_originalId", messageId.getValue());
                deadLetterPayload.put("_failTime", java.time.LocalDateTime.now().toString());

                MapRecord<String, String, String> deadRecord = StreamRecords.newRecord()
                        .ofMap(deadLetterPayload)
                        .withStreamKey(DEAD_LETTER_KEY);
                redisTemplate.opsForStream().add(deadRecord);
                log.info("消息已写入死信队列: originalId={}", messageId);
            }
        } catch (Exception e) {
            log.error("写入死信队列失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    /**
     * 处理支付成功消息 - 调用订单服务更新状态
     */
    private void processMessage(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        String orderId = (String) body.get("orderId");

        if (orderId == null || "true".equals(body.get("init"))) {
            // 跳过初始化记录
            return;
        }

        log.info("处理支付成功消息: orderId={}", orderId);

        String url = orderServiceUrl + "/api/orders/" + orderId + "/status";
        Map<String, String> request = new HashMap<>();
        request.put("status", "PAID");

        restTemplate.postForEntity(url, request, String.class);
        log.info("订单 {} 状态已更新为 PAID", orderId);
    }

    public String send(String topic, Map<String, Object> payload) {
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

    public long getPendingCount() {
        try {
            PendingInfo pending = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP_NAME);
            return pending.getTotalCount();
        } catch (Exception e) {
            log.error("查询待处理消息失败: {}", e.getMessage());
            return 0;
        }
    }

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
