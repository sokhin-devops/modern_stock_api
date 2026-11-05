package com.modernstock.report;

import java.time.LocalDate;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.business.ExpiryWindows;
import com.modernstock.common.ApiException;
import com.modernstock.report.MoneyReportService.Party;
import com.modernstock.report.MoneyReportService.PurchaseReport;
import com.modernstock.report.MoneyReportService.PurchasesBy;
import com.modernstock.report.MoneyReportService.ShiftReport;
import com.modernstock.report.MoneyReportService.Statement;
import com.modernstock.report.MoneyReportService.VatReport;
import com.modernstock.report.ReportService.AbcReport;
import com.modernstock.report.ReportService.CustomerDebtReport;
import com.modernstock.report.ReportService.ProfitReport;
import com.modernstock.report.ReportService.SalesBy;
import com.modernstock.report.ReportService.SalesReport;
import com.modernstock.report.ReportService.SupplierDebtReport;
import com.modernstock.report.StockReportService.CountReport;
import com.modernstock.report.StockReportService.ExpiryReport;
import com.modernstock.report.StockReportService.LossReport;
import com.modernstock.report.StockReportService.OnHandReport;
import com.modernstock.report.StockReportService.ReorderReport;
import com.modernstock.report.StockReportService.SlowReport;
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
    private final MoneyReportService money;
    private final ExpiryWindows expiryWindows;

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

    @Operation(summary = "Near expiry and expired",
            description = "Batches in stock expiring within one of the business's windows (30, 60 or 90 days unless changed in "
                    + "Settings › Rules; the first by default), and expired ones.")
    @GetMapping("/expiry")
    @PreAuthorize("hasAuthority('report:view')")
    public ExpiryReport expiryReport(@RequestParam(required = false) Integer days) {
        List<Integer> windows = expiryWindows.current();
        int within = days == null ? windows.get(0) : days;
        if (!windows.contains(within)) {
            throw ApiException.badRequest("EXPIRY_DAYS_INVALID", windows.get(0), windows.get(1), windows.get(2));
        }
        return stock.expiry(within, windows);
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

    @Operation(summary = "Adjustments and losses", description = "Stock taken out by adjustments (by reason), short counts and damaged "
            + "returns, valued at cost for members who see cost. Defaults: the last 30 days.")
    @GetMapping("/losses")
    @PreAuthorize("hasAuthority('report:view')")
    public LossReport lossesReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        return stock.losses(from == null ? end.minusDays(29) : from, end);
    }

    @Operation(summary = "Slow-moving and dead stock", description = "Products in stock not sold for 30, 60, 90 or 180 days, or never.")
    @GetMapping("/slow-moving")
    @PreAuthorize("hasAuthority('report:view')")
    public SlowReport slowMovingReport(@RequestParam(defaultValue = "60") int days) {
        if (days != 30 && days != 60 && days != 90 && days != 180) {
            throw ApiException.badRequest("SLOW_DAYS_INVALID");
        }
        return stock.slowMoving(days);
    }

    @Operation(summary = "Cash shifts", description = "Closed shifts: expected and counted cash in USD and KHR, over or short, voids.")
    @GetMapping("/cash-shifts")
    @PreAuthorize("hasAuthority('report:view')")
    public ShiftReport cashShiftReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        return money.cashShifts(from == null ? end.minusDays(29) : from, end);
    }

    @Operation(summary = "VAT summary", description = "VAT on sales (less returns) and on purchases per month. Defaults: the last 12 months.")
    @GetMapping("/vat")
    @PreAuthorize("hasAuthority('report:view')")
    public VatReport vatReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        return money.vat(from == null ? end.withDayOfMonth(1).minusMonths(11) : from, end);
    }

    @Operation(summary = "Purchases", description = "Goods received less returns, by SUPPLIER or PRODUCT. Defaults: the last 30 days.")
    @GetMapping("/purchases")
    @PreAuthorize("hasAuthority('report:view')")
    public PurchaseReport purchasesReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                          @RequestParam(defaultValue = "SUPPLIER") PurchasesBy by) {
        LocalDate end = end(to);
        return money.purchases(from == null ? end.minusDays(29) : from, end, by);
    }

    @Operation(summary = "Customer or supplier statement",
            description = "Every invoice (bill), payment, credit and refund with a running balance. Defaults: the last 90 days.")
    @GetMapping("/statement")
    @PreAuthorize("hasAuthority('report:view')")
    public Statement statement(@RequestParam Party party, @RequestParam Long id, @RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {
        LocalDate end = end(to);
        return money.statement(party, id, from == null ? end.minusDays(89) : from, end);
    }

    private static LocalDate end(LocalDate to) {
        return to == null ? ReportService.today() : to;
    }
}
