-- Example plans (PLAIN.md 11.13). The super admin can change them later without code. NULL value = unlimited.

INSERT INTO plans (code, name, price_monthly, price_yearly, is_public, sort_order) VALUES
    ('FREE',  'Free',  0,  0,   TRUE, 1),
    ('BASIC', 'Basic', 9,  90,  TRUE, 2),
    ('PRO',   'Pro',   25, 250, TRUE, 3);

INSERT INTO plan_limits (plan_id, limit_key, value)
SELECT p.id, l.limit_key, l.value
FROM plans p
JOIN (VALUES
    ('FREE',  'branches', 1),    ('FREE',  'members', 2),    ('FREE',  'products', 300),
    ('FREE',  'telegram_alerts', 0), ('FREE',  'excel_export', 0), ('FREE',  'tax_invoice', 0),
    ('BASIC', 'branches', 3),    ('BASIC', 'members', 10),   ('BASIC', 'products', 3000),
    ('BASIC', 'telegram_alerts', 1), ('BASIC', 'excel_export', 1), ('BASIC', 'tax_invoice', 0),
    ('PRO',   'branches', 10),   ('PRO',   'members', 30),   ('PRO',   'products', NULL),
    ('PRO',   'telegram_alerts', 1), ('PRO',   'excel_export', 1), ('PRO',   'tax_invoice', 1)
) AS l (plan_code, limit_key, value) ON l.plan_code = p.code;

INSERT INTO platform_settings (key, value) VALUES
    ('trial_days', '30'),
    ('max_businesses_per_account', '5'),
    ('invitation_expiry_days', '7');
