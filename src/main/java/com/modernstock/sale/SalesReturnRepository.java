package com.modernstock.sale;

import java.math.BigDecimal;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface SalesReturnRepository extends TenantRepository<SalesReturn> {

    @Query("""
            select r from SalesReturn r
            where r.branchId in :branchIds and (:status is null or r.status = :status)
            order by r.id desc
            """)
    Page<SalesReturn> search(Collection<Long> branchIds, SalesReturn.Status status, Pageable pageable);

    /** How much of a sold line was already returned, or is waiting to be (posted and requested returns). */
    @Query("""
            select coalesce(sum(i.qty), 0) from SalesReturn r join r.items i
            where i.saleItemId = :saleItemId and r.status in (com.modernstock.sale.SalesReturn.Status.POSTED,
                  com.modernstock.sale.SalesReturn.Status.WAITING_APPROVAL)
            """)
    BigDecimal returnedQty(Long saleItemId);

    @Override
    default String entityName() {
        return "Sales return";
    }
}
