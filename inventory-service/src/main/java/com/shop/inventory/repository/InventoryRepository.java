package com.shop.inventory.repository;

import com.shop.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 库存数据访问层
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * 根据商品ID查询库存
     */
    Optional<Inventory> findByProductId(String productId);

    /**
     * 扣减库存（带乐观锁）
     * 只有当库存充足时才扣减
     *
     * @param productId 商品ID
     * @param quantity  扣减数量
     * @return 更新的记录数（0表示库存不足或不存在）
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity - :quantity WHERE i.productId = :productId AND i.quantity >= :quantity")
    int deductFromDb(@Param("productId") String productId, @Param("quantity") int quantity);

    /**
     * 恢复库存
     *
     * @param productId 商品ID
     * @param quantity  恢复数量
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity + :quantity WHERE i.productId = :productId")
    void restoreFromDb(@Param("productId") String productId, @Param("quantity") int quantity);
}
