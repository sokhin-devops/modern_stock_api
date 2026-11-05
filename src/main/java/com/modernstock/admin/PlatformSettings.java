package com.modernstock.admin;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Platform-wide values the super admin can change without code (FR-8.8, FR-8.10): trial length, businesses per
 * account, invitation expiry and the announcement banner over the web app.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettings {

    public static final String TRIAL_DAYS = "trial_days";
    public static final String MAX_BUSINESSES = "max_businesses_per_account";
    public static final String INVITATION_DAYS = "invitation_expiry_days";
    public static final String ANNOUNCEMENT_EN = "announcement_en";
    public static final String ANNOUNCEMENT_KM = "announcement_km";
    /** INFO or WARN (maintenance, an outage). */
    public static final String ANNOUNCEMENT_LEVEL = "announcement_level";

    public static final int DEFAULT_TRIAL_DAYS = 30;
    public static final int DEFAULT_MAX_BUSINESSES = 5;
    public static final int DEFAULT_INVITATION_DAYS = 7;

    private final JdbcTemplate jdbc;

    public int getInt(String key, int defaultValue) {
        List<String> values = jdbc.queryForList("SELECT value FROM platform_settings WHERE key = ?", String.class, key);
        return values.isEmpty() ? defaultValue : Integer.parseInt(values.get(0));
    }

    public String getString(String key, String defaultValue) {
        List<String> values = jdbc.queryForList("SELECT value FROM platform_settings WHERE key = ?", String.class, key);
        return values.isEmpty() ? defaultValue : values.get(0);
    }

    public void set(String key, String value) {
        jdbc.update("""
                INSERT INTO platform_settings (key, value) VALUES (?, ?)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value
                """, key, value == null ? "" : value);
    }
}
