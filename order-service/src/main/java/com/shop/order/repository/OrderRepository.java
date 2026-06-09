package com.shop.order.repository;

import com.shop.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 订单数据访问层
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 根据订单号查询
     */
    Optional<Order> findByOrderNo(String orderNo);

    /**
     * 根据用户ID查询所有订单
     */
    List<Order> findByUserId(Long userId);

    /**
     * 根据状态查询订单
     */
    List<Order> findByStatus(String status);
}
