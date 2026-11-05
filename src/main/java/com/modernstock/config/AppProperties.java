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

    /**
     * mode=demo turns on the demo payment buttons; transferAccounts are where owners send bank transfers (none = no
     * transfers); company is who issues the billing invoices (printed on them).
     */
    public record Billing(String mode, List<TransferAccount> transferAccounts, Company company) {
    }

    public record Company(String name, String address, String phone, String email, String vatTin) {
    }

    public record TransferAccount(String bank, String name, String number) {
    }

    /**
     * The bot that sends Telegram messages; empty token = Telegram is off on this server. Telegram reaches the API by
     * the webhook (webhookUrl, registered at start, checked with webhookSecret), or, in development without a public
     * URL, the API asks Telegram for new messages every few seconds (polling).
     */
    public record Telegram(String botToken, String botUsername, String webhookUrl, String webhookSecret, boolean polling) {
    }
}
