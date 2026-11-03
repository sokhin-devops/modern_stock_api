package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.modernstock.support.ApiTest;

/** Invitations without email, role permissions checked by the API, and the last-Owner rule. */
class MembersAndPermissionsTest extends ApiTest {

    @Test
    void cashierJoinsByLinkAndOnlyGetsCashierRights() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "10", "9.80");
        long customer = ok(post("/customers", Map.of("name", "Small shop", "phone", "012 111 222",
                "creditLimit", 20), shop)).id();
        long cashierRole = 0;
        for (var role : ok(get("/roles", shop)).body()) {
            if (role.get("code").asText().equals("CASHIER")) {
                cashierRole = role.get("id").asLong();
            }
        }

        Res invite = ok(post("/members/invitations", Map.of("roleId", cashierRole, "branchId", shop.branchId()), shop));
        String token = invite.at("/link").asText().replaceAll(".*/invite/", "");
        Res details = ok(call(HttpMethod.GET, "/public/invitations/" + token, null, null, null, null, null));
        assertThat(details.at("/roleNameEn").asText()).isEqualTo("Cashier");
        assertThat(details.at("/branchName").asText()).isEqualTo("Toul Kork");

        Res joined = ok(call(HttpMethod.POST, "/auth/sign-up-with-invitation", Map.of("token", token,
                "fullName", "Dara Sok", "username", "dara" + System.nanoTime() % 100000, "password", "Secret@123",
                "confirmPassword", "Secret@123"), null, null, null, null));
        assertThat(joined.at("/businessId").asLong()).isEqualTo(shop.businessId());
        Shop cashier = new Shop(joined.at("/accessToken").asText(), shop.businessId(), shop.branchId(),
                shop.warehouseId(), shop.units());

        // the link works once
        assertThat(call(HttpMethod.POST, "/auth/sign-up-with-invitation", Map.of("token", token, "fullName", "X",
                "username", "someone_else", "password", "Secret@123"), null, null, null, null).code())
                .isEqualTo("INVITATION_NOT_OPEN");

        // a cashier sells, but can't add products, void, or go over a credit limit
        Res sale = ok(post("/pos/checkout", Map.of("items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 20))), cashier));
        assertThat(post("/products", Map.of("nameEn", "X", "baseUnitId", shop.units().get("pcs"), "sellPrice", 1),
                cashier).code()).isEqualTo("PERMISSION_DENIED");
        assertThat(post("/sales-receipts/" + sale.id() + "/void", Map.of("reason", "x"), cashier).status())
                .isEqualTo(403);
        assertThat(post("/pos/checkout", Map.of("customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 2))), cashier).code())
                .isEqualTo("CREDIT_LIMIT_EXCEEDED");
        // cost prices are hidden from cashiers
        assertThat(get("/products/" + rice, cashier).at("/avgCost").isNull()).isTrue();

        // the member has no verified email, so the Owner can make a reset link
        long memberId = 0;
        for (var m : ok(get("/members", shop)).body()) {
            if (m.get("fullName").asText().equals("Dara Sok")) {
                memberId = m.get("id").asLong();
                assertThat(m.get("canResetPassword").asBoolean()).isTrue();
            }
        }
        assertThat(ok(post("/members/" + memberId + "/reset-link", null, shop)).at("/link").asText())
                .contains("/reset-password?token=");

        // removing the cashier works at once: the next request is refused
        assertThat(call(HttpMethod.DELETE, "/members/" + memberId, null, shop.token(), shop.businessId(), null, null)
                .status()).isEqualTo(204);
        assertThat(get("/products", cashier).code()).isEqualTo("NOT_A_MEMBER");
    }

    @Test
    void theLastOwnerCannotBeRemoved() {
        Shop shop = newShop();
        long ownerMemberId = ok(get("/members", shop)).at("/0/id").asLong();
        Res res = call(HttpMethod.DELETE, "/members/" + ownerMemberId, null, shop.token(), shop.businessId(), null, null);
        assertThat(res.code()).isEqualTo("LAST_OWNER");
    }
}
