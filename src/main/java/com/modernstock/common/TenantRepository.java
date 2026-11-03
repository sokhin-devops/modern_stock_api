package com.modernstock.common;

import java.util.Objects;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import com.modernstock.business.BusinessContext;

/**
 * Repository for tenant tables. Use {@link #get(Long)} instead of findById: it also checks the business,
 * so a wrong ID from another business is a 404, never someone else's data.
 */
@NoRepositoryBean
public interface TenantRepository<T extends TenantEntity> extends JpaRepository<T, Long> {

    default T get(Long id) {
        return findById(id)
                .filter(e -> Objects.equals(e.getBusinessId(), BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound(entityName()));
    }

    /** Name used in "not found" messages. Repositories override it with a default method. */
    default String entityName() {
        return "Record";
    }
}
