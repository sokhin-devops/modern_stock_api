package com.modernstock.business;

import java.util.List;
import java.util.Optional;

import com.modernstock.common.TenantRepository;

public interface BranchRepository extends TenantRepository<Branch> {

    List<Branch> findAllByOrderByMainDescNameEnAsc();

    long countByActiveTrue();

    boolean existsByCodeIgnoreCase(String code);

    Optional<Branch> findFirstByMainTrue();

    @Override
    default String entityName() {
        return "Branch";
    }
}
