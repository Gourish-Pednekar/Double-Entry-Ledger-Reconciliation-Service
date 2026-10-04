# Ledger

A double-entry ledger and reconciliation service built with Java 21, Spring Boot, and PostgreSQL.

The goal of this project is to model how core financial systems keep money correct: every transaction is balanced, history is immutable, retried payments never double-charge, concurrent transfers cannot overdraw an account, and the database itself enforces the rules so that no application bug can corrupt the books.

## Status

Work in progress. The sections below describe what is implemented and measured today; the roadmap at the end shows what is planned.

## Design principles

- **Double-entry bookkeeping.** Every transaction consists of two or more entries, each a debit or a credit against an account. Total debits must equal total credits.
- **The database enforces correctness.** Balance checks live in a deferred constraint trigger in PostgreSQL, not only in application code. Even a direct SQL insert cannot create an unbalanced transaction.
- **Defense in depth.** The API validates the balance first so clients get a clear error, and the database trigger remains as the backstop.
- **Idempotent writes.** Every transaction is posted with an idempotency key, so a retried request cannot move money twice.
- **Overdraft prevention without races.** Customer wallets (liability accounts) can never go below zero. Concurrent transfers are serialized with row locks taken in a consistent order, so the balance check always sees current data and deadlocks cannot form.
- **Append-only history.** Entries and transactions can never be updated or deleted. Mistakes are corrected by posting a new, reversing transaction, which preserves a full audit trail.
- **Exact arithmetic.** Amounts are stored as `NUMERIC(19,4)`. Floating-point types are never used for money.
- **UTC everywhere.** The application runs in UTC and timestamps are stored as `TIMESTAMPTZ`.
- **Schema as code.** All schema changes are versioned Flyway migrations. Applied migrations are never edited.
- **Measure before optimizing.** Performance changes are justified with `EXPLAIN ANALYZE` on a seeded benchmark database, and results are recorded in this repository.

## Tech stack

| Area                 | Choice                                                                      |
| -------------------- | --------------------------------------------------------------------------- |
| Language             | Java 21                                                                     |
| Framework            | Spring Boot 4 (Web, Data JPA, Validation, Actuator)                         |
| Database             | PostgreSQL 16                                                               |
| Data access          | Spring Data JPA for simple CRUD, JdbcClient with raw SQL for ledger queries |
| Migrations           | Flyway                                                                      |
| Local infrastructure | Docker Compose                                                              |
| Testing              | JUnit 5, AssertJ, Testcontainers (PostgreSQL 16)                            |

## Data model

```
accounts       id, code (unique), name, type, currency, parent_id, created_at
transactions   id, idempotency_key (unique), request_hash, description, created_at
entries        id, transaction_id, account_id, direction (DEBIT | CREDIT), amount (> 0), created_at
bank_statements id, account_id, imported_at
bank_lines     id, statement_id, reference, booked_on, amount (signed, non-zero), description
```

Account types: `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`.

Accounts form a hierarchy through `parent_id`. A child account must have the same type and currency as its parent, so that rolled-up totals are meaningful.

### Database-level guarantees

| Rule                                 | Mechanism                                                       |
| ------------------------------------ | --------------------------------------------------------------- |
| Debits equal credits per transaction | Deferred constraint trigger on `entries`, evaluated at `COMMIT` |
| At least two entries per transaction | Same trigger                                                    |
| Amounts are positive                 | `CHECK (amount > 0)`                                            |
| Ledger rows are immutable            | `BEFORE UPDATE OR DELETE` triggers that raise an exception      |
| One transaction per idempotency key  | `UNIQUE` constraint on `transactions.idempotency_key`           |

Example of the balance rule in action:

```
ERROR:  Transaction 2 is unbalanced: debits=500.0000 credits=400.0000
```

### Indexes on `entries`

| Index                      | Columns                                                                  | Purpose                                                      |
| -------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------ |
| `entries_pkey`             | `id`                                                                     | Primary key                                                  |
| `idx_entries_transaction`  | `transaction_id`                                                         | Join from transaction to its entries                         |
| `idx_entries_account_time` | `account_id, created_at, id` INCLUDE `direction, amount, transaction_id` | Covering index for balances and statements (see Performance) |

## API

### Accounts

| Method | Path                                                         | Description                                                                                                                       |
| ------ | ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/api/accounts`                                              | Create an account (201; 409 if the code exists; 404 if the parent is unknown; 422 if the parent has a different type or currency) |
| GET    | `/api/accounts`                                              | List accounts                                                                                                                     |
| GET    | `/api/accounts/{id}`                                         | Get one account (404 if unknown)                                                                                                  |
| GET    | `/api/accounts/{id}/balance`                                 | Total debits, total credits, and balance                                                                                          |
| GET    | `/api/accounts/{id}/statement?from=YYYY-MM-DD&to=YYYY-MM-DD` | Opening balance, every entry in the period with a running balance, and closing balance (dates are UTC and inclusive)              |
| GET    | `/api/accounts/tree`                                         | Chart of accounts as a nested hierarchy; each node shows its own balance and a rolled-up total including all descendants          |

Balance sign convention: asset and expense accounts are debit-normal (balance = debits - credits); liability, equity, and revenue accounts are credit-normal (balance = credits - debits).

### Transactions

`POST /api/transactions` with an `Idempotency-Key` header (required, up to 64 characters).

```json
{
  "description": "Owner invests 1000",
  "entries": [
    { "accountId": 1, "direction": "DEBIT", "amount": 1000.0 },
    { "accountId": 2, "direction": "CREDIT", "amount": 1000.0 }
  ]
}
```

| Situation                                                  | Response                                                                                               |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| New key, valid and balanced request                        | 201 Created with the transaction                                                                       |
| Same key, same body (a retry)                              | 200 OK with the original transaction and an `Idempotent-Replayed: true` header; nothing new is written |
| Same key, different body                                   | 422 Unprocessable Content                                                                              |
| Debits do not equal credits                                | 422 Unprocessable Content                                                                              |
| A debit would take a liability (wallet) account below zero | 422 Unprocessable Content                                                                              |
| Unknown account                                            | 404 Not Found                                                                                          |
| Malformed request                                          | 400 Bad Request                                                                                        |

#### How idempotency works

The service stores a SHA-256 hash of each request body next to its key. A repeated key with a matching hash is a genuine retry and replays the stored result. A repeated key with a different hash is a client bug and is rejected. If two identical requests arrive at the same moment, the unique constraint lets exactly one insert succeed; the other catches the duplicate-key error and replays the winner's result. The insert runs in its own database transaction so the loser can still query afterward.

## Statements and the chart of accounts

**Statements.** The running balance is a window function (`SUM(...) OVER (ORDER BY created_at, id)`) scoped to the requested period, added to an opening balance computed with a separate aggregate query. Both queries run in one `REPEATABLE_READ` transaction so they see the same snapshot and the numbers always agree. Reading only the requested period, rather than the account's whole history, is what makes statements fast on busy accounts (see Performance).

**Chart of accounts.** A recursive CTE builds every (ancestor, descendant) pair from `parent_id`, and each ancestor's rolled-up total is the sum of its descendants' own balances. The recursion has a depth guard so that a data error cannot cause an infinite loop. The nested structure is assembled in Java.

## Bank reconciliation

Import a bank statement as CSV, then compare it against the ledger. The comparison is a `FULL OUTER JOIN` between the ledger entries on the account and the imported bank lines, which keeps unmatched rows from both sides (an inner join would hide every discrepancy; a left join would see only one direction).

| Method | Path                                       | Description                                                                                                              |
| ------ | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| POST   | `/api/accounts/{id}/bank-statements`       | Import a statement. Body is raw CSV with `Content-Type: text/csv` (201; 400 for invalid CSV; 404 for an unknown account) |
| GET    | `/api/bank-statements/{id}/reconciliation` | Reconcile an imported statement against the ledger (404 for an unknown statement)                                        |

CSV format, with this exact header (the description may contain commas):

```
reference,date,amount,description
invest-001,2026-10-02,1000.00,Deposit
```

A positive amount means the account's balance increased (a deposit into an asset account). Imports are validated completely before anything is written; a duplicate reference within one file, a malformed date or amount, or a wrong header is rejected with a 400 naming the line.

Each item is classified as one of:

| Status              | Meaning                                      |
| ------------------- | -------------------------------------------- |
| `MATCHED`           | Present on both sides with equal amounts     |
| `AMOUNT_MISMATCH`   | Present on both sides with different amounts |
| `MISSING_IN_BANK`   | In the ledger but not on the bank statement  |
| `MISSING_IN_LEDGER` | On the bank statement but not in the ledger  |

The response also includes a count per status, the ledger and bank totals, and the difference between them.

### Example result

A statement with one correct line, one line that is 50.00 short, and one bank fee the ledger does not know about, reconciled against an account whose ledger also holds a 500.00 initial deposit the statement omits:

| Reference      | Status              | Ledger  | Bank    | Difference |
| -------------- | ------------------- | ------- | ------- | ---------- |
| `invest-001`   | `MATCHED`           | 1000.00 | 1000.00 | 0.00       |
| `invest-002`   | `AMOUNT_MISMATCH`   | 250.00  | 200.00  | -50.00     |
| `bank-fee-001` | `MISSING_IN_LEDGER` | none    | -15.00  | -15.00     |
| `TX#1`         | `MISSING_IN_BANK`   | 500.00  | none    | -500.00    |

Totals: ledger 1750.00, bank 1185.00, difference -565.00 (the sum of the item differences). This was verified manually against a running instance; there is no automated test for reconciliation yet.

### Matching rules and limitations

- **Matching key.** A bank line's `reference` is matched to the ledger transaction's idempotency key. Ledger transactions without a key appear as `TX#<id>` so they are still reported. Real bank data is often less clean, and fuzzy matching on amount and date is not implemented.
- **Date window.** The ledger side is limited to the date range covered by the bank lines, using UTC dates. A payment booked on one day by the ledger and the next day by the bank can appear as a false discrepancy; the statement should cover a period wide enough to absorb timing differences.
- **One line per reference.** Ledger entries are summed per transaction, and bank references must be unique within a statement.

## Concurrency control

The overdraft rule is checked in application code: read the account balance, compare it with the requested debit, then insert the entries. Written naively, this has a race condition. Two transfers debiting the same wallet at the same moment both read the same balance, both decide they can afford it, and both commit.

### The bug, reproduced

`OverdraftConcurrencyTest` funds 5 wallets with 100.00 each and fires 2,000 random transfers (1.00 to 49.99) from 32 threads at once. It then replays every entry in order with a window function to find the lowest balance any wallet ever held.

With the naive check, one recorded run ended with a wallet at **-91.09**, even though every individual balance check had passed.

### The fix

Before checking balances, each transaction locks every account it touches with `SELECT ... FOR UPDATE`, **lowest account id first**:

- Concurrent transfers that touch the same account queue up instead of reading the same stale balance. Under PostgreSQL's default `READ COMMITTED` isolation, the balance query that runs after the lock is granted sees the previous transfer's committed entries.
- Locking in a fixed order prevents deadlocks. If one transfer locked wallet 1 then 2 while another locked 2 then 1, each would wait on the other. With sorted locking, both go for the lower id first, so one waits and no cycle can form.

With the fix, five recorded runs of the same test produced 1,613, 1,670, 1,717, 1,704, and 1,641 successful transfers, with the remainder rejected for insufficient funds. In every run there was no overdrawn wallet, no unexpected error (no deadlocks), and the total money across the wallets was unchanged.

### Scope and limitations

- The overdraft rule currently applies to all `LIABILITY` accounts. A per-account setting would be a natural improvement.
- Concurrency tests are probabilistic. A passing run is evidence, not proof, so the test is meant to be run repeatedly.

## Performance

Measured on a benchmark database seeded with 1,000 accounts, 500,000 transactions, and 1,000,000 entries. One hot account appears in 100,000 entries (10% of all entries); a typical account has about 900. The seed script bypasses the application for speed, so its output was verified separately: zero unbalanced transactions.

Timings are `EXPLAIN (ANALYZE)` execution times from the second of two consecutive runs (warm cache), on PostgreSQL 16 in Docker Desktop on a Windows laptop.

### Step 1: covering index

| Query                                   | Baseline (ms) | With covering index (ms) | Plan with the new index                |
| --------------------------------------- | ------------- | ------------------------ | -------------------------------------- |
| Balance, hot account (100k entries)     | 21.4          | 9.1                      | Index Only Scan, 0 heap fetches        |
| Balance, typical account (900 entries)  | 1.0           | 0.15                     | Index Only Scan, 0 heap fetches        |
| Opening balance, hot account            | 18.2          | 12.6                     | Index Only Scan, 0 heap fetches        |
| Statement lines, hot account, one month | 122.7         | 113.7                    | unchanged: still sorts about 100k rows |

The baseline already had an index on `(account_id, id)`. The covering index lets PostgreSQL answer balance queries from the index alone. It did **not** meaningfully help the statement-lines query, because the cost there was in the query's shape, not the lookup.

### Step 2: rewrite the statement query

The original statement query computed the running balance over the account's entire history and filtered to the requested month afterward. The rewritten query filters first, computes the window over only the requested period (8,389 rows instead of 100,000), and adds a separately computed opening balance. Both versions were verified to return identical results (8,389 rows, zero differences in either direction).

| Statement for one month, hot account                    | Execution time |
| ------------------------------------------------------- | -------------- |
| Baseline (opening balance 18.2 ms + lines 122.7 ms)     | about 141 ms   |
| Covering index, original query (12.6 ms + 113.7 ms)     | about 126 ms   |
| Covering index, rewritten query (single combined query) | about 42 ms    |

Most of the improvement came from the query rewrite, not from the index.

### Step 3: the old index was redundant

After the covering index existed, the planner already chose it for every account-scoped query measured. Dropping `idx_entries_account` changed no query plans. The timings moved by roughly 10 to 15% in the slower direction (for example the rewritten statement went from 41.7 ms to 48.2 ms); since no plan changed and the dropped index was not being used, this looks like run-to-run noise rather than a real regression, but single runs cannot distinguish the two with certainty. The drop is part of the V5 migration.

| Index space on `entries`                                                                        | Size   |
| ----------------------------------------------------------------------------------------------- | ------ |
| Before (`entries_pkey` 21 MB, `idx_entries_transaction` 19 MB, `idx_entries_account` 39 MB)     | 79 MB  |
| After (`entries_pkey` 21 MB, `idx_entries_transaction` 19 MB, `idx_entries_account_time` 64 MB) | 104 MB |

Net index cost: **+25 MB**.

### What was not measured

- **Write cost.** Every insert now maintains a larger index. The overdraft and concurrency tests still pass, but insert throughput was not benchmarked.
- **End-to-end API latency.** The numbers above are database query times, not HTTP response times. The application runs the statement as two queries inside one transaction, so its latency will differ from the single combined benchmark query.
- **Other hardware and cold caches.** These are single-machine, warm-cache, single-run figures. Treat them as order-of-magnitude evidence, not precise multipliers.

### Known remaining cost

The statement query plan still performs a parallel sequential scan of the `transactions` table for the join, even after the rewrite. It was left alone because further tuning was outside the scope of the measurements taken.

### Reproducing the benchmark

The scripts are in the `bench` folder. They use `docker cp` and `psql -f` rather than piping, because piping SQL through PowerShell into `docker exec` mangled psql meta-commands on the development machine.

```powershell
# 1. Copy the development schema into a separate benchmark database (stop the app first)
docker exec -i ledger-db psql -U ledger -d postgres -c "CREATE DATABASE ledger_bench TEMPLATE ledger;"

# 2. Seed 1M entries
docker cp bench\seed.sql ledger-db:/tmp/seed.sql
docker exec -i ledger-db psql -v ON_ERROR_STOP=1 -U ledger -d ledger_bench -f /tmp/seed.sql

# 2b. Verify the seed (expect 0 unbalanced transactions) and refresh planner statistics
docker exec -i ledger-db psql -U ledger -d ledger_bench -c "SELECT COUNT(*) AS unbalanced FROM (SELECT transaction_id FROM entries GROUP BY transaction_id HAVING COUNT(*) < 2 OR SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0) x;"
docker exec -i ledger-db psql -U ledger -d ledger_bench -c "VACUUM ANALYZE entries;"

# 3. Run the benchmark queries twice and keep the second run
docker cp bench\queries.sql ledger-db:/tmp/queries.sql
docker exec -i ledger-db psql -U ledger -d ledger_bench -f /tmp/queries.sql | Out-Null
docker exec -i ledger-db psql -U ledger -d ledger_bench -f /tmp/queries.sql | Out-File -Encoding utf8 bench\before.txt
```

`bench/q4_rewrite.sql` holds the rewritten statement query and `bench/verify_q4_q5.sql` checks that both versions return the same rows.

## Getting started

### Prerequisites

- JDK 21 or later
- Docker Desktop

### Run locally

Start PostgreSQL:

```bash
docker compose up -d
```

Start the application (Flyway applies all migrations on startup):

```bash
./mvnw spring-boot:run
```

On Windows PowerShell, use `.\mvnw spring-boot:run`.

The service listens on `http://localhost:8080`.

### Try it

```powershell
# Create accounts
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/accounts -ContentType "application/json" `
  -Body '{"code":"1000","name":"Cash","type":"ASSET","currency":"INR"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/accounts -ContentType "application/json" `
  -Body '{"code":"3000","name":"Owner Equity","type":"EQUITY","currency":"INR"}'

# Post a transaction (run it twice to see the idempotent replay)
$body = '{"description":"Owner invests 1000","entries":[{"accountId":1,"direction":"DEBIT","amount":1000.00},{"accountId":2,"direction":"CREDIT","amount":1000.00}]}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/transactions -ContentType "application/json" `
  -Headers @{"Idempotency-Key"="invest-001"} -Body $body

# Check a balance, a statement, and the chart of accounts
Invoke-RestMethod http://localhost:8080/api/accounts/1/balance
Invoke-RestMethod "http://localhost:8080/api/accounts/1/statement?from=2026-01-01&to=2026-12-31"
Invoke-RestMethod http://localhost:8080/api/accounts/tree

# Import a bank statement for account 1 and reconcile it
$csv = @"
reference,date,amount,description
invest-001,2026-10-02,1000.00,Deposit
"@
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/accounts/1/bank-statements -ContentType "text/csv" -Body $csv
Invoke-RestMethod http://localhost:8080/api/bank-statements/1/reconciliation
```

The statement dates are UTC. Adjust them to include the day you ran the example.

### Reset the local database

```bash
docker compose down -v
docker compose up -d
```

## Testing

Integration tests run against a real PostgreSQL container started by Testcontainers, so triggers, constraints, and locking behave exactly as in production. Docker must be running.

```bash
./mvnw test
```

Both concurrency tests clear the database before running.

### Concurrency test

`ConcurrentTransfersTest` creates a cash account and ten customer wallets, funds each wallet generously (so the overdraft rule never rejects a transfer), then fires 6,000 requests from 32 threads at once. These are 3,000 random wallet-to-wallet transfers, each submitted twice with the same idempotency key at the same moment, in shuffled order. It asserts that:

- no request fails
- each unique key created exactly one transaction, and each duplicate was replayed
- the transactions table contains only the funding transactions plus the 3,000 unique transfers
- total debits equal total credits across the whole ledger
- the total money held in wallets is unchanged by the transfers

The test runs the service layer directly, which exercises the real database transaction and idempotency logic without HTTP overhead.

`OverdraftConcurrencyTest` is described under Concurrency control above.

## Migrations

| Version | Purpose                                                                                        |
| ------- | ---------------------------------------------------------------------------------------------- |
| V1      | Accounts table                                                                                 |
| V2      | Transactions and entries, balance trigger, append-only triggers                                |
| V3      | Change `accounts.currency` to `VARCHAR(3)` to match the JPA mapping                            |
| V4      | Add `transactions.request_hash` for idempotency checks                                         |
| V5      | Add the covering index `idx_entries_account_time` and drop the redundant `idx_entries_account` |
| V6      | Bank statements and bank lines for reconciliation                                              |

## Roadmap

- [x] Project setup, Docker Compose, Flyway
- [x] Accounts, transactions, and entries schema
- [x] Balance-enforcing constraint trigger
- [x] Append-only enforcement
- [x] Account REST API
- [x] Transaction endpoint with idempotency keys
- [x] Account balance endpoint
- [x] Parallel-transfer correctness test with duplicate idempotency keys
- [x] Overdraft prevention with ordered row locking
- [x] Account statements with running balances (window functions)
- [x] Hierarchical chart of accounts (recursive CTE)
- [x] Benchmark with EXPLAIN ANALYZE: covering index and period-scoped statement query
- [x] Bank statement import and reconciliation (FULL OUTER JOIN)
- [ ] Multi-currency support with FX conversion (LATERAL join)
- [ ] Partitioning, partial indexes, or materialized views, only if measurements justify them
- [ ] Insert-throughput benchmark to quantify index write cost
- [ ] Broader integration tests (reconciliation, API status codes, trigger behavior, statements, chart of accounts)
- [ ] API documentation with Swagger UI
- [ ] Dockerfile and deployment

## Project layout

```
src/main/java/com/gourish/ledger/account      accounts, balances, statements, chart of accounts
src/main/java/com/gourish/ledger/transaction  transaction posting, idempotency, overdraft control
src/main/java/com/gourish/ledger/reconciliation  bank statement CSV import and reconciliation
src/main/resources/db/migration               Flyway migrations
src/test/java/com/gourish/ledger              integration and concurrency tests
bench                                         benchmark seed, queries, and recorded results
docker-compose.yml                            local PostgreSQL
```
