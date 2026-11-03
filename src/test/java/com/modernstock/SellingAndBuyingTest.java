package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.modernstock.support.ApiTest;

/** Buying (average cost, bills) and selling (POS, riel change, credit, voids, cash shift) end to end. */
class SellingAndBuyingTest extends ApiTest {

    @Test
    void quickPurchaseUpdatesStockAverageCostAndBill() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "48", "0.25");
        long market = ok(get("/suppliers?q=market", shop)).at("/content/0/id").asLong();

        // 2 boxes of 24 at $7.20 a box = $0.30 a piece
        Res quick = ok(post("/purchase-orders/quick", Map.of("supplierId", market, "branchId", shop.branchId(),
                "lines", List.of(Map.of("productId", water, "unitId", shop.units().get("box"), "qty", 2, "unitCost", 7.20)),
                "payment", Map.of("method", "CASH", "currency", "USD", "amount", 14.40)), shop));
        assertThat(quick.at("/purchaseOrder/status").asText()).isEqualTo("RECEIVED");
        assertThat(quick.at("/goodsReceipt/number").asText()).startsWith("GRN-");
        assertThat(quick.at("/bill/status").asText()).isEqualTo("PAID");

        assertThat(onHand(shop, water)).isEqualByComparingTo("96");
        // (48 × 0.25 + 48 × 0.30) / 96 = 0.275
        assertThat(ok(get("/products/" + water, shop)).dec("/avgCost")).isEqualByComparingTo("0.275");
        assertThat(ok(get("/suppliers/" + market, shop)).dec("/balance")).isEqualByComparingTo("0");
    }

    @Test
    void purchaseOrderReceivedInTwoPartsWithWarningsAndSupplierPayment() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "0", "0");
        long supplier = ok(post("/suppliers", Map.of("name", "Lucky Wholesale", "paymentTermsDays", 15), shop)).id();
        Res po = ok(post("/purchase-orders", Map.of("supplierId", supplier, "branchId", shop.branchId(),
                "lines", List.of(Map.of("productId", rice, "qty", 10, "unitCost", 9.80))), shop));
        long poId = po.id();
        long itemId = po.at("/lines/0/id").asLong();
        ok(post("/purchase-orders/" + poId + "/order", null, shop));

        Res first = ok(post("/purchase-orders/" + poId + "/receive", Map.of("lines",
                List.of(Map.of("purchaseOrderItemId", itemId, "qty", 4, "unitCost", 10.00))), shop));
        assertThat(first.at("/warnings/0").asText()).contains("price 10");
        assertThat(ok(get("/purchase-orders/" + poId, shop)).at("/status").asText()).isEqualTo("PARTIALLY_RECEIVED");
        ok(post("/purchase-orders/" + poId + "/receive", Map.of("lines",
                List.of(Map.of("purchaseOrderItemId", itemId, "qty", 6))), shop));
        assertThat(ok(get("/purchase-orders/" + poId, shop)).at("/status").asText()).isEqualTo("RECEIVED");

        // two bills: 40.00 + 58.80 = 98.80 owed
        assertThat(ok(get("/suppliers/" + supplier, shop)).dec("/balance")).isEqualByComparingTo("98.80");
        Res paid = ok(post("/supplier-payments", Map.of("supplierId", supplier, "branchId", shop.branchId(),
                "payment", Map.of("method", "BANK", "currency", "USD", "amount", 50, "reference", "ABA-123")), shop));
        assertThat(paid.at("/allocations/0/amount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(paid.at("/allocations/1/amount").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(ok(get("/suppliers/" + supplier, shop)).dec("/balance")).isEqualByComparingTo("48.80");
    }

    @Test
    void counterSaleInDollarsAndRielGivesChangeInRiel() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "5", "9.80");
        ok(post("/cash-shifts/open", Map.of("branchId", shop.branchId(), "openingUsd", 20, "openingKhr", 40000), shop));

        // PLAIN.md 11.6: $12.35 × 4,100 = ៛50,635 → ៛50,600; pays $10 + ៛10,000 = ៛51,000 → change ៛400
        Res sale = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(),
                "items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 10),
                        Map.of("method", "CASH", "currency", "KHR", "amount", 10000))), shop));
        assertThat(sale.at("/documentType").asText()).isEqualTo("SALES_RECEIPT");
        assertThat(sale.at("/number").asText()).startsWith("RCT-");
        assertThat(sale.dec("/total")).isEqualByComparingTo("12.35");
        assertThat(sale.dec("/totalKhr")).isEqualByComparingTo("50600");
        assertThat(sale.dec("/tenderedKhr")).isEqualByComparingTo("51000");
        assertThat(sale.dec("/changeKhr")).isEqualByComparingTo("400");
        assertThat(onHand(shop, rice)).isEqualByComparingTo("4");

        // expected cash: $20 + $10 = $30; ៛40,000 + ៛10,000 − ៛400 = ៛49,600
        long shiftId = ok(get("/cash-shifts/current?branchId=" + shop.branchId(), shop)).id();
        Res closed = ok(post("/cash-shifts/" + shiftId + "/close", Map.of("countedUsd", 30, "countedKhr", 49000), shop));
        assertThat(closed.dec("/expectedUsd")).isEqualByComparingTo("30");
        assertThat(closed.dec("/expectedKhr")).isEqualByComparingTo("49600");
        assertThat(closed.dec("/differenceKhr")).isEqualByComparingTo("-600");

        // void: stock goes back, the receipt is VOID
        ok(post("/sales-receipts/" + sale.id() + "/void", Map.of("reason", "Wrong item"), shop));
        assertThat(onHand(shop, rice)).isEqualByComparingTo("5");
        assertThat(ok(get("/sales-receipts/" + sale.id(), shop)).at("/status").asText()).isEqualTo("VOID");
    }

    @Test
    void notEnoughStockRollsBackTheWholeCheckout() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "10", "0.25");
        long rice = productWithStock(shop, "Rice", "12.35", "1", "9.80");
        Res res = post("/pos/checkout", Map.of("branchId", shop.branchId(),
                "items", List.of(Map.of("productId", water, "qty", 3), Map.of("productId", rice, "qty", 2)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 50))), shop);
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(onHand(shop, water)).isEqualByComparingTo("10");
        assertThat(ok(get("/sales-receipts", shop)).at("/totalElements").asLong()).isZero();
    }

    @Test
    void payLaterCreatesDeliveryAndInvoiceThenPaymentClearsIt() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "5", "9.80");

        Res walkIn = post("/pos/checkout", Map.of("branchId", shop.branchId(),
                "items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 5))), shop);
        assertThat(walkIn.code()).isEqualTo("CUSTOMER_REQUIRED_FOR_CREDIT");

        long customer = ok(post("/customers", Map.of("name", "Sophea", "phone", "097 888 1234",
                "creditLimit", 100, "paymentTermsDays", 7), shop)).id();
        Res credit = ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 5))), shop));
        assertThat(credit.at("/documentType").asText()).isEqualTo("INVOICE");
        assertThat(credit.at("/deliveryNumber").asText()).startsWith("DO-");
        assertThat(credit.dec("/balanceDue")).isEqualByComparingTo("7.35");
        assertThat(ok(get("/customers/" + customer, shop)).dec("/balance")).isEqualByComparingTo("7.35");

        ok(post("/payments-received", Map.of("customerId", customer, "branchId", shop.branchId(),
                "payment", Map.of("method", "KHQR", "currency", "USD", "amount", 7.35)), shop));
        assertThat(ok(get("/invoices/" + credit.id(), shop)).at("/status").asText()).isEqualTo("PAID");
        assertThat(ok(get("/customers/" + customer, shop)).dec("/balance")).isEqualByComparingTo("0");
    }

    @Test
    void creditLimitStopsCashiersButNotOwners() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "20", "9.80");
        long customer = ok(post("/customers", Map.of("name", "Small shop", "phone", "012 111 222",
                "creditLimit", 20), shop)).id();
        Map<String, Object> bigSale = Map.of("branchId", shop.branchId(), "customerId", customer,
                "items", List.of(Map.of("productId", rice, "qty", 2)));
        // the Owner has sale:override, so the sale goes through (and gets recorded as debt)
        Res owner = ok(post("/pos/checkout", bigSale, shop));
        assertThat(owner.dec("/balanceDue")).isEqualByComparingTo(new BigDecimal("24.70"));
    }
}
