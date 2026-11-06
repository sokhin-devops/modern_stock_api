package com.modernstock.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modernstock.business.BusinessContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * In-app notifications (PLAIN.md 10.2, 11.10): stored per member, made from events (InAppNotifications), read in the bell.
 * Native SQL is not filtered by @TenantId, so every query has business_id by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    public record NotificationRow(Long id,
                                  @Schema(description = "APPROVAL_ADJUSTMENT, APPROVAL_STOCK_COUNT, APPROVAL_SALES_RETURN, "
                                          + "APPROVAL_PURCHASE_RETURN, TRANSFER_SENT, SHIFT_SHORT, SALE_VOIDED, STOCK_OUT")
                                  String type,
                                  @Schema(description = "Values for the text, e.g. number, amount, product") Map<String, Object> params,
                                  String link, Instant createdAt, boolean read) {
    }

    public record Notifications(@Schema(description = "Not read yet, of all of them") long unread, List<NotificationRow> items) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * One notification for every active member of the business who has one of {@code permissions} and works in
     * {@code branchId} (null: any branch), except {@code exceptUser} (who did it).
     */
    @Transactional
    public int notify(Long businessId, Long branchId, List<String> permissions, Long exceptUser, String type, Map<String, Object> params,
                      String link) {
        String body;
        try {
            body = json.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            body = "{}";
        }
        return jdbc.update("""
                insert into notifications (business_id, user_id, type, params, link)
                select distinct :business, m.user_id, :type, :params, :link
                  from business_members m
                  join users u on u.id = m.user_id and u.status = 'ACTIVE'
                  join role_permissions rp on rp.role_id = m.role_id
                  join permissions p on p.id = rp.permission_id and p.code in (:permissions)
                 where m.business_id = :business and m.status = 'ACTIVE'
                   and (cast(:except as bigint) is null or m.user_id <> :except)
                   and (cast(:branch as bigint) is null or m.branch_id is null or m.branch_id = :branch
                        or exists (select 1 from member_branches mb where mb.member_id = m.id and mb.branch_id = :branch))
                """, new MapSqlParameterSource()
                .addValue("business", businessId)
                .addValue("type", type)
                .addValue("params", body)
                .addValue("link", link)
                .addValue("permissions", permissions)
                .addValue("except", exceptUser, java.sql.Types.BIGINT)
                .addValue("branch", branchId, java.sql.Types.BIGINT));
    }

    /** The member's latest notifications in the open business, newest first. */
    @Transactional(readOnly = true)
    public Notifications mine(int size) {
        MapSqlParameterSource p = me().addValue("size", Math.max(1, Math.min(size, 100)));
        Long unread = jdbc.queryForObject("select count(*) from notifications where business_id = :business and user_id = :user and read_at is null",
                p, Long.class);
        List<NotificationRow> items = new ArrayList<>();
        jdbc.query("""
                select id, type, params, link, created_at, read_at from notifications
                 where business_id = :business and user_id = :user order by created_at desc, id desc limit :size
                """, p, rs -> {
            Map<String, Object> params;
            try {
                params = json.readValue(rs.getString("params"), MAP);
            } catch (JsonProcessingException e) {
                params = Map.of();
            }
            items.add(new NotificationRow(rs.getLong("id"), rs.getString("type"), params, rs.getString("link"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") != null));
        });
        return new Notifications(unread == null ? 0 : unread, items);
    }

    /** One of the member's notifications, or all of them (id null), read now. */
    @Transactional
    public Notifications markRead(Long id) {
        MapSqlParameterSource p = me().addValue("id", id, java.sql.Types.BIGINT);
        jdbc.update("""
                update notifications set read_at = now()
                 where business_id = :business and user_id = :user and read_at is null and (cast(:id as bigint) is null or id = :id)
                """, p);
        return mine(20);
    }

    /** Read notifications older than 90 days are let go, every night. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Phnom_Penh")
    public void cleanUp() {
        int gone = jdbc.update("delete from notifications where read_at is not null and created_at < now() - interval '90 days'",
                new MapSqlParameterSource());
        if (gone > 0) {
            log.info("Removed {} old read notifications", gone);
        }
    }

    private static MapSqlParameterSource me() {
        return new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("user", BusinessContext.require().userId());
    }
}
