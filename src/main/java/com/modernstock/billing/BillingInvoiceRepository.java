package com.modernstock.billing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, Long> {

    List<BillingInvoice> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    List<BillingInvoice> findByBusinessIdAndStatus(Long businessId, BillingInvoice.Status status);

    @Query(value = "select nextval('billing_invoice_number_seq')", nativeQuery = true)
    long nextNumber();
}
