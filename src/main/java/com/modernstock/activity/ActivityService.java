package com.modernstock.activity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.PageResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

/**
 * The activity log (FR-6.22): who did what and when, with the old and new values of what changed (prices, voids, roles,
 * members, settings). Entries are written in the same transaction as the change, so a change that fails leaves none.
 */
@Service
@RequiredArgsConstructor
public class ActivityService {

    public record ActivityRow(Long id, Instant at, Long userId, String userName, String action, String entityType,
                              Long entityId, @Schema(description = "What it is about, as it was named then") String label,
                              Long branchId, String branchName,
                              @Schema(description = "Only the values that changed") Map<String, Object> oldValues,
                              Map<String, Object> newValues) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BranchScope scope;

    /** Values in the order given; nulls are allowed (Map.of refuses them). */
    public static Map<String, Object> values(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** A change: only the keys whose value changed are kept, and nothing is written when none did. */
    public void changed(String action, String entityType, Long entityId, String label, Long branchId,
                        Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (!same(before.get(key), after.get(key))) {
                oldValues.put(key, before.get(key));
                newValues.put(key, after.get(key));
            }
        }
        if (!newValues.isEmpty()) {
            record(action, entityType, entityId, label, branchId, oldValues, newValues);
        }
    }

    /** An action as it is: something created, voided, removed or posted. */
    public void record(String action, String entityType, Long entityId, String label, Long branchId,
                       Map<String, ?> oldValues, Map<String, ?> newValues) {
        BusinessContext.Context c = BusinessContext.require();
        jdbc.update("""
                insert into activity_logs (business_id, branch_id, user_id, action, entity_type, entity_id, label,
                                           old_values, new_values, ip_address)
                values (:business, :branch, :user, :action, :type, :entity, :label, cast(:old as jsonb), cast(:new as jsonb), :ip)
                """, new MapSqlParameterSource()
                .addValue("business", c.businessId())
                .addValue("branch", branchId)
                .addValue("user", c.userId())
                .addValue("action", action)
                .addValue("type", entityType)
                .addValue("entity", entityId)
                .addValue("label", label == null || label.length() <= 200 ? label : label.substring(0, 200))
                .addValue("old", write(oldValues))
                .addValue("new", write(newValues))
                .addValue("ip", ip()));
    }

    /**
     * Newest first. Members who see every branch also see business-wide entries; a member of one branch sees that
     * branch's entries.
     */
    @Transactional(readOnly = true)
    public PageResponse<ActivityRow> list(Long userId, String entityType, LocalDate from, LocalDate to, String q, Pageable pageable) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("branches", scope.branchIds())
                .addValue("wide", BusinessContext.require().memberBranchIds() == null)
                .addValue("user", userId, java.sql.Types.BIGINT)
                .addValue("type", entityType, java.sql.Types.VARCHAR)
                .addValue("from", from, java.sql.Types.DATE)
                .addValue("to", to, java.sql.Types.DATE)
                .addValue("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%", java.sql.Types.VARCHAR)
                .addValue("size", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String where = """
                 where a.business_id = :business
                   and (a.branch_id in (:branches) or (a.branch_id is null and :wide))
                   and (cast(:user as bigint) is null or a.user_id = :user)
                   and (cast(:type as varchar) is null or a.entity_type = :type)
                   and (cast(:from as date) is null or (a.created_at at time zone 'Asia/Phnom_Penh')::date >= :from)
                   and (cast(:to as date) is null or (a.created_at at time zone 'Asia/Phnom_Penh')::date <= :to)
                   and (cast(:q as varchar) is null or lower(coalesce(a.label, '')) like :q)
                """;
        Long total = jdbc.queryForObject("select count(*) from activity_logs a" + where, p, Long.class);
        List<ActivityRow> rows = jdbc.query("""
                select a.*, u.full_name, b.name_en as branch_name from activity_logs a
                  left join users u on u.id = a.user_id left join branches b on b.id = a.branch_id
                """ + where + " order by a.id desc limit :size offset :offset", p, (rs, n) -> new ActivityRow(
                rs.getLong("id"), rs.getTimestamp("created_at").toInstant(), (Long) rs.getObject("user_id"), rs.getString("full_name"),
                rs.getString("action"), rs.getString("entity_type"), (Long) rs.getObject("entity_id"), rs.getString("label"),
                (Long) rs.getObject("branch_id"), rs.getString("branch_name"), read(rs.getString("old_values")),
                read(rs.getString("new_values"))));
        return PageResponse.of(new PageImpl<>(rows, pageable, total == null ? 0 : total));
    }

    private String write(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> read(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static String ip() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest().getRemoteAddr() : null;
    }
}
