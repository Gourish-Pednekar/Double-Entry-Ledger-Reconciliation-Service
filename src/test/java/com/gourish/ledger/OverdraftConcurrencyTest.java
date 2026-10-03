package com.gourish.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
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
import com.gourish.ledger.transaction.InsufficientFundsException;
import com.gourish.ledger.transaction.PostTransactionRequest;
import com.gourish.ledger.transaction.TransactionService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OverdraftConcurrencyTest {

    private static final int WALLETS = 5;
    private static final int TRANSFERS = 2000;
    private static final int THREADS = 32;
    private static final BigDecimal OPENING = new BigDecimal("100.00");

    private record Job(String key, PostTransactionRequest request) {}

    @Autowired AccountService accounts;
    @Autowired TransactionService transactions;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE entries, transactions, accounts RESTART IDENTITY CASCADE").update();
    }

    @Test
    void walletsNeverGoNegativeUnderConcurrentTransfers() throws Exception {
        // --- setup: 5 wallets with only 100.00 each, so funds run out quickly ---
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

        // --- jobs: random transfers of 1.00 to 49.99; many must be rejected ---
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int from = rnd.nextInt(WALLETS);
            int to = (from + 1 + rnd.nextInt(WALLETS - 1)) % WALLETS;
            BigDecimal amount = BigDecimal.valueOf(100 + rnd.nextInt(4900), 2);
            jobs.add(new Job("t-" + i, new PostTransactionRequest("Transfer " + i, List.of(
                    new EntryRequest(wallets.get(from), Direction.DEBIT, amount),
                    new EntryRequest(wallets.get(to), Direction.CREDIT, amount)))));
        }

        // --- fire everything at once ---
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (Job job : jobs) {
            pool.submit(() -> {
                try {
                    start.await();
                    transactions.post(job.key(), job.request());
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();      // a legitimate business rejection
                } catch (Throwable t) {
                    unexpected.add(t);               // deadlocks, SQL errors, anything else
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();

        System.out.printf("RESULT: succeeded=%d rejected=%d unexpected=%d%n",
                succeeded.get(), rejected.get(), unexpected.size());

        // --- assertions ---
        assertThat(unexpected).as("only insufficient-funds rejections are allowed").isEmpty();
        assertThat(succeeded.get()).as("some transfers should succeed").isPositive();
        assertThat(rejected.get()).as("the overdraft rule should reject some").isPositive();

        // Lowest balance any wallet ever had, replaying entries in order (window function)
        BigDecimal lowest = jdbc.sql("""
                SELECT COALESCE(MIN(running), 0) FROM (
                    SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END)
                           OVER (PARTITION BY account_id ORDER BY id) AS running
                    FROM entries WHERE account_id IN (:ids)
                ) t
                """).param("ids", wallets).query(BigDecimal.class).single();
        assertThat(lowest).as("no wallet may ever be overdrawn").isGreaterThanOrEqualTo(BigDecimal.ZERO);

        BigDecimal total = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM entries WHERE account_id IN (:ids)
                """).param("ids", wallets).query(BigDecimal.class).single();
        assertThat(total).as("money inside wallets is conserved")
                .isEqualByComparingTo(OPENING.multiply(BigDecimal.valueOf(WALLETS)));
    }
}