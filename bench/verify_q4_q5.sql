WITH signed AS (
    SELECT e.id,
        e.created_at,
        CASE
            WHEN e.direction = 'DEBIT' THEN e.amount
            ELSE - e.amount
        END AS delta
    FROM entries e
    WHERE e.account_id = 1
),
running AS (
    SELECT id,
        created_at,
        SUM(delta) OVER (
            ORDER BY created_at,
                id
        ) AS balance_after
    FROM signed
),
old_q AS (
    SELECT id,
        balance_after
    FROM running
    WHERE created_at >= timestamptz '2025-12-01 00:00:00+00'
        AND created_at < timestamptz '2026-01-01 00:00:00+00'
),
period AS (
    SELECT e.id,
        e.created_at,
        CASE
            WHEN e.direction = 'DEBIT' THEN e.amount
            ELSE - e.amount
        END AS delta
    FROM entries e
    WHERE e.account_id = 1
        AND e.created_at >= timestamptz '2025-12-01 00:00:00+00'
        AND e.created_at < timestamptz '2026-01-01 00:00:00+00'
),
new_q AS (
    SELECT p.id,
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
)
SELECT (
        SELECT COUNT(*)
        FROM old_q
    ) AS old_rows,
    (
        SELECT COUNT(*)
        FROM new_q
    ) AS new_rows,
    (
        SELECT COUNT(*)
        FROM (
                SELECT *
                FROM old_q
                EXCEPT
                SELECT *
                FROM new_q
            ) a
    ) AS only_in_old,
    (
        SELECT COUNT(*)
        FROM (
                SELECT *
                FROM new_q
                EXCEPT
                SELECT *
                FROM old_q
            ) b
    ) AS only_in_new;