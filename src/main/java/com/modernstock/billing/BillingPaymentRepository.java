package com.modernstock.billing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingPaymentRepository extends JpaRepository<BillingPayment, Long> {

    List<BillingPayment> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);
}
