package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.modernstock.support.ApiTest;

/**
 * Voids (PLAIN.md 11.1 rule 3), attachments (rule 4), the Transactions list and the customer and supplier pages (12.2),
 * reorder points from sales history (11.9) and in-app notifications (10.2, 11.10).
 */
class VoidsAndNewScreensTest extends ApiTest {

    /** A 1×1 PNG: the smallest picture FileStorage takes. */
    private static final byte[] PNG = java.util.HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415478da63f8ffff3f0005fe02fea7d6a5d00000000049454e44ae426082");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purchaseVoidsGiveStockCostAndDebtBack() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "48", "0.25");
        long supplier = ok(post("/suppliers", Map.of("name", "Lucky Wholesale"), shop)).id();

        // bought on credit, then voided through its bill: stock, average cost and what is owed go back
        Res quick = ok(post("/purchase-orders/quick", Map.of("supplierId", supplier, "branchId", shop.branchId(),
                "lines", List.of(Map.of("productId", water, "qty", 48, "unitCost", 0.35))), shop));
        assertThat(onHand(shop, water)).isEqualByComparingTo("96");
        ok(post("/bills/" + quick.at("/bill/id").asLong() + "/void", null, shop));
        assertThat(onHand(shop, water)).isEqualByComparingTo("48");
        assertThat(ok(get("/products/" + water, shop)).dec("/avgCost")).isEqualByComparingTo("0.25");
        assertThat(ok(get("/goods-receipts/" + quick.at("/goodsReceipt/id").asLong(), shop)).at("/status").asText()).isEqualTo("VOID");
        assertThat(ok(get("/suppliers/" + supplier, shop)).dec("/balance")).isEqualByComparingTo("0");
        assertThat(post("/bills/" + quick.at("/bill/id").asLong() + "/void", null, shop).code()).isEqualTo("WRONG_STATUS");

        // paid now: the receipt waits until its payment is voided
        Res paid = ok(post("/purchase-orders/quick", Map.of("supplierId", supplier, "branchId", shop.branchId(),
                "lines", List.of(Map.of("productId", water, "qty", 10, "unitCost", 0.30)),
                "payment", Map.of("method", "CASH", "currency", "USD", "amount", 3)), shop));
        long grn = paid.at("/goodsReceipt/id").asLong();
        assertThat(post("/goods-receipts/" + grn + "/void", null, shop).code()).isEqualTo("VOID_BILL_PAID");
        ok(post("/supplier-payments/" + paid.at("/payment/id").asLong() + "/void", null, shop));
        assertThat(ok(get("/bills/" + paid.at("/bill/id").asLong(), shop)).dec("/balance")).isEqualByComparingTo("3");
        ok(post("/goods-receipts/" + grn + "/void", null, shop));
        assertThat(onHand(shop, water)).isEqualByComparingTo("48");

        // a supplier credit written by hand
        long credit = ok(post("/supplier-credits", Map.of("supplierId", supplier, "branchId", shop.branchId(), "amount", 5, "reason", "price fix"), shop)).id();
        assertThat(ok(post("/supplier-credits/" + credit + "/void", null, shop)).at("/status").asText()).isEqualTo("VOID");
    }

    @Test
    void saleVoidsReopenWhatTheyChanged() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "10", "9.80");
        long customer = ok(post("/customers", Map.of("name", "Sophea", "phone", "097 888 1234", "creditLimit", 500), shop)).id();

        // pay later, then voided: the goods come back and nothing is owed
        Res invoice = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 2)), "payments", List.of()), shop));
        assertThat(onHand(shop, rice)).isEqualByComparingTo("8");
        Res voided = ok(post("/invoices/" + invoice.id() + "/void", null, shop));
        assertThat(voided.at("/status").asText()).isEqualTo("VOID");
        assertThat(voided.dec("/balance")).isEqualByComparingTo("0");
        assertThat(onHand(shop, rice)).isEqualByComparingTo("10");
        assertThat(ok(get("/customers/" + customer, shop)).dec("/balance")).isEqualByComparingTo("0");

        // paid: refused until the payment is voided
        Res second = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 1)), "payments", List.of()), shop));
        Res payment = ok(post("/payments-received", Map.of("customerId", customer, "branchId", shop.branchId(),
                "payment", Map.of("method", "CASH", "currency", "USD", "amount", 12.35),
                "allocations", List.of(Map.of("documentId", second.id(), "amount", 12.35))), shop));
        assertThat(post("/invoices/" + second.id() + "/void", null, shop).code()).isEqualTo("VOID_INVOICE_PAID");
        assertThat(ok(get("/payments/" + payment.id(), shop)).at("/partyName").asText()).isEqualTo("Sophea");
        ok(post("/payments-received/" + payment.id() + "/void", null, shop));
        assertThat(ok(get("/invoices/" + second.id(), shop)).dec("/balance")).isEqualByComparingTo("12.35");
        ok(post("/invoices/" + second.id() + "/void", null, shop));

        // a return voided: its credit comes off the invoice and the goods leave stock again
        Res third = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 2)), "payments", List.of()), shop));
        long saleItem = ok(get("/invoices/" + third.id() + "/returnable", shop)).at("/lines/0/saleItemId").asLong();
        BigDecimal before = onHand(shop, rice);
        Res ret = ok(post("/sales-returns", Map.of("invoiceId", third.id(), "lines", List.of(Map.of("saleItemId", saleItem, "qty", 1,
                "condition", "GOOD")), "reason", "wrong size"), shop));
        assertThat(ok(get("/invoices/" + third.id(), shop)).dec("/balance")).isEqualByComparingTo("12.35");
        ok(post("/sales-returns/" + ret.id() + "/void", null, shop));
        assertThat(ok(get("/invoices/" + third.id(), shop)).dec("/balance")).isEqualByComparingTo("24.70");
        assertThat(onHand(shop, rice)).isEqualByComparingTo(before);

        // a credit note by hand
        long note = ok(post("/credit-notes", Map.of("customerId", customer, "branchId", shop.branchId(), "amount", 2, "reason", "goodwill"), shop)).id();
        assertThat(ok(post("/credit-notes/" + note + "/void", null, shop)).at("/status").asText()).isEqualTo("VOID");
    }

    @Test
    void aPostedCountIsVoidedBack() {
        Shop shop = newShop();
        long soap = productWithStock(shop, "Soap", "1.00", "10", "0.50");
        Res count = ok(post("/stock-counts", Map.of("warehouseId", shop.warehouseId(), "productIds", List.of(soap)), shop));
        ok(call(HttpMethod.PUT, "/stock-counts/" + count.id() + "/lines", Map.of("lines",
                List.of(Map.of("itemId", count.at("/lines/0/id").asLong(), "countedQty", 13))), shop.token(), shop.businessId(), null, null));
        ok(post("/stock-counts/" + count.id() + "/post", null, shop));
        assertThat(onHand(shop, soap)).isEqualByComparingTo("13");
        assertThat(ok(post("/stock-counts/" + count.id() + "/void", null, shop)).at("/status").asText()).isEqualTo("VOID");
        assertThat(onHand(shop, soap)).isEqualByComparingTo("10");
    }

    @Test
    void transactionsListAndCustomerPage() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "10.00", "20", "8.00");
        long customer = ok(post("/customers", Map.of("name", "Dara Shop", "phone", "012 333 444", "creditLimit", 500,
                "paymentTermsDays", 7), shop)).id();
        Res invoice = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 3)), "payments", List.of()), shop));
        ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 10))), shop));

        // every document of the side, newest first, with the kinds the member may open
        JsonNode all = ok(get("/transactions?side=CUSTOMER&size=50", shop)).body();
        List<String> types = new ArrayList<>();
        all.get("content").forEach(r -> types.add(r.get("type").asText()));
        assertThat(types).contains("INVOICE", "DELIVERY", "SALES_RECEIPT");
        // the POS sale's own payment is part of the sale, not a row of its own
        assertThat(types).doesNotContain("PAYMENT_RECEIVED");

        JsonNode mine = ok(get("/transactions?side=CUSTOMER&partyId=" + customer + "&view=invoices", shop)).body();
        assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.at("/content/0/balance").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(ok(get("/transactions?side=CUSTOMER&status=VOID", shop)).at("/totalElements").asLong()).isZero();
        assertThat(ok(get("/transactions?side=CUSTOMER&q=" + invoice.at("/number").asText(), shop)).at("/totalElements").asLong()).isEqualTo(1);

        // the customer's page: key amounts and statement
        Res summary = ok(get("/customers/" + customer + "/summary", shop));
        assertThat(summary.dec("/balance")).isEqualByComparingTo("30.00");
        assertThat(summary.dec("/thisYear")).isEqualByComparingTo("30.00");
        assertThat(summary.at("/branches/0/balance").decimalValue()).isEqualByComparingTo("30.00");
        Res statement = ok(get("/customers/" + customer + "/statement", shop));
        assertThat(statement.dec("/closing")).isEqualByComparingTo("30.00");
        assertThat(ok(get("/customers?size=5&q=dara", shop)).at("/content/0/overdue").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void anyDocumentKeepsAPhoto() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "10.00", "5", "8.00");
        long receipt = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 10))), shop)).id();
        String path = "/attachments/sales-receipts/" + receipt;
        assertThat(ok(get(path, shop)).at("/hasPhoto").asBoolean()).isFalse();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(shop.token());
        headers.set("X-Business-Id", String.valueOf(shop.businessId()));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(PNG) {
            @Override
            public String getFilename() {
                return "receipt.png";
            }
        });
        assertThat(rest.exchange(path + "/photo", HttpMethod.POST, new HttpEntity<>(form, headers), String.class).getStatusCode().value())
                .isEqualTo(200);
        assertThat(ok(get(path, shop)).at("/hasPhoto").asBoolean()).isTrue();
        assertThat(ok(call(HttpMethod.DELETE, path + "/photo", null, shop.token(), shop.businessId(), null, null)).at("/hasPhoto").asBoolean())
                .isFalse();
        assertThat(get("/attachments/nothing/1", shop).status()).isEqualTo(404);
    }

    @Test
    void reorderPointFromSalesHistoryIsTheLevelEveryCheckUses() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "200", "0.25");
        List<Long> receipts = new ArrayList<>();
        for (int day = 1; day <= 8; day++) {
            receipts.add(ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", water, "qty", day)),
                    "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 10))), shop)).id());
        }
        // the sales spread over 8 days of the last 30
        for (int i = 0; i < receipts.size(); i++) {
            jdbc.update("update sales_receipts set sold_at = now() - make_interval(days => ?) where id = ?", i + 1, receipts.get(i));
        }
        assertThat(ok(post("/stock/reorder-points/recalculate", null, shop)).at("/products").asInt()).isEqualTo(1);
        BigDecimal point = jdbc.queryForObject("select reorder_point from reorder_points where product_id = ?", BigDecimal.class, water);
        // sold on 8 of the last 30 days: enough history for a point of its own, which the stock page then uses
        assertThat(point).isNotNull();
        assertThat(ok(get("/stock/levels?q=water", shop)).at("/content/0/reorderLevel").decimalValue()).isEqualByComparingTo(point);

        // a point above what is on hand: low everywhere it is checked
        jdbc.update("update reorder_points set reorder_point = 500 where product_id = ?", water);
        assertThat(ok(get("/reports/reorder", shop)).at("/rows/0/basis").asText()).isEqualTo("SALES");
        assertThat(ok(get("/stock/summary", shop)).at("/lowStockItems").asInt()).isEqualTo(1);
    }

    @Test
    void membersAreToldWhatConcernsThem() throws InterruptedException {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "10.00", "5", "8.00");
        Shop cashier = cashierOf(shop);
        long sale = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", rice, "qty", 2)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 20))), cashier)).id();
        long item = ok(get("/sales-receipts/" + sale + "/returnable", cashier)).at("/lines/0/saleItemId").asLong();

        // a cashier asks for a return: the Owner (who approves) is told, with a link to the return itself
        long ret = ok(post("/sales-returns", Map.of("salesReceiptId", sale, "lines", List.of(Map.of("saleItemId", item, "qty", 1,
                "condition", "DAMAGED")), "reason", "broken bag", "refund", Map.of("method", "CASH", "currency", "USD")), cashier)).id();
        JsonNode mine = awaitNotifications(shop, 1);
        assertThat(mine.at("/items/0/type").asText()).isEqualTo("APPROVAL_SALES_RETURN");
        assertThat(mine.at("/items/0/link").asText()).isEqualTo("/customers/transactions/returns/" + ret);
        assertThat(ok(get("/notifications", cashier)).at("/unread").asInt()).isZero();

        assertThat(ok(post("/notifications/" + mine.at("/items/0/id").asLong() + "/read", null, shop)).at("/unread").asInt()).isZero();

        // alerts marked read leave the badge until something new comes
        ok(post("/alerts/read", null, shop));
        assertThat(ok(get("/alerts", shop)).at("/total").asInt()).isZero();
    }

    private JsonNode awaitNotifications(Shop shop, int unread) throws InterruptedException {
        JsonNode body = null;
        for (int i = 0; i < 50; i++) {
            body = ok(get("/notifications", shop)).body();
            if (body.get("unread").asInt() >= unread) {
                return body;
            }
            Thread.sleep(100);
        }
        return body;
    }

    private Shop cashierOf(Shop shop) {
        long role = 0;
        for (JsonNode r : ok(get("/roles", shop)).body()) {
            if (r.get("code").asText().equals("CASHIER")) {
                role = r.get("id").asLong();
            }
        }
        String token = ok(post("/members/invitations", Map.of("roleId", role, "branchId", shop.branchId()), shop)).at("/link").asText()
                .replaceAll(".*/invite/", "");
        Res joined = ok(call(HttpMethod.POST, "/auth/sign-up-with-invitation", Map.of("token", token, "fullName", "Dara Sok",
                "username", "dara" + System.nanoTime() % 100000, "password", "Secret@123", "confirmPassword", "Secret@123"),
                null, null, null, null));
        return new Shop(joined.at("/accessToken").asText(), shop.businessId(), shop.branchId(), shop.warehouseId(), shop.units());
    }
}
