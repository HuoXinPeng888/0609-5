package com.shop.payment.service;

import com.shop.payment.model.PaymentRecord;
import com.shop.payment.repository.PaymentRecordRepository;
import com.shop.payment.client.MqClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

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
    public PaymentResult processPayment(String orderId, double amount) {
        // 缺少幂等性检查！
        // 正确做法：先查询是否存在 SUCCESS 状态的支付记录
        // PaymentRecord existing = paymentRepo.findByOrderIdAndStatus(orderId, "SUCCESS");
        // if (existing != null) {
        //     return new PaymentResult(true, existing.getPaymentNo(), "已支付");
        // }

        // 直接创建新的支付记录
        String paymentNo = "PAY-" + System.currentTimeMillis();
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
            // 调用第三方支付网关
            // 网关可能超时，导致支付状态不确定
            PaymentGateway.GatewayResult result = paymentGateway.charge(orderId, amount, paymentNo);

            if (result.isSuccess()) {
                // 支付成功
                record.setStatus("SUCCESS");
                record.setGatewayTxnId(result.getTxnId());
                record.setUpdateTime(LocalDateTime.now());
                paymentRepo.save(record);

                log.info("支付成功: orderId={}, paymentNo={}, txnId={}",
                        orderId, paymentNo, result.getTxnId());

                // 发送支付成功消息到 MQ
                // 通知订单服务更新订单状态
                try {
                    mqClient.send("payment.success", Map.of(
                            "orderId", orderId,
                            "paymentNo", paymentNo,
                            "amount", amount
                    ));
                    log.info("支付成功消息已发送: orderId={}", orderId);
                } catch (Exception e) {
                    // MQ 发送失败，但支付已成功
                    // 订单状态可能无法及时更新
                    log.error("发送支付成功消息失败: {}", e.getMessage());
                }

                return new PaymentResult(true, paymentNo, "支付成功");
            } else {
                // 支付失败
                record.setStatus("FAILED");
                record.setErrorMessage(result.getMessage());
                record.setUpdateTime(LocalDateTime.now());
                paymentRepo.save(record);

                log.warn("支付失败: orderId={}, message={}", orderId, result.getMessage());
                return new PaymentResult(false, paymentNo, result.getMessage());
            }
        } catch (Exception e) {
            // 网关超时或其他异常
            // 支付状态不确定，记录为 PROCESSING
            // 调用方可能会重试，但因为没有幂等控制，重试会创建新的支付记录！
            record.setStatus("PROCESSING");
            record.setErrorMessage("网关调用异常: " + e.getMessage());
            record.setUpdateTime(LocalDateTime.now());
            paymentRepo.save(record);

            log.error("支付网关调用异常: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("支付处理异常: " + e.getMessage(), e);
        }
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
