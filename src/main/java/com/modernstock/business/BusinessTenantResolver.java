package com.modernstock.business;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/** Gives Hibernate the open business. Outside a business it returns 0, which matches no rows, so mistakes fail closed. */
@Component
public class BusinessTenantResolver implements CurrentTenantIdentifierResolver<Long> {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long businessId = BusinessContext.businessId();
        return businessId != null ? businessId : 0L;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
