package com.shop.order.model;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 订单实体类
 * 存储订单的基本信息，包括用户、金额、状态等
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String orderNo;

    private Long userId;

    // 注意：这里使用 double 类型存储金额，存在精度问题
    // 正确的做法应该使用 BigDecimal
    private double totalAmount;

    // 订单状态：CREATED, PAYING, PAID, SHIPPED, COMPLETED, CANCELLED
    private String status;

    // 锁定的库存记录ID，用于取消订单时释放库存
    private Long inventoryLockedId;

    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime updateTime;

    public Order() {
    }

    public Order(String orderNo, Long userId, double totalAmount) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.status = "CREATED";
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

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(double totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
    }

    public Long getInventoryLockedId() {
        return inventoryLockedId;
    }

    public void setInventoryLockedId(Long inventoryLockedId) {
        this.inventoryLockedId = inventoryLockedId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getPayTime() {
        return payTime;
    }

    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", orderNo='" + orderNo + '\'' +
                ", userId=" + userId +
                ", totalAmount=" + totalAmount +
                ", status='" + status + '\'' +
                ", createTime=" + createTime +
                '}';
    }
}
