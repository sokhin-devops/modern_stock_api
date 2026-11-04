package com.modernstock.billing;

/**
 * A way to pay a billing invoice (PLAIN.md 11.13). A payment starts as Pending; the gateway confirms it later
 * (BillingService.complete), as Succeeded or Failed. KHQR or ABA PayWay later is a new class, not a new workflow.
 */
public interface PaymentGateway {

    /** Short name saved on each payment, e.g. DEMO. */
    String name();

    /** Starts a payment for the invoice; returns the gateway's reference for it. */
    String start(BillingInvoice invoice);
}
