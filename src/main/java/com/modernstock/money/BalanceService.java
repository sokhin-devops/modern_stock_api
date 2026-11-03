package com.modernstock.money;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.business.BusinessContext;

import lombok.RequiredArgsConstructor;

/**
 * What customers owe and what the business owes suppliers (PLAIN.md 11.7, 11.8):
 * opening balance + open document amounts − money paid but not yet allocated (deposits, advances).
 * Native SQL is not filtered by @TenantId, so every query has business_id by hand.
 */
@Service
@RequiredArgsConstructor
public class BalanceService {

    private static final String CUSTOMER_SQL = """
            SELECT c.id,
                   c.opening_balance
                 + COALESCE((SELECT SUM(i.total - i.paid_amount) FROM invoices i
                             WHERE i.business_id = c.business_id AND i.customer_id = c.id AND i.status <> 'VOID'), 0)
                 - COALESCE((SELECT SUM(p.amount_usd) FROM payments p
                             WHERE p.business_id = c.business_id AND p.party_type = 'CUSTOMER' AND p.party_id = c.id
                               AND p.status = 'POSTED'), 0)
                 + COALESCE((SELECT SUM(a.amount) FROM allocations a JOIN payments p ON a.from_type = 'PAYMENT' AND a.from_id = p.id
                             WHERE p.business_id = c.business_id AND p.party_type = 'CUSTOMER' AND p.party_id = c.id
                               AND p.status = 'POSTED'), 0) AS balance
            FROM customers c
            WHERE c.business_id = :businessId AND c.id IN (:ids)
            """;

    private static final String SUPPLIER_SQL = """
            SELECT s.id,
                   s.opening_balance
                 + COALESCE((SELECT SUM(b.total - b.paid_amount) FROM bills b
                             WHERE b.business_id = s.business_id AND b.supplier_id = s.id AND b.status <> 'VOID'), 0)
                 - COALESCE((SELECT SUM(p.amount_usd) FROM payments p
                             WHERE p.business_id = s.business_id AND p.party_type = 'SUPPLIER' AND p.party_id = s.id
                               AND p.status = 'POSTED'), 0)
                 + COALESCE((SELECT SUM(a.amount) FROM allocations a JOIN payments p ON a.from_type = 'PAYMENT' AND a.from_id = p.id
                             WHERE p.business_id = s.business_id AND p.party_type = 'SUPPLIER' AND p.party_id = s.id
                               AND p.status = 'POSTED'), 0) AS balance
            FROM suppliers s
            WHERE s.business_id = :businessId AND s.id IN (:ids)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> customers(Collection<Long> ids) {
        return query(CUSTOMER_SQL, ids);
    }

    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> suppliers(Collection<Long> ids) {
        return query(SUPPLIER_SQL, ids);
    }

    public BigDecimal customer(Long id) {
        return customers(java.util.List.of(id)).getOrDefault(id, BigDecimal.ZERO);
    }

    private Map<Long, BigDecimal> query(String sql, Collection<Long> ids) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", BusinessContext.require().businessId())
                .addValue("ids", ids);
        jdbc.query(sql, params, rs -> {
            result.put(rs.getLong(1), rs.getBigDecimal(2));
        });
        return result;
    }
}
