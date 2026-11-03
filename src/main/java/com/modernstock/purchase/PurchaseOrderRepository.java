package com.modernstock.purchase;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface PurchaseOrderRepository extends TenantRepository<PurchaseOrder> {

    @Query("""
            select p from PurchaseOrder p
            where p.branchId in :branchIds
              and (:status is null or p.status = :status)
              and (:supplierId is null or p.supplierId = :supplierId)
            order by p.id desc
            """)
    Page<PurchaseOrder> search(Collection<Long> branchIds, PurchaseOrder.Status status, Long supplierId,
                               Pageable pageable);

    @Override
    default String entityName() {
        return "Purchase order";
    }
}
