package com.modernstock.purchase;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface BillRepository extends TenantRepository<Bill> {

    @Query("""
            select b from Bill b
            where b.branchId in :branchIds
              and (:supplierId is null or b.supplierId = :supplierId)
              and (:status is null or b.status = :status)
            order by b.dueDate, b.id
            """)
    Page<Bill> search(Collection<Long> branchIds, Long supplierId, Bill.Status status, Pageable pageable);

    /** Oldest due first, for paying bills automatically. */
    List<Bill> findBySupplierIdAndStatusInOrderByDueDateAscIdAsc(Long supplierId, Collection<Bill.Status> statuses);

    Optional<Bill> findByGoodsReceiptId(Long goodsReceiptId);

    @Override
    default String entityName() {
        return "Bill";
    }
}
