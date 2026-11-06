package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import com.modernstock.support.ApiTest;

/** Sign-up, tokens, creating a business, and the business context that keeps tenants apart. */
class AccountAndTenancyTest extends ApiTest {

    @Test
    void signUpVerifyLoginAndRefresh() {
        String email = uniqueEmail();
        Res signUp = call(HttpMethod.POST, "/auth/sign-up", Map.of("fullName", "Sokha", "email", email,
                "password", "Secret@123", "acceptTerms", true), null, null, null, null);
        assertThat(signUp.status()).isEqualTo(201);

        Res early = call(HttpMethod.POST, "/auth/login", Map.of("login", email, "password", "Secret@123"),
                null, null, null, null);
        assertThat(early.status()).isEqualTo(403);
        assertThat(early.code()).isEqualTo("EMAIL_NOT_VERIFIED");

        // asking for a new link retires the first one: only the newest link works
        String firstLink = signUp.body().get("devLink").asText().replaceAll(".*token=", "");
        Res resent = ok(call(HttpMethod.POST, "/auth/resend-verification", Map.of("email", email), null, null, null, null));
        String token = resent.body().get("devLink").asText().replaceAll(".*token=", "");
        assertThat(token).isNotEqualTo(firstLink);
        assertThat(call(HttpMethod.POST, "/auth/verify-email", Map.of("token", firstLink), null, null, null, null).code())
                .isEqualTo("INVALID_OR_EXPIRED_LINK");

        ok(call(HttpMethod.POST, "/auth/verify-email", Map.of("token", token), null, null, null, null));
        assertThat(call(HttpMethod.POST, "/auth/verify-email", Map.of("token", token), null, null, null, null).code())
                .isEqualTo("INVALID_OR_EXPIRED_LINK");

        Res wrong = call(HttpMethod.POST, "/auth/login", Map.of("login", email, "password", "nope-nope"),
                null, null, null, null);
        assertThat(wrong.status()).isEqualTo(401);

        Res login = ok(call(HttpMethod.POST, "/auth/login", Map.of("login", email, "password", "Secret@123"),
                null, null, null, null));
        String cookie = login.headers().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("ms_refresh=").contains("HttpOnly").contains("SameSite=Strict");

        // the refresh token is rotated: the first one stops working after use
        String refreshCookie = cookie.substring(0, cookie.indexOf(';'));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, refreshCookie);
        var first = rest.exchange("/auth/refresh", HttpMethod.POST, new org.springframework.http.HttpEntity<>(headers), String.class);
        var second = rest.exchange("/auth/refresh", HttpMethod.POST, new org.springframework.http.HttpEntity<>(headers), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(401);

        Res me = ok(call(HttpMethod.GET, "/me/businesses", null, login.body().get("accessToken").asText(),
                null, null, null));
        assertThat(me.body().size()).isZero();
    }

    @Test
    void businessContextKeepsTenantsApart() {
        Shop a = newShop();
        Shop b = newShop();
        long productOfA = productWithStock(a, "Water", "0.50", "10", "0.25");

        // no business header
        Res missing = call(HttpMethod.GET, "/products", null, a.token(), null, null, null);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("BUSINESS_HEADER_REQUIRED");

        // B can't open A's business
        Res notMember = call(HttpMethod.GET, "/products", null, b.token(), a.businessId(), null, null);
        assertThat(notMember.status()).isEqualTo(403);
        assertThat(notMember.code()).isEqualTo("NOT_A_MEMBER");

        // B in its own business can't reach A's product by ID
        assertThat(get("/products/" + productOfA, b).status()).isEqualTo(404);
        assertThat(get("/products", b).at("/totalElements").asLong()).isZero();

        // onboarding runs once
        Res again = call(HttpMethod.PUT, "/business/onboarding", Map.of(
                "mainBranch", Map.of("nameEn", "Again"), "usdToKhr", 4100, "vatEnabled", false), a.token(),
                a.businessId(), null, null);
        assertThat(again.status()).isEqualTo(409);

        // the business started a Pro trial
        Res billing = ok(get("/billing", a));
        assertThat(billing.at("/status").asText()).isEqualTo("TRIALING");
        assertThat(billing.at("/effectivePlanCode").asText()).isEqualTo("PRO");
    }

    @Test
    void errorsAreTranslated() {
        Shop shop = newShop();
        long id = productWithStock(shop, "Water", "0.50", "1", "0.25");
        Res res = call(HttpMethod.POST, "/pos/checkout", Map.of("items",
                java.util.List.of(Map.of("productId", id, "qty", 5)), "payments",
                java.util.List.of(Map.of("method", "CASH", "currency", "USD", "amount", 10))),
                shop.token(), shop.businessId(), shop.branchId(), "km");
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(res.body().get("detail").asText()).startsWith("ស្តុក");
    }
}
