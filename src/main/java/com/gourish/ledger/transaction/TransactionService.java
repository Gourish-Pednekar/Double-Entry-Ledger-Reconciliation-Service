package com.gourish.ledger.transaction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.gourish.ledger.account.AccountNotFoundException;

@Service
public class TransactionService {

    private record Stored(Long id, String hash, String description, java.time.Instant createdAt) {
    }

    private final TransactionWriter writer;
    private final JdbcClient jdbc;

    public TransactionService(TransactionWriter writer, JdbcClient jdbc) {
        this.writer = writer;
        this.jdbc = jdbc;
    }

    // Deliberately NOT @Transactional: see TransactionWriter.
    public PostResult post(String key, PostTransactionRequest req) {
        requireBalanced(req);
        String hash = hash(req);

        Optional<Stored> existing = findByKey(key);
        if (existing.isPresent()) {
            return replay(key, existing.get(), hash);
        }

        requireAccountsExist(req);

        try {
            writer.insert(key, hash, req);
        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key committed first. Replay its result.
            return replay(key, findByKey(key).orElseThrow(), hash);
        }
        return new PostResult(load(findByKey(key).orElseThrow()), true);
    }

    private PostResult replay(String key, Stored stored, String hash) {
        if (!stored.hash().equals(hash)) {
            throw new IdempotencyConflictException(key);
        }
        return new PostResult(load(stored), false);
    }

    private void requireBalanced(PostTransactionRequest req) {
        BigDecimal debits = sum(req, Direction.DEBIT);
        BigDecimal credits = sum(req, Direction.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new UnbalancedTransactionException(debits, credits);
        }
    }

    private static BigDecimal sum(PostTransactionRequest req, Direction d) {
        return req.entries().stream()
                .filter(e -> e.direction() == d)
                .map(EntryRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireAccountsExist(PostTransactionRequest req) {
        List<Long> ids = req.entries().stream().map(EntryRequest::accountId).distinct().toList();
        List<Long> found = jdbc.sql("SELECT id FROM accounts WHERE id IN (:ids)")
                .param("ids", ids)
                .query(Long.class)
                .list();
        for (Long id : ids) {
            if (!found.contains(id))
                throw new AccountNotFoundException(id);
        }
    }

    private Optional<Stored> findByKey(String key) {
        return jdbc.sql("""
                SELECT id, request_hash, description, created_at
                FROM transactions WHERE idempotency_key = :key
                """)
                .param("key", key)
                .query((rs, n) -> new Stored(
                        rs.getLong("id"),
                        rs.getString("request_hash"),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private TransactionResponse load(Stored s) {
        List<EntryResponse> entries = jdbc.sql("""
                SELECT account_id, direction, amount FROM entries
                WHERE transaction_id = :id ORDER BY id
                """)
                .param("id", s.id())
                .query((rs, n) -> new EntryResponse(
                        rs.getLong("account_id"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount")))
                .list();
        return new TransactionResponse(s.id(), s.description(), s.createdAt(), entries);
    }

    private static String hash(PostTransactionRequest req) {
        StringBuilder sb = new StringBuilder(req.description());
        for (EntryRequest e : req.entries()) {
            sb.append('|').append(e.accountId())
                    .append(':').append(e.direction())
                    .append(':').append(e.amount().setScale(4).toPlainString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}