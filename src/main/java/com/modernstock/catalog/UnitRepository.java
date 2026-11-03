package com.modernstock.catalog;

import java.util.List;

import com.modernstock.common.TenantRepository;

public interface UnitRepository extends TenantRepository<Unit> {

    List<Unit> findAllByOrderByIdAsc();

    @Override
    default String entityName() {
        return "Unit";
    }
}
