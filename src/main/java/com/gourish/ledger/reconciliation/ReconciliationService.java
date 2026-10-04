package com.gourish.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gourish.ledger.account.Account;
import com.gourish.ledger.account.AccountNotFoundException;
import com.gourish.ledger.account.AccountRepository;
import com.gourish.ledger.account.AccountType;

@Service
public class ReconciliationService {

    private record Header(Long accountId, LocalDate from, LocalDate to) {
    }

    private final AccountRepository accounts;
    private final JdbcClient jdbc;

    public ReconciliationService(AccountRepository accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(Long statementId) {
        Header h = jdbc.sql("""
                SELECT s.account_id, MIN(l.booked_on) AS d_from, MAX(l.booked_on) AS d_to
                FROM bank_statements s
                JOIN bank_lines l ON l.statement_id = s.id
                WHERE s.id = :sid
                GROUP BY s.account_id
                """)
                .param("sid", statementId)
                .query((rs, n) -> new Header(
                        rs.getLong("account_id"),
                        rs.getObject("d_from", LocalDate.class),
                        rs.getObject("d_to", LocalDate.class)))
                .optional()
                .orElseThrow(() -> new BankStatementNotFoundException(statementId));

        Account account = accounts.findById(h.accountId())
                .orElseThrow(() -> new AccountNotFoundException(h.accountId()));

        // A positive bank amount means the account's balance increased.
        boolean debitNormal = account.getType() == AccountType.ASSET || account.getType() == AccountType.EXPENSE;
        String plus = debitNormal ? "DEBIT" : "CREDIT";

        OffsetDateTime start = h.from().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime end = h.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        List<ReconciliationItem> items = jdbc.sql("""
                WITH ledger AS (
                    SELECT COALESCE(t.idempotency_key, 'TX#' || t.id) AS reference,
                           t.id AS transaction_id,
                           SUM(CASE WHEN e.direction = :plus THEN e.amount ELSE -e.amount END) AS amount
                    FROM entries e
                    JOIN transactions t ON t.id = e.transaction_id
                    WHERE e.account_id = :account AND e.created_at >= :start AND e.created_at < :end
                    GROUP BY t.id, t.idempotency_key
                ),
                bank AS (
                    SELECT reference, booked_on, amount
                    FROM bank_lines
                    WHERE statement_id = :sid
                )
                SELECT COALESCE(l.reference, b.reference) AS reference,
                       l.transaction_id,
                       l.amount  AS ledger_amount,
                       b.amount  AS bank_amount,
                       b.booked_on,
                       CASE WHEN l.reference IS NULL THEN 'MISSING_IN_LEDGER'
                            WHEN b.reference IS NULL THEN 'MISSING_IN_BANK'
                            WHEN l.amount = b.amount THEN 'MATCHED'
                            ELSE 'AMOUNT_MISMATCH' END AS status
                FROM ledger l
                FULL OUTER JOIN bank b ON b.reference = l.reference
                ORDER BY status, reference
                """)
                .param("plus", plus)
                .param("account", h.accountId())
                .param("start", start)
                .param("end", end)
                .param("sid", statementId)
                .query((rs, n) -> {
                    BigDecimal ledger = rs.getBigDecimal("ledger_amount");
                    BigDecimal bank = rs.getBigDecimal("bank_amount");
                    BigDecimal difference = (bank == null ? BigDecimal.ZERO : bank)
                            .subtract(ledger == null ? BigDecimal.ZERO : ledger);
                    return new ReconciliationItem(
                            rs.getString("reference"),
                            rs.getString("status"),
                            rs.getObject("transaction_id", Long.class),
                            ledger,
                            bank,
                            rs.getObject("booked_on", LocalDate.class),
                            difference);
                })
                .list();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("MATCHED", "AMOUNT_MISMATCH", "MISSING_IN_BANK", "MISSING_IN_LEDGER")) {
            counts.put(s, 0L);
        }
        BigDecimal ledgerTotal = BigDecimal.ZERO;
        BigDecimal bankTotal = BigDecimal.ZERO;
        for (ReconciliationItem i : items) {
            counts.merge(i.status(), 1L, Long::sum);
            if (i.ledgerAmount() != null)
                ledgerTotal = ledgerTotal.add(i.ledgerAmount());
            if (i.bankAmount() != null)
                bankTotal = bankTotal.add(i.bankAmount());
        }

        return new ReconciliationResponse(statementId, h.accountId(), h.from(), h.to(),
                counts, ledgerTotal, bankTotal, bankTotal.subtract(ledgerTotal), items);
    }
}