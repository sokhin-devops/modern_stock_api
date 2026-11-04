package com.modernstock.catalog;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.Branch;
import com.modernstock.business.BranchRepository;
import com.modernstock.business.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Reorder levels per branch (FR-4.7): a branch keeps its own level, or the product's when it has none; 0 = not watched
 * there. A view over several branches adds up their levels, so "All branches" is low when all of them together hold no
 * more than they keep together. The stock by branch view shows which branch is low.
 */
@Service
@RequiredArgsConstructor
public class ReorderLevels {

    private final BranchReorderLevelRepository overrides;
    private final BranchRepository branches;
    private final BranchScope scope;

    /**
     * SQL for the level of product {@code p} summed over the active branches that match {@code branches}, e.g.
     * {@code "b.id in (:branches)"} or {@code "b.business_id = :business"}.
     */
    public static String sql(String branches) {
        return """
                (select coalesce(sum(coalesce(r.reorder_level, p.reorder_level)), 0) from branches b
                   left join branch_reorder_levels r on r.branch_id = b.id and r.product_id = p.id
                  where b.is_active and %s)""".formatted(branches);
    }

    /** The active branches of the ones the request may see (BranchScope). */
    @Transactional(readOnly = true)
    public List<Long> scopeBranches() {
        return branches.findAllById(scope.branchIds()).stream().filter(Branch::isActive).map(Branch::getId).sorted().toList();
    }

    /** Each product's level summed over the branches the request may see. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> inScope(Collection<Product> products) {
        Map<Long, BigDecimal> sums = new HashMap<>();
        perBranch(products, scopeBranches()).forEach((productId, levels) ->
                sums.put(productId, levels.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        return sums;
    }

    /** product → branch → its level there, for each of the branches. */
    @Transactional(readOnly = true)
    public Map<Long, Map<Long, BigDecimal>> perBranch(Collection<Product> products, Collection<Long> branchIds) {
        Map<String, BigDecimal> own = new HashMap<>();
        if (!products.isEmpty() && !branchIds.isEmpty()) {
            overrides.findByProductIdInAndBranchIdIn(products.stream().map(Product::getId).toList(), branchIds)
                    .forEach(o -> own.put(o.getProductId() + "-" + o.getBranchId(), o.getReorderLevel()));
        }
        Map<Long, Map<Long, BigDecimal>> levels = new HashMap<>();
        for (Product p : products) {
            Map<Long, BigDecimal> byBranch = new HashMap<>();
            for (Long branchId : branchIds) {
                byBranch.put(branchId, own.getOrDefault(p.getId() + "-" + branchId, p.getReorderLevel()));
            }
            levels.put(p.getId(), byBranch);
        }
        return levels;
    }
}
