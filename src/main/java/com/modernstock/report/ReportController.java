package com.modernstock.report;

import java.time.LocalDate;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.common.ApiException;
import com.modernstock.report.ReportService.AbcReport;
import com.modernstock.report.ReportService.CustomerDebtReport;
import com.modernstock.report.ReportService.ProfitReport;
import com.modernstock.report.ReportService.SalesBy;
import com.modernstock.report.ReportService.SalesReport;
import com.modernstock.report.ReportService.SupplierDebtReport;
import com.modernstock.report.StockReportService.CountReport;
import com.modernstock.report.StockReportService.ExpiryReport;
import com.modernstock.report.StockReportService.OnHandReport;
import com.modernstock.report.StockReportService.ReorderReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The reports of PLAIN.md 14. Every report covers the branch selected in the top bar (or all the member sees).
 * Sales reports of a cashier cover their own sales; money reports are for Owners and Managers (403 REPORT_NOT_ALLOWED).
 */
@Tag(name = "Reports")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;
    private final StockReportService stock;

    @Operation(summary = "Sales summary and gross profit",
            description = "Grouped by DAY, PRODUCT, CATEGORY, BRANCH or CASHIER. Cost, profit and margin only for Owners and "
                    + "Managers. Defaults: the last 30 days. At most one year.")
    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('report:view')")
    public SalesReport salesReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                   @RequestParam(defaultValue = "DAY") SalesBy by) {
        LocalDate end = end(to);
        return reports.sales(from == null ? end.minusDays(29) : from, end, by);
    }

    @Operation(summary = "Profit and loss", description = "Sales revenue, cost of goods sold, expenses and net profit. Defaults: this month.")
    @GetMapping("/profit")
    @PreAuthorize("hasAuthority('report:view')")
    public ProfitReport profitReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        return reports.profit(from == null ? end.withDayOfMonth(1) : from, end);
    }

    @Operation(summary = "Customer debt", description = "What each customer owes, opening balance included, and how much is past due.")
    @GetMapping("/customer-debt")
    @PreAuthorize("hasAuthority('report:view')")
    public CustomerDebtReport customerDebtReport() {
        return reports.customerDebt();
    }

    @Operation(summary = "Supplier debt", description = "What the business owes each supplier, bills due in 7 days and past due.")
    @GetMapping("/supplier-debt")
    @PreAuthorize("hasAuthority('report:view')")
    public SupplierDebtReport supplierDebtReport() {
        return reports.supplierDebt();
    }

    @Operation(summary = "ABC analysis", description = "Products ranked by the value sold (at cost): A = first 80%, B = next 15%, "
            + "C = last 5%. Defaults: the last 12 months.")
    @GetMapping("/abc")
    @PreAuthorize("hasAuthority('report:view')")
    public AbcReport abcReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        return reports.abc(from == null ? end.minusYears(1).plusDays(1) : from, end);
    }

    @Operation(summary = "Stock on hand", description = "Quantity and value per product; stock in transit as its own line.")
    @GetMapping("/stock-on-hand")
    @PreAuthorize("hasAuthority('report:view')")
    public OnHandReport stockOnHandReport(@RequestParam(required = false) Long categoryId) {
        return stock.onHand(categoryId);
    }

    @Operation(summary = "Low stock and reorder suggestions",
            description = "Products at or below their reorder level, with a suggested order for the lead time plus 14 days of sales.")
    @GetMapping("/reorder")
    @PreAuthorize("hasAuthority('report:view')")
    public ReorderReport reorderReport() {
        return stock.reorder();
    }

    @Operation(summary = "Near expiry and expired", description = "Batches in stock expiring within 30, 60 or 90 days, and expired ones.")
    @GetMapping("/expiry")
    @PreAuthorize("hasAuthority('report:view')")
    public ExpiryReport expiryReport(@RequestParam(defaultValue = "30") int days) {
        if (days != 30 && days != 60 && days != 90) {
            throw ApiException.badRequest("EXPIRY_DAYS_INVALID");
        }
        return stock.expiry(days);
    }

    @Operation(summary = "Stock count differences", description = "Counted vs system quantity in counts posted in the period. Defaults: the last 30 days.")
    @GetMapping("/count-differences")
    @PreAuthorize("hasAuthority('report:view')")
    public CountReport countDifferencesReport(@RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        LocalDate start = from == null ? end.minusDays(29) : from;
        reports.params(start, end); // the same date rules as every report
        return stock.countDifferences(start, end);
    }

    private static LocalDate end(LocalDate to) {
        return to == null ? ReportService.today() : to;
    }
}
