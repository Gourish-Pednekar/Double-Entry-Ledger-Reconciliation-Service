package com.gourish.ledger.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChartService {

    private record Row(Long id, String code, String name, AccountType type, String currency,
                       Long parentId, BigDecimal own, BigDecimal total) {}

    private final JdbcClient jdbc;

    public ChartService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<AccountNode> tree() {
        List<Row> rows = jdbc.sql("""
                WITH RECURSIVE tree AS (
                    -- every account is its own ancestor at depth 0
                    SELECT id AS ancestor_id, id AS descendant_id, 0 AS depth
                    FROM accounts
                    UNION ALL
                    -- walk down one level at a time; depth guard protects against cycles
                    SELECT t.ancestor_id, a.id, t.depth + 1
                    FROM tree t
                    JOIN accounts a ON a.parent_id = t.descendant_id
                    WHERE t.depth < 20
                ),
                own AS (
                    SELECT a.id,
                           COALESCE(SUM(CASE
                               WHEN e.direction = CASE WHEN a.type IN ('ASSET','EXPENSE')
                                                       THEN 'DEBIT' ELSE 'CREDIT' END
                               THEN e.amount ELSE -e.amount END), 0) AS bal
                    FROM accounts a
                    LEFT JOIN entries e ON e.account_id = a.id
                    GROUP BY a.id
                ),
                rolled AS (
                    SELECT t.ancestor_id AS id, SUM(o.bal) AS total
                    FROM tree t
                    JOIN own o ON o.id = t.descendant_id
                    GROUP BY t.ancestor_id
                )
                SELECT a.id, a.code, a.name, a.type, a.currency, a.parent_id,
                       o.bal AS own_balance, r.total AS total_balance
                FROM accounts a
                JOIN own o ON o.id = a.id
                JOIN rolled r ON r.id = a.id
                ORDER BY a.code
                """)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getString("currency"),
                        rs.getObject("parent_id", Long.class),
                        rs.getBigDecimal("own_balance"),
                        rs.getBigDecimal("total_balance")))
                .list();

        Map<Long, AccountNode> nodes = new LinkedHashMap<>();
        for (Row r : rows) {
            nodes.put(r.id(), new AccountNode(r.id(), r.code(), r.name(), r.type(), r.currency(),
                    r.own(), r.total(), new ArrayList<>()));
        }

        List<AccountNode> roots = new ArrayList<>();
        for (Row r : rows) {
            AccountNode node = nodes.get(r.id());
            if (r.parentId() == null) {
                roots.add(node);
            } else {
                nodes.get(r.parentId()).children().add(node);
            }
        }
        return roots;
    }
}