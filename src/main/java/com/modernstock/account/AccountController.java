package com.modernstock.account;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.auth.AuthService.MessageResponse;
import com.modernstock.auth.AuthService.UserResponse;
import com.modernstock.auth.User;
import com.modernstock.auth.UserRepository;
import com.modernstock.billing.Subscription;
import com.modernstock.billing.SubscriptionRepository;
import com.modernstock.business.Business;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.business.BusinessRepository;
import com.modernstock.business.BusinessService;
import com.modernstock.business.BusinessService.CreateBusinessRequest;
import com.modernstock.common.ApiException;
import com.modernstock.common.CurrentUser;
import com.modernstock.common.Messages;
import com.modernstock.common.Phones;
import com.modernstock.member.InvitationService;
import com.modernstock.member.InvitationService.MyInvitation;
import com.modernstock.member.Permission;
import com.modernstock.member.Role;
import com.modernstock.member.RoleRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Things an account does outside any business: profile, my businesses, my invitations. */
@Tag(name = "My account")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class AccountController {

    public record UpdateMeRequest(@NotBlank @Size(max = 120) String fullName,
                                  @Schema(description = "Sign-in name; empty keeps it") @Size(min = 3, max = 60)
                                  @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
                                  @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
                                  @Pattern(regexp = "km|en") String locale,
                                  @Pattern(regexp = "light|dark|system") String uiMode,
                                  @Pattern(regexp = "comfortable|compact") String uiDensity) {
    }

    /** Only what is sent changes (the app saves one choice at a time). */
    public record PreferencesRequest(@Pattern(regexp = "km|en") String locale,
                                     @Pattern(regexp = "light|dark|system") String uiMode,
                                     @Pattern(regexp = "comfortable|compact") String uiDensity) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record MyBusinessResponse(Long id, String nameEn, String nameKm, String businessType,
                                     Business.Status status, boolean onboardingCompleted, String roleCode,
                                     String roleNameEn, String roleNameKm, Long branchId,
                                     Subscription.Status subscriptionStatus, Instant trialEndsAt,
                                     @Schema(description = "What this member may do; the web app shows only these menus")
                                     List<String> permissions) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BusinessMemberRepository members;
    private final BusinessRepository businesses;
    private final RoleRepository roles;
    private final SubscriptionRepository subscriptions;
    private final BusinessService businessService;
    private final InvitationService invitations;
    private final Messages messages;

    @GetMapping
    @Transactional(readOnly = true)
    public UserResponse getMe() {
        return UserResponse.from(currentUser());
    }

    @Operation(summary = "Edit my profile, language and appearance")
    @PutMapping
    @Transactional
    public UserResponse updateMe(@Valid @RequestBody UpdateMeRequest req) {
        User u = currentUser();
        u.setFullName(req.fullName().trim());
        String username = req.username() == null ? null : req.username().trim();
        if (username != null && !username.isEmpty() && !username.equalsIgnoreCase(u.getUsername())) {
            if (users.existsByUsernameIgnoreCase(username)) {
                throw ApiException.conflict("USERNAME_TAKEN");
            }
            u.setUsername(username);
        }
        u.setPhone(req.phone());
        if (req.locale() != null) {
            u.setLocale(req.locale());
        }
        if (req.uiMode() != null) {
            u.setUiMode(req.uiMode());
        }
        if (req.uiDensity() != null) {
            u.setUiDensity(req.uiDensity());
        }
        return UserResponse.from(u);
    }

    @Operation(summary = "Save language, light/dark/system mode or density", description = "Fields left out stay as they are.")
    @PutMapping("/preferences")
    @Transactional
    public UserResponse updatePreferences(@Valid @RequestBody PreferencesRequest req) {
        User u = currentUser();
        if (req.locale() != null) {
            u.setLocale(req.locale());
        }
        if (req.uiMode() != null) {
            u.setUiMode(req.uiMode());
        }
        if (req.uiDensity() != null) {
            u.setUiDensity(req.uiDensity());
        }
        return UserResponse.from(u);
    }

    @PutMapping("/password")
    @Transactional
    public MessageResponse changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        User u = currentUser();
        if (!passwordEncoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD");
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        return new MessageResponse(messages.get("PASSWORD_CHANGED"), null);
    }

    @Operation(summary = "Businesses I belong to",
            description = "After login: none = create your first business; one = open it; several = show the list.")
    @GetMapping("/businesses")
    @Transactional(readOnly = true)
    public List<MyBusinessResponse> myBusinesses() {
        List<BusinessMember> memberships = members.findByUserIdAndStatus(CurrentUser.id(), BusinessMember.Status.ACTIVE);
        List<Long> ids = memberships.stream().map(BusinessMember::getBusinessId).toList();
        Map<Long, Business> byId = businesses.findAllById(ids).stream()
                .collect(Collectors.toMap(Business::getId, Function.identity()));
        Map<Long, Subscription> subs = subscriptions.findByBusinessIdIn(ids).stream()
                .collect(Collectors.toMap(Subscription::getBusinessId, Function.identity()));
        return memberships.stream()
                .filter(m -> byId.get(m.getBusinessId()).getStatus() != Business.Status.ARCHIVED)
                .map(m -> toResponse(byId.get(m.getBusinessId()), m, subs.get(m.getBusinessId())))
                .toList();
    }

    @Operation(summary = "Create a business",
            description = "You become its Owner and get a 30-day Pro trial. Then open it (X-Business-Id) and call PUT /business/onboarding.")
    @PostMapping("/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public MyBusinessResponse createBusiness(@Valid @RequestBody CreateBusinessRequest req) {
        Long userId = CurrentUser.id();
        if (currentUser().getStatus() != User.Status.ACTIVE) {
            throw ApiException.forbidden("ACCOUNT_DISABLED");
        }
        Business b = businessService.create(userId, req);
        BusinessMember m = members.findByBusinessIdAndUserId(b.getId(), userId).orElseThrow();
        return toResponse(b, m, subscriptions.findByBusinessId(b.getId()).orElse(null));
    }

    @Operation(summary = "Pending invitations sent to my verified email")
    @GetMapping("/invitations")
    public List<MyInvitation> myInvitations() {
        return invitations.forUser(CurrentUser.id());
    }

    @Operation(summary = "Accept an invitation (already have an account)", description = "Returns the business to open.")
    @PostMapping("/invitations/{key}/accept")
    @Transactional
    public MyBusinessResponse acceptInvitation(
            @Parameter(description = "The token from the link, or the id from GET /me/invitations") @PathVariable String key) {
        Long userId = CurrentUser.id();
        Long businessId = invitations.accept(key, userId);
        BusinessMember m = members.findByBusinessIdAndUserId(businessId, userId).orElseThrow();
        return toResponse(businesses.findById(businessId).orElseThrow(), m,
                subscriptions.findByBusinessId(businessId).orElse(null));
    }

    @PostMapping("/invitations/{key}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declineInvitation(@PathVariable String key) {
        invitations.decline(key, CurrentUser.id());
    }

    private User currentUser() {
        return users.findById(CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private MyBusinessResponse toResponse(Business b, BusinessMember m, Subscription s) {
        Role r = roles.findById(m.getRoleId()).orElseThrow();
        return new MyBusinessResponse(b.getId(), b.getNameEn(), b.getNameKm(), b.getBusinessType(), b.getStatus(),
                b.isOnboardingCompleted(), r.getCode(), r.getNameEn(), r.getNameKm(), m.getBranchId(),
                s == null ? null : s.getStatus(), s == null ? null : s.getTrialEndsAt(),
                r.getPermissions().stream().map(Permission::getCode).sorted().toList());
    }
}
