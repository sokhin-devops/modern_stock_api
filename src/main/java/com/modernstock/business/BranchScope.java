package com.modernstock.business;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * What a request may read: the selected branch, the member's own branches, or every branch
 * ("All branches"). Lists, the dashboard and reports filter by these IDs (PLAIN.md 11.11).
 */
@Service
@RequiredArgsConstructor
public class BranchScope {

    private final BranchRepository branches;
    private final WarehouseRepository warehouses;

    @Transactional(readOnly = true)
    public List<Long> branchIds() {
        Set<Long> only = BusinessContext.branchFilter();
        return only != null ? only.stream().sorted().toList() : branches.findAll().stream().map(Branch::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> warehouseIds() {
        Set<Long> only = BusinessContext.branchFilter();
        return (only != null ? only.stream().sorted().flatMap(b -> warehouses.findByBranchIdOrderByIdAsc(b).stream()).toList()
                : warehouses.findAll()).stream().map(Warehouse::getId).toList();
    }
}
