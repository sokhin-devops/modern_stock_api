package com.modernstock.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.modernstock.billing.EntitlementService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Immediate Telegram alerts (FR-5.5): a product sold out, a cash shift closed short, a sale voided. Each is published
 * as an event by the work that causes it and sent once that work is saved, in the background, so a sale never waits for
 * Telegram. Sent to the business's connected chat when its plan includes Telegram alerts and the Owner left the alert on
 * (Settings › Telegram). There is no business context here: every query names the business itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramAlerts {

    public static final String OUT_OF_STOCK = "telegram_out_of_stock";
    public static final String SHIFT_SHORT = "telegram_shift_short";
    public static final String SALE_VOID = "telegram_sale_void";

    /** The last units of a product in a warehouse were sold. */
    public record StockOut(Long businessId, Long productId, Long warehouseId) {
    }

    /** A cash shift was closed with less cash than expected. */
    public record ShiftShort(Long businessId, Long shiftId) {
    }

    public record SaleVoided(Long businessId, Long receiptId, Long voidedBy, String reason) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TelegramClient telegram;
    private final EntitlementService entitlements;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(StockOut e) {
        send(e.businessId(), OUT_OF_STOCK, km -> {
            Map<String, Object> r = jdbc.queryForMap("""
                    select p.name_en, p.name_km, w.name_en as wh_en, w.name_km as wh_km, b.name_en as br_en, b.name_km as br_km
                      from products p, warehouses w join branches b on b.id = w.branch_id
                     where p.id = :product and w.id = :warehouse and p.business_id = :business and w.business_id = :business
                    """, params(e.businessId()).addValue("product", e.productId()).addValue("warehouse", e.warehouseId()));
            String product = name(r, "name_en", "name_km", km);
            String where = name(r, "br_en", "br_km", km) + " · " + name(r, "wh_en", "wh_km", km);
            return km ? "🔴 អស់ស្តុក៖ " + product + "\n" + where + "\nសូមបញ្ជាទិញបន្ថែម។"
                    : "🔴 Out of stock: " + product + "\n" + where + "\nTime to reorder.";
        });
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ShiftShort e) {
        send(e.businessId(), SHIFT_SHORT, km -> {
            Map<String, Object> r = jdbc.queryForMap("""
                    select u.full_name, b.name_en as br_en, b.name_km as br_km, s.expected_usd, s.counted_usd, s.expected_khr, s.counted_khr
                      from cash_shifts s join users u on u.id = s.cashier_id join branches b on b.id = s.branch_id
                     where s.id = :shift and s.business_id = :business
                    """, params(e.businessId()).addValue("shift", e.shiftId()));
            BigDecimal usd = diff(r, "counted_usd", "expected_usd");
            BigDecimal khr = diff(r, "counted_khr", "expected_khr");
            String amounts = (usd.signum() < 0 ? "$" + usd.negate().setScale(2, RoundingMode.HALF_UP).toPlainString() : "")
                    + (usd.signum() < 0 && khr.signum() < 0 ? " + " : "")
                    + (khr.signum() < 0 ? "៛" + String.format("%,d", khr.negate().longValue()) : "");
            String cashier = (String) r.get("full_name");
            String branch = name(r, "br_en", "br_km", km);
            return km ? "⚠️ វេនបិទខ្វះលុយ " + amounts + "\nអ្នកគិតលុយ៖ " + cashier + " · " + branch
                    : "⚠️ Cash shift closed short by " + amounts + "\nCashier: " + cashier + " · " + branch;
        });
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SaleVoided e) {
        send(e.businessId(), SALE_VOID, km -> {
            Map<String, Object> r = jdbc.queryForMap("""
                    select r.number, r.total, b.name_en as br_en, b.name_km as br_km, c.full_name as cashier,
                           (select full_name from users where id = :by) as voided_by
                      from sales_receipts r join branches b on b.id = r.branch_id join users c on c.id = r.cashier_id
                     where r.id = :receipt and r.business_id = :business
                    """, params(e.businessId()).addValue("receipt", e.receiptId()).addValue("by", e.voidedBy()));
            String total = "$" + ((BigDecimal) r.get("total")).setScale(2, RoundingMode.HALF_UP).toPlainString();
            String branch = name(r, "br_en", "br_km", km);
            return km ? "🧾 បានលុបការលក់ " + r.get("number") + " (" + total + ")\n" + branch + " · អ្នកលក់៖ " + r.get("cashier")
                            + "\nលុបដោយ៖ " + r.get("voided_by") + "\nមូលហេតុ៖ " + e.reason()
                    : "🧾 Sale voided: " + r.get("number") + " (" + total + ")\n" + branch + " · sold by " + r.get("cashier")
                            + "\nVoided by " + r.get("voided_by") + "\nReason: " + e.reason();
        });
    }

    private interface Text {
        String build(boolean km);
    }

    /** Sends when there is a bot, a chat, the plan feature and the alert is on; never throws. */
    private void send(Long businessId, String key, Text text) {
        try {
            if (!telegram.configured() || !entitlements.hasFeature(businessId, EntitlementService.TELEGRAM_ALERTS)) {
                return;
            }
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select b.language,
                           (select value from business_settings where business_id = b.id and key = 'telegram_chat_id') as chat,
                           (select value from business_settings where business_id = b.id and key = :key) as switch
                      from businesses b where b.id = :business and b.status = 'ACTIVE'
                    """, params(businessId).addValue("key", key));
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> b = rows.get(0);
            String chat = (String) b.get("chat");
            if (chat == null || chat.isBlank() || "false".equals(b.get("switch"))) {
                return;
            }
            String message = text.build("km".equals(b.get("language")));
            log.debug("Telegram alert {} for business {}: {}", key, businessId, message);
            telegram.send(chat, message);
        } catch (RuntimeException ex) {
            log.warn("Telegram alert {} for business {} failed: {}", key, businessId, ex.getMessage());
        }
    }

    private static MapSqlParameterSource params(Long businessId) {
        return new MapSqlParameterSource().addValue("business", businessId);
    }

    private static String name(Map<String, Object> r, String en, String km, boolean inKhmer) {
        Object k = r.get(km);
        return inKhmer && k != null && !k.toString().isBlank() ? k.toString() : (String) r.get(en);
    }

    private static BigDecimal diff(Map<String, Object> r, String counted, String expected) {
        BigDecimal c = (BigDecimal) r.get(counted);
        BigDecimal x = (BigDecimal) r.get(expected);
        return c == null || x == null ? BigDecimal.ZERO : c.subtract(x);
    }
}
