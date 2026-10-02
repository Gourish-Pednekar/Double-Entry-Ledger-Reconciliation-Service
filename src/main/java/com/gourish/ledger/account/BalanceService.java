package com.gourish.ledger.account;

import java.math.BigDecimal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceService {

    private record Totals(BigDecimal debits, BigDecimal credits) {
    }

    private final AccountRepository repo;
    private final JdbcClient jdbc;

    public BalanceService(AccountRepository repo, JdbcClient jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BalanceResponse balanceOf(Long id) {
        Account a = repo.findById(id).orElseThrow(() -> new AccountNotFoundException(id));

        Totals t = jdbc.sql("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
                FROM entries WHERE account_id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new Totals(rs.getBigDecimal("debits"), rs.getBigDecimal("credits")))
                .single();

        boolean debitNormal = a.getType() == AccountType.ASSET || a.getType() == AccountType.EXPENSE;
        BigDecimal balance = debitNormal ? t.debits().subtract(t.credits())
                : t.credits().subtract(t.debits());

        return new BalanceResponse(a.getId(), a.getCode(), a.getType(), a.getCurrency(),
                t.debits(), t.credits(), balance);
    }
}