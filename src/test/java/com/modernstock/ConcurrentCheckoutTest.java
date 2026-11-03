package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.modernstock.support.ApiTest;

/** PLAIN.md 11.4: two cashiers sell the last unit at the same moment — one must succeed and one must fail. */
class ConcurrentCheckoutTest extends ApiTest {

    @Test
    void twoCashiersSellTheLastUnit() throws Exception {
        Shop shop = newShop();
        long rice = productWithStock(shop, "Rice", "12.35", "1", "9.80");
        Map<String, Object> sale = Map.of("branchId", shop.branchId(),
                "items", List.of(Map.of("productId", rice, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "currency", "USD", "amount", 20)));

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> sell = () -> {
            start.await();
            return post("/pos/checkout", sale, shop).status();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(sell);
            Future<Integer> b = pool.submit(sell);
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 422);
        } finally {
            pool.shutdown();
        }
        assertThat(onHand(shop, rice)).isEqualByComparingTo("0");
    }
}
