package com.gourish.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.gourish.ledger.account.AccountService;
import com.gourish.ledger.account.AccountType;
import com.gourish.ledger.account.CreateAccountRequest;
import com.gourish.ledger.reconciliation.BankStatementService;
import com.gourish.ledger.reconciliation.ImportResult;
import com.gourish.ledger.reconciliation.InvalidCsvException;
import com.gourish.ledger.reconciliation.ReconciliationResponse;
import com.gourish.ledger.reconciliation.ReconciliationService;
import com.gourish.ledger.transaction.Direction;
import com.gourish.ledger.transaction.EntryRequest;
import com.gourish.ledger.transaction.PostTransactionRequest;
import com.gourish.ledger.transaction.TransactionService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationTest {

    @Autowired
    AccountService accounts;
    @Autowired
    TransactionService transactions;
    @Autowired
    BankStatementService statements;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        // CASCADE also clears bank_statements and bank_lines, which reference accounts
        jdbc.sql("TRUNCATE entries, transactions, accounts RESTART IDENTITY CASCADE").update();
    }

    private void post(String key, Long debit, Long credit, String amount) {
        BigDecimal a = new BigDecimal(amount);
        transactions.post(key, new PostTransactionRequest("Test " + key, List.of(
                new EntryRequest(debit, Direction.DEBIT, a),
                new EntryRequest(credit, Direction.CREDIT, a))));
    }

    private static String status(ReconciliationResponse r, String reference) {
        return r.items().stream().filter(i -> i.reference().equals(reference))
                .findFirst().orElseThrow().status();
    }

    @Test
    void classifiesAllFourOutcomes() {
        Long cash = accounts.create(new CreateAccountRequest("CASH", "Cash", AccountType.ASSET, "INR", null)).id();
        Long equity = accounts.create(new CreateAccountRequest("EQ", "Equity", AccountType.EQUITY, "INR", null)).id();

        post("t-match", cash, equity, "100.00");
        post("t-diff", cash, equity, "250.00");
        post("t-ledger-only", cash, equity, "40.00");

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String csv = "reference,date,amount,description\n"
                + "t-match," + today + ",100.00,Deposit\n"
                + "t-diff," + today + ",200.00,Short by 50\n"
                + "bank-only," + today + ",-15.00,Bank fee\n";

        ImportResult imported = statements.importCsv(cash, csv);
        assertThat(imported.lineCount()).isEqualTo(3);

        ReconciliationResponse r = reconciliation.reconcile(imported.statementId());

        assertThat(status(r, "t-match")).isEqualTo("MATCHED");
        assertThat(status(r, "t-diff")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(status(r, "t-ledger-only")).isEqualTo("MISSING_IN_BANK");
        assertThat(status(r, "bank-only")).isEqualTo("MISSING_IN_LEDGER");

        assertThat(r.counts().get("MATCHED")).isEqualTo(1L);
        assertThat(r.counts().get("AMOUNT_MISMATCH")).isEqualTo(1L);
        assertThat(r.counts().get("MISSING_IN_BANK")).isEqualTo(1L);
        assertThat(r.counts().get("MISSING_IN_LEDGER")).isEqualTo(1L);

        assertThat(r.ledgerTotal()).isEqualByComparingTo("390.00"); // 100 + 250 + 40
        assertThat(r.bankTotal()).isEqualByComparingTo("285.00"); // 100 + 200 - 15
        assertThat(r.difference()).isEqualByComparingTo("-105.00");
    }

    @Test
    void rejectsDuplicateReferencesAndWritesNothing() {
        Long cash = accounts.create(new CreateAccountRequest("CASH", "Cash", AccountType.ASSET, "INR", null)).id();

        String csv = "reference,date,amount,description\n"
                + "x,2026-10-02,10.00,a\n"
                + "x,2026-10-02,20.00,b\n";

        assertThatThrownBy(() -> statements.importCsv(cash, csv))
                .isInstanceOf(InvalidCsvException.class)
                .hasMessageContaining("duplicate reference");

        Long statementCount = jdbc.sql("SELECT COUNT(*) FROM bank_statements").query(Long.class).single();
        assertThat(statementCount).isZero();
    }
}