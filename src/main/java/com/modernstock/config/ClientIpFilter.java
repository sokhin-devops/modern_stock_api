package com.modernstock.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The visitor's address for the rate limits and the activity log. In the cloud every request comes through Cloudflare
 * and then Render's proxy, so the connection's own address is a proxy's, and X-Forwarded-For can start with whatever
 * the visitor sent. Cloudflare puts the real address in a header of its own (CF-Connecting-IP, replacing any the
 * visitor sent); with app.client-ip-header set, getRemoteAddr() returns that header.
 * <p>
 * Set it only where nobody can reach the API without Cloudflare (DevSecOps.md 6.7: the onrender.com address is off).
 * Empty (the default, a PC, the tests): the connection's address, as before.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientIpFilter extends OncePerRequestFilter {

    private final String header;

    public ClientIpFilter(@Value("${app.client-ip-header:}") String header) {
        this.header = header;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return header.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getHeader(header);
        if (ip == null || ip.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String visitor = ip.strip();
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getRemoteAddr() {
                return visitor;
            }
        }, response);
    }
}
