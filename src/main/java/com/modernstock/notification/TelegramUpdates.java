package com.modernstock.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.modernstock.config.AppProperties;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * How Telegram's messages reach the API: the webhook (registered with Telegram at start when TELEGRAM_WEBHOOK_URL is
 * set, and checked with the secret header), or, in development without a public URL, polling every 3 seconds.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
public class TelegramUpdates {

    private final AppProperties props;
    private final TelegramClient telegram;
    private final TelegramLinkService links;
    private volatile long offset;

    @PostMapping("/public/telegram/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret,
                                        @RequestBody JsonNode update) {
        String expected = props.telegram() == null ? null : props.telegram().webhookSecret();
        if (expected == null || expected.isBlank() || secret == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        links.handle(update);
        return ResponseEntity.ok().build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhook() {
        String url = webhookUrl();
        if (url != null && telegram.configured()) {
            boolean ok = telegram.call("setWebhook", Map.of("url", url, "secret_token", props.telegram().webhookSecret(),
                    "allowed_updates", "[\"message\"]"), 10) != null;
            log.info("Telegram webhook {}: {}", ok ? "registered" : "NOT registered", url);
        }
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    public void poll() {
        if (props.telegram() == null || !props.telegram().polling() || webhookUrl() != null || !telegram.configured()) {
            return;
        }
        JsonNode updates = telegram.call("getUpdates",
                Map.of("offset", String.valueOf(offset), "timeout", "0", "allowed_updates", "[\"message\"]"), 5);
        if (updates == null) {
            return;
        }
        for (JsonNode update : updates) {
            offset = update.path("update_id").asLong() + 1;
            try {
                links.handle(update);
            } catch (RuntimeException e) {
                log.warn("Telegram update {} failed: {}", update.path("update_id").asLong(), e.getMessage());
            }
        }
    }

    private String webhookUrl() {
        String url = props.telegram() == null ? null : props.telegram().webhookUrl();
        return url == null || url.isBlank() ? null : url;
    }
}
