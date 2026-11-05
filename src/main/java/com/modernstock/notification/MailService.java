package com.modernstock.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modernstock.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends verification, invitation and reset emails through an email API over HTTPS (Resend), because Render's free plan
 * blocks SMTP (DevSecOps.md C5). Without an API key nothing is sent: with dev links on (development, tests) the email is
 * written to the log, otherwise only who it was for, since its link is as good as a password. Failures are logged and
 * never break the request: the person can ask for the email again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private static final URI RESEND = URI.create("https://api.resend.com/emails");

    private final AppProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public void send(String to, String subject, String body) {
        String key = props.mail() == null ? null : props.mail().apiKey();
        if (key == null || key.isBlank()) {
            if (props.devLinks()) {
                log.info("EMAIL to={} subject=\"{}\"\n{}", to, subject, body);
            } else {
                log.warn("EMAIL not sent, MAIL_API_KEY is empty: to={} subject=\"{}\"", to, subject);
            }
            return;
        }
        try {
            String payload = json.writeValueAsString(
                    Map.of("from", props.mail().from(), "to", List.of(to), "subject", subject, "text", body));
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(RESEND)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                log.info("EMAIL sent to={} subject=\"{}\"", to, subject);
            } else {
                log.warn("EMAIL refused to={} subject=\"{}\": {} {}", to, subject, res.statusCode(), res.body());
            }
        } catch (IOException e) {
            log.warn("EMAIL failed to={} subject=\"{}\": {}", to, subject, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
