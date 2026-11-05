package com.modernstock.business;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * The three near-expiry windows a business watches (FR-7.10): 30, 60 and 90 days unless the Owner picks others in
 * Settings › Rules. The first one warns (dashboard, alerts, the daily summary); the other two are for planning ahead.
 */
@Service
@RequiredArgsConstructor
public class ExpiryWindows {

    /** Stored in business_settings as "30,60,90". */
    public static final String KEY = "expiry_days";
    public static final List<Integer> DEFAULT = List.of(30, 60, 90);
    public static final int MAX_DAYS = 365;

    private final NamedParameterJdbcTemplate jdbc;

    /** The windows of a business, also for jobs that run outside a request. */
    public List<Integer> of(Long businessId) {
        List<String> value = jdbc.queryForList("select value from business_settings where business_id = :business and key = :key",
                new MapSqlParameterSource("business", businessId).addValue("key", KEY), String.class);
        return parse(value.isEmpty() ? null : value.get(0));
    }

    /** The windows of the business in the request. */
    public List<Integer> current() {
        return of(BusinessContext.businessId());
    }

    /** Anything unreadable falls back to the defaults. */
    public static List<Integer> parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            List<Integer> days = Arrays.stream(value.split(",")).map(String::trim).map(Integer::valueOf).toList();
            return valid(days) ? days : DEFAULT;
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    public static String format(List<Integer> days) {
        return days.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** Three windows of 1 to 365 days, each longer than the one before. */
    public static boolean valid(List<Integer> days) {
        if (days == null || days.size() != 3) {
            return false;
        }
        int before = 0;
        for (Integer d : days) {
            if (d == null || d <= before || d > MAX_DAYS) {
                return false;
            }
            before = d;
        }
        return true;
    }
}
