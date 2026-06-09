package com.shop.payment.service;

import com.shop.payment.model.PaymentRecord;
import com.shop.payment.repository.PaymentRecordRepository;
import com.shop.payment.client.MqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 支付服务
 * 负责处理支付、查询支付状态和退款
 */
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
     * 发起支付
     * 注意：缺少幂等性控制！
     * 同一个 orderId 可以重复发起支付，导致重复扣款
     *
     * @param orderId 订单ID
     * @param amount  支付金额
     * @return 支付结果
     */
    public PaymentResult processPayment(String orderId, BigDecimal amount) {
        // Step 1: Idempotency + create PROCESSING record (in its own transaction)
        PaymentRecord record;
        try {
            record = createProcessingRecord(orderId, amount);
        } catch (Exception e) {
            // Duplicate payment or DB error
            throw new RuntimeException("支付请求处理失败: " + e.getMessage(), e);
        }

        // Step 2: Call payment gateway (outside transaction)
        PaymentGateway.GatewayResult result;
        try {
            result = paymentGateway.charge(orderId, amount, record.getPaymentNo());
        } catch (Exception e) {
            // Gateway timeout - record stays PROCESSING
            record.setErrorMessage("网关调用异常: " + e.getMessage());
            record.setUpdateTime(LocalDateTime.now());
            paymentRepo.save(record);
            throw new RuntimeException("支付处理异常: " + e.getMessage(), e);
        }

        // Step 3: Update record based on result
        if (result.isSuccess()) {
            record.setStatus("SUCCESS");
            record.setGatewayTxnId(result.getTxnId());
            record.setUpdateTime(LocalDateTime.now());
            paymentRepo.save(record);

            // Send MQ message
            try {
                mqClient.send("payment.success", Map.of(
                    "orderId", orderId,
                    "paymentNo", record.getPaymentNo(),
                    "amount", amount.toString()
                ));
            } catch (Exception e) {
                log.error("发送支付成功消息失败: {}", e.getMessage());
            }

            return new PaymentResult(true, record.getPaymentNo(), "支付成功");
        } else {
            record.setStatus("FAILED");
            record.setErrorMessage(result.getMessage());
            record.setUpdateTime(LocalDateTime.now());
            paymentRepo.save(record);
            return new PaymentResult(false, record.getPaymentNo(), result.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentRecord createProcessingRecord(String orderId, BigDecimal amount) {
        // Idempotency: check existing SUCCESS payment
        PaymentRecord existing = paymentRepo.findByOrderIdAndStatus(orderId, "SUCCESS")
            .orElse(null);
        if (existing != null) {
            throw new RuntimeException("该订单已支付成功，请勿重复操作");
        }

        // Create new PROCESSING record
        String paymentNo = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        PaymentRecord record = new PaymentRecord();
        record.setOrderId(orderId);
        record.setPaymentNo(paymentNo);
        record.setAmount(amount);
        record.setStatus("PROCESSING");
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return paymentRepo.save(record);
    }

    /**
     * 根据订单ID查询支付记录
     *
     * @param orderId 订单ID
     * @return 支付记录
     */
    public PaymentRecord getPaymentByOrderId(String orderId) {
        return paymentRepo.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("支付记录不存在: orderId=" + orderId));
    }

    /**
     * 根据ID查询支付记录
     *
     * @param id 支付记录ID
     * @return 支付记录
     */
    public PaymentRecord getPaymentById(Long id) {
        return paymentRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("支付记录不存在: id=" + id));
    }

    /**
     * 退款
     * 注意：同样缺少幂等性控制
     *
     * @param paymentId 支付记录ID
     * @return 退款结果
     */
    @Transactional
    public PaymentResult refund(Long paymentId) {
        PaymentRecord record = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("支付记录不存在: id=" + paymentId));

        if ("REFUNDED".equals(record.getStatus())) {
            return new PaymentResult(true, record.getPaymentNo(), "已退款，请勿重复操作");
        }

        if (!"SUCCESS".equals(record.getStatus())) {
            throw new RuntimeException("只有支付成功的订单才能退款");
        }

        // 调用网关退款
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

    /**
     * 支付结果
     */
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
