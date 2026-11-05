package com.modernstock.billing;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.modernstock.common.Dates;
import com.modernstock.notification.MailService;
import com.modernstock.notification.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reminders 7, 3 and 1 days before a renewal or the end of a trial (FR-10.15): by email to the business's Owners and
 * on its Telegram chat, once each (billing_reminders), in the business's language. In the app the alerts show the same
 * (TRIAL_ENDING, RENEWAL_SOON).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReminders {

    static final List<Long> DAYS = List.of(7L, 3L, 1L);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final TelegramClient telegram;
    private final PlanRepository plans;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Phnom_Penh")
    public void sendAll() {
        int sent = sendDue(Dates.today());
        if (sent > 0) {
            log.info("Billing reminders sent: {}", sent);
        }
    }

    /** Sends the reminders due on `today`; returns how many businesses were reminded. */
    public int sendDue(LocalDate today) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.business_id, s.status, s.subscribed, s.trial_ends_at, s.current_period_end, s.plan_id, s.pending_plan_id,
                       s.billing_cycle, s.pending_billing_cycle, b.name_en, b.name_km, b.language,
                       (select c.value from business_settings c where c.business_id = b.id and c.key = 'telegram_chat_id') as chat
                  from subscriptions s join businesses b on b.id = s.business_id
                 where b.status = 'ACTIVE'
                   and ((s.status = 'TRIALING' and s.trial_ends_at is not null)
                     or (s.status = 'ACTIVE' and not s.cancel_at_period_end and s.current_period_end is not null))
                """);
        int sent = 0;
        for (Map<String, Object> r : rows) {
            boolean trial = "TRIALING".equals(r.get("status"));
            Instant due = ((Timestamp) r.get(trial ? "trial_ends_at" : "current_period_end")).toInstant();
            LocalDate dueDay = due.atZone(Dates.PHNOM_PENH).toLocalDate();
            long days = ChronoUnit.DAYS.between(today, dueDay);
            Long businessId = ((Number) r.get("business_id")).longValue();
            if (!DAYS.contains(days) || jdbc.update("insert into billing_reminders (business_id, due_at, days_before) values (?, ?, ?) "
                    + "on conflict do nothing", businessId, Timestamp.from(due), (int) days) == 0) {
                continue;
            }
            String text = text(r, trial, days, dueDay);
            jdbc.queryForList("""
                    select u.email from business_members m join roles r on r.id = m.role_id join users u on u.id = m.user_id
                     where m.business_id = ? and m.status = 'ACTIVE' and r.code = 'OWNER' and u.email is not null
                    """, String.class, businessId)
                    .forEach(email -> mail.send(email, "km".equals(r.get("language")) ? "ការរំលឹកពី ModernStock" : "ModernStock reminder", text));
            String chat = (String) r.get("chat");
            if (chat != null && !chat.isBlank() && telegram.configured()) {
                telegram.send(chat, text);
            }
            sent++;
        }
        return sent;
    }

    private String text(Map<String, Object> r, boolean trial, long days, LocalDate dueDay) {
        boolean km = "km".equals(r.get("language"));
        String name = km && r.get("name_km") != null ? (String) r.get("name_km") : (String) r.get("name_en");
        Long planId = ((Number) (r.get("pending_plan_id") != null ? r.get("pending_plan_id") : r.get("plan_id"))).longValue();
        Plan plan = plans.findById(planId).orElseThrow();
        Subscription.Cycle cycle = Subscription.Cycle.valueOf((String) (r.get("pending_billing_cycle") != null
                ? r.get("pending_billing_cycle") : r.get("billing_cycle")));
        BigDecimal amount = BillingService.price(plan, cycle).add(addons(((Number) r.get("business_id")).longValue(), cycle));
        String date = DATE.format(dueDay);
        String money = "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        if (trial && !Boolean.TRUE.equals(r.get("subscribed"))) {
            return km ? "ការសាកល្បង ModernStock សម្រាប់ " + name + " នឹងបញ្ចប់ក្នុងរយៈពេល " + days + " ថ្ងៃ (" + date + ")។ "
                    + "ជ្រើសគម្រោង ដើម្បីរក្សាអ្វីៗទាំងអស់ បើមិនដូច្នោះទេ វានឹងប្តូរទៅ Free។"
                    : "Your ModernStock trial for " + name + " ends in " + days + (days == 1 ? " day" : " days") + " (" + date + "). "
                    + "Choose a plan to keep everything on; otherwise it moves to Free.";
        }
        if (trial) {
            return km ? "ការសាកល្បងសម្រាប់ " + name + " នឹងបញ្ចប់ក្នុងរយៈពេល " + days + " ថ្ងៃ (" + date + ")។ គម្រោង " + plan.getName()
                    + " របស់អ្នកចាប់ផ្តើមពេលនោះ៖ " + money + "។"
                    : "The trial for " + name + " ends in " + days + (days == 1 ? " day" : " days") + " (" + date + "). Your "
                    + plan.getName() + " plan starts then: " + money + ".";
        }
        return km ? name + "៖ គម្រោង " + plan.getName() + " របស់អ្នកនឹងបន្តក្នុងរយៈពេល " + days + " ថ្ងៃ (" + date + ")៖ " + money + "។"
                : name + ": your " + plan.getName() + " plan renews in " + days + (days == 1 ? " day" : " days") + " (" + date + "): " + money + ".";
    }

    /** The add-ons that renew with the plan. */
    private BigDecimal addons(Long businessId, Subscription.Cycle cycle) {
        BigDecimal monthly = jdbc.queryForObject("""
                select coalesce(sum(sa.quantity * a.price_monthly), 0) from subscription_addons sa join addons a on a.id = sa.addon_id
                 where sa.business_id = ? and sa.status = 'ACTIVE' and not sa.remove_at_period_end
                """, BigDecimal.class, businessId);
        BigDecimal value = monthly == null ? BigDecimal.ZERO : monthly;
        return cycle == Subscription.Cycle.YEARLY ? value.multiply(BigDecimal.TEN) : value;
    }
}
