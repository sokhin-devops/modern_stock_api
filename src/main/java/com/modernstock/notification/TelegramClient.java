package com.modernstock.notification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modernstock.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Calls to the Telegram Bot API with the server's bot. Failures are logged and reported as false/null; Telegram being
 * down must never break a sale or a report.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramClient {

    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String username;

    public boolean configured() {
        return token() != null;
    }

    /** The bot's @username (without @) for t.me links: from the settings, else asked once from Telegram. */
    public String botUsername() {
        if (username == null) {
            String configured = props.telegram() == null ? null : props.telegram().botUsername();
            if (configured != null && !configured.isBlank()) {
                username = configured.replace("@", "").trim();
            } else {
                JsonNode me = call("getMe", Map.of(), 10);
                username = me == null ? null : me.path("username").asText(null);
            }
        }
        return username;
    }

    /** Sends a plain text message; false when Telegram refused it or could not be reached. */
    public boolean send(String chatId, String text) {
        return call("sendMessage", Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", "true"), 10) != null;
    }

    /** Calls a Bot API method; returns its "result", or null on any failure. */
    public JsonNode call(String method, Map<String, String> params, int timeoutSeconds) {
        String token = token();
        if (token == null) {
            return null;
        }
        String body = new LinkedHashMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                    .timeout(Duration.ofSeconds(timeoutSeconds + 5L))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode reply = json.readTree(res.body());
            if (res.statusCode() != 200 || !reply.path("ok").asBoolean()) {
                log.warn("Telegram {} failed: {} {}", method, res.statusCode(), reply.path("description").asText());
                return null;
            }
            return reply.path("result");
        } catch (IOException e) {
            log.warn("Telegram {} failed: {}", method, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String token() {
        String token = props.telegram() == null ? null : props.telegram().botToken();
        return token == null || token.isBlank() ? null : token;
    }
}
