package com.modernstock.sale;

import java.math.BigDecimal;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface SalesReceiptRepository extends TenantRepository<SalesReceipt> {

    @Query("""
            select r from SalesReceipt r
            where r.branchId in :branchIds
              and (:cashierId is null or r.cashierId = :cashierId)
              and (:status is null or r.status = :status)
            order by r.id desc
            """)
    Page<SalesReceipt> search(Collection<Long> branchIds, Long cashierId, SalesReceipt.Status status, Pageable pageable);

    @Query("""
            select coalesce(sum(r.changeUsd), 0) from SalesReceipt r
            where r.cashShiftId = :shiftId and r.status = com.modernstock.sale.SalesReceipt.Status.POSTED
            """)
    BigDecimal changeUsd(Long shiftId);

    @Query("""
            select coalesce(sum(r.changeKhr), 0) from SalesReceipt r
            where r.cashShiftId = :shiftId and r.status = com.modernstock.sale.SalesReceipt.Status.POSTED
            """)
    BigDecimal changeKhr(Long shiftId);

    long countByCashShiftIdAndStatus(Long cashShiftId, SalesReceipt.Status status);

    @Override
    default String entityName() {
        return "Sales receipt";
    }
}
