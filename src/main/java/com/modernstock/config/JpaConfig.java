package com.modernstock.config;

import java.time.Instant;
import java.util.Optional;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.modernstock.business.BusinessTenantResolver;
import com.modernstock.common.CurrentUser;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditor", dateTimeProviderRef = "auditingTime")
public class JpaConfig {

    /** created_by / updated_by = the logged-in account. */
    @Bean
    AuditorAware<Long> auditor() {
        return () -> Optional.ofNullable(CurrentUser.idOrNull());
    }

    @Bean
    DateTimeProvider auditingTime() {
        return () -> Optional.of(Instant.now());
    }

    /** Connects Hibernate's @TenantId to the open business. */
    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer(BusinessTenantResolver resolver) {
        return props -> props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
