package com.modernstock.inventory;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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

    @Query("""
            select b from StockBatch b
            where b.productId = :productId and b.warehouseId = :warehouseId
              and ((:batchNo is null and b.batchNo is null) or b.batchNo = :batchNo)
              and ((:expiryDate is null and b.expiryDate is null) or b.expiryDate = :expiryDate)
            order by b.id
            """)
    List<StockBatch> findSame(Long productId, Long warehouseId, String batchNo, LocalDate expiryDate);

    default Optional<StockBatch> findFirstSame(Long productId, Long warehouseId, String batchNo, LocalDate expiryDate) {
        return findSame(productId, warehouseId, batchNo, expiryDate).stream().findFirst();
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
