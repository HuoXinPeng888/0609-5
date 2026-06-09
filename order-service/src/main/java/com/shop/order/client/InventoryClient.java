package com.shop.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 库存服务 Feign 客户端
 * 用于调用 inventory-service 的库存扣减和恢复接口
 */
@FeignClient(name = "inventory-service", url = "${inventory.service.url}")
public interface InventoryClient {

    /**
     * 扣减库存
     *
     * @param request 扣减请求，包含商品列表
     * @return 扣减结果
     */
    @PostMapping("/api/inventory/deduct")
    InventoryResponse deductStock(@RequestBody DeductRequest request);

    /**
     * 恢复库存（订单取消时调用）
     *
     * @param request 恢复请求，包含订单ID
     */
    @PostMapping("/api/inventory/restore")
    void restoreStock(@RequestBody RestoreRequest request);

    /**
     * 库存扣减请求
     */
    class DeductRequest {
        private List<DeductItem> items;

        public DeductRequest() {
        }

        public DeductRequest(List<DeductItem> items) {
            this.items = items;
        }

        public List<DeductItem> getItems() {
            return items;
        }

        public void setItems(List<DeductItem> items) {
            this.items = items;
        }
    }

    /**
     * 扣减明细项
     */
    class DeductItem {
        private String productId;
        private int quantity;

        public DeductItem() {
        }

        public DeductItem(String productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public String getProductId() {
            return productId;
        }

        public void setProductId(String productId) {
            this.productId = productId;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }
    }

    /**
     * 库存恢复请求
     */
    class RestoreRequest {
        private Long orderId;

        public RestoreRequest() {
        }

        public RestoreRequest(Long orderId) {
            this.orderId = orderId;
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }
    }

    /**
     * 库存操作响应
     */
    class InventoryResponse {
        private boolean success;
        private String message;

        public InventoryResponse() {
        }

        public InventoryResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
