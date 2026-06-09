package com.shop.order.service;

import com.shop.order.client.InventoryClient;
import com.shop.order.client.PaymentClient;
import com.shop.order.client.InventoryClient.DeductItem;
import com.shop.order.client.InventoryClient.DeductRequest;
import com.shop.order.client.InventoryClient.RestoreRequest;
import com.shop.order.client.PaymentClient.PaymentRequest;
import com.shop.order.client.PaymentClient.PaymentResponse;
import com.shop.order.model.Order;
import com.shop.order.model.OrderItem;
import com.shop.order.repository.OrderRepository;
import com.shop.order.repository.OrderItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepo;
    private final OrderItemRepository orderItemRepo;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    public OrderService(OrderRepository orderRepo,
                        OrderItemRepository orderItemRepo,
                        InventoryClient inventoryClient,
                        PaymentClient paymentClient) {
        this.orderRepo = orderRepo;
        this.orderItemRepo = orderItemRepo;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
    }

    /**
     * 创建订单
     * 流程：计算金额 -> 扣减库存 -> 创建订单 -> 发起支付
     */
    @Transactional
    public Order createOrder(Long userId, List<OrderItem> items) {
        // 使用 BigDecimal 计算金额，RoundingMode.HALF_UP
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItem item : items) {
            BigDecimal itemTotal = item.getPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(itemTotal);
        }
        totalAmount = totalAmount.setScale(2, RoundingMode.HALF_UP);
        log.info("计算订单总金额: {}", totalAmount);

        // 扣减库存
        List<DeductItem> deductItems = items.stream()
                .map(i -> new DeductItem(i.getProductId(), i.getQuantity()))
                .collect(Collectors.toList());

        try {
            var deductRequest = new DeductRequest(deductItems);
            var response = inventoryClient.deductStock(deductRequest);
            if (!response.isSuccess()) {
                log.error("库存扣减失败: {}", response.getMessage());
                throw new RuntimeException("库存不足: " + response.getMessage());
            }
            log.info("库存扣减成功");
        } catch (Exception e) {
            log.error("调用库存服务失败: {}", e.getMessage());
            throw new RuntimeException("库存服务调用失败: " + e.getMessage());
        }

        // 创建订单记录
        String orderNo = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Order order = new Order(orderNo, userId, totalAmount);
        order.setStatus("CREATED");
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order = orderRepo.save(order);

        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            orderItemRepo.save(item);
        }
        log.info("订单创建成功: orderNo={}", orderNo);

        // 发起支付
        try {
            order.setStatus("PAYING");
            orderRepo.save(order);

            PaymentRequest paymentRequest = new PaymentRequest(
                    String.valueOf(order.getId()),
                    totalAmount
            );
            PaymentResponse paymentResponse = paymentClient.initiatePayment(paymentRequest);

            if (paymentResponse.isSuccess()) {
                log.info("支付发起成功: paymentId={}", paymentResponse.getPaymentId());
            } else {
                // 支付发起失败，回滚库存，取消订单
                log.warn("支付发起失败: {}", paymentResponse.getMessage());
                rollbackInventory(order.getId());
                order.setStatus(OrderStateMachine.CANCELLED);
                order.setUpdateTime(LocalDateTime.now());
                orderRepo.save(order);
            }
        } catch (Exception e) {
            // 支付服务超时，订单留在 PAYING 状态，等待超时定时任务处理
            log.error("支付服务调用异常，订单将由超时任务处理: orderNo={}, error={}",
                    orderNo, e.getMessage());
        }

        return order;
    }

    /**
     * 取消订单 - 库存恢复失败则阻止取消
     */
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在: " + orderId));

        String currentStatus = order.getStatus();
        if (!OrderStateMachine.canTransit(currentStatus, OrderStateMachine.CANCELLED)) {
            throw new RuntimeException("当前状态不允许取消: " + currentStatus);
        }

        // 恢复库存 - 失败则阻止取消
        try {
            RestoreRequest restoreRequest = new RestoreRequest(order.getId());
            inventoryClient.restoreStock(restoreRequest);
            log.info("库存恢复成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("库存恢复失败，无法取消订单: orderId={}, error={}", orderId, e.getMessage());
            throw new RuntimeException("库存恢复失败，无法取消订单: " + e.getMessage());
        }

        order.setStatus(OrderStateMachine.CANCELLED);
        order.setUpdateTime(LocalDateTime.now());
        orderRepo.save(order);

        log.info("订单已取消: orderId={}", orderId);
        return order;
    }

    public Order getOrder(Long orderId) {
        return orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在: " + orderId));
    }

    public List<Order> listOrders(Long userId) {
        return orderRepo.findByUserId(userId);
    }

    @Transactional
    public Order updateOrderStatus(Long orderId, String newStatus) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在: " + orderId));

        String currentStatus = order.getStatus();
        if (!OrderStateMachine.canTransit(currentStatus, newStatus)) {
            throw new RuntimeException("非法状态转换: " + currentStatus + " -> " + newStatus);
        }

        order.setStatus(newStatus);
        order.setUpdateTime(LocalDateTime.now());

        if ("PAID".equals(newStatus)) {
            order.setPayTime(LocalDateTime.now());
        }

        return orderRepo.save(order);
    }

    /**
     * 回滚库存（内部方法）
     */
    private void rollbackInventory(Long orderId) {
        try {
            RestoreRequest restoreRequest = new RestoreRequest(orderId);
            inventoryClient.restoreStock(restoreRequest);
            log.info("库存回滚成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("库存回滚失败，需人工介入: orderId={}, error={}", orderId, e.getMessage());
        }
    }
}
