package com.modernstock.common;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Translated text for success messages, in the caller's Accept-Language. */
@Component
@RequiredArgsConstructor
public class Messages {

    private final MessageSource source;

    public String get(String code, Object... args) {
        return source.getMessage(code, args, code, LocaleContextHolder.getLocale());
    }
}
