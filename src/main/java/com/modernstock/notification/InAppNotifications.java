package com.modernstock.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns what happened into notifications for the members it concerns (PLAIN.md 11.10: "according to their role"), in
 * their branch, after the change is saved: approvals for those who approve, a transfer for those who receive it at the
 * destination, a short cash shift and a voided sale for those who see all cash, out of stock for those who order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppNotifications {

    /** A branch transfer left the source and is on its way to {@code toBranchId}. */
    public record TransferSent(Long businessId, Long transferId, String number, Long fromBranchId, Long toBranchId, Long sentBy) {
    }

    private final NotificationService notifications;
    private final NamedParameterJdbcTemplate jdbc;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TelegramAlerts.ApprovalWaiting e) {
        List<String> who = switch (e.kind()) {
            case ADJUSTMENT, STOCK_COUNT -> List.of("adjustment:approve");
            case SALES_RETURN -> List.of("sale:void");
            case PURCHASE_RETURN -> List.of("bill:manage");
        };
        // the document itself: its own page (returns) or its list with it open (adjustments, counts)
        String table = switch (e.kind()) {
            case ADJUSTMENT -> "stock_adjustments";
            case STOCK_COUNT -> "stock_counts";
            case SALES_RETURN -> "sales_returns";
            case PURCHASE_RETURN -> "purchase_returns";
        };
        Long id = jdbc.queryForList("select id from " + table + " where business_id = :business and number = :number",
                new MapSqlParameterSource("business", e.businessId()).addValue("number", e.number()), Long.class)
                .stream().findFirst().orElse(null);
        String link = switch (e.kind()) {
            case ADJUSTMENT -> "/inventory/adjustments" + (id == null ? "" : "?open=" + id);
            case STOCK_COUNT -> "/inventory/stock-counts" + (id == null ? "" : "?open=" + id);
            case SALES_RETURN -> id == null ? "/customers/transactions?tab=returns" : "/customers/transactions/returns/" + id;
            case PURCHASE_RETURN -> id == null ? "/suppliers/transactions?tab=returns" : "/suppliers/transactions/returns/" + id;
        };
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("number", e.number());
        params.put("by", userName(e.askedBy()));
        if (e.amount() != null) {
            params.put("amount", e.amount().setScale(2, RoundingMode.HALF_UP));
        }
        send(e.businessId(), e.branchId(), who, e.askedBy(), "APPROVAL_" + e.kind().name(), params, link);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TransferSent e) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("number", e.number());
        params.putAll(branchNames(e.businessId(), e.fromBranchId(), "from"));
        send(e.businessId(), e.toBranchId(), List.of("transfer:manage"), e.sentBy(), "TRANSFER_SENT", params,
                "/inventory/transfers?status=IN_TRANSIT");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TelegramAlerts.ShiftShort e) {
        Map<String, Object> r = jdbc.queryForMap("""
                select s.branch_id, s.cashier_id, u.full_name, s.expected_usd, s.counted_usd, s.expected_khr, s.counted_khr
                  from cash_shifts s join users u on u.id = s.cashier_id where s.id = :id and s.business_id = :business
                """, new MapSqlParameterSource("id", e.shiftId()).addValue("business", e.businessId()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cashier", r.get("full_name"));
        params.put("usd", diff(r, "counted_usd", "expected_usd").negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        params.put("khr", diff(r, "counted_khr", "expected_khr").negate().max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP));
        send(e.businessId(), ((Number) r.get("branch_id")).longValue(), List.of("cash_shift:all"), ((Number) r.get("cashier_id")).longValue(),
                "SHIFT_SHORT", params, "/customers/cash-shifts");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TelegramAlerts.SaleVoided e) {
        Map<String, Object> r = jdbc.queryForMap("select number, total, branch_id from sales_receipts where id = :id and business_id = :business",
                new MapSqlParameterSource("id", e.receiptId()).addValue("business", e.businessId()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("number", r.get("number"));
        params.put("amount", ((BigDecimal) r.get("total")).setScale(2, RoundingMode.HALF_UP));
        params.put("by", userName(e.voidedBy()));
        params.put("reason", e.reason());
        send(e.businessId(), ((Number) r.get("branch_id")).longValue(), List.of("cash_shift:all"), e.voidedBy(), "SALE_VOIDED", params,
                "/customers/transactions/sales-receipts/" + e.receiptId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TelegramAlerts.StockOut e) {
        Map<String, Object> r = jdbc.queryForMap("""
                select p.name_en, p.name_km, w.branch_id, w.name_en as wh_en, w.name_km as wh_km
                  from products p, warehouses w where p.id = :product and w.id = :warehouse
                   and p.business_id = :business and w.business_id = :business
                """, new MapSqlParameterSource("product", e.productId()).addValue("warehouse", e.warehouseId())
                .addValue("business", e.businessId()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("product", r.get("name_en"));
        params.put("productKm", r.get("name_km") == null ? r.get("name_en") : r.get("name_km"));
        params.put("warehouse", r.get("wh_en"));
        params.put("warehouseKm", r.get("wh_km") == null ? r.get("wh_en") : r.get("wh_km"));
        send(e.businessId(), ((Number) r.get("branch_id")).longValue(), List.of("purchase_order:manage"), null, "STOCK_OUT", params,
                "/inventory/stock-card?product=" + e.productId());
    }

    private void send(Long business, Long branch, List<String> permissions, Long except, String type, Map<String, Object> params, String link) {
        try {
            notifications.notify(business, branch, permissions, except, type, params, link);
        } catch (RuntimeException ex) {
            log.warn("Notification {} for business {} failed: {}", type, business, ex.getMessage());
        }
    }

    private String userName(Long id) {
        if (id == null) {
            return "";
        }
        return jdbc.queryForList("select full_name from users where id = :id", new MapSqlParameterSource("id", id), String.class)
                .stream().findFirst().orElse("");
    }

    private Map<String, Object> branchNames(Long business, Long branch, String prefix) {
        Map<String, Object> r = jdbc.queryForMap("select name_en, coalesce(name_km, name_en) as name_km from branches where id = :id and business_id = :business",
                new MapSqlParameterSource("id", branch).addValue("business", business));
        return Map.of(prefix, r.get("name_en"), prefix + "Km", r.get("name_km"));
    }

    private static BigDecimal diff(Map<String, Object> r, String counted, String expected) {
        BigDecimal c = (BigDecimal) r.get(counted);
        BigDecimal x = (BigDecimal) r.get(expected);
        return (c == null ? BigDecimal.ZERO : c).subtract(x == null ? BigDecimal.ZERO : x);
    }
}
