package com.modernstock.inventory;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface StockBatchRepository extends TenantRepository<StockBatch> {

    /** FEFO: earliest expiry first, batches without expiry last, then oldest first. */
    @Query("""
            select b from StockBatch b
            where b.productId = :productId and b.warehouseId = :warehouseId and b.qtyOnHand > 0
            order by case when b.expiryDate is null then 1 else 0 end, b.expiryDate, b.receivedAt, b.id
            """)
    List<StockBatch> findForPick(Long productId, Long warehouseId);

    List<StockBatch> findByProductIdAndWarehouseIdOrderByIdAsc(Long productId, Long warehouseId);

    /**
     * The batch with the same number and expiry, compared in Java: PostgreSQL can't type a null date
     * parameter in "? is null", and a product has only a few batches per warehouse.
     */
    default Optional<StockBatch> findFirstSame(Long productId, Long warehouseId, String batchNo, LocalDate expiryDate) {
        return findByProductIdAndWarehouseIdOrderByIdAsc(productId, warehouseId).stream()
                .filter(b -> Objects.equals(b.getBatchNo(), batchNo) && Objects.equals(b.getExpiryDate(), expiryDate))
                .findFirst();
    }

    @Query("""
            select b from StockBatch b
            where b.qtyOnHand > 0 and b.expiryDate is not null and b.expiryDate <= :until
              and b.warehouseId in :warehouseIds
              and (:productId is null or b.productId = :productId)
            order by b.expiryDate, b.id
            """)
    List<StockBatch> findExpiring(LocalDate until, Collection<Long> warehouseIds, Long productId);

    @Override
    default String entityName() {
        return "Batch";
    }
}
