package com.shop.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * 支付网关模拟器
 * 模拟第三方支付网关的行为，包括网络延迟和偶发失败
 */
@Component
public class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);
    private static final Random random = new Random();

    /**
     * 模拟支付扣款
     * 包含随机延迟（模拟网络抖动）和 10% 的失败率
     *
     * @param orderId   订单ID
     * @param amount    支付金额
     * @param paymentNo 支付单号
     * @return 网关处理结果
     */
    public GatewayResult charge(String orderId, double amount, String paymentNo) {
        log.info("调用支付网关扣款: orderId={}, amount={}, paymentNo={}", orderId, amount, paymentNo);

        // 模拟网络延迟：500ms - 3000ms
        int delay = 500 + random.nextInt(2500);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("支付网关调用被中断");
        }

        // 10% 概率失败
        if (random.nextInt(100) < 10) {
            log.warn("支付网关返回失败: orderId={}", orderId);
            return new GatewayResult(false, null, "网关返回失败：账户余额不足或卡号异常");
        }

        // 5% 概率超时（延迟超过 2500ms 时模拟超时）
        if (delay > 2500) {
            log.warn("支付网关超时: orderId={}, delay={}ms", orderId, delay);
            throw new RuntimeException("支付网关调用超时");
        }

        // 支付成功
        String txnId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("支付网关扣款成功: orderId={}, txnId={}", orderId, txnId);
        return new GatewayResult(true, txnId, "支付成功");
    }

    /**
     * 模拟退款
     *
     * @param orderId      订单ID
     * @param amount       退款金额
     * @param gatewayTxnId 原交易号
     * @return 网关处理结果
     */
    public GatewayResult refund(String orderId, double amount, String gatewayTxnId) {
        log.info("调用支付网关退款: orderId={}, amount={}, gatewayTxnId={}", orderId, amount, gatewayTxnId);

        // 模拟网络延迟
        int delay = 500 + random.nextInt(1500);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("支付网关调用被中断");
        }

        // 5% 概率失败
        if (random.nextInt(100) < 5) {
            log.warn("支付网关退款失败: orderId={}", orderId);
            return new GatewayResult(false, null, "退款失败：原交易已超过退款期限");
        }

        // 退款成功
        String refundTxnId = "REFUND-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("支付网关退款成功: orderId={}, refundTxnId={}", orderId, refundTxnId);
        return new GatewayResult(true, refundTxnId, "退款成功");
    }

    /**
     * 网关处理结果
     */
    public static class GatewayResult {
        private final boolean success;
        private final String txnId;
        private final String message;

        public GatewayResult(boolean success, String txnId, String message) {
            this.success = success;
            this.txnId = txnId;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getTxnId() {
            return txnId;
        }

        public String getMessage() {
            return message;
        }
    }
}
