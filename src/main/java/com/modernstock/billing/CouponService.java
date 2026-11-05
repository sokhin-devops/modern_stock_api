package com.modernstock.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Coupons (FR-10.16): the super admin makes a code with a discount for a number of billing periods; an Owner enters it
 * once per business, and the next full-period invoices (the first one and renewals) are cheaper. One coupon runs at a
 * time. Coupons belong to the platform, not to a business, so they are kept with plain SQL.
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    public record CouponRow(Long id, String code, int percentOff,
                            @Schema(description = "Full-period invoices it discounts") int periods,
                            @Schema(description = "null = no limit") Integer maxRedemptions, LocalDate expiresOn, boolean active,
                            String note, @Schema(description = "Businesses that entered it") long redemptions) {
    }

    public record CouponRequest(@NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
                                @Min(1) @Max(100) int percentOff, @Min(1) @Max(24) int periods,
                                @Min(1) Integer maxRedemptions, LocalDate expiresOn, boolean active, @Size(max = 255) String note) {
    }

    /** The coupon running for a business. */
    public record Running(String code, int percentOff, int periodsLeft) {
    }

    private final JdbcTemplate jdbc;

    public List<CouponRow> list() {
        return jdbc.query("""
                select c.*, (select count(*) from coupon_redemptions r where r.coupon_id = c.id) as used
                  from coupons c order by c.created_at desc
                """, (rs, n) -> new CouponRow(rs.getLong("id"), rs.getString("code"), rs.getInt("percent_off"), rs.getInt("periods"),
                (Integer) rs.getObject("max_redemptions"), rs.getDate("expires_on") == null ? null : rs.getDate("expires_on").toLocalDate(),
                rs.getBoolean("is_active"), rs.getString("note"), rs.getLong("used")));
    }

    @Transactional
    public void create(CouponRequest req) {
        String code = code(req.code());
        if (!jdbc.queryForList("select 1 from coupons where code = ?", Integer.class, code).isEmpty()) {
            throw ApiException.conflict("COUPON_CODE_TAKEN", code);
        }
        jdbc.update("""
                insert into coupons (code, percent_off, periods, max_redemptions, expires_on, is_active, note) values (?, ?, ?, ?, ?, ?, ?)
                """, code, req.percentOff(), req.periods(), req.maxRedemptions(), req.expiresOn(), req.active(), blank(req.note()));
    }

    /** Changes apply to who enters it from now on; businesses that entered it keep what they got. */
    @Transactional
    public void update(Long id, CouponRequest req) {
        String code = code(req.code());
        if (!jdbc.queryForList("select 1 from coupons where code = ? and id <> ?", Integer.class, code, id).isEmpty()) {
            throw ApiException.conflict("COUPON_CODE_TAKEN", code);
        }
        if (jdbc.update("""
                update coupons set code = ?, percent_off = ?, periods = ?, max_redemptions = ?, expires_on = ?, is_active = ?, note = ? where id = ?
                """, code, req.percentOff(), req.periods(), req.maxRedemptions(), req.expiresOn(), req.active(), blank(req.note()), id) == 0) {
            throw ApiException.notFound("coupon");
        }
    }

    /** The Owner enters a code: refused when unknown, off, expired, used up, used before here, or another one runs. */
    @Transactional
    public Running redeem(Long businessId, String raw) {
        String code = code(raw);
        List<CouponRow> found = list().stream().filter(c -> c.code().equals(code)).toList();
        CouponRow c = found.isEmpty() ? null : found.get(0);
        if (c == null || !c.active() || (c.expiresOn() != null && c.expiresOn().isBefore(Dates.today()))
                || (c.maxRedemptions() != null && c.redemptions() >= c.maxRedemptions())) {
            throw ApiException.rule("COUPON_INVALID");
        }
        if (!jdbc.queryForList("select 1 from coupon_redemptions where coupon_id = ? and business_id = ?", Integer.class, c.id(), businessId)
                .isEmpty()) {
            throw ApiException.rule("COUPON_USED");
        }
        running(businessId).ifPresent(r -> {
            throw ApiException.rule("COUPON_RUNNING", r.code());
        });
        jdbc.update("insert into coupon_redemptions (coupon_id, business_id, periods_left) values (?, ?, ?)", c.id(), businessId, c.periods());
        return new Running(c.code(), c.percentOff(), c.periods());
    }

    public Optional<Running> running(Long businessId) {
        return jdbc.query("""
                select c.code, c.percent_off, r.periods_left from coupon_redemptions r join coupons c on c.id = r.coupon_id
                 where r.business_id = ? and r.periods_left > 0 order by r.redeemed_at limit 1
                """, (rs, n) -> new Running(rs.getString("code"), rs.getInt("percent_off"), rs.getInt("periods_left")), businessId)
                .stream().findFirst();
    }

    /** Takes one period of the running coupon for an invoice of this amount: the discount, or empty. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Discount> take(Long businessId, BigDecimal amount) {
        Optional<Running> running = running(businessId);
        if (running.isEmpty() || amount.signum() <= 0) {
            return Optional.empty();
        }
        Running r = running.get();
        jdbc.update("""
                update coupon_redemptions set periods_left = periods_left - 1
                 where business_id = ? and periods_left > 0 and coupon_id = (select id from coupons where code = ?)
                """, businessId, r.code());
        BigDecimal off = amount.multiply(BigDecimal.valueOf(r.percentOff())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return Optional.of(new Discount(r.code(), r.percentOff(), off));
    }

    public record Discount(String code, int percentOff, BigDecimal amount) {
    }

    private static String code(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
