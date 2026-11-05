package com.modernstock.inventory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.modernstock.business.BusinessContext;

import lombok.RequiredArgsConstructor;

/**
 * Stock reserved for sales orders (FR-3.21): what open orders (confirmed, partly delivered) still have to deliver, in
 * base units, in the order's warehouse. It stays on hand until delivered, but it is not free: transfers can't take it,
 * other orders and the POS are warned. Delivering the order itself uses its own reservation.
 */
@Service
@RequiredArgsConstructor
public class Reservations {

    private final NamedParameterJdbcTemplate jdbc;

    /** Product → base units reserved in these warehouses, leaving out one order (null = none). */
    public Map<Long, BigDecimal> reserved(Collection<Long> productIds, Collection<Long> warehouseIds, Long exceptOrderId) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (productIds.isEmpty() || warehouseIds == null || warehouseIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select i.product_id, sum((i.qty_ordered - i.qty_delivered) * coalesce(pu.factor, 1)) as reserved
                  from sales_order_items i join sales_orders o on o.id = i.sales_order_id
                  left join product_units pu on pu.product_id = i.product_id and pu.unit_id = i.unit_id
                 where o.business_id = :business and o.status in ('CONFIRMED', 'PARTIALLY_DELIVERED')
                   and o.warehouse_id in (:warehouses) and i.product_id in (:products) and i.qty_ordered > i.qty_delivered
                   and (cast(:except as bigint) is null or o.id <> :except)
                 group by i.product_id
                """, new MapSqlParameterSource()
                .addValue("business", BusinessContext.businessId())
                .addValue("warehouses", warehouseIds)
                .addValue("products", productIds)
                .addValue("except", exceptOrderId, java.sql.Types.BIGINT), rs -> {
            result.put(rs.getLong("product_id"), rs.getBigDecimal("reserved"));
        });
        return result;
    }
}
