package com.shop.mq.controller;

import com.shop.mq.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 消息队列控制器
 * 提供消息发送和查询的 REST API
 */
@RestController
@RequestMapping("/api/mq")
public class MQController {

    private static final Logger log = LoggerFactory.getLogger(MQController.class);

    private final MessageService messageService;

    public MQController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 发送消息
     * POST /api/mq/send
     */
    @PostMapping("/send")
    public ResponseEntity<?> sendMessage(@RequestBody SendRequest request) {
        try {
            log.info("发送消息请求: topic={}, payload={}", request.getTopic(), request.getPayload());
            String messageId = messageService.send(request.getTopic(), request.getPayload());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "messageId", messageId
            ));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "发送失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查看待处理消息数量
     * GET /api/mq/pending
     */
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingCount() {
        long count = messageService.getPendingCount();
        return ResponseEntity.ok(Map.of(
                "pendingCount", count
        ));
    }

    /**
     * 查看死信队列
     * GET /api/mq/dead-letter
     */
    @GetMapping("/dead-letter")
    public ResponseEntity<?> getDeadLetters() {
        List<MapRecord<String, Object, Object>> deadLetters = messageService.getDeadLetters();
        return ResponseEntity.ok(Map.of(
                "count", deadLetters.size(),
                "messages", deadLetters
        ));
    }

    /**
     * 发送消息请求体
     */
    public static class SendRequest {
        private String topic;
        private Map<String, Object> payload;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public void setPayload(Map<String, Object> payload) {
            this.payload = payload;
        }
    }
}
