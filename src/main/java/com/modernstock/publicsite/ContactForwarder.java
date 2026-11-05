package com.modernstock.publicsite;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.modernstock.config.AppProperties;
import com.modernstock.notification.TelegramClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Website contact messages forwarded to the team's Telegram chat (FR-9.7), once saved and in the background, so the
 * form never waits for Telegram. Without a bot or app.telegram.team-chat-id they are only saved (Admin › Messages).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactForwarder {

    /** A contact message was saved. */
    public record ContactReceived(Long messageId) {
    }

    private final ContactMessageRepository messages;
    private final TelegramClient telegram;
    private final AppProperties props;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ContactReceived e) {
        String chat = props.telegram() == null ? null : props.telegram().teamChatId();
        if (chat == null || chat.isBlank() || !telegram.configured()) {
            return;
        }
        messages.findById(e.messageId()).ifPresent(m -> {
            StringBuilder text = new StringBuilder("📩 New message from the website\n").append(m.getName());
            if (m.getPhone() != null && !m.getPhone().isBlank()) {
                text.append("\n📞 ").append(m.getPhone());
            }
            if (m.getEmail() != null && !m.getEmail().isBlank()) {
                text.append("\n✉️ ").append(m.getEmail());
            }
            text.append("\n🌐 ").append("km".equals(m.getLanguage()) ? "ខ្មែរ" : "English").append("\n\n").append(m.getMessage());
            if (!telegram.send(chat, text.toString())) {
                log.warn("Contact message {} could not be sent to the team chat", m.getId());
            }
        });
    }
}
