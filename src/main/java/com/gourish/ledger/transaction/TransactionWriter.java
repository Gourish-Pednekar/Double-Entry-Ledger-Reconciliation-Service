package com.gourish.ledger.transaction;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionWriter {

    private final JdbcClient jdbc;

    public TransactionWriter(JdbcClient jdbc) { this.jdbc = jdbc; }
     private void lockAccounts(PostTransactionRequest req) {
        req.entries().stream()
                .map(EntryRequest::accountId)
                .distinct()
                .sorted()
                .forEach(id -> jdbc.sql("SELECT id FROM accounts WHERE id = :id FOR UPDATE")
                        .param("id", id)
                        .query(Long.class)
                        .single());
    }
    @Transactional
    public Long insert(String key, String hash, PostTransactionRequest req) {
        Long txId = jdbc.sql("""
                INSERT INTO transactions (idempotency_key, request_hash, description)
                VALUES (:key, :hash, :description) RETURNING id
                """)
                .param("key", key)
                .param("hash", hash)
                .param("description", req.description())
                .query(Long.class)
                .single();
        lockAccounts(req);
        requireSufficientFunds(req);

        for (EntryRequest e : req.entries()) {
            jdbc.sql("""
                    INSERT INTO entries (transaction_id, account_id, direction, amount)
                    VALUES (:tx, :account, :direction, :amount)
                    """)
                    .param("tx", txId)
                    .param("account", e.accountId())
                    .param("direction", e.direction().name())
                    .param("amount", e.amount())
                    .update();
        }
        return txId;   // the deferred balance trigger fires at COMMIT, after this returns
    }

    /** Liability accounts (customer wallets) may never end up below zero. */
    private void requireSufficientFunds(PostTransactionRequest req) {
        // Net effect of this request on each account's credit-normal balance
        Map<Long, BigDecimal> net = new HashMap<>();
        for (EntryRequest e : req.entries()) {
            BigDecimal signed = e.direction() == Direction.CREDIT ? e.amount() : e.amount().negate();
            net.merge(e.accountId(), signed, BigDecimal::add);
        }

        for (Map.Entry<Long, BigDecimal> change : net.entrySet()) {
            if (change.getValue().signum() >= 0) continue;   // balance does not go down

            Long accountId = change.getKey();
            String type = jdbc.sql("SELECT type FROM accounts WHERE id = :id")
                    .param("id", accountId).query(String.class).single();
            if (!"LIABILITY".equals(type)) continue;

            BigDecimal balance = jdbc.sql("""
                    SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                    FROM entries WHERE account_id = :id
                    """)
                    .param("id", accountId).query(BigDecimal.class).single();

            if (balance.add(change.getValue()).signum() < 0) {
                throw new InsufficientFundsException(accountId, balance, change.getValue().negate());
            }
        }
    }
}