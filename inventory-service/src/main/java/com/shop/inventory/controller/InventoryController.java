package com.shop.inventory.controller;

import com.shop.inventory.model.Inventory;
import com.shop.inventory.service.InventoryService;
import com.shop.inventory.service.InventoryService.DeductItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 库存控制器
 * 提供库存相关的 REST API
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * 扣减库存
     * POST /api/inventory/deduct
     */
    @PostMapping("/deduct")
    public ResponseEntity<?> deductStock(@RequestBody DeductRequest request) {
        try {
            log.info("扣减库存请求: items={}", request.getItems());
            boolean success = inventoryService.deductStock(request.getItems());
            if (success) {
                return ResponseEntity.ok(Map.of("success", true, "message", "扣减成功"));
            } else {
                return ResponseEntity.ok(Map.of("success", false, "message", "库存不足"));
            }
        } catch (Exception e) {
            log.error("扣减库存失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "扣减失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 恢复库存
     * POST /api/inventory/restore
     */
    @PostMapping("/restore")
    public ResponseEntity<?> restoreStock(@RequestBody RestoreRequest request) {
        try {
            log.info("恢复库存请求: orderId={}", request.getOrderId());
            inventoryService.restoreStock(request.getOrderId());
            return ResponseEntity.ok(Map.of("success", true, "message", "恢复成功"));
        } catch (Exception e) {
            log.error("恢复库存失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "恢复失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询库存
     * GET /api/inventory/{productId}
     */
    @GetMapping("/{productId}")
    public ResponseEntity<?> getStock(@PathVariable String productId) {
        int stock = inventoryService.getStock(productId);
        return ResponseEntity.ok(Map.of(
                "productId", productId,
                "stock", stock
        ));
    }

    /**
     * 初始化库存（用于测试）
     * POST /api/inventory/init
     */
    @PostMapping("/init")
    public ResponseEntity<?> initInventory(@RequestBody InitRequest request) {
        Inventory inventory = inventoryService.initInventory(
                request.getProductId(),
                request.getProductName(),
                request.getQuantity()
        );
        return ResponseEntity.ok(inventory);
    }

    /**
     * 扣减请求体
     */
    public static class DeductRequest {
        private List<DeductItem> items;

        public List<DeductItem> getItems() {
            return items;
        }

        public void setItems(List<DeductItem> items) {
            this.items = items;
        }
    }

    /**
     * 恢复请求体
     */
    public static class RestoreRequest {
        private Long orderId;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }
    }

    /**
     * 初始化请求体
     */
    public static class InitRequest {
        private String productId;
        private String productName;
        private int quantity;

        public String getProductId() {
            return productId;
        }

        public void setProductId(String productId) {
            this.productId = productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }
    }
}
