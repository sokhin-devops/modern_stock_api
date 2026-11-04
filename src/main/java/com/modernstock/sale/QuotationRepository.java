package com.modernstock.sale;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface QuotationRepository extends TenantRepository<Quotation> {

    @Query("""
            select q from Quotation q
            where q.branchId in :branchIds and (:customerId is null or q.customerId = :customerId)
              and (:status is null or q.status = :status)
            order by q.id desc
            """)
    Page<Quotation> search(Collection<Long> branchIds, Long customerId, Quotation.Status status, Pageable pageable);

    @Override
    default String entityName() {
        return "Quotation";
    }
}
