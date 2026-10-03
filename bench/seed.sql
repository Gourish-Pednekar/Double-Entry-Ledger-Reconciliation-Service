-- Benchmark seed: 1,000 accounts, 500,000 transactions, 1,000,000 entries.
-- Run ONLY against ledger_bench. Bypasses the application and the balance trigger
-- for speed; the data is generated balanced and verified at the end.
TRUNCATE entries,
transactions,
accounts RESTART IDENTITY CASCADE;
ALTER TABLE entries DISABLE TRIGGER trg_entries_balanced;
-- Account 1 is a "hot" account that appears in 20% of transactions
INSERT INTO accounts (code, name, type, currency)
SELECT 'A' || lpad(g::text, 4, '0'),
    CASE
        WHEN g = 1 THEN 'Treasury'
        ELSE 'Account ' || g
    END,
    'ASSET',
    'INR'
FROM generate_series(1, 1000) g;
-- One transaction every 63 seconds, starting 2025-01-01 (about one year of data)
INSERT INTO transactions (description, created_at)
SELECT 'Bench transfer ' || g,
    timestamptz '2025-01-01 00:00:00+00' + (g * interval '63 seconds')
FROM generate_series(1, 500000) g
ORDER BY g;
WITH base AS (
    SELECT t.id,
        t.created_at,
        (t.id * 7919) % 999 AS a0,
        (100 + (t.id * 37) % 4900) / 100.0 AS amt
    FROM transactions t
),
pairs AS (
    SELECT id,
        created_at,
        amt,
        CASE
            WHEN id % 5 = 0 THEN 1
            ELSE 2 + a0
        END AS from_acct,
        2 + ((a0 + 1 + (id % 998)) % 999) AS to_acct -- never equals from_acct
    FROM base
)
INSERT INTO entries (
        transaction_id,
        account_id,
        direction,
        amount,
        created_at
    )
SELECT p.id,
    CASE
        d.n
        WHEN 1 THEN p.from_acct
        ELSE p.to_acct
    END,
    CASE
        d.n
        WHEN 1 THEN 'DEBIT'
        ELSE 'CREDIT'
    END,
    p.amt,
    p.created_at
FROM pairs p
    CROSS JOIN (
        VALUES (1),
            (2)
    ) AS d(n)
ORDER BY p.id,
    d.n;
ALTER TABLE entries ENABLE TRIGGER trg_entries_balanced;
\ echo == = Verification: unbalanced transactions (expect 0) == =
SELECT COUNT(*) AS unbalanced
FROM (
        SELECT transaction_id
        FROM entries
        GROUP BY transaction_id
        HAVING COUNT(*) < 2
            OR SUM(
                CASE
                    WHEN direction = 'DEBIT' THEN amount
                    ELSE - amount
                END
            ) <> 0
    ) x;
VACUUM ANALYZE accounts;
VACUUM ANALYZE transactions;
VACUUM ANALYZE entries;
\ echo == = Row counts
and sizes == =
SELECT (
        SELECT COUNT(*)
        FROM entries
    ) AS entries,
    (
        SELECT COUNT(*)
        FROM entries
        WHERE account_id = 1
    ) AS hot_account_entries,
    pg_size_pretty(pg_total_relation_size('entries')) AS entries_total_size;