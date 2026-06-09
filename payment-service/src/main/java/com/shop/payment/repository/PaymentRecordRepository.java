package com.shop.payment.repository;

import com.shop.payment.model.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 支付记录数据访问层
 */
@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {

    /**
     * 根据订单ID查询支付记录
     */
    Optional<PaymentRecord> findByOrderId(String orderId);

    /**
     * 根据订单ID和状态查询
     */
    Optional<PaymentRecord> findByOrderIdAndStatus(String orderId, String status);

    /**
     * 根据支付单号查询
     */
    Optional<PaymentRecord> findByPaymentNo(String paymentNo);

    /**
     * 根据状态查询所有记录
     */
    List<PaymentRecord> findByStatus(String status);
}
