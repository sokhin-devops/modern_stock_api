package com.modernstock.member;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.auth.AuthService;
import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BranchRepository;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.common.ApiException;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

/** Members of the open business. A business always keeps at least one active Owner. */
@Service
@RequiredArgsConstructor
public class MemberService {

    public record MemberResponse(Long id, Long userId, String fullName, String username, String email, String phone,
                                 Long roleId, String roleCode, String roleNameEn, String roleNameKm,
                                 @Schema(description = "The first branch; null = all branches") Long branchId,
                                 @Schema(description = "Every branch the member works in; null = all branches") List<Long> branchIds,
                                 BusinessMember.Status status, Instant joinedAt,
                                 @Schema(description = "The Owner can create a reset link (joined without a verified email)")
                                 boolean canResetPassword) {
    }

    public record UpdateMemberRequest(Long roleId,
                                      @Schema(description = "Work in this branch") Long branchId,
                                      @Schema(description = "Work in these branches (FR-6.18); wins over branchId") List<Long> branchIds,
                                      @Schema(description = "true = access to all branches (branchId is ignored)") Boolean allBranches,
                                      @Schema(description = "ACTIVE or DISABLED; use DELETE to remove") BusinessMember.Status status) {
    }

    public record RoleResponse(Long id, String code, String nameEn, String nameKm, boolean builtIn,
                               List<String> permissions) {
        static RoleResponse from(Role r) {
            return new RoleResponse(r.getId(), r.getCode(), r.getNameEn(), r.getNameKm(), r.isBuiltIn(),
                    r.getPermissions().stream().map(Permission::getCode).sorted().toList());
        }
    }

    public record LinkResponse(String link, Instant expiresAt) {
    }

    private final BusinessMemberRepository members;
    private final UserRepository users;
    private final RoleRepository roles;
    private final BranchRepository branches;
    private final EntitlementService entitlements;
    private final AuthService auth;
    private final ActivityService activity;

    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        Long businessId = BusinessContext.require().businessId();
        List<BusinessMember> list = members.findByBusinessIdAndStatusNotOrderByJoinedAt(businessId,
                BusinessMember.Status.REMOVED);
        Map<Long, User> userById = users.findAllById(list.stream().map(BusinessMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Role> roleById = roles.findVisibleTo(businessId).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        return list.stream().map(m -> toResponse(m, userById.get(m.getUserId()), roleById.get(m.getRoleId())))
                .sorted(Comparator.comparing(MemberResponse::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public MemberResponse update(Long id, UpdateMemberRequest req) {
        BusinessMember m = owned(id);
        boolean wasActiveOwner = m.getStatus() == BusinessMember.Status.ACTIVE && isOwnerRole(m.getRoleId());
        Map<String, Object> before = facts(m);

        if (req.roleId() != null) {
            Role role = roles.findById(req.roleId()).orElseThrow(() -> ApiException.notFound("Role"));
            if (role.getBusinessId() != null && !role.getBusinessId().equals(m.getBusinessId())) {
                throw ApiException.notFound("Role");
            }
            m.setRoleId(role.getId());
        }
        if (Boolean.TRUE.equals(req.allBranches())) {
            m.setBranchId(null);
            m.getMoreBranchIds().clear();
        } else if (req.branchIds() != null && !req.branchIds().isEmpty()) {
            List<Long> ids = req.branchIds().stream().distinct().sorted().toList();
            ids.forEach(branches::get);
            m.setBranchId(ids.get(0));
            m.getMoreBranchIds().clear();
            m.getMoreBranchIds().addAll(ids.subList(1, ids.size()));
        } else if (req.branchId() != null) {
            branches.get(req.branchId());
            m.setBranchId(req.branchId());
            m.getMoreBranchIds().clear();
        }
        if (req.status() != null && req.status() != m.getStatus()) {
            if (req.status() == BusinessMember.Status.REMOVED) {
                throw ApiException.badRequest("USE_DELETE_TO_REMOVE");
            }
            if (req.status() == BusinessMember.Status.ACTIVE) {
                entitlements.checkCanAdd(EntitlementService.MEMBERS);
            }
            m.setStatus(req.status());
        }
        boolean stillActiveOwner = m.getStatus() == BusinessMember.Status.ACTIVE && isOwnerRole(m.getRoleId());
        if (wasActiveOwner && !stillActiveOwner) {
            requireAnotherOwner(m);
        }
        User user = users.findById(m.getUserId()).orElseThrow();
        activity.changed("MEMBER_CHANGED", "MEMBER", m.getId(), user.getFullName(), null, before, facts(m));
        return toResponse(m, user, roles.findById(m.getRoleId()).orElseThrow());
    }

    /** Removed members keep their names on old documents. */
    @Transactional
    public void remove(Long id) {
        BusinessMember m = owned(id);
        if (m.getStatus() == BusinessMember.Status.ACTIVE && isOwnerRole(m.getRoleId())) {
            requireAnotherOwner(m);
        }
        m.setStatus(BusinessMember.Status.REMOVED);
        activity.record("MEMBER_REMOVED", "MEMBER", m.getId(), users.findById(m.getUserId()).map(User::getFullName).orElse(null),
                null, facts(m), null);
    }

    /** Role, branch and status, by name, for the activity log; branch null = all branches. */
    private Map<String, Object> facts(BusinessMember m) {
        return ActivityService.values("role", roles.findById(m.getRoleId()).map(Role::getNameEn).orElse(null),
                "branch", m.getBranchId() == null ? null : m.workBranchIds().stream()
                        .map(id -> branches.findById(id).map(b -> b.getNameEn()).orElse("?")).collect(Collectors.joining(", ")),
                "status", m.getStatus().name());
    }

    /**
     * Only for accounts that joined through this business and have no verified email of their own.
     * The Owner never sees or sets passwords; the member opens the link and chooses one.
     */
    @Transactional
    public LinkResponse resetLink(Long id) {
        BusinessMember m = owned(id);
        User u = users.findById(m.getUserId()).orElseThrow();
        if (!m.getBusinessId().equals(u.getManagedByBusinessId()) || u.getEmailVerifiedAt() != null) {
            throw ApiException.rule("RESET_LINK_NOT_ALLOWED");
        }
        Duration validFor = Duration.ofHours(24);
        activity.record("RESET_LINK_CREATED", "MEMBER", m.getId(), u.getFullName(), null, null, null);
        return new LinkResponse(auth.resetLink(u, validFor), Instant.now().plus(validFor));
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> roles() {
        return roles.findVisibleTo(BusinessContext.require().businessId()).stream().map(RoleResponse::from).toList();
    }

    private void requireAnotherOwner(BusinessMember m) {
        Long ownerRoleId = roles.findBuiltIn(Role.OWNER).orElseThrow().getId();
        long otherOwners = members.countByBusinessIdAndRoleIdAndStatusAndIdNot(m.getBusinessId(), ownerRoleId,
                BusinessMember.Status.ACTIVE, m.getId());
        if (otherOwners == 0) {
            throw ApiException.rule("LAST_OWNER");
        }
    }

    private boolean isOwnerRole(Long roleId) {
        return roles.findById(roleId).map(Role::isOwner).orElse(false);
    }

    private BusinessMember owned(Long id) {
        return members.findByIdAndBusinessId(id, BusinessContext.require().businessId())
                .filter(m -> m.getStatus() != BusinessMember.Status.REMOVED)
                .orElseThrow(() -> ApiException.notFound("Member"));
    }

    private static MemberResponse toResponse(BusinessMember m, User u, Role r) {
        return new MemberResponse(m.getId(), u.getId(), u.getFullName(), u.getUsername(), u.getEmail(), u.getPhone(),
                r.getId(), r.getCode(), r.getNameEn(), r.getNameKm(), m.getBranchId(), m.workBranchIds(), m.getStatus(), m.getJoinedAt(),
                m.getBusinessId().equals(u.getManagedByBusinessId()) && u.getEmailVerifiedAt() == null);
    }
}
