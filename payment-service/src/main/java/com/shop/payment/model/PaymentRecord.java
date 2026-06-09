package com.shop.payment.model;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_records")
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String orderId;

    @Column(unique = true, nullable = false)
    private String paymentNo;

    // 使用 BigDecimal 保证金额精度
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    private String status;

    private String gatewayTxnId;

    private String errorMessage;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public PaymentRecord() {
    }

    public PaymentRecord(String orderId, String paymentNo, BigDecimal amount) {
        this.orderId = orderId;
        this.paymentNo = paymentNo;
        this.amount = amount;
        this.status = "PENDING";
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
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
