package com.modernstock.sale;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface InvoiceRepository extends TenantRepository<Invoice> {

    @Query("""
            select i from Invoice i
            where i.branchId in :branchIds
              and (:customerId is null or i.customerId = :customerId)
              and (:status is null or i.status = :status)
            order by i.id desc
            """)
    Page<Invoice> search(Collection<Long> branchIds, Long customerId, Invoice.Status status, Pageable pageable);

    /** Oldest due first, for applying a payment automatically. */
    List<Invoice> findByCustomerIdAndStatusInOrderByDueDateAscIdAsc(Long customerId, Collection<Invoice.Status> statuses);

    Optional<Invoice> findByDeliveryId(Long deliveryId);

    @Override
    default String entityName() {
        return "Invoice";
    }
}
