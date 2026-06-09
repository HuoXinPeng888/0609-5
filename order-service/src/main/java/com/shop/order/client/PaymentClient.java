package com.shop.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * 支付服务 Feign 客户端
 * 用于调用 payment-service 的支付相关接口
 */
@FeignClient(name = "payment-service", url = "${payment.service.url}")
public interface PaymentClient {

    /**
     * 发起支付
     *
     * @param request 支付请求
     * @return 支付响应
     */
    @PostMapping("/api/payments")
    PaymentResponse initiatePayment(@RequestBody PaymentRequest request);

    /**
     * 查询支付状态
     *
     * @param orderId 订单ID
     * @return 支付状态
     */
    @GetMapping("/api/payments/{orderId}")
    PaymentStatusResponse getPaymentStatus(@PathVariable String orderId);

    /**
     * 支付请求
     */
    class PaymentRequest {
        private String orderId;
        private double amount;

        public PaymentRequest() {
        }

        public PaymentRequest(String orderId, double amount) {
            this.orderId = orderId;
            this.amount = amount;
        }

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

    /**
     * 支付响应
     */
    class PaymentResponse {
        private String paymentId;
        private boolean success;
        private String message;

        public PaymentResponse() {
        }

        public PaymentResponse(String paymentId, boolean success, String message) {
            this.paymentId = paymentId;
            this.success = success;
            this.message = message;
        }

        public String getPaymentId() {
            return paymentId;
        }

        public void setPaymentId(String paymentId) {
            this.paymentId = paymentId;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * 支付状态查询响应
     */
    class PaymentStatusResponse {
        private String orderId;
        private String status;
        private double amount;

        public PaymentStatusResponse() {
        }

        public PaymentStatusResponse(String orderId, String status, double amount) {
            this.orderId = orderId;
            this.status = status;
            this.amount = amount;
        }

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }
    }
}
