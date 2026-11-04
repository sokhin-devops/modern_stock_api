package com.modernstock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Jobs: the daily Telegram summary, Telegram polling in development, the billing lifecycle and quotation expiry; and
 * background work such as sending immediate Telegram alerts.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
