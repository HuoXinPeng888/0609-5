package com.shop.payment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 消息队列客户端
 * 通过 HTTP 调用 mq-service 发送消息
 */
@Component
public class MqClient {

    private static final Logger log = LoggerFactory.getLogger(MqClient.class);

    private final RestTemplate restTemplate;
    private final String mqServiceUrl;

    public MqClient(@Value("${mq.service.url:http://localhost:8084}") String mqServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.mqServiceUrl = mqServiceUrl;
    }

    /**
     * 发送消息到指定主题
     *
     * @param topic   消息主题
     * @param payload 消息内容
     */
    public void send(String topic, Map<String, Object> payload) {
        String url = mqServiceUrl + "/api/mq/send";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> request = Map.of(
                "topic", topic,
                "payload", payload
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            log.info("发送消息到 MQ: topic={}, payload={}", topic, payload);
            String response = restTemplate.postForObject(url, entity, String.class);
            log.info("消息发送成功: response={}", response);
        } catch (Exception e) {
            log.error("发送消息到 MQ 失败: topic={}, error={}", topic, e.getMessage());
            throw new RuntimeException("MQ 发送失败: " + e.getMessage(), e);
        }
    }
}
