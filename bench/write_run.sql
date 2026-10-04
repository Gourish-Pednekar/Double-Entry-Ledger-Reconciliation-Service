TRUNCATE entries;
BEGIN;
EXPLAIN (ANALYZE)
INSERT INTO entries (
        transaction_id,
        account_id,
        direction,
        amount,
        created_at
    )
SELECT t.id,
    CASE
        d.n
        WHEN 1 THEN ((t.id * 7919) % 1000) + 1
        ELSE ((t.id * 104729) % 1000) + 1
    END,
    CASE
        d.n
        WHEN 1 THEN 'DEBIT'
        ELSE 'CREDIT'
    END,
    10.00,
    t.created_at
FROM transactions t
    CROSS JOIN (
        VALUES (1),
            (2)
    ) AS d(n);
ROLLBACK;