package com.modernstock.business;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.modernstock.common.ApiException;

/**
 * The open business and branch for the current request, set by {@link BusinessContextFilter}
 * from the X-Business-Id and X-Branch-Id headers (PLAIN.md 11.11).
 */
public final class BusinessContext {

    /**
     * @param memberBranchId   the member's fixed branch, or null when the member may use all branches
     * @param selectedBranchId the branch chosen in the top bar, or null for "All branches"
     */
    public record Context(Long businessId, Long userId, Long memberId, String roleCode,
                          Long memberBranchId, Long selectedBranchId, Set<String> permissions) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

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
        return c.memberBranchId() == null || Objects.equals(c.memberBranchId(), branchId);
    }

    public static void requireBranch(Long branchId) {
        if (!canAccessBranch(branchId)) {
            throw ApiException.forbidden("BRANCH_ACCESS_DENIED");
        }
    }

    /** The branch a member can only see, or null when they see all branches. Used to filter lists. */
    public static Long branchFilter() {
        Context c = require();
        return c.selectedBranchId() != null ? c.selectedBranchId() : c.memberBranchId();
    }

    /** Branch for a new document: the one in the request, else the selected branch. "All branches" needs an explicit one. */
    public static Long branchForWrite(Long requested) {
        Context c = require();
        Long branchId = requested != null ? requested : c.selectedBranchId();
        if (branchId == null) {
            throw ApiException.badRequest("BRANCH_REQUIRED");
        }
        requireBranch(branchId);
        return branchId;
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
