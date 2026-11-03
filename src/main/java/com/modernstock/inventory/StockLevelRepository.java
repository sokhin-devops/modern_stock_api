package com.modernstock.inventory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

    /** SELECT ... FOR UPDATE: two cashiers selling the last unit wait for each other (PLAIN.md 11.4). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockLevel s where s.productId = :productId and s.warehouseId = :warehouseId")
    Optional<StockLevel> lockByProductAndWarehouse(Long productId, Long warehouseId);

    @Query("select coalesce(sum(s.qtyOnHand), 0) from StockLevel s where s.productId = :productId")
    BigDecimal totalQty(Long productId);

    List<StockLevel> findByProductIdOrderByWarehouseId(Long productId);

    List<StockLevel> findByProductIdInAndWarehouseIdIn(Collection<Long> productIds, Collection<Long> warehouseIds);

    List<StockLevel> findByProductIdIn(Collection<Long> productIds);
}
