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
              and lower(c.number) like :number and c.createdAt >= :start and c.createdAt < :end
            order by c.id desc""")
    Page<StockCount> search(Collection<Long> branchIds, StockCount.Status status, String number, java.time.Instant start,
                            java.time.Instant end, Pageable pageable);

    @Override
    default String entityName() {
        return "Stock count";
    }
}
