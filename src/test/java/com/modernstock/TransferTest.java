package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.modernstock.support.ApiTest;

/** PLAIN.md 11.9: moves inside a branch, transfers between branches, shortfalls and returns. */
class TransferTest extends ApiTest {

    @Test
    void branchTransferWithShortfallBecomesLostInTransitAdjustment() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "100", "0.25");
        Res senSok = ok(post("/branches", Map.of("nameEn", "Sen Sok"), shop));
        long senSokId = senSok.id();

        Res draft = ok(post("/transfers", Map.of("fromWarehouseId", shop.warehouseId(), "toBranchId", senSokId,
                "lines", List.of(Map.of("productId", water, "qty", 10))), shop));
        assertThat(draft.at("/kind").asText()).isEqualTo("BRANCH");
        assertThat(draft.at("/status").asText()).isEqualTo("DRAFT");
        assertThat(onHand(shop, water)).isEqualByComparingTo("100");

        Res sent = ok(post("/transfers/" + draft.id() + "/send", null, shop));
        assertThat(sent.at("/status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(sent.dec("/value")).isEqualByComparingTo("2.50");
        assertThat(ok(get("/stock/summary", shop)).dec("/inTransitValue")).isEqualByComparingTo("2.50");

        // the destination branch can't be deactivated while stock is on the way
        Res blocked = call(HttpMethod.DELETE, "/branches/" + senSokId, null, shop.token(), shop.businessId(), null, null);
        assertThat(blocked.code()).isEqualTo("BRANCH_HAS_TRANSFERS_IN_TRANSIT");

        long itemId = sent.at("/lines/0/id").asLong();
        Res received = ok(post("/transfers/" + draft.id() + "/receive",
                Map.of("lines", List.of(Map.of("itemId", itemId, "qtyReceived", 8))), shop));
        assertThat(received.at("/status").asText()).isEqualTo("RECEIVED");
        assertThat(received.at("/shortLines").asInt()).isEqualTo(1);

        Res adjustment = ok(get("/adjustments/" + received.at("/adjustmentId").asLong(), shop));
        assertThat(adjustment.at("/reason").asText()).isEqualTo("LOST_IN_TRANSIT");
        assertThat(adjustment.dec("/lines/0/qtyChange")).isEqualByComparingTo("-2");

        // Toul Kork 90, Sen Sok 8 (10 sent − 2 lost)
        Res senSokView = call(HttpMethod.GET, "/products/" + water, null, shop.token(), shop.businessId(), senSokId, null);
        assertThat(senSokView.dec("/qtyOnHand")).isEqualByComparingTo("8");
        Res mainView = call(HttpMethod.GET, "/products/" + water, null, shop.token(), shop.businessId(), shop.branchId(), null);
        assertThat(mainView.dec("/qtyOnHand")).isEqualByComparingTo("90");

        // received once only
        assertThat(post("/transfers/" + draft.id() + "/receive", null, shop).code()).isEqualTo("WRONG_STATUS");
    }

    @Test
    void returnToSenderPutsTheSameStockBack() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "30", "0.25");
        long senSok = ok(post("/branches", Map.of("nameEn", "Sen Sok"), shop)).id();
        long id = ok(post("/transfers", Map.of("fromWarehouseId", shop.warehouseId(), "toBranchId", senSok,
                "lines", List.of(Map.of("productId", water, "qty", 12))), shop)).id();
        ok(post("/transfers/" + id + "/send", null, shop));
        assertThat(onHand(shop, water)).isEqualByComparingTo("18");

        Res returned = ok(post("/transfers/" + id + "/return", Map.of("reason", "Wrong branch"), shop));
        assertThat(returned.at("/status").asText()).isEqualTo("RETURNED");
        assertThat(onHand(shop, water)).isEqualByComparingTo("30");
    }

    @Test
    void moveInsideABranchIsOneStep() {
        Shop shop = newShop();
        long water = productWithStock(shop, "Water", "0.50", "24", "0.25");
        long backStore = ok(post("/branches/" + shop.branchId() + "/warehouses",
                Map.of("nameEn", "Back store", "sales", false, "receiving", true), shop)).id();

        Res draft = ok(post("/transfers", Map.of("fromWarehouseId", shop.warehouseId(), "toWarehouseId", backStore,
                "lines", List.of(Map.of("productId", water, "unitId", shop.units().get("box"), "qty", 1))), shop));
        assertThat(draft.at("/kind").asText()).isEqualTo("MOVE");
        assertThat(draft.dec("/lines/0/baseQty")).isEqualByComparingTo("24");

        Res moved = ok(post("/transfers/" + draft.id() + "/move", null, shop));
        assertThat(moved.at("/status").asText()).isEqualTo("MOVED");
        assertThat(moved.at("/toName").asText()).isEqualTo("Toul Kork · Back store");

        Res stock = ok(get("/stock/levels/" + water, shop));
        assertThat(stock.at("/warehouses").findValuesAsText("warehouseName"))
                .contains("Toul Kork · Back store");
        assertThat(onHand(shop, water)).isEqualByComparingTo("24");
    }
}
