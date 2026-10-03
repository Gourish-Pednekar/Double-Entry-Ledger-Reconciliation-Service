package com.gourish.ledger.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gourish.ledger.transaction.Direction;

@Service
public class StatementService {

    private final AccountRepository repo;
    private final JdbcClient jdbc;

    public StatementService(AccountRepository repo, JdbcClient jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public StatementResponse statement(Long id, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'");
        }
        Account a = repo.findById(id).orElseThrow(() -> new AccountNotFoundException(id));

        // Entries in this direction increase the balance; the other direction decreases it
        boolean debitNormal = a.getType() == AccountType.ASSET || a.getType() == AccountType.EXPENSE;
        String plus = debitNormal ? "DEBIT" : "CREDIT";

        // Period is [from 00:00 UTC, to+1 day 00:00 UTC)
        OffsetDateTime start = from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        BigDecimal opening = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction = :plus THEN amount ELSE -amount END), 0)
                FROM entries
                WHERE account_id = :id AND created_at < :start
                """)
                .param("plus", plus).param("id", id).param("start", start)
                .query(BigDecimal.class).single();

        // The window function runs over the account's FULL history (CTE "running");
        // the date filter is applied afterwards so balances carry across periods.
        // Ordering by (created_at, id) keeps this consistent with the opening balance above.
        List<StatementLine> lines = jdbc.sql("""
                WITH signed AS (
                    SELECT e.id, e.transaction_id, t.description, e.created_at, e.direction, e.amount,
                           CASE WHEN e.direction = :plus THEN e.amount ELSE -e.amount END AS delta
                    FROM entries e
                    JOIN transactions t ON t.id = e.transaction_id
                    WHERE e.account_id = :id
                ),
                running AS (
                    SELECT *, SUM(delta) OVER (ORDER BY created_at, id) AS balance_after
                    FROM signed
                )
                SELECT id, transaction_id, description, created_at, direction, amount, balance_after
                FROM running
                WHERE created_at >= :start AND created_at < :end
                ORDER BY created_at, id
                """)
                .param("plus", plus).param("id", id).param("start", start).param("end", end)
                .query((rs, n) -> new StatementLine(
                        rs.getLong("id"),
                        rs.getLong("transaction_id"),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("balance_after")))
                .list();

        BigDecimal closing = lines.isEmpty() ? opening : lines.get(lines.size() - 1).balanceAfter();

        return new StatementResponse(a.getId(), a.getCode(), a.getType(), a.getCurrency(),
                from, to, opening, closing, lines);
    }
}