-- Voids for every posted document (PLAIN.md 11.1 rule 3, section 13 "every posted document also has /void"):
-- when and by whom. The status column already has VOID.
ALTER TABLE goods_receipts   ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE bills            ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE payments         ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE invoices         ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE deliveries       ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE sales_returns    ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE purchase_returns ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE credit_notes     ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE supplier_credits ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
ALTER TABLE stock_counts     ADD COLUMN voided_at TIMESTAMPTZ, ADD COLUMN voided_by BIGINT;
