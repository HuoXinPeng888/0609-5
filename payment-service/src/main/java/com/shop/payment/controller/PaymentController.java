package com.shop.payment.controller;

import com.shop.payment.model.PaymentRecord;
import com.shop.payment.service.PaymentService;
import com.shop.payment.service.PaymentService.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付控制器
 * 提供支付相关的 REST API
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 发起支付
     * POST /api/payments
     */
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

    /**
     * 查询支付状态
     * GET /api/payments/{orderId}
     */
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

    /**
     * 退款
     * POST /api/payments/{paymentId}/refund
     */
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

    /**
     * 支付请求体
     */
    public static class PaymentRequest {
        private String orderId;
        private double amount;

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }
    }
}
