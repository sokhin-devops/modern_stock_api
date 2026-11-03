package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.modernstock.common.Dates;
import com.modernstock.support.ApiTest;

/** PLAIN.md 11.5: earliest expiry first, expired batches can't be sold, they leave through an adjustment. */
class BatchesAndExpiryTest extends ApiTest {

    @Test
    void earliestExpirySellsFirstAndExpiredStockCantBeSold() {
        Shop shop = newShop();
        // the business day is Phnom Penh time, whatever the clock of the PC running the tests
        LocalDate today = Dates.today();
        long medicine = ok(post("/products", Map.of("nameEn", "Paracetamol", "baseUnitId", shop.units().get("pack"),
                "sellPrice", 1.00, "trackExpiry", true), shop)).id();

        Res noExpiry = post("/adjustments", Map.of("warehouseId", shop.warehouseId(), "kind", "OPENING", "lines",
                List.of(Map.of("productId", medicine, "qtyChange", 5, "unitCost", 0.5))), shop);
        assertThat(noExpiry.code()).isEqualTo("EXPIRY_REQUIRED");

        ok(post("/adjustments", Map.of("warehouseId", shop.warehouseId(), "kind", "OPENING", "lines", List.of(
                Map.of("productId", medicine, "qtyChange", 5, "unitCost", 0.5, "batchNo", "LATE", "expiryDate", today.plusDays(60).toString()),
                Map.of("productId", medicine, "qtyChange", 5, "unitCost", 0.5, "batchNo", "SOON", "expiryDate", today.plusDays(10).toString()),
                Map.of("productId", medicine, "qtyChange", 3, "unitCost", 0.5, "batchNo", "OLD", "expiryDate", today.minusDays(1).toString()))),
                shop));
        assertThat(ok(get("/stock/batches?expiringInDays=30", shop)).body().findValuesAsText("batchNo"))
                .containsExactly("OLD", "SOON");

        // 6 packs: all of SOON, then 1 of LATE; OLD is expired and skipped
        ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", medicine, "qty", 6)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 6))), shop));
        Map<String, String> left = batches(shop, medicine);
        assertThat(left).containsEntry("LATE", "4").containsEntry("OLD", "3").doesNotContainKey("SOON");

        // 7 on hand, but only 4 can be sold
        Res tooMany = post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", medicine, "qty", 5)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 5))), shop);
        assertThat(tooMany.code()).isEqualTo("INSUFFICIENT_STOCK");

        // expired goods leave through an adjustment, valued at average cost
        long oldBatch = 0;
        for (JsonNode w : ok(get("/stock/levels/" + medicine, shop)).at("/warehouses")) {
            for (JsonNode b : w.get("batches")) {
                if (b.get("batchNo").asText().equals("OLD")) {
                    oldBatch = b.get("id").asLong();
                    assertThat(b.get("expired").asBoolean()).isTrue();
                }
            }
        }
        Res expired = ok(post("/adjustments", Map.of("warehouseId", shop.warehouseId(), "kind", "ADJUSTMENT",
                "reason", "EXPIRED", "lines", List.of(Map.of("productId", medicine, "qtyChange", -3, "batchId", oldBatch))), shop));
        assertThat(expired.dec("/totalValue")).isEqualByComparingTo("-1.50");
        assertThat(onHand(shop, medicine)).isEqualByComparingTo("4");
    }

    private Map<String, String> batches(Shop shop, long productId) {
        Map<String, String> result = new java.util.HashMap<>();
        for (JsonNode w : ok(get("/stock/levels/" + productId, shop)).at("/warehouses")) {
            for (JsonNode b : w.get("batches")) {
                result.put(b.get("batchNo").asText(), b.get("qtyOnHand").decimalValue().stripTrailingZeros().toPlainString());
            }
        }
        return result;
    }
}
