package com.modernstock.billing;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The demo gateway (billing.mode=demo): no money moves. The pay screen's "Pay (demo)" and "Fail payment (demo)"
 * buttons send the simulated confirmation (BillingController), like a real gateway's callback would.
 */
@Component
public class DemoPaymentGateway implements PaymentGateway {

    @Override
    public String name() {
        return "DEMO";
    }

    @Override
    public String start(BillingInvoice invoice) {
        return "DEMO-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
    }
}
