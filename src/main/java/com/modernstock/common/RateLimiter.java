package com.modernstock.common;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Rate limits (PLAIN.md 7 Security, Bucket4j): logging in, signing up, the password and verification emails, links with
 * a token, the contact form, the PIN switch and coupon codes. One bucket per rule and key (an IP address, a login, a
 * user, a business), in memory: right for one API instance; idle buckets are dropped. Over the limit: 429
 * TOO_MANY_REQUESTS with how long to wait. Off in tests (app.rate-limits.enabled).
 */
@Component
public class RateLimiter {

    public enum Rule {
        /** Logins from one address, and tries on one account from anywhere. */
        LOGIN_IP(20, Duration.ofMinutes(1)),
        LOGIN_ACCOUNT(10, Duration.ofMinutes(15)),
        SIGN_UP_IP(5, Duration.ofHours(1)),
        /** Password reset and verification emails: per address, and per email so nobody is flooded. */
        EMAIL_IP(5, Duration.ofMinutes(15)),
        EMAIL_ADDRESS(3, Duration.ofHours(1)),
        /** Links with a token (verify, reset, invitations): guessing them is pointless, but not free. */
        TOKEN_IP(30, Duration.ofMinutes(15)),
        CONTACT_IP(5, Duration.ofHours(1)),
        PIN_SWITCH_USER(10, Duration.ofMinutes(1)),
        COUPON_BUSINESS(10, Duration.ofHours(1));

        private final int tries;
        private final Duration per;

        Rule(int tries, Duration per) {
            this.tries = tries;
            this.per = per;
        }
    }

    private record Entry(Bucket bucket, AtomicLong lastUsed) {
    }

    /** A bucket nobody used for this long is forgotten (it would be full again anyway). */
    private static final long IDLE_MS = Duration.ofHours(2).toMillis();

    private final boolean enabled;
    private final ConcurrentMap<String, Entry> buckets = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${app.rate-limits.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** Takes one try for this key, or refuses with 429 and the minutes to wait. */
    public void check(Rule rule, String key) {
        if (!enabled || key == null || key.isBlank()) {
            return;
        }
        Entry entry = buckets.computeIfAbsent(rule.name() + ':' + key.strip().toLowerCase(Locale.ROOT),
                k -> new Entry(Bucket.builder()
                        .addLimit(Bandwidth.builder().capacity(rule.tries).refillGreedy(rule.tries, rule.per).build())
                        .build(), new AtomicLong()));
        entry.lastUsed().set(System.currentTimeMillis());
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long minutes = Math.max(1, (TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", minutes);
        }
    }

    /** The caller's address (behind Cloudflare in the cloud: from CF-Connecting-IP, see ClientIpFilter). */
    public static String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    void dropIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_MS;
        buckets.values().removeIf(e -> e.lastUsed().get() < cutoff);
    }
}
