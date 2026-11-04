package com.modernstock.notification;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.SettingsService;
import com.modernstock.common.ApiException;
import com.modernstock.auth.AuthService.MessageResponse;
import com.modernstock.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Telegram (PLAIN.md 11.14): the chat that gets the business's messages and which messages it wants. The bot itself
 * belongs to the server (TELEGRAM_BOT_TOKEN); without one, the settings are kept but nothing is sent.
 */
@Tag(name = "Telegram")
@RestController
@RequestMapping("/business/telegram")
@RequiredArgsConstructor
public class TelegramController {

    public record TelegramSettings(
            @Schema(description = "Chat or group ID, e.g. -1001234567890; the bot must be in the group") @Size(max = 40) String chatId,
            @Schema(description = "Sales summary every evening") boolean dailySummary,
            boolean lowStockAlerts,
            boolean expiryAlerts,
            @Schema(description = "Read only: the server has a bot", accessMode = Schema.AccessMode.READ_ONLY) boolean botConfigured) {
    }

    private final SettingsService settings;
    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @GetMapping
    @PreAuthorize("hasAuthority('business:manage')")
    public TelegramSettings getTelegramSettings() {
        return new TelegramSettings(settings.value("telegram_chat_id", ""),
                Boolean.parseBoolean(settings.value("telegram_daily_summary", "true")),
                Boolean.parseBoolean(settings.value("telegram_low_stock", "true")),
                Boolean.parseBoolean(settings.value("telegram_expiry", "true")),
                botToken() != null);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('business:manage')")
    public TelegramSettings updateTelegramSettings(@Valid @RequestBody TelegramSettings req) {
        settings.put("telegram_chat_id", req.chatId() == null ? "" : req.chatId().trim());
        settings.put("telegram_daily_summary", String.valueOf(req.dailySummary()));
        settings.put("telegram_low_stock", String.valueOf(req.lowStockAlerts()));
        settings.put("telegram_expiry", String.valueOf(req.expiryAlerts()));
        return getTelegramSettings();
    }

    @Operation(summary = "Send a test message", description = "422 TELEGRAM_NOT_CONFIGURED when the server has no bot; 422 TELEGRAM_FAILED when Telegram refuses.")
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('business:manage')")
    public MessageResponse sendTelegramTest() {
        String token = botToken();
        if (token == null) {
            throw ApiException.rule("TELEGRAM_NOT_CONFIGURED");
        }
        String chat = settings.value("telegram_chat_id", "");
        if (chat.isBlank()) {
            throw ApiException.rule("TELEGRAM_NO_CHAT");
        }
        String body = "chat_id=" + URLEncoder.encode(chat, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode("ModernStock ✓ សួស្តី! Telegram works.", StandardCharsets.UTF_8);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw ApiException.rule("TELEGRAM_FAILED");
            }
        } catch (java.io.IOException e) {
            throw ApiException.rule("TELEGRAM_FAILED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.rule("TELEGRAM_FAILED");
        }
        return new MessageResponse("OK", null);
    }

    private String botToken() {
        String token = props.telegram() == null ? null : props.telegram().botToken();
        return token == null || token.isBlank() ? null : token;
    }
}
