package com.shop.payment.model;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 支付记录实体类
 * 存储支付的详细信息
 */
@Entity
@Table(name = "payment_records")
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联的订单ID
    private String orderId;

    // 支付单号（唯一）
    @Column(unique = true, nullable = false)
    private String paymentNo;

    // 支付金额（使用 double，存在精度问题）
    private double amount;

    // 支付状态：PENDING, PROCESSING, SUCCESS, FAILED
    private String status;

    // 第三方网关交易号
    private String gatewayTxnId;

    // 错误信息
    private String errorMessage;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public PaymentRecord() {
    }

    public PaymentRecord(String orderId, String paymentNo, double amount) {
        this.orderId = orderId;
        this.paymentNo = paymentNo;
        this.amount = amount;
        this.status = "PENDING";
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }

    public String getGatewayTxnId() {
        return gatewayTxnId;
    }

    public void setGatewayTxnId(String gatewayTxnId) {
        this.gatewayTxnId = gatewayTxnId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "PaymentRecord{" +
                "id=" + id +
                ", orderId='" + orderId + '\'' +
                ", paymentNo='" + paymentNo + '\'' +
                ", amount=" + amount +
                ", status='" + status + '\'' +
                ", createTime=" + createTime +
                '}';
    }
}
