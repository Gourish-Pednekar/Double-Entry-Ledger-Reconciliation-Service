SELECT 'Q5: statement for December 2025 incl. opening balance, period-only window' AS query;
EXPLAIN (ANALYZE, BUFFERS) WITH period AS (
    SELECT e.id,
        e.transaction_id,
        e.created_at,
        e.direction,
        e.amount,
        CASE
            WHEN e.direction = 'DEBIT' THEN e.amount
            ELSE - e.amount
        END AS delta
    FROM entries e
    WHERE e.account_id = 1
        AND e.created_at >= timestamptz '2025-12-01 00:00:00+00'
        AND e.created_at < timestamptz '2026-01-01 00:00:00+00'
)
SELECT p.id,
    p.transaction_id,
    t.description,
    p.created_at,
    p.direction,
    p.amount,
    (
        SELECT COALESCE(
                SUM(
                    CASE
                        WHEN direction = 'DEBIT' THEN amount
                        ELSE - amount
                    END
                ),
                0
            )
        FROM entries
        WHERE account_id = 1
            AND created_at < timestamptz '2025-12-01 00:00:00+00'
    ) + SUM(p.delta) OVER (
        ORDER BY p.created_at,
            p.id
    ) AS balance_after
FROM period p
    JOIN transactions t ON t.id = p.transaction_id
ORDER BY p.created_at,
    p.id;