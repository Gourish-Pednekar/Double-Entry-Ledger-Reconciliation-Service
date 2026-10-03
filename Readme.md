# Ledger

A double-entry ledger and reconciliation service built with Java 21, Spring Boot, and PostgreSQL.

The goal of this project is to model how core financial systems keep money correct: every transaction is balanced, history is immutable, retried payments never double-charge, and the database itself enforces the rules so that no application bug can corrupt the books.

## Status

Work in progress. The sections below describe what is implemented today; the roadmap at the end shows what is planned.

## Design principles

- **Double-entry bookkeeping.** Every transaction consists of two or more entries, each a debit or a credit against an account. Total debits must equal total credits.
- **The database enforces correctness.** Balance checks live in a deferred constraint trigger in PostgreSQL, not only in application code. Even a direct SQL insert cannot create an unbalanced transaction.
- **Defense in depth.** The API validates the balance first so clients get a clear error, and the database trigger remains as the backstop.
- **Overdraft prevention without races.** Customer wallets (liability accounts) can never go below zero. Concurrent transfers are serialized with row locks taken in a consistent order, so the balance check always sees current data and deadlocks cannot form.
- **Append-only history.** Entries and transactions can never be updated or deleted. Mistakes are corrected by posting a new, reversing transaction, which preserves a full audit trail.
- **Idempotent writes.** Every transaction is posted with an idempotency key, so a retried request cannot move money twice.
- **Exact arithmetic.** Amounts are stored as `NUMERIC(19,4)`. Floating-point types are never used for money.
- **UTC everywhere.** The application runs in UTC and timestamps are stored as `TIMESTAMPTZ`.
- **Schema as code.** All schema changes are versioned Flyway migrations. Applied migrations are never edited.

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
```

Account types: `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`.

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

## API

### Accounts

| Method | Path                         | Description                                                |
| ------ | ---------------------------- | ---------------------------------------------------------- |
| POST   | `/api/accounts`              | Create an account (201, or 409 if the code already exists) |
| GET    | `/api/accounts`              | List accounts                                              |
| GET    | `/api/accounts/{id}`         | Get one account (404 if unknown)                           |
| GET    | `/api/accounts/{id}/balance` | Total debits, total credits, and balance                   |

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

# Check a balance
Invoke-RestMethod http://localhost:8080/api/accounts/1/balance
```

### Reset the local database

```bash
docker compose down -v
docker compose up -d
```

## Concurrency control

The overdraft rule is checked in application code: read the account balance, compare it with the requested debit, then insert the entries. Written naively, this has a race condition. Two transfers debiting the same wallet at the same moment both read the same balance, both decide they can afford it, and both commit.

### The bug, reproduced

`OverdraftConcurrencyTest` funds 5 wallets with 100.00 each and fires 2,000 random transfers (1.00 to 49.99) from 32 threads at once. It then replays every entry in order with a window function to find the lowest balance any wallet ever held.

With the naive check, one recorded run ended with a wallet at **-91.09**, even though every individual balance check had passed.

### The fix

Before checking balances, each transaction locks every account it touches with `SELECT ... FOR UPDATE`, **lowest account id first**:

- Concurrent transfers that touch the same account queue up instead of reading the same stale balance. Under PostgreSQL's default `READ COMMITTED` isolation, the balance query that runs after the lock is granted sees the previous transfer's committed entries.
- Locking in a fixed order prevents deadlocks. If one transfer locked wallet 1 then 2 while another locked 2 then 1, each would wait on the other. With sorted locking, both go for the lower id first, so one waits and no cycle can form.

With the fix, the same test recorded 1,613 successful transfers, 387 legitimate insufficient-funds rejections, **no overdrawn wallet, and no unexpected errors (no deadlocks)**. Total money across the wallets was unchanged.

### Scope and limitations

- The overdraft rule currently applies to all `LIABILITY` accounts. A per-account setting would be a natural improvement.
- Concurrency tests are probabilistic. A passing run is evidence, not proof, so the test is meant to be run repeatedly.

## Testing

Integration tests run against a real PostgreSQL container started by Testcontainers, so triggers, constraints, and locking behave exactly as in production. Docker must be running.

```bash
./mvnw test
```

### Concurrency test

`ConcurrentTransfersTest` creates a cash account and ten customer wallets, funds each wallet generously (so the overdraft rule never rejects a transfer), then fires 6,000 requests from 32 threads at once. These are 3,000 random wallet-to-wallet transfers, each submitted twice with the same idempotency key at the same moment, in shuffled order. It asserts that:

- no request fails
- each unique key created exactly one transaction, and each duplicate was replayed
- the transactions table contains only the funding transactions plus the 3,000 unique transfers
- total debits equal total credits across the whole ledger
- the total money held in wallets is unchanged by the transfers

The test runs the service layer directly, which exercises the real database transaction and idempotency logic without HTTP overhead.

`OverdraftConcurrencyTest` is described under Concurrency control above. Both tests clear the database before running.

## Migrations

| Version | Purpose                                                             |
| ------- | ------------------------------------------------------------------- |
| V1      | Accounts table                                                      |
| V2      | Transactions and entries, balance trigger, append-only triggers     |
| V3      | Change `accounts.currency` to `VARCHAR(3)` to match the JPA mapping |
| V4      | Add `transactions.request_hash` for idempotency checks              |

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
- [ ] Running balances (window functions)
- [ ] Hierarchical chart of accounts (recursive CTE)
- [ ] Monthly statements (CTEs)
- [ ] Multi-currency support with FX conversion (LATERAL join)
- [ ] Bank statement reconciliation (FULL OUTER JOIN)
- [ ] Performance work with EXPLAIN ANALYZE (partial indexes, partitioning, materialized views)
- [x] Integration test infrastructure with Testcontainers
- [ ] Broader integration tests (API status codes, trigger behavior)
- [ ] API documentation with Swagger UI
- [ ] Dockerfile and deployment

## Project layout

```
src/main/java/com/gourish/ledger/account      accounts and balances
src/main/java/com/gourish/ledger/transaction  transaction posting and idempotency
src/main/resources/db/migration               Flyway migrations
src/test/java/com/gourish/ledger              integration and concurrency tests
docker-compose.yml                            local PostgreSQL
```
