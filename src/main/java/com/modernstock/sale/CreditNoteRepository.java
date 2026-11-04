package com.modernstock.sale;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface CreditNoteRepository extends TenantRepository<CreditNote> {

    @Query("""
            select c from CreditNote c
            where c.branchId in :branchIds and (:customerId is null or c.customerId = :customerId)
              and (:status is null or c.status = :status)
            order by c.id desc
            """)
    Page<CreditNote> search(Collection<Long> branchIds, Long customerId, CreditNote.Status status, Pageable pageable);

    List<CreditNote> findBySalesReturnId(Long salesReturnId);

    @Override
    default String entityName() {
        return "Credit note";
    }
}
