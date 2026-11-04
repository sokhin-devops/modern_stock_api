package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.modernstock.support.ApiTest;

/** Expenses, stock counts, reports and Telegram settings (V4): the areas the web app's last screens use. */
class ExpensesCountsReportsTest extends ApiTest {

    private Res put(String path, Object body, Shop shop) {
        return call(HttpMethod.PUT, path, body, shop.token(), shop.businessId(), null, null);
    }

    @Test
    void expensesHaveStarterCategoriesAndLowerTheNetProfit() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "5", "9.80");
        ok(post("/pos/checkout", Map.of("branchId", shop.branchId(), "items", List.of(Map.of("productId", rice, "qty", 2)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 30))), shop));

        Res categories = ok(get("/expense-categories", shop));
        assertThat(categories.body().size()).isEqualTo(6);
        long rent = 0;
        for (var c : categories.body()) {
            if (c.get("nameEn").asText().equals("Rent")) {
                rent = c.get("id").asLong();
            }
        }
        Res expense = ok(post("/expenses", Map.of("branchId", shop.branchId(), "categoryId", rent, "method", "CASH",
                "currency", "KHR", "amount", 41000), shop));
        assertThat(expense.at("/number").asText()).startsWith("EXP-");
        assertThat(expense.dec("/amountUsd")).isEqualByComparingTo("10.00");

        // 2 × 12.35 = 24.70 revenue, 2 × 9.80 = 19.60 cost, 5.10 gross, − 10.00 rent = −4.90
        String today = LocalDate.now().toString();
        Res profit = ok(get("/reports/profit?from=" + today + "&to=" + today, shop));
        assertThat(profit.dec("/revenue")).isEqualByComparingTo("24.70");
        assertThat(profit.dec("/costOfGoods")).isEqualByComparingTo("19.60");
        assertThat(profit.dec("/expenses")).isEqualByComparingTo("10.00");
        assertThat(profit.dec("/netProfit")).isEqualByComparingTo("-4.90");

        Res sales = ok(get("/reports/sales?from=" + today + "&to=" + today, shop));
        assertThat(sales.at("/documents").asLong()).isEqualTo(1);
        assertThat(sales.at("/topProducts/0/name").asText()).isEqualTo("Rice");

        ok(post("/expenses/" + expense.id() + "/void", null, shop));
        assertThat(ok(get("/reports/profit?from=" + today + "&to=" + today, shop)).dec("/expenses")).isEqualByComparingTo("0");
    }

    @Test
    void stockCountPostsOnlyTheDifferences() {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "10", "9.80");
        long water = productWithStock(shop, "Water", "0.50", "24", "0.25");
        long warehouse = shop.warehouseId();

        Res count = ok(post("/stock-counts", Map.of("warehouseId", warehouse), shop));
        assertThat(count.at("/number").asText()).startsWith("CNT-");
        long riceLine = 0;
        for (var line : count.body().get("lines")) {
            if (line.get("productId").asLong() == rice) {
                riceLine = line.get("id").asLong();
            }
        }
        // 8 rice found instead of 10; water not counted, so it stays at 24
        ok(put("/stock-counts/" + count.id() + "/lines", Map.of("lines", List.of(Map.of("itemId", riceLine, "countedQty", 8))), shop));
        Res posted = ok(post("/stock-counts/" + count.id() + "/post", null, shop));
        assertThat(posted.at("/status").asText()).isEqualTo("POSTED");
        assertThat(posted.dec("/differenceValue")).isEqualByComparingTo("-19.60");
        assertThat(onHand(shop, rice)).isEqualByComparingTo("8");
        assertThat(onHand(shop, water)).isEqualByComparingTo("24");
    }

    @Test
    void telegramSettingsAreKeptAndTestNeedsABot() {
        Shop shop = newShop();
        Res saved = ok(put("/business/telegram", Map.of("chatId", "-100123", "dailySummary", true, "lowStockAlerts", false,
                "expiryAlerts", true), shop));
        assertThat(saved.at("/chatId").asText()).isEqualTo("-100123");
        assertThat(saved.at("/lowStockAlerts").asBoolean()).isFalse();
        assertThat(saved.at("/botConfigured").asBoolean()).isFalse();
        assertThat(post("/business/telegram/test", null, shop).status()).isEqualTo(422);
    }
}
