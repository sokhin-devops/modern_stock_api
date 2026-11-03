package com.modernstock.member;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.modernstock.admin.PlatformSettings;
import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BranchRepository;
import com.modernstock.business.Business;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.business.BusinessRepository;
import com.modernstock.common.ApiException;
import com.modernstock.common.Phones;
import com.modernstock.common.Tokens;
import com.modernstock.config.AppProperties;
import com.modernstock.notification.MailService;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Invitations (PLAIN.md 11.12): the Owner picks a role and a branch and shares a one-time link by email,
 * Telegram or QR code. The link works once and expires after 7 days; pending invitations count as members.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    public record InviteRequest(
            @NotNull Long roleId,
            @Schema(description = "The branch the person will work in; empty = all branches") Long branchId,
            @Schema(description = "Send by email. Empty = copy the link or show it as a QR code") @Email String email) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationResponse(Long id, Long roleId, String roleNameEn, String roleNameKm, Long branchId,
                                     String branchName, String email, Invitation.Method method,
                                     Invitation.Status status, Instant expiresAt, Instant createdAt,
                                     @Schema(description = "Only right after create or resend") String link) {
    }

    public record InvitationDetails(String businessName, String businessNameKm, String roleNameEn, String roleNameKm,
                                    String branchName, String email, Instant expiresAt, Invitation.Status status) {
    }

    public record MyInvitation(Long id, Long businessId, String businessName, String roleNameEn, String roleNameKm,
                               String branchName, Instant expiresAt) {
    }

    public record SignUpWithInvitationRequest(
            @NotBlank String token,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            String confirmPassword,
            @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
            @Schema(description = "Optional. For email invitations the invited email is used") @Email String email) {
    }

    public record Joined(User user, Long businessId) {
    }

    private final InvitationRepository invitations;
    private final RoleRepository roles;
    private final BusinessRepository businesses;
    private final BusinessMemberRepository members;
    private final BranchRepository branches;
    private final UserRepository users;
    private final EntitlementService entitlements;
    private final PlatformSettings platformSettings;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final AppProperties props;
    private final JdbcTemplate jdbc;

    @Transactional
    public InvitationResponse create(InviteRequest req) {
        BusinessContext.Context ctx = BusinessContext.require();
        Role role = visibleRole(req.roleId(), ctx.businessId());
        if (req.branchId() != null) {
            branches.get(req.branchId());
        }
        entitlements.checkCanAdd(EntitlementService.MEMBERS);

        Invitation inv = new Invitation();
        inv.setBusinessId(ctx.businessId());
        inv.setRoleId(role.getId());
        inv.setBranchId(req.branchId());
        inv.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().trim().toLowerCase());
        inv.setMethod(inv.getEmail() != null ? Invitation.Method.EMAIL : Invitation.Method.LINK);
        inv.setInvitedBy(ctx.userId());
        String link = renew(inv);
        invitations.save(inv);
        if (inv.getMethod() == Invitation.Method.EMAIL) {
            mail.send(inv.getEmail(), "You are invited to ModernStock", "Open this link within 7 days: " + link);
        }
        return toResponse(inv, link);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> list() {
        return invitations.findByBusinessIdOrderByCreatedAtDesc(BusinessContext.require().businessId()).stream()
                .map(i -> toResponse(i, null)).toList();
    }

    @Transactional
    public InvitationResponse resend(Long id) {
        Invitation inv = owned(id);
        if (inv.getStatus() != Invitation.Status.PENDING && inv.getStatus() != Invitation.Status.EXPIRED) {
            throw ApiException.rule("INVITATION_NOT_OPEN");
        }
        String link = renew(inv);
        if (inv.getMethod() == Invitation.Method.EMAIL) {
            mail.send(inv.getEmail(), "You are invited to ModernStock", "Open this link within 7 days: " + link);
        }
        return toResponse(inv, link);
    }

    @Transactional
    public void cancel(Long id) {
        Invitation inv = owned(id);
        if (inv.getStatus() != Invitation.Status.PENDING) {
            throw ApiException.rule("INVITATION_NOT_OPEN");
        }
        inv.setStatus(Invitation.Status.CANCELLED);
    }

    @Transactional(readOnly = true)
    public InvitationDetails details(String token) {
        Invitation inv = byToken(token);
        Business b = businesses.findById(inv.getBusinessId()).orElseThrow();
        Role role = roles.findById(inv.getRoleId()).orElseThrow();
        return new InvitationDetails(b.getNameEn(), b.getNameKm(), role.getNameEn(), role.getNameKm(),
                branchName(inv.getBranchId()), inv.getEmail(), inv.getExpiresAt(), shownStatus(inv));
    }

    @Transactional(readOnly = true)
    public List<MyInvitation> forUser(Long userId) {
        User u = users.findById(userId).orElseThrow();
        if (u.getEmail() == null || u.getEmailVerifiedAt() == null) {
            return List.of();
        }
        List<Invitation> open = invitations.findOpenForEmail(u.getEmail());
        Map<Long, Business> byId = businesses.findAllById(open.stream().map(Invitation::getBusinessId).toList())
                .stream().collect(Collectors.toMap(Business::getId, Function.identity()));
        return open.stream().map(i -> {
            Role role = roles.findById(i.getRoleId()).orElseThrow();
            return new MyInvitation(i.getId(), i.getBusinessId(), byId.get(i.getBusinessId()).getNameEn(),
                    role.getNameEn(), role.getNameKm(), branchName(i.getBranchId()), i.getExpiresAt());
        }).toList();
    }

    /** key = the token from the link, or the invitation ID from GET /me/invitations. Returns the business ID. */
    @Transactional
    public Long accept(String key, Long userId) {
        User u = users.findById(userId).orElseThrow();
        Invitation inv = byKey(key, u);
        join(inv, u);
        return inv.getBusinessId();
    }

    @Transactional
    public void decline(String key, Long userId) {
        Invitation inv = byKey(key, users.findById(userId).orElseThrow());
        requireOpen(inv);
        inv.setStatus(Invitation.Status.DECLINED);
    }

    /** New account from an invitation link: no email needed; email invitations count as verified email. */
    @Transactional
    public Joined signUpWithInvitation(SignUpWithInvitationRequest req) {
        if (req.confirmPassword() != null && !req.confirmPassword().equals(req.password())) {
            throw ApiException.badRequest("PASSWORDS_DO_NOT_MATCH");
        }
        Invitation inv = byToken(req.token());
        requireOpen(inv);
        if (users.existsByUsernameIgnoreCase(req.username())) {
            throw ApiException.conflict("USERNAME_TAKEN");
        }
        String email = inv.getMethod() == Invitation.Method.EMAIL ? inv.getEmail()
                : (req.email() == null || req.email().isBlank() ? null : req.email().trim().toLowerCase());
        if (email != null && users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("EMAIL_TAKEN");
        }
        User u = new User();
        u.setFullName(req.fullName().trim());
        u.setUsername(req.username().trim());
        u.setEmail(email);
        u.setPhone(req.phone());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setStatus(User.Status.ACTIVE);
        if (inv.getMethod() == Invitation.Method.EMAIL) {
            u.setEmailVerifiedAt(Instant.now());
        } else {
            u.setManagedByBusinessId(inv.getBusinessId());
        }
        Business b = businesses.findById(inv.getBusinessId()).orElseThrow();
        u.setLocale(b.getLanguage());
        users.save(u);
        join(inv, u);
        return new Joined(u, inv.getBusinessId());
    }

    private void join(Invitation inv, User u) {
        requireOpen(inv);
        if (inv.getMethod() == Invitation.Method.EMAIL
                && (u.getEmail() == null || !u.getEmail().equalsIgnoreCase(inv.getEmail()))) {
            throw ApiException.forbidden("INVITATION_EMAIL_MISMATCH");
        }
        BusinessMember m = members.findByBusinessIdAndUserId(inv.getBusinessId(), u.getId()).orElse(null);
        if (m != null && m.getStatus() != BusinessMember.Status.REMOVED) {
            throw ApiException.conflict("ALREADY_MEMBER");
        }
        if (m == null) {
            m = new BusinessMember();
            m.setBusinessId(inv.getBusinessId());
            m.setUserId(u.getId());
        }
        m.setRoleId(inv.getRoleId());
        m.setBranchId(inv.getBranchId());
        m.setStatus(BusinessMember.Status.ACTIVE);
        m.setInvitedBy(inv.getInvitedBy());
        m.setJoinedAt(Instant.now());
        members.save(m);
        inv.setStatus(Invitation.Status.ACCEPTED);
        inv.setAcceptedBy(u.getId());
    }

    private void requireOpen(Invitation inv) {
        if (inv.getStatus() == Invitation.Status.PENDING && !inv.isOpen()) {
            inv.setStatus(Invitation.Status.EXPIRED);
            throw ApiException.rule("INVITATION_EXPIRED");
        }
        if (inv.getStatus() != Invitation.Status.PENDING) {
            throw ApiException.rule("INVITATION_NOT_OPEN");
        }
    }

    private String renew(Invitation inv) {
        String raw = Tokens.random();
        inv.setTokenHash(Tokens.sha256(raw));
        inv.setStatus(Invitation.Status.PENDING);
        inv.setExpiresAt(Instant.now().plus(Duration.ofDays(
                platformSettings.getInt(PlatformSettings.INVITATION_DAYS, 7))));
        return props.webAppUrl() + "/invite/" + raw;
    }

    private Invitation byToken(String token) {
        return invitations.findByTokenHash(Tokens.sha256(token))
                .orElseThrow(() -> ApiException.notFound("Invitation"));
    }

    private Invitation byKey(String key, User u) {
        if (key.chars().allMatch(Character::isDigit)) {
            Invitation inv = invitations.findById(Long.valueOf(key))
                    .orElseThrow(() -> ApiException.notFound("Invitation"));
            if (inv.getEmail() == null || u.getEmail() == null || !inv.getEmail().equalsIgnoreCase(u.getEmail())) {
                throw ApiException.notFound("Invitation");
            }
            return inv;
        }
        return byToken(key);
    }

    private Invitation owned(Long id) {
        return invitations.findByIdAndBusinessId(id, BusinessContext.require().businessId())
                .orElseThrow(() -> ApiException.notFound("Invitation"));
    }

    private Role visibleRole(Long roleId, Long businessId) {
        Role role = roles.findById(roleId).orElseThrow(() -> ApiException.notFound("Role"));
        if (role.getBusinessId() != null && !role.getBusinessId().equals(businessId)) {
            throw ApiException.notFound("Role");
        }
        return role;
    }

    /** Branches are tenant rows; the invitation page has no business context, so read the name directly. */
    private String branchName(Long branchId) {
        if (branchId == null) {
            return null;
        }
        List<String> names = jdbc.queryForList("SELECT name_en FROM branches WHERE id = ?", String.class, branchId);
        return names.isEmpty() ? null : names.get(0);
    }

    private static Invitation.Status shownStatus(Invitation inv) {
        return inv.getStatus() == Invitation.Status.PENDING && !inv.isOpen() ? Invitation.Status.EXPIRED : inv.getStatus();
    }

    private InvitationResponse toResponse(Invitation i, String link) {
        Role role = roles.findById(i.getRoleId()).orElseThrow();
        return new InvitationResponse(i.getId(), role.getId(), role.getNameEn(), role.getNameKm(), i.getBranchId(),
                branchName(i.getBranchId()), i.getEmail(), i.getMethod(), shownStatus(i), i.getExpiresAt(),
                i.getCreatedAt(), link);
    }
}
