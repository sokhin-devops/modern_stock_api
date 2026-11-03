package com.modernstock.admin;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Platform-wide values the super admin can change without code: trial length, businesses per account... */
@Service
@RequiredArgsConstructor
public class PlatformSettings {

    public static final String TRIAL_DAYS = "trial_days";
    public static final String MAX_BUSINESSES = "max_businesses_per_account";
    public static final String INVITATION_DAYS = "invitation_expiry_days";

    private final JdbcTemplate jdbc;

    public int getInt(String key, int defaultValue) {
        List<String> values = jdbc.queryForList("SELECT value FROM platform_settings WHERE key = ?", String.class, key);
        return values.isEmpty() ? defaultValue : Integer.parseInt(values.get(0));
    }
}
