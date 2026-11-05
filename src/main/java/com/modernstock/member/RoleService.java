package com.modernstock.member;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.common.ApiException;
import com.modernstock.member.MemberService.RoleResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Custom roles (FR-6.24, PLAIN.md section 5): the Owner makes the business's own roles by ticking permissions, often
 * starting from a copy of a built-in role. Built-in roles can't be changed. Owner-only permissions (members and roles,
 * business settings, billing) can't be given, so an Owner stays in control. A role still used by members or pending
 * invitations can't be deleted. Changes apply on each member's next request and go to the activity log.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    public record PermissionResponse(String code, String area, String nameEn, String nameKm,
                                     @Schema(description = "Only built-in Owners have it; a custom role can't") boolean ownerOnly) {
    }

    public record RoleRequest(@NotBlank @Size(max = 60) String nameEn, @Size(max = 60) String nameKm,
                              @Schema(description = "Permission codes, e.g. sale:create") @NotNull Set<String> permissions) {
    }

    /** Can't be given to a custom role (PLAIN.md section 5). */
    public static final Set<String> OWNER_ONLY = Set.of("member:manage", "business:manage", "billing:manage");
    static final int MAX_CUSTOM = 10;

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final BusinessMemberRepository members;
    private final InvitationRepository invitations;
    private final ActivityService activity;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<PermissionResponse> permissions() {
        return permissions.findAll().stream()
                .map(p -> new PermissionResponse(p.getCode(), p.getArea(), p.getNameEn(), p.getNameKm(), OWNER_ONLY.contains(p.getCode())))
                .toList();
    }

    @Transactional
    public RoleResponse create(RoleRequest req) {
        Long businessId = BusinessContext.require().businessId();
        if (roles.countByBusinessId(businessId) >= MAX_CUSTOM) {
            throw ApiException.rule("ROLE_LIMIT", MAX_CUSTOM);
        }
        Role r = new Role();
        r.setBusinessId(businessId);
        r.setCode(nextCode(businessId));
        apply(r, req);
        roles.save(r);
        activity.record("ROLE_CREATED", "ROLE", r.getId(), r.getNameEn(), null, null,
                ActivityService.values("permissions", codes(r)));
        return RoleResponse.from(r);
    }

    @Transactional
    public RoleResponse update(Long id, RoleRequest req) {
        Role r = custom(id);
        String name = r.getNameEn();
        String nameKm = r.getNameKm();
        Set<String> before = codes(r);
        apply(r, req);
        Set<String> after = codes(r);
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        activity.changed("ROLE_CHANGED", "ROLE", r.getId(), r.getNameEn(), null,
                ActivityService.values("nameEn", name, "nameKm", nameKm, "removed", removed.isEmpty() ? null : List.copyOf(removed)),
                ActivityService.values("nameEn", r.getNameEn(), "nameKm", r.getNameKm(), "added", added.isEmpty() ? null : List.copyOf(added)));
        return RoleResponse.from(r);
    }

    @Transactional
    public void delete(Long id) {
        Role r = custom(id);
        Long businessId = r.getBusinessId();
        if (members.countByBusinessIdAndRoleIdAndStatusNot(businessId, r.getId(), BusinessMember.Status.REMOVED) > 0
                || invitations.countByBusinessIdAndRoleIdAndStatus(businessId, r.getId(), Invitation.Status.PENDING) > 0) {
            throw ApiException.rule("ROLE_IN_USE");
        }
        activity.record("ROLE_DELETED", "ROLE", r.getId(), r.getNameEn(), null, ActivityService.values("permissions", codes(r)), null);
        // Removed members and closed invitations still point to the role: they keep the built-in Cashier instead
        Long fallback = roles.findBuiltIn("CASHIER").orElseThrow().getId();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("role", r.getId()).addValue("fallback", fallback)
                .addValue("business", businessId);
        jdbc.update("update business_members set role_id = :fallback where business_id = :business and role_id = :role", p);
        jdbc.update("update invitations set role_id = :fallback where business_id = :business and role_id = :role", p);
        roles.delete(r);
    }

    private void apply(Role r, RoleRequest req) {
        String name = req.nameEn().trim();
        boolean taken = roles.findVisibleTo(r.getBusinessId()).stream()
                .anyMatch(other -> !other.getId().equals(r.getId()) && other.getNameEn().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.conflict("ROLE_NAME_TAKEN", name);
        }
        Set<String> wanted = new HashSet<>(req.permissions());
        if (wanted.stream().anyMatch(OWNER_ONLY::contains)) {
            throw ApiException.rule("ROLE_OWNER_ONLY_PERMISSION");
        }
        List<Permission> found = permissions.findByCodeIn(wanted);
        if (found.size() != wanted.size()) {
            throw ApiException.badRequest("ROLE_PERMISSION_UNKNOWN");
        }
        r.setNameEn(name);
        r.setNameKm(req.nameKm() == null || req.nameKm().isBlank() ? null : req.nameKm().trim());
        r.setPermissions(new HashSet<>(found));
    }

    /** A custom role of the open business; built-in roles can't be changed. */
    private Role custom(Long id) {
        Role r = roles.findById(id).orElseThrow(() -> ApiException.notFound("Role"));
        if (r.isBuiltIn() || r.getBusinessId() == null) {
            throw ApiException.rule("ROLE_BUILT_IN");
        }
        if (!r.getBusinessId().equals(BusinessContext.require().businessId())) {
            throw ApiException.notFound("Role");
        }
        return r;
    }

    private String nextCode(Long businessId) {
        Set<String> used = roles.findVisibleTo(businessId).stream().map(Role::getCode).collect(Collectors.toSet());
        int n = 1;
        while (used.contains("CUSTOM_" + n)) {
            n++;
        }
        return "CUSTOM_" + n;
    }

    private static Set<String> codes(Role r) {
        return r.getPermissions().stream().map(Permission::getCode).collect(Collectors.toCollection(TreeSet::new));
    }
}
