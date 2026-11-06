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
 * Reorder levels per branch (FR-4.7, PLAIN.md 11.9): a branch's own manual level first; else the reorder point computed
 * from its sales history every morning (ReorderPoints); else the product's manual level. 0 = not watched there. A view over several branches adds up their levels, so "All branches" is low when all of them together hold no
 * more than they keep together. The stock by branch view shows which branch is low.
 */
@Service
@RequiredArgsConstructor
public class ReorderLevels {

    private final BranchReorderLevelRepository overrides;
    private final BranchRepository branches;
    private final BranchScope scope;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    /**
     * SQL for the level of product {@code p} summed over the active branches that match {@code branches}, e.g.
     * {@code "b.id in (:branches)"} or {@code "b.business_id = :business"}.
     */
    public static String sql(String branches) {
        return """
                (select coalesce(sum(coalesce(r.reorder_level, rp.reorder_point, p.reorder_level)), 0) from branches b
                   left join branch_reorder_levels r on r.branch_id = b.id and r.product_id = p.id
                   left join reorder_points rp on rp.branch_id = b.id and rp.product_id = p.id
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
        Map<String, BigDecimal> computed = new HashMap<>();
        if (!products.isEmpty() && !branchIds.isEmpty()) {
            jdbc.query("select product_id, branch_id, reorder_point from reorder_points where product_id in (:products) and branch_id in (:branches)",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                            .addValue("products", products.stream().map(Product::getId).toList()).addValue("branches", branchIds),
                    rs -> {
                        computed.put(rs.getLong("product_id") + "-" + rs.getLong("branch_id"), rs.getBigDecimal("reorder_point"));
                    });
        }
        Map<Long, Map<Long, BigDecimal>> levels = new HashMap<>();
        for (Product p : products) {
            Map<Long, BigDecimal> byBranch = new HashMap<>();
            for (Long branchId : branchIds) {
                String key = p.getId() + "-" + branchId;
                byBranch.put(branchId, own.containsKey(key) ? own.get(key) : computed.getOrDefault(key, p.getReorderLevel()));
            }
            levels.put(p.getId(), byBranch);
        }
        return levels;
    }
}
