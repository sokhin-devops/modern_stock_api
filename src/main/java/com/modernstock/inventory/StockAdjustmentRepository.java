package com.modernstock.inventory;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface StockAdjustmentRepository extends TenantRepository<StockAdjustment> {

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
