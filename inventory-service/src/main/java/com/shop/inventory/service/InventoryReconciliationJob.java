package com.shop.inventory.service;

import com.shop.inventory.model.Inventory;
import com.shop.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存对账定时任务
 * 对比 Redis 库存和数据库库存，以数据库为准修正 Redis
 * 
 * 问题：对账间隔 1 小时太长，高并发下 1 小时内的超卖已造成损失
 */
@Component
public class InventoryReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationJob.class);

    private final StringRedisTemplate redisTemplate;
    private final InventoryRepository inventoryRepo;

    public InventoryReconciliationJob(StringRedisTemplate redisTemplate, InventoryRepository inventoryRepo) {
        this.redisTemplate = redisTemplate;
        this.inventoryRepo = inventoryRepo;
    }

    /**
     * 每小时执行一次对账
     * 以数据库库存为准，修正 Redis 中的库存数据
     */
    @Scheduled(fixedRate = 3600000)  // 1小时 = 3600秒 — 间隔太长！
    public void reconcile() {
        log.info("开始库存对账...");

        List<Inventory> dbInventories = inventoryRepo.findAll();
        int fixed = 0;

        for (Inventory inv : dbInventories) {
            String redisKey = "inventory:" + inv.getProductId();
            String redisValue = redisTemplate.opsForValue().get(redisKey);

            if (redisValue != null) {
                int redisStock = Integer.parseInt(redisValue);
                if (redisStock != inv.getQuantity()) {
                    log.warn("库存不一致: productId={}, Redis={}, DB={}",
                            inv.getProductId(), redisStock, inv.getQuantity());
                    // 以数据库为准修正 Redis
                    redisTemplate.opsForValue().set(redisKey, String.valueOf(inv.getQuantity()));
                    fixed++;
                }
            }
        }

        log.info("库存对账完成，修正了 {} 条记录", fixed);
        // 注意：没有生成差异报告，没有报警机制
    }
}
