package com.modernstock.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BranchScope;
import com.modernstock.business.BusinessContext;
import com.modernstock.common.ApiException;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.PageResponse;
import com.modernstock.currency.CurrencyService;
import com.modernstock.money.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Expenses and their categories (PLAIN.md 11.9). Each expense keeps the exchange rate of its day. */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    public record CategoryRequest(@NotBlank @Size(max = 120) String nameEn, @Size(max = 120) String nameKm, Boolean active) {
    }

    public record CategoryResponse(Long id, String nameEn, String nameKm, boolean active) {
        static CategoryResponse from(ExpenseCategory c) {
            return new CategoryResponse(c.getId(), c.getNameEn(), c.getNameKm(), c.isActive());
        }
    }

    public record ExpenseRequest(
            @Schema(description = "Defaults to the selected branch") Long branchId,
            @NotNull Long categoryId,
            Long supplierId,
            @Schema(description = "Defaults to today") LocalDate expenseDate,
            @NotNull Payment.Method method,
            @NotNull Payment.Currency currency,
            @Schema(description = "USD with cents or whole riel") @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @Size(max = 60) String reference,
            @Size(max = 500) String note) {
    }

    public record ExpenseResponse(Long id, String number, Long branchId, Long categoryId, String categoryName, Long supplierId,
                                  LocalDate expenseDate, Payment.Method method, Payment.Currency currency, BigDecimal amount,
                                  BigDecimal exchangeRate, BigDecimal amountUsd, String reference, String note,
                                  Expense.Status status) {
    }

    /** First categories of a new business, so the first expense needs no setup. */
    private static final List<String[]> DEFAULTS = List.of(
            new String[] {"Rent", "ថ្លៃជួល"}, new String[] {"Electricity", "ថ្លៃភ្លើង"}, new String[] {"Water", "ថ្លៃទឹក"},
            new String[] {"Salaries", "ប្រាក់ខែ"}, new String[] {"Transport", "ការដឹកជញ្ជូន"}, new String[] {"Other", "ផ្សេងៗ"});

    private final ExpenseCategoryRepository categories;
    private final ActivityService activity;
    private final ExpenseRepository expenses;
    private final DocumentNumberService numbers;
    private final CurrencyService currency;
    private final BranchScope scope;

    @Transactional
    public List<CategoryResponse> categories() {
        if (categories.count() == 0) {
            DEFAULTS.forEach(d -> {
                ExpenseCategory c = new ExpenseCategory();
                c.setNameEn(d[0]);
                c.setNameKm(d[1]);
                categories.save(c);
            });
        }
        return categories.findAllByOrderByNameEnAsc().stream().map(CategoryResponse::from).toList();
    }

    @Transactional
    public CategoryResponse saveCategory(Long id, CategoryRequest req) {
        ExpenseCategory c = id == null ? new ExpenseCategory() : categories.get(id);
        c.setNameEn(req.nameEn().trim());
        c.setNameKm(req.nameKm());
        if (req.active() != null) {
            c.setActive(req.active());
        }
        return CategoryResponse.from(categories.save(c));
    }

    /** A category in use is switched off instead, so old expenses keep their category. */
    @Transactional
    public void deleteCategory(Long id) {
        ExpenseCategory c = categories.get(id);
        if (expenses.existsByCategoryId(id)) {
            c.setActive(false);
        } else {
            categories.delete(c);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> search(LocalDate from, LocalDate to, Long categoryId, Pageable pageable) {
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(2999, 12, 31) : to;
        List<ExpenseCategory> all = categories.findAll();
        return PageResponse.of(expenses.search(scope.branchIds(), start, end, categoryId, pageable), e -> toResponse(e, all));
    }

    @Transactional
    public ExpenseResponse create(ExpenseRequest req) {
        ExpenseCategory category = categories.get(req.categoryId());
        if (!category.isActive()) {
            throw ApiException.rule("CATEGORY_INACTIVE");
        }
        BigDecimal rate = currency.current().usdToKhr();
        Expense e = new Expense();
        e.setNumber(numbers.next(DocType.EXP));
        e.setBranchId(BusinessContext.branchForWrite(req.branchId()));
        e.setCategoryId(category.getId());
        e.setSupplierId(req.supplierId());
        e.setExpenseDate(req.expenseDate() == null ? LocalDate.now() : req.expenseDate());
        e.setMethod(req.method());
        e.setCurrency(req.currency());
        e.setAmount(req.amount());
        e.setExchangeRate(rate);
        e.setAmountUsd(req.currency() == Payment.Currency.USD ? req.amount() : CurrencyService.toUsd(req.amount(), rate));
        e.setReference(req.reference());
        e.setNote(req.note());
        return toResponse(expenses.save(e), List.of(category));
    }

    @Transactional
    public ExpenseResponse voidExpense(Long id) {
        Expense e = expenses.get(id);
        BusinessContext.requireBranch(e.getBranchId());
        if (e.getStatus() != Expense.Status.POSTED) {
            throw ApiException.rule("WRONG_STATUS", e.getStatus());
        }
        e.setStatus(Expense.Status.VOID);
        e.setVoidedAt(Instant.now());
        activity.record("EXPENSE_VOIDED", "EXPENSE", e.getId(), e.getNumber(), e.getBranchId(), null,
                ActivityService.values("amountUsd", e.getAmountUsd()));
        return toResponse(e, categories.findAll());
    }

    private ExpenseResponse toResponse(Expense e, List<ExpenseCategory> all) {
        String name = all.stream().filter(c -> c.getId().equals(e.getCategoryId())).map(ExpenseCategory::getNameEn).findFirst().orElse("");
        return new ExpenseResponse(e.getId(), e.getNumber(), e.getBranchId(), e.getCategoryId(), name, e.getSupplierId(),
                e.getExpenseDate(), e.getMethod(), e.getCurrency(), e.getAmount(), e.getExchangeRate(), e.getAmountUsd(),
                e.getReference(), e.getNote(), e.getStatus());
    }
}
