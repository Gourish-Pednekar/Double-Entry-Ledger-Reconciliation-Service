TRUNCATE entries,
transactions,
accounts RESTART IDENTITY CASCADE;
-- Skip the deferred balance trigger so only heap and index maintenance is measured
ALTER TABLE entries DISABLE TRIGGER trg_entries_balanced;
INSERT INTO accounts (code, name, type, currency)
SELECT 'W' || lpad(g::text, 4, '0'),
    'Account ' || g,
    'ASSET',
    'INR'
FROM generate_series(1, 1000) g;
INSERT INTO transactions (description, created_at)
SELECT 'write bench ' || g,
    timestamptz '2025-01-01 00:00:00+00' + (g * interval '63 seconds')
FROM generate_series(1, 100000) g;
VACUUM ANALYZE accounts;
VACUUM ANALYZE transactions;