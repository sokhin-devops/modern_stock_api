package com.modernstock.purchase;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface SupplierCreditRepository extends TenantRepository<SupplierCredit> {

    @Query("""
            select c from SupplierCredit c
            where c.branchId in :branchIds and (:supplierId is null or c.supplierId = :supplierId)
              and (:status is null or c.status = :status)
            order by c.id desc
            """)
    Page<SupplierCredit> search(Collection<Long> branchIds, Long supplierId, SupplierCredit.Status status, Pageable pageable);

    List<SupplierCredit> findByPurchaseReturnId(Long purchaseReturnId);

    @Override
    default String entityName() {
        return "Supplier credit";
    }
}
