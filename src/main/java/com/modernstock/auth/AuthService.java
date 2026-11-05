package com.modernstock.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.modernstock.activity.ActivityService;
import com.modernstock.business.BusinessMember;
import com.modernstock.business.BusinessMemberRepository;
import com.modernstock.common.ApiException;
import com.modernstock.common.Messages;
import com.modernstock.common.Phones;
import com.modernstock.common.Tokens;
import com.modernstock.config.AppProperties;
import com.modernstock.member.RoleRepository;
import com.modernstock.notification.MailService;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Sign-up, email verification, login with rotating refresh tokens, and password reset (PLAIN.md 11.12). */
@Service
@RequiredArgsConstructor
public class AuthService {

    public record SignUpRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Pattern(regexp = Phones.KH, message = "{phone.invalid}") String phone,
            @Schema(example = "km") @Pattern(regexp = "km|en") String locale,
            @AssertTrue(message = "{terms.required}") boolean acceptTerms) {
    }

    public record LoginRequest(
            @Schema(description = "Email or username", example = "owner@demo.com") @NotBlank String login,
            @Schema(example = "Demo@1234") @NotBlank String password) {
    }

    public record TokenRequest(@Schema(description = "The token from the link") @NotBlank String token) {
    }

    public record EmailRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    /** devLink is only filled in development (app.dev-links), so the flow can be tested without email. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageResponse(String message, String devLink) {
    }

    public record UserResponse(Long id, String fullName, String username, String email, String phone, String locale,
                               String uiMode, String uiDensity, boolean emailVerified, boolean superAdmin,
                               @Schema(description = "A POS PIN is set (FR-6.19)") boolean hasPin) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getFullName(), u.getUsername(), u.getEmail(), u.getPhone(),
                    u.getLocale(), u.getUiMode(), u.getUiDensity(), u.getEmailVerifiedAt() != null, u.isSuperAdmin(),
                    u.getPinHash() != null);
        }
    }

    public record PinSwitchRequest(@NotNull Long businessId, @NotNull Long userId,
                                   @Schema(example = "1234") @NotBlank @jakarta.validation.constraints.Pattern(regexp = "\\d{4,6}") String pin) {
    }

    /** Wrong PINs in a row before the PIN locks, and for how long. */
    public static final int PIN_TRIES = 5;
    public static final Duration PIN_LOCK = Duration.ofMinutes(15);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user,
                                @Schema(description = "Only after joining through an invitation: the business to open")
                                Long businessId) {
    }

    /** The refresh token never goes in the body; the controller puts it in an HttpOnly cookie. */
    public record Session(TokenResponse body, String refreshToken) {
    }

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final UserTokenRepository userTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final MailService mail;
    private final Messages messages;
    private final AppProperties props;
    private final BusinessMemberRepository members;
    private final RoleRepository roles;
    private final ActivityService activity;

    @Transactional
    public MessageResponse signUp(SignUpRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("EMAIL_TAKEN");
        }
        User u = new User();
        u.setFullName(req.fullName().trim());
        u.setEmail(req.email().trim().toLowerCase());
        u.setPhone(req.phone());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setLocale(req.locale() == null ? "km" : req.locale());
        u.setStatus(User.Status.UNVERIFIED);
        users.save(u);
        return new MessageResponse(messages.get("SIGN_UP_CHECK_EMAIL"), sendVerification(u));
    }

    @Transactional
    public MessageResponse verifyEmail(String token) {
        UserToken t = usableToken(token, UserToken.Type.VERIFY_EMAIL);
        User u = users.findById(t.getUserId()).orElseThrow();
        t.setUsedAt(Instant.now());
        u.setEmailVerifiedAt(Instant.now());
        if (u.getStatus() == User.Status.UNVERIFIED) {
            u.setStatus(User.Status.ACTIVE);
        }
        return new MessageResponse(messages.get("EMAIL_VERIFIED"), null);
    }

    /** Always answers the same way, so nobody can find out which emails have accounts. */
    @Transactional
    public MessageResponse resendVerification(String email) {
        String devLink = users.findByEmailIgnoreCase(email)
                .filter(u -> u.getStatus() == User.Status.UNVERIFIED)
                .map(this::sendVerification)
                .orElse(null);
        return new MessageResponse(messages.get("VERIFICATION_SENT"), devLink);
    }

    @Transactional
    public Session login(LoginRequest req) {
        User u = users.findByLogin(req.login().trim())
                .filter(found -> passwordEncoder.matches(req.password(), found.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));
        if (u.getStatus() == User.Status.UNVERIFIED) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED");
        }
        if (u.getStatus() == User.Status.DISABLED) {
            throw ApiException.forbidden("ACCOUNT_DISABLED");
        }
        u.setLastLoginAt(Instant.now());
        return issueSession(u, null);
    }

    /** Rotates the refresh token: the old one stops working. */
    /**
     * Quick cashier switch on a shared POS device (FR-6.19): someone is signed in here, and another member of the same
     * business who sells takes over with their PIN. Five wrong PINs lock that PIN for 15 minutes.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session pinSwitch(Long callerId, PinSwitchRequest req) {
        BusinessMember caller = members.findByBusinessIdAndUserId(req.businessId(), callerId)
                .filter(m -> m.getStatus() == BusinessMember.Status.ACTIVE)
                .orElseThrow(() -> ApiException.forbidden("PERMISSION_DENIED"));
        BusinessMember target = members.findByBusinessIdAndUserId(req.businessId(), req.userId())
                .filter(m -> m.getStatus() == BusinessMember.Status.ACTIVE && !m.isDisabledByPlan())
                .orElseThrow(() -> ApiException.notFound("Member"));
        User u = users.findById(target.getUserId()).filter(x -> x.getStatus() == User.Status.ACTIVE && x.getPinHash() != null)
                .orElseThrow(() -> ApiException.notFound("Member"));
        boolean sells = roles.findById(target.getRoleId())
                .map(r -> r.getPermissions().stream().anyMatch(p -> "sale:create".equals(p.getCode()))).orElse(false);
        if (!sells) {
            throw ApiException.forbidden("PERMISSION_DENIED");
        }
        Instant now = Instant.now();
        if (u.getPinLockedUntil() != null && u.getPinLockedUntil().isAfter(now)) {
            throw ApiException.rule("PIN_LOCKED", java.time.temporal.ChronoUnit.MINUTES.between(now, u.getPinLockedUntil()) + 1);
        }
        if (!passwordEncoder.matches(req.pin(), u.getPinHash())) {
            u.setPinFailed(u.getPinFailed() + 1);
            if (u.getPinFailed() >= PIN_TRIES) {
                u.setPinFailed(0);
                u.setPinLockedUntil(now.plus(PIN_LOCK));
                throw ApiException.rule("PIN_LOCKED", PIN_LOCK.toMinutes());
            }
            throw ApiException.badRequest("PIN_WRONG", PIN_TRIES - u.getPinFailed());
        }
        u.setPinFailed(0);
        u.setPinLockedUntil(null);
        u.setLastLoginAt(now);
        activity.recordAs(req.businessId(), u.getId(), "CASHIER_SWITCHED", "MEMBER", target.getId(),
                users.findById(caller.getUserId()).map(User::getFullName).orElse(null) + " → " + u.getFullName());
        return issueSession(u, req.businessId());
    }

    @Transactional
    public Session refresh(String rawRefreshToken) {
        RefreshToken current = rawRefreshToken == null ? null
                : refreshTokens.findByTokenHash(Tokens.sha256(rawRefreshToken)).orElse(null);
        if (current == null || !current.isUsable()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED");
        }
        current.setRevokedAt(Instant.now());
        User u = users.findById(current.getUserId()).orElseThrow();
        if (u.getStatus() != User.Status.ACTIVE) {
            throw ApiException.forbidden("ACCOUNT_DISABLED");
        }
        return issueSession(u, null);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null) {
            refreshTokens.findByTokenHash(Tokens.sha256(rawRefreshToken))
                    .ifPresent(t -> t.setRevokedAt(Instant.now()));
        }
    }

    @Transactional
    public void logoutAll(Long userId) {
        refreshTokens.revokeAll(userId, Instant.now());
    }

    @Transactional
    public MessageResponse forgotPassword(String email) {
        String devLink = users.findByEmailIgnoreCase(email)
                .filter(u -> u.getStatus() == User.Status.ACTIVE && u.getEmailVerifiedAt() != null)
                .map(u -> {
                    String link = resetLink(u, Duration.ofHours(1));
                    mail.send(u.getEmail(), "Reset your ModernStock password", "Open this link within 1 hour: " + link);
                    return link;
                })
                .orElse(null);
        return new MessageResponse(messages.get("RESET_LINK_SENT"), props.devLinks() ? devLink : null);
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest req) {
        UserToken t = usableToken(req.token(), UserToken.Type.PASSWORD_RESET);
        User u = users.findById(t.getUserId()).orElseThrow();
        t.setUsedAt(Instant.now());
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        refreshTokens.revokeAll(u.getId(), Instant.now());
        return new MessageResponse(messages.get("PASSWORD_CHANGED"), null);
    }

    /** Also used for the Owner-created reset links of staff without email. */
    @Transactional
    public String resetLink(User u, Duration validFor) {
        return props.webAppUrl() + "/reset-password?token=" + createToken(u, UserToken.Type.PASSWORD_RESET, validFor);
    }

    @Transactional
    public Session issueSession(User u, Long businessId) {
        String raw = Tokens.random();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(u.getId());
        rt.setTokenHash(Tokens.sha256(raw));
        rt.setExpiresAt(Instant.now().plus(Duration.ofDays(props.jwt().refreshTokenDays())));
        refreshTokens.save(rt);
        TokenResponse body = new TokenResponse(jwt.issue(u), "Bearer", jwt.accessTokenLifetime().toSeconds(),
                UserResponse.from(u), businessId);
        return new Session(body, raw);
    }

    private String sendVerification(User u) {
        String link = props.webAppUrl() + "/verify-email?token="
                + createToken(u, UserToken.Type.VERIFY_EMAIL, Duration.ofHours(24));
        mail.send(u.getEmail(), "Verify your ModernStock email", "Open this link within 24 hours: " + link);
        return props.devLinks() ? link : null;
    }

    private String createToken(User u, UserToken.Type type, Duration validFor) {
        String raw = Tokens.random();
        UserToken t = new UserToken();
        t.setUserId(u.getId());
        t.setType(type);
        t.setTokenHash(Tokens.sha256(raw));
        t.setExpiresAt(Instant.now().plus(validFor));
        userTokens.save(t);
        return raw;
    }

    private UserToken usableToken(String raw, UserToken.Type type) {
        return userTokens.findByTokenHashAndType(Tokens.sha256(raw), type)
                .filter(UserToken::isUsable)
                .orElseThrow(() -> ApiException.badRequest("INVALID_OR_EXPIRED_LINK"));
    }
}
