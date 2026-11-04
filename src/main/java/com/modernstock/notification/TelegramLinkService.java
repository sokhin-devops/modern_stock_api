package com.modernstock.notification;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Connecting a business to a Telegram chat (PLAIN.md 11.10): the Owner gets a one-time code, opens
 * t.me/&lt;bot&gt;?start=&lt;code&gt; (private chat) or ?startgroup=&lt;code&gt; (a group), and Telegram sends
 * "/start &lt;code&gt;" to the API, which saves that chat for the business. Codes work once, for 30 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramLinkService {

    public static final String CHAT_ID = "telegram_chat_id";
    public static final String CHAT_TITLE = "telegram_chat_title";
    static final String CODE = "telegram_connect_code";
    static final String CODE_EXPIRES = "telegram_connect_expires";
    static final Duration CODE_LIFE = Duration.ofMinutes(30);

    private static final Pattern START = Pattern.compile("^/start(?:@\\w+)?\\s+([A-Za-z0-9_-]{6,64})\\s*$");
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TelegramClient telegram;

    public record Code(String code, Instant expiresAt) {
    }

    /** A new one-time code for the business (the last one stops working). */
    @Transactional
    public Code newCode(Long businessId) {
        StringBuilder code = new StringBuilder("ms");
        for (int i = 0; i < 12; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        Instant expires = Instant.now().plus(CODE_LIFE);
        put(businessId, CODE, code.toString());
        put(businessId, CODE_EXPIRES, expires.toString());
        return new Code(code.toString(), expires);
    }

    @Transactional
    public void disconnect(Long businessId) {
        jdbc.update("delete from business_settings where business_id = ? and key in (?, ?, ?, ?)",
                businessId, CHAT_ID, CHAT_TITLE, CODE, CODE_EXPIRES);
    }

    /** One update from Telegram (webhook or polling): connects the chat when it is "/start &lt;valid code&gt;". */
    @Transactional
    public void handle(JsonNode update) {
        JsonNode message = update.path("message");
        String text = message.path("text").asText("");
        Matcher m = START.matcher(text.trim());
        if (!m.matches()) {
            return;
        }
        JsonNode chat = message.path("chat");
        String chatId = chat.path("id").asText();
        String title = chat.hasNonNull("title") ? chat.get("title").asText()
                : (chat.path("first_name").asText("") + " " + chat.path("last_name").asText("")).trim();
        List<Map<String, Object>> found = jdbc.queryForList("""
                select c.business_id, e.value as expires, b.name_en, b.name_km, b.language
                  from business_settings c
                  join business_settings e on e.business_id = c.business_id and e.key = ?
                  join businesses b on b.id = c.business_id
                 where c.key = ? and c.value = ?
                """, CODE_EXPIRES, CODE, m.group(1));
        if (found.isEmpty() || Instant.parse((String) found.get(0).get("expires")).isBefore(Instant.now())) {
            telegram.send(chatId, "❌ This link has expired or was already used. Make a new one in ModernStock: Settings › Telegram.\n"
                    + "❌ តំណនេះផុតកំណត់ ឬបានប្រើរួចហើយ។ សូមបង្កើតថ្មីក្នុង ModernStock៖ ការកំណត់ › Telegram។");
            return;
        }
        Map<String, Object> row = found.get(0);
        Long businessId = ((Number) row.get("business_id")).longValue();
        put(businessId, CHAT_ID, chatId);
        put(businessId, CHAT_TITLE, title.isBlank() ? chatId : title);
        jdbc.update("delete from business_settings where business_id = ? and key in (?, ?)", businessId, CODE, CODE_EXPIRES);
        boolean khmer = "km".equals(row.get("language"));
        String name = khmer && row.get("name_km") != null ? (String) row.get("name_km") : (String) row.get("name_en");
        telegram.send(chatId, khmer
                ? "✅ បានភ្ជាប់ជាមួយ " + name + "។ សេចក្តីសង្ខេបប្រចាំថ្ងៃនឹងមកដល់នៅម៉ោង ៧ ព្រឹក។"
                : "✅ Connected to " + name + ". The daily summary arrives at 07:00.");
        log.info("Telegram chat {} connected to business {}", chatId, businessId);
    }

    private void put(Long businessId, String key, String value) {
        jdbc.update("""
                insert into business_settings (business_id, key, value) values (?, ?, ?)
                on conflict (business_id, key) do update set value = excluded.value, updated_at = now()
                """, businessId, key, value);
    }
}
