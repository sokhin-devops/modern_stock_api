package com.modernstock.report;

import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;

/**
 * What a member's reports cover (PLAIN.md 14): Owner and Manager see all sales, profit and debts (they manage every
 * cashier's money); a cashier sees only their own sales; anyone who sees stock gets the stock reports. Branches follow
 * BranchScope, so a Manager sees only their branch.
 */
final class ReportScope {

    private ReportScope() {
    }

    /** Everyone's sales, profit and the money reports. */
    static boolean money() {
        return BusinessContext.has("cash_shift:all");
    }

    /** Sales, limited to the member's own when they are not allowed to see everyone's. */
    static boolean sales() {
        return money() || BusinessContext.has("sale:create");
    }

    static boolean stock() {
        return BusinessContext.has("stock:view");
    }

    /** Stock values and cost: only for members who manage products (as on the stock pages). */
    static boolean cost() {
        return BusinessContext.has("product:manage");
    }

    /** The cashier whose sales a report is limited to, or null for everyone's. */
    static Long ownCashier() {
        return money() ? null : BusinessContext.require().userId();
    }

    static void requireMoney() {
        if (!money()) {
            throw ApiException.forbidden("REPORT_NOT_ALLOWED");
        }
    }

    static void requireSales() {
        if (!sales()) {
            throw ApiException.forbidden("REPORT_NOT_ALLOWED");
        }
    }

    static void requireStock() {
        if (!stock()) {
            throw ApiException.forbidden("REPORT_NOT_ALLOWED");
        }
    }
}
