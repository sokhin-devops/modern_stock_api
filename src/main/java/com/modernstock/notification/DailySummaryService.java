package com.modernstock.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.billing.EntitlementService;
import com.modernstock.business.ExpiryWindows;
import com.modernstock.catalog.ReorderLevels;
import com.modernstock.common.Dates;
import com.modernstock.inventory.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The daily Telegram summary at 07:00 Phnom Penh time (FR-5.2, PLAIN.md 11.10): yesterday's sales (net of returns),
 * gross profit and cash received by currency per branch, then low stock, batches expiring within the first near-expiry
 * window (30 days unless changed, FR-7.10) and transfers in transit for too long (FR-4.13). It goes
 * to every active business with a connected chat whose plan has Telegram alerts (every business during the trial), in
 * the business language.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NamedParameterJdbcTemplate jdbc;
    private final TelegramClient telegram;
    private final EntitlementService entitlements;
    private final ExpiryWindows expiryWindows;

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Phnom_Penh")
    public void sendAll() {
        if (!telegram.configured()) {
            return;
        }
        List<Map<String, Object>> targets = jdbc.queryForList("""
                select b.id, c.value as chat from businesses b
                  join business_settings c on c.business_id = b.id and c.key = 'telegram_chat_id' and c.value <> ''
                 where b.status = 'ACTIVE'
                   and not exists (select 1 from business_settings s where s.business_id = b.id
                                    and s.key = 'telegram_daily_summary' and s.value = 'false')
                """, new MapSqlParameterSource());
        LocalDate yesterday = Dates.today().minusDays(1);
        int sent = 0;
        for (Map<String, Object> t : targets) {
            Long businessId = ((Number) t.get("id")).longValue();
            try {
                if (entitlements.hasFeature(businessId, EntitlementService.TELEGRAM_ALERTS)
                        && telegram.send((String) t.get("chat"), text(businessId, yesterday))) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Daily summary for business {} failed: {}", businessId, e.getMessage());
            }
        }
        log.info("Daily Telegram summary sent to {} of {} businesses", sent, targets.size());
    }

    /** The summary of one day for one business, in its language. */
    @Transactional(readOnly = true)
    public String text(Long businessId, LocalDate day) {
        int soonDays = expiryWindows.of(businessId).get(0);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("business", businessId).addValue("day", day)
                .addValue("soon", Dates.today().plusDays(soonDays)).addValue("today", Dates.today())
                .addValue("lateDays", TransferService.LATE_DAYS);
        Map<String, Object> business = jdbc.queryForMap("select name_en, name_km, language from businesses where id = :business", p);
        // The Owner can leave the low stock or the expiry line out (Settings › Telegram).
        List<String> off = jdbc.queryForList("select key from business_settings where business_id = :business and value = 'false'"
                + " and key in ('telegram_low_stock', 'telegram_expiry')", p, String.class);
        boolean km = "km".equals(business.get("language"));
        String name = km && business.get("name_km") != null ? (String) business.get("name_km") : (String) business.get("name_en");

        List<Map<String, Object>> branches = jdbc.queryForList("""
                select br.id, br.name_en, br.name_km,
                  coalesce((select sum(r.total) from sales_receipts r where r.branch_id = br.id and r.status = 'POSTED'
                             and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :day), 0)
                  + coalesce((select sum(i.total) from invoices i where i.branch_id = br.id and i.status <> 'VOID'
                             and i.invoice_date = :day), 0)
                  - coalesce((select sum(sr.total) from sales_returns sr where sr.branch_id = br.id and sr.status = 'POSTED'
                             and (sr.returned_at at time zone 'Asia/Phnom_Penh')::date = :day), 0) as sales,
                  coalesce((select sum(r.total_khr) from sales_receipts r where r.branch_id = br.id and r.status = 'POSTED'
                             and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :day), 0)
                  + coalesce((select sum(i.total_khr) from invoices i where i.branch_id = br.id and i.status <> 'VOID'
                             and i.invoice_date = :day), 0)
                  - coalesce((select sum(round(sr.total * coalesce((select x.usd_to_khr from exchange_rates x
                                       where x.business_id = sr.business_id order by x.rate_date desc limit 1), 0)))
                                from sales_returns sr where sr.branch_id = br.id and sr.status = 'POSTED'
                               and (sr.returned_at at time zone 'Asia/Phnom_Penh')::date = :day), 0) as sales_khr,
                  (select count(*) from sales_receipts r where r.branch_id = br.id and r.status = 'POSTED'
                     and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :day)
                  + (select count(*) from invoices i where i.branch_id = br.id and i.status <> 'VOID' and i.invoice_date = :day) as docs,
                  coalesce((select sum(si.qty * si.unit_price - si.discount - si.base_qty * si.cost_at_sale)
                              from sale_items si join sales_receipts r on r.id = si.sales_receipt_id
                             where r.branch_id = br.id and r.status = 'POSTED' and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :day), 0)
                  + coalesce((select sum(si.qty * si.unit_price - si.discount - si.base_qty * si.cost_at_sale)
                              from sale_items si join invoices i on i.delivery_id = si.delivery_id
                             where i.branch_id = br.id and i.status <> 'VOID' and i.invoice_date = :day), 0)
                  - coalesce((select sum(ri.qty * ri.unit_net - case when ri.condition = 'GOOD' then ri.base_qty * ri.cost_at_sale else 0 end)
                              from sales_return_items ri join sales_returns sr on sr.id = ri.return_id
                             where sr.branch_id = br.id and sr.status = 'POSTED' and (sr.returned_at at time zone 'Asia/Phnom_Penh')::date = :day), 0) as profit,
                  coalesce((select sum(pm.amount) from payments pm where pm.branch_id = br.id and pm.direction = 'IN'
                             and pm.status = 'POSTED' and pm.method = 'CASH' and pm.currency = 'USD'
                             and (pm.paid_at at time zone 'Asia/Phnom_Penh')::date = :day), 0)
                  - coalesce((select sum(r.change_usd) from sales_receipts r where r.branch_id = br.id and r.status = 'POSTED'
                             and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :day), 0) as cash_usd,
                  coalesce((select sum(pm.amount) from payments pm where pm.branch_id = br.id and pm.direction = 'IN'
                             and pm.status = 'POSTED' and pm.method = 'CASH' and pm.currency = 'KHR'
                             and (pm.paid_at at time zone 'Asia/Phnom_Penh')::date = :day), 0)
                  - coalesce((select sum(r.change_khr) from sales_receipts r where r.branch_id = br.id and r.status = 'POSTED'
                             and (r.sold_at at time zone 'Asia/Phnom_Penh')::date = :day), 0) as cash_khr
                  from branches br where br.business_id = :business and br.is_active order by br.is_main desc, br.name_en
                """, p);

        List<String> lines = new ArrayList<>();
        lines.add("📊 " + name + (km ? ": ម្សិលមិញ (" : ": yesterday (") + DAY.format(day) + ")");
        for (Map<String, Object> b : branches) {
            if (branches.size() > 1) {
                lines.add("");
                lines.add("🏪 " + (km && b.get("name_km") != null ? b.get("name_km") : b.get("name_en")));
            }
            long docs = ((Number) b.get("docs")).longValue();
            lines.add((km ? "ការលក់៖ " : "Sales: ") + usd(b.get("sales")) + " (" + khr(b.get("sales_khr")) + "), "
                    + docs + (km ? " វិក្កយបត្រ" : docs == 1 ? " receipt" : " receipts"));
            lines.add((km ? "ប្រាក់ចំណេញដុល៖ " : "Gross profit: ") + usd(b.get("profit")));
            lines.add((km ? "សាច់ប្រាក់ទទួលបាន៖ " : "Cash received: ") + usd(b.get("cash_usd")) + " + " + khr(b.get("cash_khr")));
        }

        List<Map<String, Object>> low = jdbc.queryForList("""
                select * from (select p.name_en, p.name_km, u.symbol, %s as level,
                                      coalesce((select sum(l.qty_on_hand) from stock_levels l where l.product_id = p.id), 0) as qty
                                 from products p join units u on u.id = p.base_unit_id
                                where p.business_id = :business and p.is_active) s
                 where s.level > 0 and s.qty <= s.level
                 order by s.qty, s.name_en
                """.formatted(ReorderLevels.sql("b.business_id = :business")), p);
        Long expiring = jdbc.queryForObject("""
                select count(*) from stock_batches where business_id = :business and qty_on_hand > 0
                   and expiry_date between :today and :soon
                """, p, Long.class);
        Long late = jdbc.queryForObject("""
                select count(*) from stock_transfers where business_id = :business and status = 'IN_TRANSIT'
                   and sent_at < now() - make_interval(days => :lateDays)
                """, p, Long.class);
        lines.add("");
        boolean showLow = !off.contains("telegram_low_stock");
        if (showLow && low.isEmpty()) {
            lines.add(km ? "✅ គ្មានស្តុកទាបទេ" : "✅ No low stock");
        } else if (showLow) {
            Map<String, Object> first = low.get(0);
            String product = km && first.get("name_km") != null ? (String) first.get("name_km") : (String) first.get("name_en");
            lines.add((km ? "⚠️ ស្តុកទាប៖ " + low.size() + " មុខ (" : "⚠️ Low stock: " + low.size() + (low.size() == 1 ? " item (" : " items ("))
                    + product + ": " + qty(first.get("qty")) + " " + first.get("symbol") + (km ? " នៅសល់)" : " left)"));
        }
        if (!off.contains("telegram_expiry") && expiring != null && expiring > 0) {
            lines.add((km ? "⏰ ផុតកំណត់ក្នុង " + khmerDigits(soonDays) + " ថ្ងៃ៖ " : "⏰ Expiring within " + soonDays + " days: ")
                    + expiring + (km ? " បាច់" : expiring == 1 ? " batch" : " batches"));
        }
        if (late != null && late > 0) {
            int d = TransferService.LATE_DAYS;
            lines.add((km ? "🚚 កំពុងដឹកលើស " + khmerDigits(d) + " ថ្ងៃ៖ " + late + " ការផ្ទេរ"
                    : "🚚 In transit over " + d + " days: " + late + (late == 1 ? " transfer" : " transfers")));
        }
        return String.join("\n", lines);
    }

    private static String usd(Object value) {
        DecimalFormat f = new DecimalFormat("$#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(((BigDecimal) value).setScale(2, RoundingMode.HALF_UP));
    }

    private static String khr(Object value) {
        DecimalFormat f = new DecimalFormat("៛#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(((BigDecimal) value).setScale(0, RoundingMode.HALF_UP));
    }

    /** 30 → ៣០, for the Khmer lines. */
    private static String khmerDigits(int n) {
        StringBuilder s = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) {
            s.append((char) ('០' + (c - '0')));
        }
        return s.toString();
    }

    private static String qty(Object value) {
        return new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }
}
