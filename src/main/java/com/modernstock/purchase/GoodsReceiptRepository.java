package com.modernstock.purchase;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface GoodsReceiptRepository extends TenantRepository<GoodsReceipt> {

    @Query("""
            select g from GoodsReceipt g
            where g.branchId in :branchIds and (:purchaseOrderId is null or g.purchaseOrderId = :purchaseOrderId)
            order by g.id desc
            """)
    Page<GoodsReceipt> search(Collection<Long> branchIds, Long purchaseOrderId, Pageable pageable);

    @Override
    default String entityName() {
        return "Goods receipt";
    }
}
