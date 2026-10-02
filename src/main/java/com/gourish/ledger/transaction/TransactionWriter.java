package com.gourish.ledger.transaction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionWriter {

    private final JdbcClient jdbc;

    public TransactionWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        return txId; // the deferred balance trigger fires at COMMIT, after this returns
    }
}