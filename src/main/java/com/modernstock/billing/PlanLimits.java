package com.modernstock.billing;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Fits a business to its plan's branch and member limits (PLAIN.md 11.13 rule 3). Nothing is deleted: when limits go
 * down, extra branches become read-only and extra members are disabled, keeping the main branch, the Owners and the
 * oldest members; when they go up again (a new subscription), the same branches and members are switched back on.
 * Transfers already in transit can still be received in a read-only branch.
 */
@Service
@RequiredArgsConstructor
public class PlanLimits {

    private final JdbcTemplate jdbc;

    @Transactional
    public void apply(Long businessId, Plan plan) {
        Integer maxBranches = plan.limit(EntitlementService.BRANCHES);
        List<Long> branches = jdbc.queryForList(
                "select id from branches where business_id = ? and is_active order by is_main desc, id", Long.class, businessId);
        for (int i = 0; i < branches.size(); i++) {
            boolean readOnly = maxBranches != null && i >= maxBranches;
            jdbc.update("update branches set read_only = ?, updated_at = now() where id = ? and read_only <> ?",
                    readOnly, branches.get(i), readOnly);
        }

        Integer maxMembers = plan.limit(EntitlementService.MEMBERS);
        List<Map<String, Object>> members = jdbc.queryForList("""
                select m.id, m.status, r.code from business_members m join roles r on r.id = m.role_id
                 where m.business_id = ? and (m.status = 'ACTIVE' or m.disabled_by_plan)
                 order by (r.code = 'OWNER') desc, m.joined_at, m.id
                """, businessId);
        int kept = 0;
        for (Map<String, Object> m : members) {
            boolean owner = "OWNER".equals(m.get("code"));
            boolean keep = owner || maxMembers == null || kept < maxMembers;
            if (keep) {
                kept++;
                jdbc.update("update business_members set status = 'ACTIVE', disabled_by_plan = false, updated_at = now() "
                        + "where id = ? and (status <> 'ACTIVE' or disabled_by_plan)", m.get("id"));
            } else {
                jdbc.update("update business_members set status = 'DISABLED', disabled_by_plan = true, updated_at = now() "
                        + "where id = ? and status = 'ACTIVE'", m.get("id"));
            }
        }
    }
}
