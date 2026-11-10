# Wallet: a double-entry ledger, built step by step

A digital wallet whose goal is **correctness**: money is never created, lost or double-spent, even under retries and concurrency.

## Docs (read in order)
1. [Concepts primer](docs/00-concepts.md)
2. [Requirements & scope](docs/01-requirements.md)
3. [High-level design](docs/02-hld.md)
4. [Low-level design](docs/03-lld.md)
5. [Decision records](docs/adr/)

## Progress
| Milestone | Status |
|---|---|
| M1 Ledger core: schema, DB guards, `LedgerService.post()` | ✅ |
| M2 Top-up / transfer / withdraw APIs + idempotency | ✅ |
| M3 Concurrency: 100 parallel transfers | ⏳ |
| M4 Refunds & reversals | ⏳ |
| M5 Invariant job + 10-min load run | ⏳ |

## Run
Requires Java 21 and Docker.

```bash
./mvnw test                 # all tests (spins up Postgres via Testcontainers)
docker compose up -d        # local Postgres for running the app
./mvnw spring-boot:run
```
