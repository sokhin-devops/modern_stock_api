package com.modernstock.expense;

import java.time.LocalDate;
import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.modernstock.common.FileStorage;
import com.modernstock.common.PageResponse;
import com.modernstock.expense.ExpenseService.CategoryRequest;
import com.modernstock.expense.ExpenseService.CategoryResponse;
import com.modernstock.expense.ExpenseService.ExpenseRequest;
import com.modernstock.expense.ExpenseService.ExpenseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Expenses")
@RestController
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenses;
    private final FileStorage files;

    @Operation(summary = "Expense categories", description = "A new business gets six to start with.")
    @GetMapping("/expense-categories")
    @PreAuthorize("hasAuthority('expense:manage')")
    public List<CategoryResponse> listExpenseCategories() {
        return expenses.categories();
    }

    @PostMapping("/expense-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('expense:manage')")
    public CategoryResponse createExpenseCategory(@Valid @RequestBody CategoryRequest req) {
        return expenses.saveCategory(null, req);
    }

    @PutMapping("/expense-categories/{id}")
    @PreAuthorize("hasAuthority('expense:manage')")
    public CategoryResponse updateExpenseCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return expenses.saveCategory(id, req);
    }

    @Operation(summary = "Delete an expense category", description = "Switched off instead when expenses use it.")
    @DeleteMapping("/expense-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('expense:manage')")
    public void deleteExpenseCategory(@PathVariable Long id) {
        expenses.deleteCategory(id);
    }

    @Operation(summary = "Expenses", description = "Newest first, in the branches you see; from/to are expense dates.")
    @GetMapping("/expenses")
    @PreAuthorize("hasAuthority('expense:manage')")
    public PageResponse<ExpenseResponse> listExpenses(@RequestParam(required = false) LocalDate from,
                                                      @RequestParam(required = false) LocalDate to,
                                                      @RequestParam(required = false) Long categoryId,
                                                      @ParameterObject Pageable pageable) {
        return expenses.search(from, to, categoryId, pageable);
    }

    @Operation(summary = "Record an expense")
    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('expense:manage')")
    public ExpenseResponse createExpense(@Valid @RequestBody ExpenseRequest req) {
        return expenses.create(req);
    }

    @Operation(summary = "The photo of the receipt", description = "FR-2.10: private, not cached; 404 without one.")
    @GetMapping("/expenses/{id}/photo")
    @PreAuthorize("hasAuthority('expense:manage')")
    public ResponseEntity<Resource> expensePhoto(@PathVariable Long id) {
        return files.privately(expenses.photo(id));
    }

    @Operation(summary = "Attach a photo of the receipt", description = "JPEG, PNG or WebP up to 2 MB; replaces the one before.")
    @PostMapping(value = "/expenses/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('expense:manage')")
    public ExpenseResponse attachExpensePhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return expenses.setPhoto(id, file);
    }

    @DeleteMapping("/expenses/{id}/photo")
    @PreAuthorize("hasAuthority('expense:manage')")
    public ExpenseResponse removeExpensePhoto(@PathVariable Long id) {
        return expenses.setPhoto(id, null);
    }

    @Operation(summary = "Void an expense", description = "It stays in the list, marked void, and leaves the reports.")
    @PostMapping("/expenses/{id}/void")
    @PreAuthorize("hasAuthority('expense:manage')")
    public ExpenseResponse voidExpense(@PathVariable Long id) {
        return expenses.voidExpense(id);
    }
}
