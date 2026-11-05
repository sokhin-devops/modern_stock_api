-- FR-6.7: a deleted account is kept as "Deleted user" (documents keep who made them), without name, email or phone.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

-- FR-6.19: a 4 to 6 digit PIN to switch cashier on a shared POS device; locked for a while after 5 wrong tries.
ALTER TABLE users ADD COLUMN pin_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN pin_failed INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN pin_locked_until TIMESTAMPTZ;
