package com.modernstock.notification;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends verification, invitation and reset emails. For now it writes them to the log; the next step is
 * Spring Mail + Thymeleaf templates in Khmer and English, with Mailpit catching them in development.
 */
@Slf4j
@Service
public class MailService {

    public void send(String to, String subject, String body) {
        log.info("EMAIL to={} subject=\"{}\"\n{}", to, subject, body);
    }
}
