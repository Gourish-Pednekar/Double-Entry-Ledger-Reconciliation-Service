# Ledger

A double-entry ledger and reconciliation service built with Java 21, Spring Boot, and PostgreSQL.

The goal of this project is to model how core financial systems keep money correct: every transaction is balanced, history is immutable, and the database itself enforces the rules so that no application bug can corrupt the books.

## Status

Work in progress. The sections below describe what is implemented today; the roadmap at the end shows what is planned.

## Design principles

- **Double-entry bookkeeping.** Every transaction consists of two or more entries, each a debit or a credit against an account. Total debits must equal total credits.
- **The database enforces correctness.** Balance checks live in a deferred constraint trigger in PostgreSQL, not only in application code. Even a direct SQL insert cannot create an unbalanced transaction.
- **Append-only history.** Entries and transactions can never be updated or deleted. Mistakes are corrected by posting a new, reversing transaction, which preserves a full audit trail.
- **Exact arithmetic.** Amounts are stored as `NUMERIC(19,4)`. Floating-point types are never used for money.
- **UTC everywhere.** The application runs in UTC and timestamps are stored as `TIMESTAMPTZ`.
- **Schema as code.** All schema changes are versioned Flyway migrations. Applied migrations are never edited.

## Tech stack

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4 (Web, Data JPA, Validation, Actuator) |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Local infrastructure | Docker Compose |
| Testing (planned) | JUnit 5, Testcontainers |

## Data model

```
accounts       id, code (unique), name, type, currency, parent_id, created_at
transactions   id, idempotency_key (unique), description, created_at
entries        id, transaction_id, account_id, direction (DEBIT | CREDIT), amount (> 0), created_at
```

Account types: `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`.

### Database-level guarantees

| Rule | Mechanism |
|---|---|
| Debits equal credits per transaction | Deferred constraint trigger on `entries`, evaluated at `COMMIT` |
| At least two entries per transaction | Same trigger |
| Amounts are positive | `CHECK (amount > 0)` |
| Ledger rows are immutable | `BEFORE UPDATE OR DELETE` triggers that raise an exception |

Example of the balance rule in action:

```
ERROR:  Transaction 2 is unbalanced: debits=500.0000 credits=400.0000
```

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

The service listens on `http://localhost:8080`. Health check: `http://localhost:8080/actuator/health`.

### Reset the local database

```bash
docker compose down -v
docker compose up -d
```

## Roadmap

- [x] Project setup, Docker Compose, Flyway
- [x] Accounts, transactions, and entries schema
- [x] Balance-enforcing constraint trigger
- [x] Append-only enforcement
- [ ] Account REST API
- [ ] Transfer endpoint with idempotency keys
- [ ] Concurrency control and a parallel-transfer correctness test
- [ ] Running balances (window functions)
- [ ] Hierarchical chart of accounts (recursive CTE)
- [ ] Monthly statements (CTEs)
- [ ] Multi-currency support with FX conversion (LATERAL join)
- [ ] Bank statement reconciliation (FULL OUTER JOIN)
- [ ] Performance work with EXPLAIN ANALYZE (partial indexes, partitioning, materialized views)
- [ ] Integration tests with Testcontainers
- [ ] API documentation with Swagger UI
- [ ] Dockerfile and deployment

## Project layout

```
src/main/java/com/gourish/ledger     application code
src/main/resources/db/migration      Flyway migrations (V1, V2, ...)
docker-compose.yml                   local PostgreSQL
```