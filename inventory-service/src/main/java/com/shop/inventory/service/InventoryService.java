package com.shop.inventory.service;

import com.shop.inventory.model.Inventory;
import com.shop.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 库存服务
 * 负责库存的扣减、恢复和查询
 * 使用 Redis 作为库存缓存，数据库作为持久化存储
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StringRedisTemplate redisTemplate;
    private final InventoryRepository inventoryRepo;

    public InventoryService(StringRedisTemplate redisTemplate, InventoryRepository inventoryRepo) {
        this.redisTemplate = redisTemplate;
        this.inventoryRepo = inventoryRepo;
    }

    /**
     * 扣减库存
     * 流程：获取分布式锁 -> 扣减 Redis 库存 -> 扣减数据库库存
     *
     * @param items 需要扣减的商品列表
     * @return 扣减是否成功
     */
    public boolean deductStock(List<DeductItem> items) {
        for (DeductItem item : items) {
            String lockKey = "inventory:lock:" + item.getProductId();

            // 第一步：获取分布式锁
            // 使用 SETNX 尝试获取锁
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked");

            if (Boolean.TRUE.equals(locked)) {
                // 第二步：设置锁过期时间
                // 注意：SETNX 和 EXPIRE 是两个独立的操作，中间存在竞态窗口
                // 如果 SETNX 成功后进程崩溃，EXPIRE 未执行，锁将永远不会释放
                redisTemplate.expire(lockKey, 10, TimeUnit.SECONDS);

                try {
                    // 第三步：扣减 Redis 库存
                    String redisKey = "inventory:" + item.getProductId();
                    Long currentStock = redisTemplate.opsForValue().decrement(redisKey, item.getQuantity());

                    if (currentStock == null || currentStock < 0) {
                        // 库存不足，回滚 Redis
                        log.warn("库存不足: productId={}, 请求数量={}, 当前库存={}",
                                item.getProductId(), item.getQuantity(), currentStock);
                        redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                        return false;
                    }

                    log.info("Redis 库存扣减成功: productId={}, 扣减数量={}, 剩余={}",
                            item.getProductId(), item.getQuantity(), currentStock);

                    // 第四步：扣减数据库库存
                    // 如果这里抛异常（如数据库连接超时），Redis 已扣减但数据库未扣减
                    // 会导致 Redis 和数据库库存不一致
                    int updated = inventoryRepo.deductFromDb(item.getProductId(), item.getQuantity());

                    if (updated == 0) {
                        // 数据库库存不足，需要回滚 Redis
                        log.warn("数据库库存不足，回滚 Redis: productId={}", item.getProductId());
                        redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                        return false;
                    }

                    log.info("数据库库存扣减成功: productId={}", item.getProductId());

                } catch (Exception e) {
                    // 数据库操作异常，但 Redis 已经扣减成功
                    // 这里没有回滚 Redis 的逻辑，导致数据不一致
                    log.error("库存扣减异常: productId={}, error={}", item.getProductId(), e.getMessage());
                    throw e;
                } finally {
                    // 释放锁
                    redisTemplate.delete(lockKey);
                }
            } else {
                // 获取锁失败，说明有其他请求正在处理
                log.warn("获取库存锁失败: productId={}", item.getProductId());
                return false;
            }
        }
        return true;
    }

    /**
     * 恢复库存（订单取消时调用）
     *
     * @param orderId 订单ID
     */
    @Transactional
    public void restoreStock(Long orderId) {
        // TODO: 需要根据订单ID查询订单明细，然后恢复对应商品的库存
        // 这里简化处理，假设传入的是商品列表
        log.info("恢复库存: orderId={}", orderId);
    }

    /**
     * 恢复指定商品的库存
     *
     * @param productId 商品ID
     * @param quantity  恢复数量
     */
    public void restoreProductStock(String productId, int quantity) {
        String redisKey = "inventory:" + productId;

        // 第一步：恢复 Redis 库存
        Long newStock = redisTemplate.opsForValue().increment(redisKey, quantity);
        log.info("Redis 库存恢复: productId={}, 恢复数量={}, 当前={}", productId, quantity, newStock);

        // 第二步：恢复数据库库存
        try {
            inventoryRepo.restoreFromDb(productId, quantity);
            log.info("数据库库存恢复成功: productId={}", productId);
        } catch (Exception e) {
            // 数据库恢复失败，但 Redis 已经恢复
            // 会导致数据不一致
            log.error("数据库库存恢复失败: productId={}, error={}", productId, e.getMessage());
        }
    }

    /**
     * 查询库存
     *
     * @param productId 商品ID
     * @return 库存数量
     */
    public int getStock(String productId) {
        String redisKey = "inventory:" + productId;
        String value = redisTemplate.opsForValue().get(redisKey);

        if (value != null) {
            return Integer.parseInt(value);
        }

        // Redis 中没有，从数据库查询
        Inventory inventory = inventoryRepo.findByProductId(productId).orElse(null);
        if (inventory != null) {
            // 同步到 Redis
            redisTemplate.opsForValue().set(redisKey, String.valueOf(inventory.getQuantity()));
            return inventory.getQuantity();
        }

        return 0;
    }

    /**
     * 初始化库存（用于测试）
     */
    @Transactional
    public Inventory initInventory(String productId, String productName, int quantity) {
        Inventory inventory = new Inventory(productId, productName, quantity);
        inventory = inventoryRepo.save(inventory);

        // 同步到 Redis
        String redisKey = "inventory:" + productId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(quantity));

        log.info("初始化库存: productId={}, quantity={}", productId, quantity);
        return inventory;
    }

    /**
     * 扣减请求项
     */
    public static class DeductItem {
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
}
