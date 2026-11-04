package com.modernstock.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        String webAppUrl,
        Cors cors,
        boolean devLinks,
        boolean demoData,
        Billing billing,
        Telegram telegram) {

    public record Jwt(String secret, int accessTokenMinutes, int refreshTokenDays) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Billing(String mode) {
    }

    /** The bot that sends Telegram messages; empty = Telegram is off on this server. */
    public record Telegram(String botToken) {
    }
}
