package com.modernstock.partner;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import com.modernstock.common.TenantRepository;

public interface SupplierRepository extends TenantRepository<Supplier> {

    @Query("""
            select s from Supplier s where s.active = true
              and (lower(s.name) like :q or coalesce(s.phone, '') like :q)
            """)
    Page<Supplier> search(String q, Pageable pageable);

    @Override
    default String entityName() {
        return "Supplier";
    }
}
