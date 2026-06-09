package com.shop.order.controller;

import com.shop.order.model.Order;
import com.shop.order.model.OrderItem;
import com.shop.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单控制器
 * 提供订单相关的 REST API
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        try {
            log.info("创建订单请求: userId={}, items={}", request.getUserId(), request.getItems().size());
            Order order = orderService.createOrder(request.getUserId(), request.getItems());
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            log.error("创建订单失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "创建订单失败",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 获取订单详情
     * GET /api/orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrder(@PathVariable Long id) {
        try {
            Order order = orderService.getOrder(id);
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 取消订单
     * POST /api/orders/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long id) {
        try {
            log.info("取消订单请求: orderId={}", id);
            Order order = orderService.cancelOrder(id);
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            log.error("取消订单失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "取消订单失败",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 获取用户的所有订单
     * GET /api/orders?userId={userId}
     */
    @GetMapping
    public ResponseEntity<?> listOrders(@RequestParam Long userId) {
        List<Order> orders = orderService.listOrders(userId);
        return ResponseEntity.ok(orders);
    }

    /**
     * 更新订单状态（供内部服务调用）
     * POST /api/orders/{id}/status
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String status = body.get("status");
            Order order = orderService.updateOrderStatus(id, status);
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            log.error("更新订单状态失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "状态更新失败",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 创建订单请求体
     */
    public static class CreateOrderRequest {
        private Long userId;
        private List<OrderItem> items;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public List<OrderItem> getItems() {
            return items;
        }

        public void setItems(List<OrderItem> items) {
            this.items = items;
        }
    }
}
