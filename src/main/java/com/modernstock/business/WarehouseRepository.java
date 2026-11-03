package com.modernstock.business;

import java.util.List;
import java.util.Optional;

import com.modernstock.common.TenantRepository;

public interface WarehouseRepository extends TenantRepository<Warehouse> {

    List<Warehouse> findByBranchIdOrderByIdAsc(Long branchId);

    List<Warehouse> findByBranchIdAndActiveTrue(Long branchId);

    Optional<Warehouse> findFirstByBranchIdAndSalesTrueAndActiveTrue(Long branchId);

    Optional<Warehouse> findFirstByBranchIdAndReceivingTrueAndActiveTrue(Long branchId);

    @Override
    default String entityName() {
        return "Warehouse";
    }
}
