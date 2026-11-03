package com.modernstock.config;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import com.modernstock.common.ProblemWriter;

@Configuration
public class WebConfig {

    /** Error messages follow the Accept-Language header the web app sends (km or en). */
    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, ProblemWriter.KHMER));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
