package com.modernstock.business;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.modernstock.common.ApiException;

/**
 * The open business and branch for the current request, set by {@link BusinessContextFilter}
 * from the X-Business-Id and X-Branch-Id headers (PLAIN.md 11.11).
 */
public final class BusinessContext {

    /**
     * @param memberBranchIds  the branches the member works in, or null when the member may use all branches
     * @param selectedBranchId the branch chosen in the top bar, or null for "All branches" (all of the member's)
     */
    public record Context(Long businessId, Long userId, Long memberId, String roleCode,
                          Set<Long> memberBranchIds, Long selectedBranchId, Set<String> permissions) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    /** Whether a branch is read-only (set by ReadOnlyBranches at start; none are before then). */
    private static volatile Predicate<Long> readOnly = id -> false;

    private BusinessContext() {
    }

    public static void set(Context context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Context> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Context require() {
        return current().orElseThrow(() -> ApiException.badRequest("BUSINESS_HEADER_REQUIRED"));
    }

    /** The open business, or null outside a business request. */
    public static Long businessId() {
        return current().map(Context::businessId).orElse(null);
    }

    public static boolean has(String permission) {
        return current().map(c -> c.permissions().contains(permission)).orElse(false);
    }

    public static boolean canAccessBranch(Long branchId) {
        Context c = require();
        return c.memberBranchIds() == null || c.memberBranchIds().contains(branchId);
    }

    public static void requireBranch(Long branchId) {
        if (!canAccessBranch(branchId)) {
            throw ApiException.forbidden("BRANCH_ACCESS_DENIED");
        }
    }

    /** The branches a request reads: the one picked in the top bar, else the member's own, else null (all). Filters lists. */
    public static Set<Long> branchFilter() {
        Context c = require();
        return c.selectedBranchId() != null ? Set.of(c.selectedBranchId()) : c.memberBranchIds();
    }

    /**
     * Branch for a new document: the one in the request, else the selected branch. "All branches" needs an explicit one.
     * A read-only branch (above the plan's limit after a plan ended) takes no new work: 422 BRANCH_READ_ONLY. Work
     * already started there, such as receiving a transfer in transit, goes through requireBranch and still works.
     */
    public static Long branchForWrite(Long requested) {
        Context c = require();
        Long branchId = requested != null ? requested : c.selectedBranchId();
        if (branchId == null) {
            throw ApiException.badRequest("BRANCH_REQUIRED");
        }
        requireBranch(branchId);
        if (readOnly.test(branchId)) {
            throw ApiException.rule("BRANCH_READ_ONLY");
        }
        return branchId;
    }

    static void readOnlyCheck(Predicate<Long> check) {
        readOnly = check;
    }

    /** Runs code inside a business, for jobs and demo data that have no HTTP request. */
    public static <T> T runAs(Context context, Supplier<T> work) {
        Context previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
