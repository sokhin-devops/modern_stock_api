package com.modernstock.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modernstock.config.AppProperties;
import com.modernstock.notification.EmailComposer.Email;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends verification, invitation and reset emails through an email API over HTTPS (Resend), because Render's free plan
 * blocks SMTP (DevSecOps.md C5). Without an API key nothing is sent: with dev links on (development, tests) the email is
 * written to the log, otherwise only who it was for, since its link is as good as a password.
 * <p>
 * Inside a transaction the email goes out only after the commit, so nobody gets a link the database never saved.
 * It is sent in the background, so a slow email API never slows the request. Failures are logged and never break
 * the request: the person can ask for the email again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private static final URI RESEND = URI.create("https://api.resend.com/emails");

    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService outbox = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-outbox");
        t.setDaemon(true);
        return t;
    });

    /** A plain-text email (billing reminders). */
    public void send(String to, String subject, String body) {
        send(to, new Email(subject, body, null));
    }

    public void send(String to, Email email) {
        Runnable job = () -> deliver(to, email);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outbox.execute(job);
                }
            });
        } else {
            outbox.execute(job);
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        outbox.shutdown();
        outbox.awaitTermination(15, TimeUnit.SECONDS);   // let queued emails go out on a normal shutdown
    }

    private void deliver(String to, Email email) {
        String key = props.mail() == null ? null : props.mail().apiKey();
        if (key == null || key.isBlank()) {
            if (props.devLinks()) {
                log.info("EMAIL to={} subject=\"{}\"\n{}", to, email.subject(), email.text());
            } else {
                log.warn("EMAIL not sent, MAIL_API_KEY is empty: to={} subject=\"{}\"", to, email.subject());
            }
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", props.mail().from());
            body.put("to", List.of(to));
            body.put("subject", email.subject());
            body.put("text", email.text());
            if (email.html() != null) {
                body.put("html", email.html());
            }
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(RESEND)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                log.info("EMAIL sent to={} subject=\"{}\"", to, email.subject());
            } else {
                log.warn("EMAIL refused to={} subject=\"{}\": {} {}", to, email.subject(), res.statusCode(), res.body());
            }
        } catch (IOException e) {
            log.warn("EMAIL failed to={} subject=\"{}\": {}", to, email.subject(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("EMAIL failed to={} subject=\"{}\": {}", to, email.subject(), e.toString());
        }
    }
}
