package com.gourish.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.gourish.ledger.account.AccountService;
import com.gourish.ledger.account.AccountType;
import com.gourish.ledger.account.CreateAccountRequest;
import com.gourish.ledger.transaction.Direction;
import com.gourish.ledger.transaction.EntryRequest;
import com.gourish.ledger.transaction.PostResult;
import com.gourish.ledger.transaction.PostTransactionRequest;
import com.gourish.ledger.transaction.TransactionService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrentTransfersTest {

    private static final int WALLETS = 10;
    private static final int TRANSFERS = 3000;
    private static final int THREADS = 32;
    private static final BigDecimal OPENING = new BigDecimal("1000.00");

    private record Job(String key, PostTransactionRequest request) {
    }

    @Autowired
    AccountService accounts;
    @Autowired
    TransactionService transactions;
    @Autowired
    JdbcClient jdbc;

    @Test
    void parallelTransfersNeverCreateOrLoseMoney() throws Exception {
        // --- setup: one cash account, ten customer wallets, each funded with 1000 ---
        Long cash = accounts.create(
                new CreateAccountRequest("CASH", "Cash", AccountType.ASSET, "INR", null)).id();

        List<Long> wallets = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            Long w = accounts.create(new CreateAccountRequest(
                    "W" + i, "Wallet " + i, AccountType.LIABILITY, "INR", null)).id();
            wallets.add(w);
            transactions.post("fund-" + i, new PostTransactionRequest("Fund wallet " + i, List.of(
                    new EntryRequest(cash, Direction.DEBIT, OPENING),
                    new EntryRequest(w, Direction.CREDIT, OPENING))));
        }

        // --- jobs: 3000 random transfers, each submitted TWICE with the same key ---
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int from = rnd.nextInt(WALLETS);
            int to = (from + 1 + rnd.nextInt(WALLETS - 1)) % WALLETS; // always different
            BigDecimal amount = BigDecimal.valueOf(100 + rnd.nextInt(4900), 2); // 1.00 to 49.99

            PostTransactionRequest req = new PostTransactionRequest("Transfer " + i, List.of(
                    new EntryRequest(wallets.get(from), Direction.DEBIT, amount),
                    new EntryRequest(wallets.get(to), Direction.CREDIT, amount)));
            jobs.add(new Job("transfer-" + i, req));
            jobs.add(new Job("transfer-" + i, req)); // the duplicate "retry"
        }
        Collections.shuffle(jobs);

        // --- fire everything at once ---
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (Job job : jobs) {
            pool.submit(() -> {
                try {
                    start.await();
                    PostResult r = transactions.post(job.key(), job.request());
                    (r.created() ? created : replayed).incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();

        // --- assertions ---
        assertThat(errors).as("no request should fail").isEmpty();
        assertThat(created.get()).as("each unique key created exactly once").isEqualTo(TRANSFERS);
        assertThat(replayed.get()).as("each duplicate was replayed").isEqualTo(TRANSFERS);

        Long txCount = jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single();
        assertThat(txCount).as("funding + unique transfers only").isEqualTo((long) (WALLETS + TRANSFERS));

        BigDecimal debits = jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM entries WHERE direction='DEBIT'")
                .query(BigDecimal.class).single();
        BigDecimal credits = jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM entries WHERE direction='CREDIT'")
                .query(BigDecimal.class).single();
        assertThat(debits).as("total debits equal total credits").isEqualByComparingTo(credits);

        BigDecimal walletTotal = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END), 0)
                FROM entries WHERE account_id IN (:ids)
                """).param("ids", wallets).query(BigDecimal.class).single();
        assertThat(walletTotal).as("money inside wallets is unchanged")
                .isEqualByComparingTo(OPENING.multiply(BigDecimal.valueOf(WALLETS)));
    }
}