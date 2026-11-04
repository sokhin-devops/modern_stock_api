package com.modernstock.notification;

import java.time.Instant;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.auth.AuthService.MessageResponse;
import com.modernstock.billing.EntitlementService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.SettingsService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Telegram (PLAIN.md 11.10): connect the business's chat with a one-time code (Telegram then tells the API which chat
 * it is), choose the messages, send a test or yesterday's summary now. The bot belongs to the server
 * (TELEGRAM_BOT_TOKEN); without one, Telegram is off.
 */
@Tag(name = "Telegram")
@RestController
@RequestMapping("/business/telegram")
@RequiredArgsConstructor
public class TelegramController {

    public record TelegramSettings(
            @Schema(description = "Read only: a chat is connected", accessMode = Schema.AccessMode.READ_ONLY) boolean connected,
            @Schema(description = "Read only: the connected chat or group", accessMode = Schema.AccessMode.READ_ONLY) String chatTitle,
            @Schema(description = "The daily summary at 07:00") boolean dailySummary,
            boolean lowStockAlerts,
            boolean expiryAlerts,
            @Schema(description = "Immediate: a product sold out") boolean outOfStockAlerts,
            @Schema(description = "Immediate: a cash shift closed short") boolean shiftShortAlerts,
            @Schema(description = "Immediate: a sale voided") boolean voidAlerts,
            @Schema(description = "Read only: the server has a bot", accessMode = Schema.AccessMode.READ_ONLY) boolean botConfigured,
            @Schema(description = "Read only: the plan includes Telegram alerts", accessMode = Schema.AccessMode.READ_ONLY) boolean planIncludes) {
    }

    public record ConnectResponse(String code, String botUsername,
                                  @Schema(description = "Opens a private chat with the bot") String privateLink,
                                  @Schema(description = "Adds the bot to a group") String groupLink,
                                  Instant expiresAt) {
    }

    private final SettingsService settings;
    private final TelegramClient telegram;
    private final TelegramLinkService links;
    private final DailySummaryService summary;
    private final EntitlementService entitlements;

    @GetMapping
    @PreAuthorize("hasAuthority('business:manage')")
    public TelegramSettings getTelegramSettings() {
        String chat = settings.value(TelegramLinkService.CHAT_ID, "");
        return new TelegramSettings(!chat.isBlank(), chat.isBlank() ? null : settings.value(TelegramLinkService.CHAT_TITLE, chat),
                Boolean.parseBoolean(settings.value("telegram_daily_summary", "true")),
                Boolean.parseBoolean(settings.value("telegram_low_stock", "true")),
                Boolean.parseBoolean(settings.value("telegram_expiry", "true")),
                Boolean.parseBoolean(settings.value(TelegramAlerts.OUT_OF_STOCK, "true")),
                Boolean.parseBoolean(settings.value(TelegramAlerts.SHIFT_SHORT, "true")),
                Boolean.parseBoolean(settings.value(TelegramAlerts.SALE_VOID, "true")),
                telegram.configured(), entitlements.hasFeature(EntitlementService.TELEGRAM_ALERTS));
    }

    @Operation(summary = "Choose the messages")
    @PutMapping
    @PreAuthorize("hasAuthority('business:manage')")
    public TelegramSettings updateTelegramSettings(@RequestBody TelegramSettings req) {
        settings.put("telegram_daily_summary", String.valueOf(req.dailySummary()));
        settings.put("telegram_low_stock", String.valueOf(req.lowStockAlerts()));
        settings.put("telegram_expiry", String.valueOf(req.expiryAlerts()));
        settings.put(TelegramAlerts.OUT_OF_STOCK, String.valueOf(req.outOfStockAlerts()));
        settings.put(TelegramAlerts.SHIFT_SHORT, String.valueOf(req.shiftShortAlerts()));
        settings.put(TelegramAlerts.SALE_VOID, String.valueOf(req.voidAlerts()));
        return getTelegramSettings();
    }

    @Operation(summary = "Start connecting a chat",
            description = "A one-time code (30 minutes) and the t.me links that open the bot with it. When the person taps "
                    + "Start, Telegram sends the code back and the chat is connected. 422 TELEGRAM_NOT_CONFIGURED without a bot.")
    @PostMapping("/connect")
    @PreAuthorize("hasAuthority('business:manage')")
    public ConnectResponse connectTelegram() {
        String bot = telegram.configured() ? telegram.botUsername() : null;
        if (bot == null) {
            throw ApiException.rule("TELEGRAM_NOT_CONFIGURED");
        }
        TelegramLinkService.Code code = links.newCode(BusinessContext.businessId());
        return new ConnectResponse(code.code(), bot, "https://t.me/" + bot + "?start=" + code.code(),
                "https://t.me/" + bot + "?startgroup=" + code.code(), code.expiresAt());
    }

    @PostMapping("/disconnect")
    @PreAuthorize("hasAuthority('business:manage')")
    public TelegramSettings disconnectTelegram() {
        links.disconnect(BusinessContext.businessId());
        return getTelegramSettings();
    }

    @Operation(summary = "Send a test message", description = "422 TELEGRAM_NOT_CONFIGURED, TELEGRAM_NO_CHAT or TELEGRAM_FAILED.")
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('business:manage')")
    public MessageResponse sendTelegramTest() {
        send("ModernStock ✓ សួស្តី! Telegram works.");
        return new MessageResponse("OK", null);
    }

    @Operation(summary = "Send yesterday's summary now", description = "The same message as the daily one at 07:00, for a demo.")
    @PostMapping("/summary")
    @PreAuthorize("hasAuthority('business:manage')")
    public MessageResponse sendTelegramSummary() {
        send(summary.text(BusinessContext.businessId(), Dates.today().minusDays(1)));
        return new MessageResponse("OK", null);
    }

    private void send(String text) {
        if (!telegram.configured()) {
            throw ApiException.rule("TELEGRAM_NOT_CONFIGURED");
        }
        String chat = settings.value(TelegramLinkService.CHAT_ID, "");
        if (chat.isBlank()) {
            throw ApiException.rule("TELEGRAM_NO_CHAT");
        }
        if (!telegram.send(chat, text)) {
            throw ApiException.rule("TELEGRAM_FAILED");
        }
    }
}
