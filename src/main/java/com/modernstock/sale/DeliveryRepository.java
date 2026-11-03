package com.modernstock.sale;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface DeliveryRepository extends TenantRepository<Delivery> {

    @Query("""
            select d from Delivery d
            where d.branchId in :branchIds and (:customerId is null or d.customerId = :customerId)
            order by d.id desc
            """)
    Page<Delivery> search(Collection<Long> branchIds, Long customerId, Pageable pageable);

    @Override
    default String entityName() {
        return "Delivery";
    }
}
