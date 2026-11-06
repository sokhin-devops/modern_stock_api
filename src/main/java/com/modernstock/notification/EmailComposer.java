package com.modernstock.notification;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import lombok.RequiredArgsConstructor;

/**
 * The account emails (verify, reset, invitation) in the reader's language, as HTML with a plain-text copy.
 * Texts are the email.* keys in i18n/messages*.properties. Every value that comes from a person (names, business
 * names) is HTML-escaped, so a name can't add links or markup to the email.
 */
@Component
@RequiredArgsConstructor
public class EmailComposer {

    public record Email(String subject, String text, String html) {
    }

    private final MessageSource messages;

    public Email verify(String language, String name, String link) {
        return compose(language, "verify", new Object[] {}, new Object[] {name}, link);
    }

    public Email reset(String language, String name, String link) {
        return compose(language, "reset", new Object[] {}, new Object[] {name}, link);
    }

    public Email invitation(String language, String inviter, String business, String link) {
        return compose(language, "invite", new Object[] {inviter}, new Object[] {inviter, business}, link);
    }

    private Email compose(String language, String kind, Object[] subjectArgs, Object[] introArgs, String link) {
        Locale locale = "en".equals(language) ? Locale.ENGLISH : Locale.forLanguageTag("km");
        String subject = text("email." + kind + ".subject", subjectArgs, locale);
        String title = text("email." + kind + ".title", null, locale);
        String intro = text("email." + kind + ".intro", introArgs, locale);
        String button = text("email." + kind + ".button", null, locale);
        String expiry = text("email." + kind + ".expiry", null, locale);
        String ignore = text("email.ignore", null, locale);
        String hint = text("email.linkHint", null, locale);
        String footer = text("email.footer", null, locale);

        String plain = title + "\n\n" + intro + "\n\n" + button + ":\n" + link + "\n\n" + expiry + "\n" + ignore
                + "\n\n-- \n" + footer + "\n";

        Object[] safeArgs = new Object[introArgs.length];
        for (int i = 0; i < introArgs.length; i++) {
            safeArgs[i] = esc(String.valueOf(introArgs[i]));
        }
        String href = esc(link);
        String html = """
                <!doctype html>
                <html lang="%s"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>%s</title></head>
                <body style="margin:0;padding:0;background:#f3f4f6;font-family:'Noto Sans Khmer','Khmer OS Battambang',Arial,sans-serif;color:#111827">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f3f4f6;padding:24px 12px">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:12px;padding:32px 28px">
                <tr><td style="font-size:18px;font-weight:700;color:#1a73e8;padding-bottom:20px">ModernStock</td></tr>
                <tr><td style="font-size:22px;font-weight:700;line-height:1.4;padding-bottom:12px">%s</td></tr>
                <tr><td style="font-size:15px;line-height:1.7;padding-bottom:24px">%s</td></tr>
                <tr><td style="padding-bottom:24px"><a href="%s" style="display:inline-block;background:#1a73e8;color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;padding:12px 24px;border-radius:999px">%s</a></td></tr>
                <tr><td style="font-size:14px;line-height:1.6;color:#374151;padding-bottom:16px">%s<br>%s</td></tr>
                <tr><td style="font-size:12px;line-height:1.6;color:#6b7280;word-break:break-all">%s<br><a href="%s" style="color:#1a73e8">%s</a></td></tr>
                </table>
                <p style="font-size:12px;color:#9ca3af;margin:16px 0 0">%s</p>
                </td></tr></table>
                </body></html>
                """.formatted(locale.getLanguage(), esc(subject), esc(title), text("email." + kind + ".intro", safeArgs, locale),
                href, esc(button), esc(expiry), esc(ignore), esc(hint), href, href, esc(footer));
        return new Email(subject, plain, html);
    }

    private String text(String key, Object[] args, Locale locale) {
        return messages.getMessage(key, args, key, locale);
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s, "UTF-8");
    }
}
