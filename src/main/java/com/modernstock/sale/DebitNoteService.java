package com.modernstock.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.modernstock.activity.ActivityService;
import com.modernstock.business.BusinessContext;
import com.modernstock.business.SettingsService;
import com.modernstock.common.ApiException;
import com.modernstock.common.Dates;
import com.modernstock.common.DocumentNumberService;
import com.modernstock.common.DocumentNumberService.DocType;
import com.modernstock.currency.CurrencyService;
import com.modernstock.partner.Customer;
import com.modernstock.partner.CustomerRepository;
import com.modernstock.sale.PosService.InvoiceResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * Debit notes (FR-3.19): more for a customer to pay after an invoice, e.g. a price that was too low or a delivery
 * charge. A debit note is an invoice of type DEBIT_NOTE without goods (DN-…), so payments, balances, statements and
 * overdue alerts take it like any invoice. Money the customer paid ahead pays it first. One that nothing has paid yet
 * can be voided.
 */
@Service
@RequiredArgsConstructor
public class DebitNoteService {

    public record DebitNoteRequest(
            @NotNull Long customerId,
            @Schema(description = "The invoice it adds to, if any (same customer)") Long invoiceId,
            @Schema(description = "USD, before VAT") @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @Schema(description = "Add VAT at the business rate (only when VAT is on)") boolean addVat,
            @Schema(description = "Why the customer owes more; printed on the note", example = "Price of rice corrected")
            @NotBlank @Size(max = 500) String reason,
            @Schema(description = "Defaults to the customer's payment terms from today") LocalDate dueDate) {
    }

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final SaleDocuments documents;
    private final DocumentNumberService numbers;
    private final CurrencyService currency;
    private final SettingsService settings;
    private final ActivityService activity;
    private final PosService pos;

    @Transactional
    public InvoiceResponse create(DebitNoteRequest req) {
        Customer customer = customers.get(req.customerId());
        Invoice related = null;
        if (req.invoiceId() != null) {
            related = invoices.get(req.invoiceId());
            if (!related.getCustomerId().equals(customer.getId()) || related.getStatus() == Invoice.Status.VOID
                    || related.getType() == Invoice.Type.DEBIT_NOTE) {
                throw ApiException.notFound("Invoice");
            }
            BusinessContext.requireBranch(related.getBranchId());
        }
        Long branchId = related != null ? related.getBranchId() : BusinessContext.branchForWrite(null);
        SettingsService.BusinessSettings s = settings.get();
        BigDecimal subtotal = req.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = req.addVat() && s.vatEnabled()
                ? subtotal.multiply(s.vatRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal total = subtotal.add(vat);
        BigDecimal rate = currency.current().usdToKhr();

        Invoice dn = new Invoice();
        dn.setType(Invoice.Type.DEBIT_NOTE);
        dn.setNumber(numbers.next(DocType.DN));
        dn.setBranchId(branchId);
        dn.setCustomerId(customer.getId());
        dn.setRelatedInvoiceId(related == null ? null : related.getId());
        dn.setInvoiceDate(Dates.today());
        dn.setDueDate(req.dueDate() != null ? req.dueDate() : Dates.today().plusDays(customer.getPaymentTermsDays()));
        dn.setExchangeRate(rate);
        dn.setSubtotal(subtotal);
        dn.setVat(vat);
        dn.setTotal(total);
        dn.setTotalKhr(currency.toKhr(total, rate));
        dn.setStatus(Invoice.Status.UNPAID);
        dn.setNote(req.reason().strip());
        invoices.save(dn);
        documents.useDeposits(dn);
        activity.record("DEBIT_NOTE_CREATED", "CUSTOMER", customer.getId(), dn.getNumber() + " · " + customer.getName(), branchId,
                null, ActivityService.values("total", total, "reason", dn.getNote()));
        return pos.invoice(dn.getId());
    }

    /** A debit note made by mistake, while nothing has paid it. */
    @Transactional
    public InvoiceResponse voidNote(Long id) {
        Invoice dn = invoices.get(id);
        BusinessContext.requireBranch(dn.getBranchId());
        if (dn.getType() != Invoice.Type.DEBIT_NOTE) {
            throw ApiException.notFound("Debit note");
        }
        if (dn.getStatus() != Invoice.Status.UNPAID || dn.getPaidAmount().signum() != 0) {
            throw ApiException.rule("DEBIT_NOTE_PAID");
        }
        dn.setStatus(Invoice.Status.VOID);
        Customer customer = customers.get(dn.getCustomerId());
        activity.record("DEBIT_NOTE_VOIDED", "CUSTOMER", customer.getId(), dn.getNumber() + " · " + customer.getName(), dn.getBranchId(),
                ActivityService.values("total", dn.getTotal()), null);
        return pos.invoice(dn.getId());
    }
}
