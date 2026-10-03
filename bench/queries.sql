SELECT 'Q1: balance, hot account (id 1)' AS query;
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(
        SUM(amount) FILTER (
            WHERE direction = 'DEBIT'
        ),
        0
    ) AS debits,
    COALESCE(
        SUM(amount) FILTER (
            WHERE direction = 'CREDIT'
        ),
        0
    ) AS credits
FROM entries
WHERE account_id = 1;
SELECT 'Q2: balance, typical account (id 500)' AS query;
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(
        SUM(amount) FILTER (
            WHERE direction = 'DEBIT'
        ),
        0
    ) AS debits,
    COALESCE(
        SUM(amount) FILTER (
            WHERE direction = 'CREDIT'
        ),
        0
    ) AS credits
FROM entries
WHERE account_id = 500;
SELECT 'Q3: opening balance before 2025-12-01, hot account' AS query;
EXPLAIN (ANALYZE, BUFFERS)
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
    AND created_at < timestamptz '2025-12-01 00:00:00+00';
SELECT 'Q4: statement lines for December 2025, hot account' AS query;
EXPLAIN (ANALYZE, BUFFERS) WITH signed AS (
    SELECT e.id,
        e.transaction_id,
        t.description,
        e.created_at,
        e.direction,
        e.amount,
        CASE
            WHEN e.direction = 'DEBIT' THEN e.amount
            ELSE - e.amount
        END AS delta
    FROM entries e
        JOIN transactions t ON t.id = e.transaction_id
    WHERE e.account_id = 1
),
running AS (
    SELECT *,
        SUM(delta) OVER (
            ORDER BY created_at,
                id
        ) AS balance_after
    FROM signed
)
SELECT id,
    transaction_id,
    description,
    created_at,
    direction,
    amount,
    balance_after
FROM running
WHERE created_at >= timestamptz '2025-12-01 00:00:00+00'
    AND created_at < timestamptz '2026-01-01 00:00:00+00'
ORDER BY created_at,
    id;