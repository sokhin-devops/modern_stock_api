-- Built-in roles and permissions (PLAIN.md section 5). Built-in roles have no business_id and can't be edited.

INSERT INTO permissions (code, area, name_en) VALUES
    ('product:view',          'Products',  'View products, categories and units'),
    ('product:manage',        'Products',  'Add and edit products, categories, units, Excel import'),
    ('branch_price:manage',   'Products',  'Set branch prices'),
    ('supplier:view',         'Suppliers', 'View suppliers'),
    ('supplier:manage',       'Suppliers', 'Add and edit suppliers'),
    ('customer:view',         'Customers', 'View customers'),
    ('customer:manage',       'Customers', 'Add and edit customers'),
    ('customer:create_pos',   'Customers', 'Add a customer from the POS'),
    ('purchase_order:view',   'Purchases', 'View purchase orders'),
    ('purchase_order:manage', 'Purchases', 'Create, order, cancel and close purchase orders'),
    ('goods_receipt:create',  'Purchases', 'Receive goods'),
    ('bill:manage',           'Purchases', 'Bills and supplier payments'),
    ('expense:manage',        'Purchases', 'Expenses'),
    ('sales_order:view',      'Sales',     'View quotations and sales orders'),
    ('sales_order:manage',    'Sales',     'Create quotations and sales orders'),
    ('delivery:manage',       'Sales',     'Deliveries'),
    ('sale:create',           'Sales',     'POS sales, payments received, pay later within the credit limit'),
    ('sale:override',         'Sales',     'Go over the credit limit, discount above the limit'),
    ('sale:void',             'Sales',     'Void sales'),
    ('cash_shift:own',        'Sales',     'Own cash shift'),
    ('cash_shift:all',        'Sales',     'All cash shifts and differences'),
    ('stock:view',            'Inventory', 'View stock, stock card, batches'),
    ('stock:opening',         'Inventory', 'Opening stock'),
    ('adjustment:post',       'Inventory', 'Post adjustments'),
    ('adjustment:request',    'Inventory', 'Request adjustments'),
    ('adjustment:approve',    'Inventory', 'Approve adjustments and counts'),
    ('transfer:manage',       'Inventory', 'Send and receive transfers'),
    ('transfer:return_void',  'Inventory', 'Return a transfer to sender, void a transfer'),
    ('count:manage',          'Inventory', 'Count stock'),
    ('report:view',           'Reports',   'Reports (scope depends on the role)'),
    ('exchange_rate:manage',  'Business',  'Exchange rate'),
    ('member:view',           'Business',  'View members'),
    ('member:manage',         'Business',  'Invite, change, disable or remove members; reset links'),
    ('business:manage',       'Business',  'Business profile, branches, settings, Telegram'),
    ('billing:manage',        'Business',  'Billing');

INSERT INTO roles (business_id, code, name_en, name_km, is_built_in) VALUES
    (NULL, 'OWNER',        'Owner',        'ម្ចាស់',            TRUE),
    (NULL, 'MANAGER',      'Manager',      'អ្នកគ្រប់គ្រង',      TRUE),
    (NULL, 'STOCK_KEEPER', 'Stock keeper', 'អ្នកគ្រប់គ្រងឃ្លាំង', TRUE),
    (NULL, 'CASHIER',      'Cashier',      'អ្នកគិតលុយ',        TRUE);

-- Owner: everything
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'OWNER' AND r.business_id IS NULL;

-- Manager: everything except members, business settings and billing
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'MANAGER' AND r.business_id IS NULL
  AND p.code NOT IN ('member:manage', 'business:manage', 'billing:manage', 'adjustment:request');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'product:view', 'supplier:view', 'purchase_order:view', 'goods_receipt:create', 'sales_order:view',
    'delivery:manage', 'stock:view', 'adjustment:request', 'transfer:manage', 'count:manage', 'report:view')
WHERE r.code = 'STOCK_KEEPER' AND r.business_id IS NULL;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'product:view', 'customer:create_pos', 'sales_order:view', 'sales_order:manage', 'sale:create',
    'cash_shift:own', 'stock:view', 'report:view')
WHERE r.code = 'CASHIER' AND r.business_id IS NULL;
