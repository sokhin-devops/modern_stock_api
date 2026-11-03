package com.modernstock.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Calls the running API over HTTP like the web app does: bearer token + X-Business-Id. */
public abstract class ApiTest extends IntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper json;

    public record Res(int status, JsonNode body, HttpHeaders headers) {
        public JsonNode at(String pointer) {
            return body.at(pointer);
        }

        public long id() {
            return body.get("id").asLong();
        }

        public String code() {
            return body.path("code").asText();
        }

        public BigDecimal dec(String pointer) {
            return new BigDecimal(body.at(pointer).asText());
        }
    }

    /** An account with an onboarded business: main branch with one warehouse, rate 4,100, 100-riel rounding. */
    public record Shop(String token, long businessId, long branchId, long warehouseId, Map<String, Long> units) {
    }

    protected Res call(HttpMethod method, String path, Object body, String token, Long businessId, Long branchId,
                       String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (businessId != null) {
            headers.set("X-Business-Id", businessId.toString());
        }
        if (branchId != null) {
            headers.set("X-Branch-Id", branchId.toString());
        }
        if (language != null) {
            headers.set("Accept-Language", language);
        }
        ResponseEntity<String> res = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        try {
            JsonNode node = res.getBody() == null || res.getBody().isBlank()
                    ? json.createObjectNode() : json.readTree(res.getBody());
            return new Res(res.getStatusCode().value(), node, res.getHeaders());
        } catch (Exception e) {
            throw new IllegalStateException(res.getBody(), e);
        }
    }

    protected Res get(String path, Shop shop) {
        return call(HttpMethod.GET, path, null, shop.token(), shop.businessId(), null, null);
    }

    protected Res post(String path, Object body, Shop shop) {
        return call(HttpMethod.POST, path, body, shop.token(), shop.businessId(), null, null);
    }

    protected Res ok(Res res) {
        assertThat(res.status()).as(res.body().toString()).isBetween(200, 299);
        return res;
    }

    protected static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.kh";
    }

    /** Sign up, verify with the dev link, log in. */
    protected String signUpAndLogin(String email) {
        Res signUp = ok(call(HttpMethod.POST, "/auth/sign-up", Map.of("fullName", "Test Owner", "email", email,
                "password", "Secret@123", "acceptTerms", true), null, null, null, null));
        String token = signUp.body().get("devLink").asText().replaceAll(".*token=", "");
        ok(call(HttpMethod.POST, "/auth/verify-email", Map.of("token", token), null, null, null, null));
        return login(email, "Secret@123");
    }

    protected String login(String login, String password) {
        return ok(call(HttpMethod.POST, "/auth/login", Map.of("login", login, "password", password),
                null, null, null, null)).body().get("accessToken").asText();
    }

    protected Shop newShop() {
        String token = signUpAndLogin(uniqueEmail());
        long businessId = ok(call(HttpMethod.POST, "/me/businesses", Map.of("nameEn", "Test Mart",
                "businessType", "MINI_MART"), token, null, null, null)).id();
        ok(call(HttpMethod.PUT, "/business/onboarding", Map.of(
                "mainBranch", Map.of("nameEn", "Toul Kork", "phone", "012 345 678"),
                "usdToKhr", 4100, "vatEnabled", false, "rielRounding", 100), token, businessId, null, null));
        JsonNode branch = ok(call(HttpMethod.GET, "/branches", null, token, businessId, null, null)).body().get(0);
        Map<String, Long> units = new java.util.HashMap<>();
        ok(call(HttpMethod.GET, "/units", null, token, businessId, null, null)).body()
                .forEach(u -> units.put(u.get("symbol").asText(), u.get("id").asLong()));
        return new Shop(token, businessId, branch.get("id").asLong(),
                branch.get("warehouses").get(0).get("id").asLong(), units);
    }

    /** A product sold by the piece, with opening stock in the shop's main warehouse. */
    protected long productWithStock(Shop shop, String name, String price, String qty, String cost) {
        long id = ok(post("/products", Map.of("nameEn", name, "baseUnitId", shop.units().get("pcs"),
                "sellPrice", new BigDecimal(price), "units", List.of(Map.of("unitId", shop.units().get("box"),
                        "factor", 24))), shop)).id();
        if (new BigDecimal(qty).signum() > 0) {
            ok(post("/adjustments", Map.of("warehouseId", shop.warehouseId(), "kind", "OPENING", "lines", List.of(
                    Map.of("productId", id, "qtyChange", new BigDecimal(qty), "unitCost", new BigDecimal(cost)))), shop));
        }
        return id;
    }

    protected BigDecimal onHand(Shop shop, long productId) {
        return ok(get("/products/" + productId, shop)).dec("/qtyOnHand");
    }
}
