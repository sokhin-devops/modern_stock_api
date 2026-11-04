package com.modernstock.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.modernstock.report.AlertService.Alerts;
import com.modernstock.report.DashboardService.DashboardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Reports")
@RestController
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;
    private final AlertService alerts;

    @Operation(summary = "Dashboard numbers by role",
            description = "Today's sales (a cashier's own), profit today and this month, the 30-day sales chart, top 10 "
                    + "products, debts and what waits for the member. Parts the role does not see are null.")
    @GetMapping("/dashboard")
    public DashboardResponse getDashboard() {
        return dashboard.dashboard();
    }

    @Operation(summary = "In-app alerts",
            description = "What needs attention for the member's role: expired and expiring batches (30/60/90 days), low "
                    + "stock, approvals, transfers to receive, open counts, overdue invoices and bills. Only kinds with a count.")
    @GetMapping("/alerts")
    public Alerts getAlerts() {
        return alerts.alerts();
    }
}
