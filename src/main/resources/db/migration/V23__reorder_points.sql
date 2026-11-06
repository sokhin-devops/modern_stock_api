-- Reorder points from sales history (PLAIN.md 11.9, the 07:00 job in section 6): recalculated every morning per branch
-- and product from the last 30 days of sales and the lead times of goods received. Only products with enough history
-- (sales on at least 7 of those days) have a row; the others keep their manual reorder level.
-- The level a branch uses: its own manual level (branch_reorder_levels) first, then this point, then the product's level.
CREATE TABLE reorder_points (
    business_id   BIGINT        NOT NULL REFERENCES businesses (id),
    branch_id     BIGINT        NOT NULL REFERENCES branches (id),
    product_id    BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    reorder_point NUMERIC(14,3) NOT NULL CHECK (reorder_point >= 0),
    safety_stock  NUMERIC(14,3) NOT NULL CHECK (safety_stock >= 0),
    -- average daily sales (ADS) and the busiest day of the 30
    ads           NUMERIC(14,3) NOT NULL,
    max_daily     NUMERIC(14,3) NOT NULL,
    lead_days     NUMERIC(8,2)  NOT NULL,
    computed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (branch_id, product_id)
);
CREATE INDEX reorder_points_business ON reorder_points (business_id);
