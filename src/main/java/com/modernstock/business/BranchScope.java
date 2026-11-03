package com.modernstock.business;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * What a request may read: the selected branch, the member's own branch, or every branch
 * ("All branches"). Lists, the dashboard and reports filter by these IDs (PLAIN.md 11.11).
 */
@Service
@RequiredArgsConstructor
public class BranchScope {

    private final BranchRepository branches;
    private final WarehouseRepository warehouses;

    @Transactional(readOnly = true)
    public List<Long> branchIds() {
        Long only = BusinessContext.branchFilter();
        return only != null ? List.of(only) : branches.findAll().stream().map(Branch::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> warehouseIds() {
        Long only = BusinessContext.branchFilter();
        return (only != null ? warehouses.findByBranchIdOrderByIdAsc(only) : warehouses.findAll())
                .stream().map(Warehouse::getId).toList();
    }
}
