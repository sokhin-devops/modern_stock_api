package com.modernstock.inventory;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface StockCountRepository extends TenantRepository<StockCount> {

    @Query("""
            select c from StockCount c
            where c.branchId in :branchIds
              and (:status is null or c.status = :status)
            order by c.id desc""")
    Page<StockCount> search(Collection<Long> branchIds, StockCount.Status status, Pageable pageable);

    @Override
    default String entityName() {
        return "Stock count";
    }
}
