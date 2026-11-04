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

    /**
     * The bot that sends Telegram messages; empty token = Telegram is off on this server. Telegram reaches the API by
     * the webhook (webhookUrl, registered at start, checked with webhookSecret), or, in development without a public
     * URL, the API asks Telegram for new messages every few seconds (polling).
     */
    public record Telegram(String botToken, String botUsername, String webhookUrl, String webhookSecret, boolean polling) {
    }
}
