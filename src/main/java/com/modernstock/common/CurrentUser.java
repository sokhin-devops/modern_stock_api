package com.modernstock.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The logged-in account. The JWT subject is the user ID. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken) {
            return Long.valueOf(auth.getName());
        }
        return null;
    }

    public static Long id() {
        Long id = idOrNull();
        if (id == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return id;
    }
}
