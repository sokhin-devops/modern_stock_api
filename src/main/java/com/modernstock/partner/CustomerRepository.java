package com.modernstock.partner;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface CustomerRepository extends TenantRepository<Customer> {

    @Query("""
            select c from Customer c where c.active = true
              and (lower(c.name) like :q or coalesce(c.phone, '') like :q)
            """)
    Page<Customer> search(String q, Pageable pageable);

    @Override
    default String entityName() {
        return "Customer";
    }
}
