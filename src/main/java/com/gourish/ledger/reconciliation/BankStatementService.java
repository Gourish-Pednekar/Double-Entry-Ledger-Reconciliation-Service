package com.gourish.ledger.reconciliation;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gourish.ledger.account.AccountNotFoundException;
import com.gourish.ledger.account.AccountRepository;

@Service
public class BankStatementService {

    private final AccountRepository accounts;
    private final JdbcClient jdbc;

    public BankStatementService(AccountRepository accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Transactional
    public ImportResult importCsv(Long accountId, String csv) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        List<BankLineInput> lines = BankCsvParser.parse(csv); // validates everything before any insert

        Long statementId = jdbc.sql("INSERT INTO bank_statements (account_id) VALUES (:a) RETURNING id")
                .param("a", accountId)
                .query(Long.class)
                .single();

        for (BankLineInput l : lines) {
            jdbc.sql("""
                    INSERT INTO bank_lines (statement_id, reference, booked_on, amount, description)
                    VALUES (:sid, :ref, :date, :amount, :desc)
                    """)
                    .param("sid", statementId)
                    .param("ref", l.reference())
                    .param("date", l.bookedOn())
                    .param("amount", l.amount())
                    .param("desc", l.description())
                    .update();
        }
        return new ImportResult(statementId, accountId, lines.size());
    }
}