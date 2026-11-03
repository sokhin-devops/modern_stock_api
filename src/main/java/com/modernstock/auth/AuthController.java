package com.modernstock.auth;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.auth.AuthService.EmailRequest;
import com.modernstock.auth.AuthService.LoginRequest;
import com.modernstock.auth.AuthService.MessageResponse;
import com.modernstock.auth.AuthService.ResetPasswordRequest;
import com.modernstock.auth.AuthService.Session;
import com.modernstock.auth.AuthService.SignUpRequest;
import com.modernstock.auth.AuthService.TokenRequest;
import com.modernstock.auth.AuthService.TokenResponse;
import com.modernstock.common.CurrentUser;
import com.modernstock.config.AppProperties;
import com.modernstock.member.InvitationService;
import com.modernstock.member.InvitationService.SignUpWithInvitationRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Auth")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    /** HttpOnly, Secure, SameSite cookie, sent only to /api/v1/auth (PLAIN.md 11.12). */
    public static final String REFRESH_COOKIE = "ms_refresh";

    private final AuthService auth;
    private final InvitationService invitations;
    private final AppProperties props;

    @Operation(summary = "Create an account", description = "The account starts unverified and gets a verification link valid 24 hours.")
    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse signUp(@Valid @RequestBody SignUpRequest req) {
        return auth.signUp(req);
    }

    @Operation(summary = "Verify the email from the link")
    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest req) {
        return auth.verifyEmail(req.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest req) {
        return auth.resendVerification(req.email());
    }

    @Operation(summary = "Log in with email or username",
            description = "Returns a 15-minute access token. The refresh token is set as the HttpOnly cookie ms_refresh.")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return withCookie(auth.login(req), HttpStatus.OK);
    }

    @Operation(summary = "New access token from the refresh cookie", description = "The refresh token is replaced on every call.")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Parameter(hidden = true) @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withCookie(auth.refresh(refreshToken), HttpStatus.OK);
    }

    @Operation(summary = "Log out on this device")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Parameter(hidden = true) @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @Operation(summary = "Log out from all devices")
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll() {
        auth.logoutAll(CurrentUser.id());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @Operation(summary = "Email a password reset link", description = "Only for accounts with a verified email. Same answer either way.")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest req) {
        return auth.forgotPassword(req.email());
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        return auth.resetPassword(req);
    }

    @Operation(summary = "Join a business from an invitation link as a new account",
            description = "Creates the account (email optional), adds the member and logs in. businessId in the answer is the business to open.")
    @PostMapping("/sign-up-with-invitation")
    public ResponseEntity<TokenResponse> signUpWithInvitation(@Valid @RequestBody SignUpWithInvitationRequest req) {
        InvitationService.Joined joined = invitations.signUpWithInvitation(req);
        return withCookie(auth.issueSession(joined.user(), joined.businessId()), HttpStatus.CREATED);
    }

    private ResponseEntity<TokenResponse> withCookie(Session session, HttpStatus status) {
        ResponseCookie cookie = cookie(session.refreshToken(), Duration.ofDays(props.jwt().refreshTokenDays()));
        return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie.toString()).body(session.body());
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }
}
