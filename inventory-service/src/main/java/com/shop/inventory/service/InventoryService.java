package com.shop.inventory.service;

import com.shop.inventory.model.Inventory;
import com.shop.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
     * 任何环节失败都会回滚所有已扣减的 Redis 库存，并通过 @Transactional 回滚数据库
     *
     * @param items 需要扣减的商品列表
     * @return 扣减是否成功
     */
    @Transactional
    public boolean deductStock(List<DeductItem> items) {
        List<DeductItem> deductedItems = new ArrayList<>();
        boolean allSuccess = true;

        for (DeductItem item : items) {
            String lockKey = "inventory:lock:" + item.getProductId();
            String lockValue = UUID.randomUUID().toString();

            // 第一步：获取分布式锁（原子操作：SET key value NX EX seconds）
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(locked)) {
                log.warn("获取库存锁失败: productId={}", item.getProductId());
                allSuccess = false;
                break;
            }

            try {
                // 第二步：扣减 Redis 库存
                String redisKey = "inventory:" + item.getProductId();
                Long currentStock = redisTemplate.opsForValue().decrement(redisKey, item.getQuantity());

                if (currentStock == null || currentStock < 0) {
                    redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                    log.warn("Redis 库存不足: productId={}, 请求数量={}, 当前库存={}",
                            item.getProductId(), item.getQuantity(), currentStock);
                    allSuccess = false;
                    break;
                }

                log.info("Redis 库存扣减成功: productId={}, 扣减数量={}, 剩余={}",
                        item.getProductId(), item.getQuantity(), currentStock);

                // 第三步：扣减数据库库存
                int updated = inventoryRepo.deductFromDb(item.getProductId(), item.getQuantity());

                if (updated == 0) {
                    redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                    log.warn("数据库库存不足，回滚 Redis: productId={}", item.getProductId());
                    allSuccess = false;
                    break;
                }

                deductedItems.add(item);
                log.info("库存扣减成功: productId={}", item.getProductId());

            } catch (Exception e) {
                String redisKey = "inventory:" + item.getProductId();
                redisTemplate.opsForValue().increment(redisKey, item.getQuantity());
                log.error("库存扣减异常: productId={}, error={}", item.getProductId(), e.getMessage());
                allSuccess = false;
                break;
            } finally {
                safeReleaseLock(lockKey, lockValue);
            }
        }

        if (!allSuccess) {
            // 回滚已成功扣减的商品 Redis 库存
            for (DeductItem d : deductedItems) {
                String redisKey = "inventory:" + d.getProductId();
                redisTemplate.opsForValue().increment(redisKey, d.getQuantity());
                log.info("回滚 Redis 库存: productId={}, quantity={}", d.getProductId(), d.getQuantity());
            }
            // 抛出异常触发 @Transactional 回滚数据库
            throw new RuntimeException("库存扣减失败，已回滚");
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
    @Transactional
    public void restoreProductStock(String productId, int quantity) {
        String lockKey = "inventory:lock:" + productId;
        String lockValue = UUID.randomUUID().toString();

        // 获取分布式锁（原子操作）
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            log.warn("恢复库存时获取锁失败: productId={}", productId);
            throw new RuntimeException("获取库存锁失败: productId=" + productId);
        }

        try {
            String redisKey = "inventory:" + productId;

            // 第一步：恢复数据库库存
            inventoryRepo.restoreFromDb(productId, quantity);
            log.info("数据库库存恢复成功: productId={}", productId);

            // 第二步：恢复 Redis 库存（DB 先成功再写 Redis，保证一致性）
            Long newStock = redisTemplate.opsForValue().increment(redisKey, quantity);
            log.info("Redis 库存恢复: productId={}, 恢复数量={}, 当前={}", productId, quantity, newStock);
        } catch (Exception e) {
            log.error("库存恢复失败: productId={}, error={}", productId, e.getMessage());
            throw new RuntimeException("库存恢复失败: productId=" + productId, e);
        } finally {
            safeReleaseLock(lockKey, lockValue);
        }
    }

    /**
     * 安全释放分布式锁
     * 使用 Lua 脚本确保只有锁的持有者才能释放锁，避免误删其他请求的锁
     *
     * @param lockKey       锁的 key
     * @param expectedValue 锁的 value（持有者的 UUID）
     */
    private void safeReleaseLock(String lockKey, String expectedValue) {
        String luaScript = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end";
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        redisTemplate.execute(script, List.of(lockKey), expectedValue);
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
