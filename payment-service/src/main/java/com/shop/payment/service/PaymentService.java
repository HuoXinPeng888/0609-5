package com.shop.payment.service;

import com.shop.payment.model.PaymentRecord;
import com.shop.payment.repository.PaymentRecordRepository;
import com.shop.payment.client.MqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRecordRepository paymentRepo;
    private final PaymentGateway paymentGateway;
    private final MqClient mqClient;

    public PaymentService(PaymentRecordRepository paymentRepo,
                          PaymentGateway paymentGateway,
                          MqClient mqClient) {
        this.paymentRepo = paymentRepo;
        this.paymentGateway = paymentGateway;
        this.mqClient = mqClient;
    }

    /**
     * 发起支付（带幂等性控制）
     * 使用 orderId 作为幂等key，同一订单不会重复扣款
     */
    @Transactional
    public PaymentResult processPayment(String orderId, BigDecimal amount) {
        // 幂等性检查：已有成功的支付记录，直接返回
        Optional<PaymentRecord> existing = paymentRepo.findByOrderIdAndStatus(orderId, "SUCCESS");
        if (existing.isPresent()) {
            PaymentRecord record = existing.get();
            log.info("支付幂等命中，订单已支付: orderId={}, paymentNo={}", orderId, record.getPaymentNo());
            return new PaymentResult(true, record.getPaymentNo(), "已支付");
        }

        // 检查是否有进行中的支付（防止并发重复创建）
        Optional<PaymentRecord> processing = paymentRepo.findByOrderIdAndStatus(orderId, "PROCESSING");
        if (processing.isPresent()) {
            PaymentRecord record = processing.get();
            log.info("支付进行中，请勿重复提交: orderId={}, paymentNo={}", orderId, record.getPaymentNo());
            return new PaymentResult(false, record.getPaymentNo(), "支付处理中，请稍后查询结果");
        }

        // 使用 UUID 生成支付单号，避免时间戳碰撞
        String paymentNo = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setPaymentNo(paymentNo);
        record.setAmount(amount);
        record.setStatus("PROCESSING");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        paymentRepo.save(record);

        log.info("创建支付记录: orderId={}, paymentNo={}, amount={}", orderId, paymentNo, amount);

        try {
            PaymentGateway.GatewayResult result = paymentGateway.charge(orderId, amount, paymentNo);

            if (result.isSuccess()) {
                record.setStatus("SUCCESS");
                record.setGatewayTxnId(result.getTxnId());
                record.setUpdateTime(LocalDateTime.now());
                paymentRepo.save(record);

                log.info("支付成功: orderId={}, paymentNo={}, txnId={}",
                        orderId, paymentNo, result.getTxnId());

                // 发送支付成功消息到 MQ，带重试
                sendPaymentSuccessMessage(orderId, paymentNo, amount);

                return new PaymentResult(true, paymentNo, "支付成功");
            } else {
                record.setStatus("FAILED");
                record.setErrorMessage(result.getMessage());
                record.setUpdateTime(LocalDateTime.now());
                paymentRepo.save(record);

                log.warn("支付失败: orderId={}, message={}", orderId, result.getMessage());
                return new PaymentResult(false, paymentNo, result.getMessage());
            }
        } catch (Exception e) {
            record.setStatus("PROCESSING");
            record.setErrorMessage("网关调用异常: " + e.getMessage());
            record.setUpdateTime(LocalDateTime.now());
            paymentRepo.save(record);

            log.error("支付网关调用异常: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("支付处理异常: " + e.getMessage(), e);
        }
    }

    /**
     * 发送支付成功消息，最多重试3次
     */
    private void sendPaymentSuccessMessage(String orderId, String paymentNo, BigDecimal amount) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                mqClient.send("payment.success", Map.of(
                        "orderId", orderId,
                        "paymentNo", paymentNo,
                        "amount", amount
                ));
                log.info("支付成功消息已发送: orderId={}", orderId);
                return;
            } catch (Exception e) {
                log.error("发送支付成功消息失败 (第{}次): orderId={}, error={}", i + 1, orderId, e.getMessage());
                if (i == maxRetries - 1) {
                    log.error("支付成功消息发送最终失败，需人工介入: orderId={}", orderId);
                }
            }
        }
    }

    public PaymentRecord getPaymentByOrderId(String orderId) {
        return paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("支付记录不存在: orderId=" + orderId));
    }

    public PaymentRecord getPaymentById(Long id) {
        return paymentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("支付记录不存在: id=" + id));
    }

    /**
     * 退款（带幂等性控制）
     */
    @Transactional
    public PaymentResult refund(Long paymentId) {
        PaymentRecord record = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("支付记录不存在: id=" + paymentId));

        // 幂等：已退款直接返回成功
        if ("REFUNDED".equals(record.getStatus())) {
            log.info("退款幂等命中，已退款: paymentId={}", paymentId);
            return new PaymentResult(true, record.getPaymentNo(), "已退款");
        }

        if (!"SUCCESS".equals(record.getStatus())) {
            throw new RuntimeException("只有支付成功的订单才能退款");
        }

        try {
            PaymentGateway.GatewayResult result = paymentGateway.refund(
                    record.getOrderId(),
                    record.getAmount(),
                    record.getGatewayTxnId()
            );

            if (result.isSuccess()) {
                record.setStatus("REFUNDED");
                record.setUpdateTime(LocalDateTime.now());
                paymentRepo.save(record);

                log.info("退款成功: paymentId={}", paymentId);
                return new PaymentResult(true, record.getPaymentNo(), "退款成功");
            } else {
                log.warn("退款失败: paymentId={}, message={}", paymentId, result.getMessage());
                return new PaymentResult(false, record.getPaymentNo(), result.getMessage());
            }
        } catch (Exception e) {
            log.error("退款异常: paymentId={}, error={}", paymentId, e.getMessage());
            throw new RuntimeException("退款处理异常: " + e.getMessage(), e);
        }
    }

    public static class PaymentResult {
        private final boolean success;
        private final String paymentNo;
        private final String message;

        public PaymentResult(boolean success, String paymentNo, String message) {
            this.success = success;
            this.paymentNo = paymentNo;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getPaymentNo() {
            return paymentNo;
        }

        public String getMessage() {
            return message;
        }
    }
}
