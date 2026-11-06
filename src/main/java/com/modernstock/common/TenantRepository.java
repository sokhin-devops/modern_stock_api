package com.modernstock.common;

import java.util.Objects;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.NoRepositoryBean;

import com.modernstock.business.BusinessContext;

import jakarta.persistence.LockModeType;

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

    /** Like {@link #get(Long)}, with the row locked until the transaction ends: a void can't run twice at once. */
    default T lock(Long id) {
        return findLockedById(id)
                .filter(e -> Objects.equals(e.getBusinessId(), BusinessContext.businessId()))
                .orElseThrow(() -> ApiException.notFound(entityName()));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<T> findLockedById(Long id);

    /** Name used in "not found" messages. Repositories override it with a default method. */
    default String entityName() {
        return "Record";
    }
}
