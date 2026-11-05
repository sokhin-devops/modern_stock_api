package com.modernstock.billing;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Fits a business to its plan's branch and member limits, add-ons included (PLAIN.md 11.13 rule 3). Nothing is
 * deleted: when limits go down, extra branches become read-only and extra members are disabled, keeping the main branch,
 * the Owners, then what the Owner chose to keep (FR-10.18), then the oldest; when they go up again (a new subscription,
 * an add-on), the same branches and members are switched back on. Transfers already in transit can still be received
 * in a read-only branch.
 */
@Service
@RequiredArgsConstructor
public class PlanLimits {

    private final JdbcTemplate jdbc;
    private final EntitlementService entitlements;

    @Transactional
    public void apply(Long businessId, Plan plan) {
        Integer maxBranches = entitlements.limit(businessId, plan, EntitlementService.BRANCHES);
        List<Long> branches = jdbc.queryForList(
                "select id from branches where business_id = ? and is_active order by is_main desc, plan_keep desc, id", Long.class, businessId);
        for (int i = 0; i < branches.size(); i++) {
            boolean readOnly = maxBranches != null && i >= maxBranches;
            jdbc.update("update branches set read_only = ?, updated_at = now() where id = ? and read_only <> ?",
                    readOnly, branches.get(i), readOnly);
        }

        Integer maxMembers = entitlements.limit(businessId, plan, EntitlementService.MEMBERS);
        List<Map<String, Object>> members = jdbc.queryForList("""
                select m.id, m.status, r.code from business_members m join roles r on r.id = m.role_id
                 where m.business_id = ? and (m.status = 'ACTIVE' or m.disabled_by_plan)
                 order by (r.code = 'OWNER') desc, m.plan_keep desc, m.joined_at, m.id
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

    public record BranchChoice(Long id, String name, boolean main, boolean readOnly, boolean keep) {
    }

    public record MemberChoice(Long id, String name, String role, boolean owner, boolean disabledByPlan, boolean keep) {
    }

    /** The active branches and the members a plan's limits can switch off, with what the Owner chose to keep. */
    @Transactional(readOnly = true)
    public List<BranchChoice> branches(Long businessId) {
        return jdbc.query("select id, name_en, is_main, read_only, plan_keep from branches where business_id = ? and is_active "
                        + "order by is_main desc, name_en", (rs, n) -> new BranchChoice(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                rs.getBoolean(4), rs.getBoolean(5)), businessId);
    }

    @Transactional(readOnly = true)
    public List<MemberChoice> members(Long businessId) {
        return jdbc.query("""
                select m.id, u.full_name, r.name_en, r.code = 'OWNER', m.disabled_by_plan, m.plan_keep
                  from business_members m join users u on u.id = m.user_id join roles r on r.id = m.role_id
                 where m.business_id = ? and (m.status = 'ACTIVE' or m.disabled_by_plan)
                 order by (r.code = 'OWNER') desc, u.full_name
                """, (rs, n) -> new MemberChoice(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                rs.getBoolean(6)), businessId);
    }

    /**
     * The Owner's choice (FR-10.18): these branches and members stay active before the others when limits go down. The
     * main branch and the Owners always stay, so the choice fills the places left. Applied now with `plan`.
     */
    @Transactional
    public void keep(Long businessId, Plan plan, Plan next, List<Long> branchIds, List<Long> memberIds) {
        List<BranchChoice> branches = branches(businessId);
        List<MemberChoice> members = members(businessId);
        List<Long> extraBranches = branches.stream().filter(b -> !b.main() && branchIds.contains(b.id())).map(BranchChoice::id).toList();
        List<Long> extraMembers = members.stream().filter(m -> !m.owner() && memberIds.contains(m.id())).map(MemberChoice::id).toList();
        Integer maxBranches = entitlements.limit(businessId, next, EntitlementService.BRANCHES);
        Integer maxMembers = entitlements.limit(businessId, next, EntitlementService.MEMBERS);
        long owners = members.stream().filter(MemberChoice::owner).count();
        if ((maxBranches != null && extraBranches.size() > Math.max(0, maxBranches - 1))
                || (maxMembers != null && extraMembers.size() > Math.max(0, maxMembers - owners))) {
            throw com.modernstock.common.ApiException.rule("BILLING_KEEP_TOO_MANY");
        }
        branches.forEach(b -> jdbc.update("update branches set plan_keep = ? where id = ?", extraBranches.contains(b.id()), b.id()));
        members.forEach(m -> jdbc.update("update business_members set plan_keep = ? where id = ?", extraMembers.contains(m.id()), m.id()));
        apply(businessId, plan);
    }
}
