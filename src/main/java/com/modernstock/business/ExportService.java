package com.modernstock.business;

import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;

/**
 * Everything the business has, as plain tables (FR-7.12): the web app writes them into one Excel file, a sheet per
 * table. Column names are keys the app translates; dates and times are Phnom Penh time. Read in one transaction, so
 * the tables agree with each other.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    public record Sheet(@Schema(description = "Sheet key, e.g. products") String key,
                        @Schema(description = "Column keys, e.g. sku, nameEn") List<String> columns,
                        List<List<Object>> rows) {
    }

    public record Export(String businessName, Instant exportedAt, List<Sheet> sheets) {
    }

    private static final ZoneId PHNOM_PENH = ZoneId.of("Asia/Phnom_Penh");

    /** Sheet key → query; the column labels of the query are the column keys. */
    private static final Map<String, String> SHEETS = new LinkedHashMap<>();

    static {
        SHEETS.put("products", """
                select p.sku as "sku", p.barcode as "barcode", p.name_en as "nameEn", p.name_km as "nameKm",
                       c.name_en as "category", u.symbol as "unit", p.sell_price as "sellPrice", p.avg_cost as "avgCost",
                       p.reorder_level as "reorderLevel", p.track_expiry as "trackExpiry", p.vat_exempt as "vatExempt", p.is_active as "active"
                  from products p left join categories c on c.id = p.category_id join units u on u.id = p.base_unit_id
                 where p.business_id = :b order by p.name_en""");
        SHEETS.put("stock", """
                select p.sku as "sku", p.name_en as "product", br.name_en as "branch", w.name_en as "warehouse", u.symbol as "unit",
                       l.qty_on_hand as "qty", round(l.qty_on_hand * p.avg_cost, 2) as "value"
                  from stock_levels l join products p on p.id = l.product_id join units u on u.id = p.base_unit_id
                  join warehouses w on w.id = l.warehouse_id join branches br on br.id = w.branch_id
                 where l.business_id = :b and l.qty_on_hand <> 0 order by p.name_en, br.name_en, w.name_en""");
        SHEETS.put("batches", """
                select p.sku as "sku", p.name_en as "product", br.name_en as "branch", w.name_en as "warehouse", s.batch_no as "batchNo",
                       s.expiry_date as "expiryDate", s.qty_on_hand as "qty", s.unit_cost as "unitCost"
                  from stock_batches s join products p on p.id = s.product_id join warehouses w on w.id = s.warehouse_id
                  join branches br on br.id = w.branch_id
                 where s.business_id = :b and s.qty_on_hand > 0 order by s.expiry_date nulls last, p.name_en""");
        SHEETS.put("customers", """
                select c.name as "name", c.type as "type", c.phone as "phone", c.address as "address", c.vat_tin as "vatTin",
                       c.credit_limit as "creditLimit", c.payment_terms_days as "paymentTermsDays", c.opening_balance as "openingBalance",
                       c.is_active as "active"
                  from customers c where c.business_id = :b order by c.name""");
        SHEETS.put("suppliers", """
                select s.name as "name", s.contact_person as "contactPerson", s.phone as "phone", s.telegram as "telegram",
                       s.address as "address", s.vat_tin as "vatTin", s.payment_terms_days as "paymentTermsDays",
                       s.opening_balance as "openingBalance", s.is_active as "active"
                  from suppliers s where s.business_id = :b order by s.name""");
        SHEETS.put("salesReceipts", """
                select r.number as "number", r.sold_at as "date", br.name_en as "branch", u.full_name as "cashier", c.name as "customer",
                       r.subtotal as "subtotal", r.discount as "discount", r.vat as "vat", r.total as "total", r.total_khr as "totalKhr",
                       r.status as "status"
                  from sales_receipts r join branches br on br.id = r.branch_id join users u on u.id = r.cashier_id
                  left join customers c on c.id = r.customer_id
                 where r.business_id = :b order by r.sold_at""");
        SHEETS.put("saleLines", """
                select coalesce(r.number, d.number) as "number", coalesce(r.sold_at, d.delivered_at) as "date", p.sku as "sku",
                       p.name_en as "product", i.qty as "qty", un.symbol as "unit", i.unit_price as "unitPrice", i.discount as "discount",
                       round(i.qty * i.unit_price - i.discount, 2) as "total", round(i.base_qty * i.cost_at_sale, 2) as "cost"
                  from sale_items i join products p on p.id = i.product_id join units un on un.id = i.unit_id
                  left join sales_receipts r on r.id = i.sales_receipt_id left join deliveries d on d.id = i.delivery_id
                 where i.business_id = :b and coalesce(r.status, 'POSTED') = 'POSTED' order by 2, 1""");
        SHEETS.put("invoices", """
                select i.number as "number", i.type as "type", i.invoice_date as "date", i.due_date as "dueDate", c.name as "customer",
                       br.name_en as "branch", i.subtotal as "subtotal", i.discount as "discount", i.vat as "vat", i.total as "total",
                       i.paid_amount as "paid", i.total - i.paid_amount as "balance", i.status as "status", i.note as "note"
                  from invoices i join customers c on c.id = i.customer_id join branches br on br.id = i.branch_id
                 where i.business_id = :b order by i.invoice_date, i.number""");
        SHEETS.put("payments", """
                select p.number as "number", p.paid_at as "date", p.direction as "direction", p.party_type as "partyType",
                       coalesce(c.name, s.name) as "party", br.name_en as "branch", p.method as "method", p.currency as "currency",
                       p.amount as "amount", p.amount_usd as "amountUsd", p.reference as "reference", p.status as "status"
                  from payments p join branches br on br.id = p.branch_id
                  left join customers c on p.party_type = 'CUSTOMER' and c.id = p.party_id
                  left join suppliers s on p.party_type = 'SUPPLIER' and s.id = p.party_id
                 where p.business_id = :b order by p.paid_at""");
        SHEETS.put("creditNotes", """
                select n.number as "number", n.issued_at as "date", c.name as "customer", n.amount as "amount", n.used_amount as "used",
                       n.refunded_amount as "refunded", n.status as "status", n.reason as "reason"
                  from credit_notes n left join customers c on c.id = n.customer_id
                 where n.business_id = :b order by n.issued_at""");
        SHEETS.put("purchaseOrders", """
                select o.number as "number", o.order_date as "date", s.name as "supplier", br.name_en as "branch", o.subtotal as "subtotal",
                       o.discount as "discount", o.vat as "vat", o.total as "total", o.status as "status"
                  from purchase_orders o join suppliers s on s.id = o.supplier_id join branches br on br.id = o.branch_id
                 where o.business_id = :b order by o.order_date, o.number""");
        SHEETS.put("bills", """
                select b.number as "number", b.supplier_invoice_no as "supplierInvoiceNo", b.bill_date as "date", b.due_date as "dueDate",
                       s.name as "supplier", br.name_en as "branch", b.total as "total", b.paid_amount as "paid",
                       b.total - b.paid_amount as "balance", b.status as "status"
                  from bills b join suppliers s on s.id = b.supplier_id join branches br on br.id = b.branch_id
                 where b.business_id = :b order by b.bill_date, b.number""");
        SHEETS.put("expenses", """
                select e.number as "number", e.expense_date as "date", ec.name_en as "category", br.name_en as "branch", e.method as "method",
                       e.currency as "currency", e.amount as "amount", e.amount_usd as "amountUsd", e.reference as "reference",
                       e.status as "status", e.note as "note"
                  from expenses e join expense_categories ec on ec.id = e.category_id join branches br on br.id = e.branch_id
                 where e.business_id = :b order by e.expense_date, e.number""");
        SHEETS.put("transfers", """
                select t.number as "number", t.kind as "kind", fw.name_en as "from", tw.name_en as "to", t.status as "status",
                       t.sent_at as "sentAt", t.received_at as "receivedAt", t.note as "note"
                  from stock_transfers t join warehouses fw on fw.id = t.from_warehouse_id join warehouses tw on tw.id = t.to_warehouse_id
                 where t.business_id = :b order by t.id""");
        SHEETS.put("adjustments", """
                select a.number as "number", a.created_at as "date", w.name_en as "warehouse", a.kind as "kind", a.reason as "reason",
                       a.status as "status", a.note as "note"
                  from stock_adjustments a join warehouses w on w.id = a.warehouse_id
                 where a.business_id = :b order by a.id""");
        SHEETS.put("movements", """
                select m.created_at as "date", p.sku as "sku", p.name_en as "product", w.name_en as "warehouse", m.type as "type",
                       m.qty as "qty", m.balance_after as "balanceAfter", m.unit_cost as "unitCost", m.reference_type as "referenceType"
                  from stock_movements m join products p on p.id = m.product_id join warehouses w on w.id = m.warehouse_id
                 where m.business_id = :b order by m.id""");
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessRepository businesses;

    @Transactional(readOnly = true)
    public Export export() {
        Long businessId = BusinessContext.businessId();
        MapSqlParameterSource p = new MapSqlParameterSource("b", businessId);
        List<Sheet> sheets = new ArrayList<>();
        SHEETS.forEach((key, sql) -> sheets.add(jdbc.query(sql, p, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns.size());
                for (int i = 1; i <= columns.size(); i++) {
                    Object v = rs.getObject(i);
                    // Times as Phnom Penh "yyyy-MM-dd HH:mm", the way people read them in Excel
                    Instant at = v instanceof Timestamp t ? t.toInstant() : v instanceof java.time.OffsetDateTime o ? o.toInstant() : null;
                    row.add(at != null ? at.atZone(PHNOM_PENH).toLocalDateTime().toString().replace('T', ' ').substring(0, 16)
                            : v instanceof java.sql.Date d ? d.toLocalDate().toString() : v);
                }
                rows.add(row);
            }
            return new Sheet(key, columns, rows);
        })));
        String name = businesses.findById(businessId).map(Business::getNameEn).orElse("");
        return new Export(name, Instant.now(), sheets);
    }
}
