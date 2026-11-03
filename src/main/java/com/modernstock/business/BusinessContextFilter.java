package com.modernstock.business;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.modernstock.common.ApiException;
import com.modernstock.common.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs after JWT validation. Reads X-Business-Id, checks that the account is an active member of an active
 * business, then adds the role's permissions as authorities so @PreAuthorize works (PLAIN.md 11.11).
 * Membership is checked on every request, so removing a member or changing a role works immediately.
 */
public class BusinessContextFilter extends OncePerRequestFilter {

    public static final String BUSINESS_HEADER = "X-Business-Id";
    public static final String BRANCH_HEADER = "X-Branch-Id";

    private static final List<String> ACCOUNT_PREFIXES =
            List.of("/auth/", "/me/", "/public/", "/admin/", "/swagger-ui", "/v3/api-docs", "/actuator");

    private static final String MEMBERSHIP_SQL = """
            SELECT m.id, m.branch_id, r.code, b.status, COALESCE(string_agg(p.code, ','), '') AS perms
            FROM business_members m
            JOIN businesses b ON b.id = m.business_id
            JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
            JOIN roles r ON r.id = m.role_id
            LEFT JOIN role_permissions rp ON rp.role_id = r.id
            LEFT JOIN permissions p ON p.id = rp.permission_id
            WHERE m.business_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'
            GROUP BY m.id, m.branch_id, r.code, b.status
            """;

    private record Membership(Long memberId, Long branchId, String roleCode, String businessStatus, Set<String> permissions) {
    }

    private final JdbcTemplate jdbc;
    private final ProblemWriter problems;

    public BusinessContextFilter(JdbcTemplate jdbc, ProblemWriter problems) {
        this.jdbc = jdbc;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "OPTIONS".equals(request.getMethod())
                || path.equals("/me")
                || path.equals("/swagger-ui.html")
                || ACCOUNT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            chain.doFilter(request, response); // not logged in: the authorization filter answers 401
            return;
        }
        try {
            Long businessId = parseId(request.getHeader(BUSINESS_HEADER), "BUSINESS_HEADER_REQUIRED");
            Long userId = Long.valueOf(jwtAuth.getName());
            Membership m = loadMembership(businessId, userId);
            Long selectedBranchId = resolveBranch(request.getHeader(BRANCH_HEADER), m, businessId);

            List<GrantedAuthority> authorities = new ArrayList<>(jwtAuth.getAuthorities());
            m.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + m.roleCode()));
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(new JwtAuthenticationToken(jwtAuth.getToken(), authorities, jwtAuth.getName()));
            SecurityContextHolder.setContext(securityContext);

            BusinessContext.set(new BusinessContext.Context(businessId, userId, m.memberId(), m.roleCode(),
                    m.branchId(), selectedBranchId, m.permissions()));
        } catch (ApiException e) {
            problems.write(request, response, e);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            BusinessContext.clear();
        }
    }

    private Membership loadMembership(Long businessId, Long userId) {
        List<Membership> rows = jdbc.query(MEMBERSHIP_SQL, (rs, i) -> new Membership(
                rs.getLong(1),
                (Long) rs.getObject(2),
                rs.getString(3),
                rs.getString(4),
                new HashSet<>(Arrays.stream(rs.getString(5).split(",")).filter(s -> !s.isBlank()).toList())),
                businessId, userId);
        if (rows.isEmpty()) {
            throw ApiException.forbidden("NOT_A_MEMBER");
        }
        Membership m = rows.get(0);
        if (!"ACTIVE".equals(m.businessStatus()) && !"SETTING_UP".equals(m.businessStatus())) {
            throw ApiException.forbidden("BUSINESS_NOT_ACTIVE");
        }
        return m;
    }

    /** A member with one branch always works in it; others pick a branch or "all" (null). */
    private Long resolveBranch(String header, Membership m, Long businessId) {
        boolean all = header == null || header.isBlank() || "all".equalsIgnoreCase(header.trim());
        if (m.branchId() != null) {
            if (!all && !m.branchId().equals(parseId(header, "BRANCH_ACCESS_DENIED"))) {
                throw ApiException.forbidden("BRANCH_ACCESS_DENIED");
            }
            return m.branchId();
        }
        if (all) {
            return null;
        }
        Long branchId = parseId(header, "BRANCH_ACCESS_DENIED");
        Integer found = jdbc.queryForObject("SELECT count(*) FROM branches WHERE id = ? AND business_id = ?",
                Integer.class, branchId, businessId);
        if (found == null || found == 0) {
            throw ApiException.forbidden("BRANCH_ACCESS_DENIED");
        }
        return branchId;
    }

    private static Long parseId(String value, String errorCode) {
        try {
            return Long.valueOf(value.trim());
        } catch (RuntimeException e) {
            throw "BRANCH_ACCESS_DENIED".equals(errorCode)
                    ? ApiException.forbidden(errorCode)
                    : ApiException.badRequest(errorCode);
        }
    }
}
