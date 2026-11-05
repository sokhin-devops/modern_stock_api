-- Returns that wait for approval (PLAIN.md section 5): a Cashier requests a sales return and a Stock keeper prepares a
-- purchase return; whoever may post returns (sale:void, bill:manage) posts or rejects them. Nothing moves before that.
ALTER TABLE sales_returns DROP CONSTRAINT sales_returns_status_check;
ALTER TABLE sales_returns ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE sales_returns ADD CONSTRAINT sales_returns_status_check
    CHECK (status IN ('WAITING_APPROVAL', 'POSTED', 'REJECTED', 'VOID'));
ALTER TABLE sales_returns
    ADD COLUMN refund_method   VARCHAR(10),
    ADD COLUMN refund_currency VARCHAR(3),
    ADD COLUMN reviewed_by     BIGINT,
    ADD COLUMN reviewed_at     TIMESTAMPTZ;

ALTER TABLE purchase_returns DROP CONSTRAINT purchase_returns_status_check;
ALTER TABLE purchase_returns ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE purchase_returns ADD CONSTRAINT purchase_returns_status_check
    CHECK (status IN ('WAITING_APPROVAL', 'POSTED', 'REJECTED', 'VOID'));
ALTER TABLE purchase_returns
    ADD COLUMN reviewed_by BIGINT,
    ADD COLUMN reviewed_at TIMESTAMPTZ;

CREATE INDEX sales_returns_waiting ON sales_returns (business_id, branch_id) WHERE status = 'WAITING_APPROVAL';
CREATE INDEX purchase_returns_waiting ON purchase_returns (business_id, branch_id) WHERE status = 'WAITING_APPROVAL';

INSERT INTO permissions (code, area, name_en) VALUES
    ('sales_return:request',    'Sales',     'Request sales returns'),
    ('purchase_return:prepare', 'Purchases', 'Prepare purchase returns');

-- The Owner has every permission; the Cashier requests and the Stock keeper prepares (the Manager posts them directly)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON (p.code = 'sales_return:request' AND r.code IN ('OWNER', 'CASHIER'))
    OR (p.code = 'purchase_return:prepare' AND r.code IN ('OWNER', 'STOCK_KEEPER'))
WHERE r.business_id IS NULL;
