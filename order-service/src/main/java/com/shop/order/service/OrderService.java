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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 订单服务
 * 负责订单的创建、支付发起、取消等核心业务
 */
@Service
@EnableScheduling
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Value("${order.pay.timeout.minutes:30}")
    private int payTimeoutMinutes;

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
     *
     * @param userId 用户ID
     * @param items  订单商品列表
     * @return 创建的订单
     */
    @Transactional
    public Order createOrder(Long userId, List<OrderItem> items) {
        // 第一步：计算订单总金额
        // 使用 BigDecimal 进行金额计算，保证精度
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItem item : items) {
            totalAmount = totalAmount.add(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        log.info("计算订单总金额: {}", totalAmount);

        // 第二步：扣减库存
        // 调用库存服务进行库存扣减
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

        // 第三步：创建订单记录
        String orderNo = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Order order = new Order(orderNo, userId, totalAmount);
        order.setStatus("CREATED");
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order = orderRepo.save(order);

        // 保存订单明细
        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            orderItemRepo.save(item);
        }
        log.info("订单创建成功: orderNo={}", orderNo);

        // 第四步：发起支付
        // 如果支付服务超时，订单会停留在 PAYING 状态
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
                log.warn("支付发起失败: {}", paymentResponse.getMessage());
                // 支付失败，但订单状态已经是 PAYING，没有回滚机制
            }
        } catch (Exception e) {
            // 支付服务调用超时或其他异常
            // 订单状态停留在 PAYING，没有超时自动取消机制
            log.error("支付服务调用异常，订单停留在 PAYING 状态: orderNo={}, error={}",
                    orderNo, e.getMessage());
            // 这里应该触发补偿机制或定时任务，但目前没有实现
        }

        return order;
    }

    /**
     * 取消订单
     * 只有 CREATED 或 PAYING 状态的订单可以取消
     *
     * @param orderId 订单ID
     * @return 取消后的订单
     */
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在: " + orderId));

        // 检查订单状态是否允许取消
        String currentStatus = order.getStatus();
        if (!OrderStateMachine.canTransit(currentStatus, OrderStateMachine.CANCELLED)) {
            throw new RuntimeException("当前状态不允许取消: " + currentStatus);
        }

        // 第一步：恢复库存
        try {
            RestoreRequest restoreRequest = new RestoreRequest(order.getId());
            inventoryClient.restoreStock(restoreRequest);
            log.info("库存恢复成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("库存恢复失败: {}", e.getMessage());
            // 库存恢复失败，但继续取消订单，可能导致库存不一致
        }

        // 第二步：更新订单状态
        order.setStatus(OrderStateMachine.CANCELLED);
        order.setUpdateTime(LocalDateTime.now());
        orderRepo.save(order);

        log.info("订单已取消: orderId={}", orderId);
        return order;
    }

    /**
     * 获取订单详情
     */
    public Order getOrder(Long orderId) {
        return orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在: " + orderId));
    }

    /**
     * 获取用户的所有订单
     */
    public List<Order> listOrders(Long userId) {
        return orderRepo.findByUserId(userId);
    }

    /**
     * 更新订单状态（供内部服务调用）
     */
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
     * 定时扫描超时的 PAYING 订单，自动取消并释放库存
     * 超时时间通过 order.pay.timeout.minutes 配置，默认 30 分钟
     */
    @Scheduled(fixedDelay = 60000)
    public void cancelExpiredPayingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(payTimeoutMinutes);
        List<Order> expiredOrders = orderRepo.findByStatusAndCreateTimeBefore(
                OrderStateMachine.PAYING, cutoff);

        if (!expiredOrders.isEmpty()) {
            log.info("发现 {} 个超时 PAYING 订单，开始自动取消", expiredOrders.size());
        }

        for (Order order : expiredOrders) {
            try {
                log.info("自动取消超时订单: orderNo={}, createTime={}",
                        order.getOrderNo(), order.getCreateTime());
                cancelOrder(order.getId());
            } catch (Exception e) {
                log.error("自动取消超时订单失败: orderNo={}, error={}",
                        order.getOrderNo(), e.getMessage());
            }
        }
    }
}
