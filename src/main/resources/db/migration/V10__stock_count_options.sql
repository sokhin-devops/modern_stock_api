-- Stock count options (FR-4.4, FR-4.9, PLAIN.md 11.2): a count covers one warehouse or every warehouse of a branch, and
-- all products, one category or a chosen list (one shelf). A blind count hides the system quantity until it is
-- submitted. Each line keeps when its system quantity was taken, so a product that moves during the count is flagged
-- for a recount. Staff submit a count; Owner or Manager posts it.

ALTER TABLE stock_counts ALTER COLUMN warehouse_id DROP NOT NULL;
ALTER TABLE stock_counts ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'ALL' CHECK (scope IN ('ALL', 'CATEGORY', 'PRODUCTS'));
ALTER TABLE stock_counts ADD COLUMN is_blind BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stock_counts ADD COLUMN submitted_at TIMESTAMPTZ;
ALTER TABLE stock_counts DROP CONSTRAINT stock_counts_status_check;
ALTER TABLE stock_counts ADD CONSTRAINT stock_counts_status_check CHECK (status IN ('DRAFT', 'SUBMITTED', 'POSTED', 'VOID'));
UPDATE stock_counts SET scope = 'CATEGORY' WHERE category_id IS NOT NULL;

ALTER TABLE stock_count_items ADD COLUMN warehouse_id BIGINT REFERENCES warehouses (id);
ALTER TABLE stock_count_items ADD COLUMN snapshot_at TIMESTAMPTZ;
UPDATE stock_count_items i SET warehouse_id = c.warehouse_id, snapshot_at = c.created_at FROM stock_counts c WHERE c.id = i.count_id;
ALTER TABLE stock_count_items ALTER COLUMN warehouse_id SET NOT NULL;
ALTER TABLE stock_count_items ALTER COLUMN snapshot_at SET NOT NULL;
