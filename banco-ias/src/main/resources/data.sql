INSERT INTO collection_order (id, merchant_id, status, pending_balance) VALUES
    ('ORD-PSE-1001', 'COM-10', 'OPEN',    1000000.00),
    ('ORD-PSE-1002', 'COM-10', 'BLOCKED',  800000.00),
    ('ORD-PSE-2001', 'COM-20', 'OPEN',    2000000.00)
ON CONFLICT (id) DO NOTHING;
