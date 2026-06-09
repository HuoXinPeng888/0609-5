package com.shop.order.service;

import com.shop.order.client.InventoryClient;
import com.shop.order.client.InventoryClient.RestoreRequest;
import com.shop.order.model.Order;
import com.shop.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PAYING 状态超时自动取消定时任务
 * 扫描超时的 PAYING 订单，自动取消并释放库存
 */
@Component
public class PayingTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PayingTimeoutJob.class);

    private final OrderRepository orderRepo;
    private final InventoryClient inventoryClient;

    @Value("${order.pay-timeout-seconds:1800}")
    private int payTimeoutSeconds;

    public PayingTimeoutJob(OrderRepository orderRepo, InventoryClient inventoryClient) {
        this.orderRepo = orderRepo;
        this.inventoryClient = inventoryClient;
    }

    @Scheduled(fixedRate = 60000)  // 每分钟检查一次
    public void cancelExpiredPayingOrders() {
        List<Order> payingOrders = orderRepo.findByStatus(OrderStateMachine.PAYING);

        if (payingOrders.isEmpty()) {
            return;
        }

        LocalDateTime expireThreshold = LocalDateTime.now().minusSeconds(payTimeoutSeconds);

        for (Order order : payingOrders) {
            if (order.getUpdateTime().isBefore(expireThreshold)) {
                try {
                    log.warn("PAYING 超时，自动取消: orderId={}, orderNo={}, updateTime={}",
                            order.getId(), order.getOrderNo(), order.getUpdateTime());

                    // 释放库存
                    try {
                        RestoreRequest restoreRequest = new RestoreRequest(order.getId());
                        inventoryClient.restoreStock(restoreRequest);
                        log.info("超时取消-库存释放成功: orderId={}", order.getId());
                    } catch (Exception e) {
                        log.error("超时取消-库存释放失败，跳过本次: orderId={}, error={}",
                                order.getId(), e.getMessage());
                        continue; // 库存释放失败，本次不取消，等下次重试
                    }

                    order.setStatus(OrderStateMachine.CANCELLED);
                    order.setUpdateTime(LocalDateTime.now());
                    orderRepo.save(order);

                    log.info("超时订单已取消: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
                } catch (Exception e) {
                    log.error("处理超时订单失败: orderId={}, error={}", order.getId(), e.getMessage());
                }
            }
        }
    }
}
