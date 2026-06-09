package com.shop.inventory.service;

import com.shop.inventory.model.Inventory;
import com.shop.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
     * 修复：原子锁、DB失败回滚Redis、多商品原子性
     */
    @Transactional
    public boolean deductStock(List<DeductItem> items) {
        List<DeductedRecord> deducted = new ArrayList<>();
        try {
            for (DeductItem item : items) {
                if (!deductSingleItem(item)) {
                    // 当前商品扣减失败，回滚已成功的
                    rollbackDeducted(deducted);
                    return false;
                }
                deducted.add(new DeductedRecord(item.getProductId(), item.getQuantity()));
            }
            return true;
        } catch (Exception e) {
            rollbackDeducted(deducted);
            throw e;
        }
    }

    private boolean deductSingleItem(DeductItem item) {
        String lockKey = "inventory:lock:" + item.getProductId();
        // 使用 UUID 作为锁持有者标识，防止误删别人的锁
        String lockValue = UUID.randomUUID().toString();

        // 原子操作：SET key value NX EX seconds
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, 30, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            log.warn("获取库存锁失败: productId={}", item.getProductId());
            return false;
        }

        try {
            String redisKey = "inventory:" + item.getProductId();
            Long currentStock = redisTemplate.opsForValue().decrement(redisKey, item.getQuantity());

            if (currentStock == null || currentStock < 0) {
                log.warn("库存不足: productId={}, 请求数量={}, 当前库存={}",
                        item.getProductId(), item.getQuantity(), currentStock);
                redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                return false;
            }

            log.info("Redis 库存扣减成功: productId={}, 扣减数量={}, 剩余={}",
                    item.getProductId(), item.getQuantity(), currentStock);

            // 扣减数据库库存
            int updated = inventoryRepo.deductFromDb(item.getProductId(), item.getQuantity());

            if (updated == 0) {
                log.warn("数据库库存不足，回滚 Redis: productId={}", item.getProductId());
                redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                return false;
            }

            log.info("数据库库存扣减成功: productId={}", item.getProductId());
            return true;

        } catch (Exception e) {
            // DB异常时回滚Redis
            String redisKey = "inventory:" + item.getProductId();
            redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
            log.error("库存扣减异常，已回滚Redis: productId={}, error={}", item.getProductId(), e.getMessage());
            throw e;
        } finally {
            // 安全释放锁：只删除自己持有的锁
            releaseLock(lockKey, lockValue);
        }
    }

    /**
     * 回滚已成功扣减的商品库存（多商品原子性保证）
     */
    private void rollbackDeducted(List<DeductedRecord> deducted) {
        for (DeductedRecord record : deducted) {
            try {
                String redisKey = "inventory:" + record.productId;
                redisTemplate.opsForValue().increment(redisKey, record.quantity);
                inventoryRepo.restoreFromDb(record.productId, record.quantity);
                log.info("已回滚库存: productId={}, quantity={}", record.productId, record.quantity);
            } catch (Exception ex) {
                log.error("回滚库存失败: productId={}, error={}", record.productId, ex.getMessage());
            }
        }
    }

    /**
     * 恢复库存（订单取消时调用）
     * 根据订单ID查询明细并恢复
     */
    @Transactional
    public void restoreStock(Long orderId) {
        log.info("恢复库存: orderId={}", orderId);
        // 注意：实际需要调用 order-service 获取订单明细
        // 当前由 order-service 直接调用 restoreProductStock 完成
    }

    /**
     * 恢复指定商品的库存（加分布式锁 + DB失败补偿）
     */
    @Transactional
    public void restoreProductStock(String productId, int quantity) {
        String lockKey = "inventory:lock:" + productId;
        String lockValue = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, 30, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            throw new RuntimeException("获取库存锁失败，无法恢复库存: productId=" + productId);
        }

        try {
            // 先写DB，再写Redis（DB为主）
            inventoryRepo.restoreFromDb(productId, quantity);
            log.info("数据库库存恢复成功: productId={}", productId);

            Long newStock = redisTemplate.opsForValue().increment("inventory:" + productId, quantity);
            log.info("Redis 库存恢复: productId={}, 恢复数量={}, 当前={}", productId, quantity, newStock);
        } catch (Exception e) {
            log.error("库存恢复失败: productId={}, error={}", productId, e.getMessage());
            throw e;
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    /**
     * 查询库存
     */
    public int getStock(String productId) {
        String redisKey = "inventory:" + productId;
        String value = redisTemplate.opsForValue().get(redisKey);

        if (value != null) {
            return Integer.parseInt(value);
        }

        Inventory inventory = inventoryRepo.findByProductId(productId).orElse(null);
        if (inventory != null) {
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

        String redisKey = "inventory:" + productId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(quantity));

        log.info("初始化库存: productId={}, quantity={}", productId, quantity);
        return inventory;
    }

    /**
     * 安全释放锁：使用 Lua 脚本保证只删除自己持有的锁
     */
    private void releaseLock(String lockKey, String expectedValue) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end";
        redisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                java.util.List.of(lockKey),
                expectedValue
        );
    }

    /**
     * 已扣减记录（用于回滚）
     */
    private static class DeductedRecord {
        final String productId;
        final int quantity;
        DeductedRecord(String productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }
    }

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
