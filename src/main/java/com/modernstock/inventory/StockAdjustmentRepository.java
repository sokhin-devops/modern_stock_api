package com.modernstock.inventory;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface StockAdjustmentRepository extends TenantRepository<StockAdjustment> {

    /** Void locks the row, so it can't run twice at once. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from StockAdjustment a where a.id = :id")
    java.util.Optional<StockAdjustment> lockById(Long id);

    @Query("""
            select a from StockAdjustment a
            where a.branchId in :branchIds
              and (:status is null or a.status = :status)
            order by a.id desc
            """)
    Page<StockAdjustment> search(Collection<Long> branchIds, StockAdjustment.Status status, Pageable pageable);

    @Override
    default String entityName() {
        return "Adjustment";
    }
}
