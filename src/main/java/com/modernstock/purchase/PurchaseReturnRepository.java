package com.modernstock.purchase;

import java.math.BigDecimal;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface PurchaseReturnRepository extends TenantRepository<PurchaseReturn> {

    @Query("""
            select r from PurchaseReturn r
            where r.branchId in :branchIds and (:supplierId is null or r.supplierId = :supplierId)
            order by r.id desc
            """)
    Page<PurchaseReturn> search(Collection<Long> branchIds, Long supplierId, Pageable pageable);

    /** How much of a received line was already sent back (posted returns). */
    @Query("""
            select coalesce(sum(i.qty), 0) from PurchaseReturn r join r.items i
            where i.goodsReceiptItemId = :itemId and r.status = com.modernstock.purchase.PurchaseReturn.Status.POSTED
            """)
    BigDecimal returnedQty(Long itemId);

    @Override
    default String entityName() {
        return "Purchase return";
    }
}
