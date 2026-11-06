package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.modernstock.notification.EmailComposer;
import com.modernstock.notification.EmailComposer.Email;

/** The account emails: the reader's language, the link in both versions, and names that can't inject HTML. */
class EmailComposerTest {

    private final EmailComposer composer = new EmailComposer(messages());

    @Test
    void englishAndKhmer() {
        Email en = composer.verify("en", "Sokha", "https://app.example/verify-email?token=abc");
        assertThat(en.subject()).isEqualTo("Confirm your email for ModernStock");
        assertThat(en.text()).contains("Hello Sokha").contains("https://app.example/verify-email?token=abc");
        assertThat(en.html()).contains("<html lang=\"en\">").contains("href=\"https://app.example/verify-email?token=abc\"");

        Email km = composer.reset("km", "Dara", "https://app.example/reset-password?token=xyz");
        assertThat(km.subject()).contains("ពាក្យសម្ងាត់");
        assertThat(km.text()).contains("Dara").contains("token=xyz");
        assertThat(km.html()).contains("<html lang=\"km\">");
    }

    @Test
    void namesAreEscaped() {
        Email inv = composer.invitation("en", "<b>Boss</b>", "Shop & <script>x</script>", "https://app.example/invite/k?a=1&b=2");
        assertThat(inv.html()).doesNotContain("<script>").doesNotContain("<b>Boss")
                .contains("&lt;b&gt;Boss&lt;/b&gt;").contains("Shop &amp; &lt;script&gt;")
                .contains("href=\"https://app.example/invite/k?a=1&amp;b=2\"");
        assertThat(inv.subject()).isEqualTo("<b>Boss</b> invited you to ModernStock");   // plain text, not HTML
    }

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
        return source;
    }
}
