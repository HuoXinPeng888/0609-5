package com.shop.payment.controller;

import com.shop.payment.model.PaymentRecord;
import com.shop.payment.service.PaymentService;
import com.shop.payment.service.PaymentService.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<?> processPayment(@RequestBody PaymentRequest request) {
        try {
            log.info("发起支付请求: orderId={}, amount={}", request.getOrderId(), request.getAmount());
            PaymentResult result = paymentService.processPayment(request.getOrderId(), request.getAmount());

            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "paymentId", result.getPaymentNo(),
                    "message", result.getMessage()
            ));
        } catch (Exception e) {
            log.error("支付处理失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "支付处理失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getPaymentStatus(@PathVariable String orderId) {
        try {
            PaymentRecord record = paymentService.getPaymentByOrderId(orderId);
            return ResponseEntity.ok(Map.of(
                    "orderId", record.getOrderId(),
                    "status", record.getStatus(),
                    "amount", record.getAmount()
            ));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<?> refund(@PathVariable Long paymentId) {
        try {
            log.info("退款请求: paymentId={}", paymentId);
            PaymentResult result = paymentService.refund(paymentId);

            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "paymentNo", result.getPaymentNo(),
                    "message", result.getMessage()
            ));
        } catch (Exception e) {
            log.error("退款处理失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "退款处理失败: " + e.getMessage()
            ));
        }
    }

    public static class PaymentRequest {
        private String orderId;
        private BigDecimal amount;

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }
}
