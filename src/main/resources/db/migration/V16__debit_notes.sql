-- A debit note (FR-3.19) is an invoice of type DEBIT_NOTE without goods; it may name the invoice it adds to.
ALTER TABLE invoices ADD COLUMN related_invoice_id BIGINT REFERENCES invoices (id);
