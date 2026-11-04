package com.modernstock.sale;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface SalesOrderRepository extends TenantRepository<SalesOrder> {

    @Query("""
            select o from SalesOrder o
            where o.branchId in :branchIds and (:customerId is null or o.customerId = :customerId)
              and (:status is null or o.status = :status)
            order by o.id desc
            """)
    Page<SalesOrder> search(Collection<Long> branchIds, Long customerId, SalesOrder.Status status, Pageable pageable);

    @Override
    default String entityName() {
        return "Sales order";
    }
}
