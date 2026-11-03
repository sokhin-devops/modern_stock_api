package com.modernstock.catalog;

import java.util.List;

import com.modernstock.common.TenantRepository;

public interface CategoryRepository extends TenantRepository<Category> {

    List<Category> findAllByOrderByNameEnAsc();

    @Override
    default String entityName() {
        return "Category";
    }
}
