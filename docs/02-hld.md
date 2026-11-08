# 02 — High-Level Design

This doc covers the system's components, how they talk to each other, and where correctness is enforced. Table columns, SQL and exact API contracts are in the LLD.

---

## 1. Architecture style

**A modular monolith: one Spring Boot service and one PostgreSQL database.**

Why not microservices? A transfer must debit A and credit B **atomically**. In one database that's one transaction. Across services it becomes a distributed-transaction problem (sagas, outbox), which is a separate topic and would bury the ledger concepts. The service is **stateless**, so we can still run several copies behind a load balancer. All coordination happens in Postgres.

---

## 2. Component diagram

```
                ┌──────────────────────────────────────────────────────────┐
  Client ──HTTP─▶                   Wallet Service (Spring Boot)           │
 (curl / tests) │                                                          │
                │  ┌──────────────┐                                        │
                │  │  API layer   │  controllers, request validation,      │
                │  │              │  error → HTTP mapping                  │
                │  └──────┬───────┘                                        │
                │         │                                                │
                │  ┌──────▼───────┐                                        │
                │  │ Idempotency  │  claim key → run → store response      │
                │  └──────┬───────┘                                        │
                │         │                                                │
                │  ┌──────▼──────────────────┐   ┌──────────────────────┐  │
                │  │  Wallet operations      │   │  Invariant checker   │  │
                │  │  topUp / transfer /     │   │  @Scheduled daily +  │  │
                │  │  withdraw / refund /    │   │  manual trigger      │  │
                │  │  reverse                │   │  (read-only)         │  │
                │  └──────┬──────────────────┘   └──────────┬───────────┘  │
                │         │ "post this journal"             │              │
                │  ┌──────▼──────────────────┐              │              │
                │  │  Ledger core            │  the ONLY    │              │
                │  │  lock accounts, verify  │  writer of   │              │
                │  │  balanced, write        │  journals,   │              │
                │  │  postings, update bal.  │  postings,   │              │
                │  └──────┬──────────────────┘  balances    │              │
                └─────────┼─────────────────────────────────┼──────────────┘
                          │ JDBC                            │
                  ┌───────▼─────────────────────────────────▼───────┐
                  │                  PostgreSQL                     │
                  │  accounts · journals · postings ·               │
                  │  idempotency_keys · invariant_runs              │
                  └─────────────────────────────────────────────────┘
```

---

## 3. Modules and responsibilities

| Module | Owns | Doesn't do |
|---|---|---|
| **api** | HTTP endpoints, input validation (BR-1, BR-2, BR-4), mapping errors to status codes | Business logic |
| **idempotency** | Claiming a key, detecting reuse or in-flight requests, storing and replaying responses | Knowing what a "transfer" is |
| **wallet** (operations) | Turns a business action into a **journal recipe** (which accounts, which signs), business rules (refund limits, reversal-once) | Writing postings directly |
| **ledger** (core) | `post(journal)`: lock accounts, check balanced, check no-overdraft, insert journal + postings, update stored balances, all in one transaction | Knowing *why* money moves |
| **account** | Creating wallets, system accounts at startup, balance/history reads | Moving money |
| **invariants** | Running the 4 checks (FR-13), recording runs and violations | Fixing anything |

**Key rule: only `ledger` writes money tables.** Every money movement, however it starts, goes through one `post()` method. That gives us a single place to get correctness right and a single place to test.

---

## 4. Request flow: P2P transfer

```
Client                API        Idempotency        Wallet ops         Ledger            Postgres
  │ POST /transfers    │              │                  │                │                  │
  │ Idempotency-Key: k │              │                  │                │                  │
  ├───────────────────▶│ validate     │                  │                │                  │
  │                    ├─────────────▶│ claim(k, hash)   │                │                  │
  │                    │              ├──────────────────┼────────────────┼─────────────────▶│ INSERT key (unique)
  │                    │              │  new? ───────────▶ transfer(A,B,x)│                  │
  │                    │              │                  ├───────────────▶│ BEGIN            │
  │                    │              │                  │                ├─────────────────▶│ lock A,B (fixed order)
  │                    │              │                  │                │ check A ≥ x      │
  │                    │              │                  │                ├─────────────────▶│ INSERT journal, postings
  │                    │              │                  │                ├─────────────────▶│ UPDATE balances
  │                    │              │◀─────────────────┴────────────────┤ save response(k) │
  │                    │              │                  │                ├─────────────────▶│ COMMIT
  │◀───────────────────┤ 201          │                  │                │                  │
  │                    │              │                  │                │                  │
  │  (retry, same k)   │              │ already done ──▶ return stored response, no work     │
```

Three paths through the idempotency check:
- **New key:** do the work.
- **Completed key:** replay the stored response.
- **In-flight key, or same key with a different body:** return 409.

Top-up, withdraw, refund and reverse follow **the same shape**. Only the journal recipe changes.

---

## 5. Journal recipes

| Operation | Postings | Extra checks |
|---|---|---|
| Top-up | Pool `+x`, Wallet `−x` | — |
| Transfer | Sender `+x`, Receiver `−x` | Sender has funds |
| Withdraw | Wallet `+x`, Pool `−x` | Wallet has funds |
| Refund (of transfer) | Receiver `+r`, Sender `−r` | Receiver has funds; total refunds ≤ original |
| Reversal | Original postings with signs flipped | Not reversed before; affected wallet has funds |

(Recall from the primer: user wallets are liabilities, so `+` on a wallet means its balance **goes down**.)

---

## 6. Where correctness is enforced

We use several layers. If one layer has a bug, the next one catches it.

| Layer | What it guarantees | Example |
|---|---|---|
| 1. API validation | Bad input never reaches the logic | amount > 0, ≤ max, not self-transfer |
| 2. Ledger code | Journal balanced, enough funds, accounts locked | `sum(postings) == 0` check before insert |
| 3. **Database constraints** | Holds even if Java code is wrong | unique idempotency key, `balance ≥ 0` check on user wallets, append-only on postings, balanced-journal check at commit |
| 4. Invariant job | **Detects** anything that slipped through | stored balance ≠ SUM(postings) |

Layers 1–3 **prevent** problems and layer 4 **detects** them. Prevention and detection are both needed in real money systems.

---

## 7. Concurrency model (high level)

- **Postgres is the only coordinator.** No Java `synchronized` and no in-memory locks, because those break as soon as there are 2 app instances.
- A money movement **locks the account rows it touches** for the length of its transaction, always **in ascending account-id order** to avoid deadlocks.
- The exact mechanism (pessimistic `FOR UPDATE` vs optimistic vs `SERIALIZABLE`) is decided in the LLD and recorded as an ADR, after milestone 3 shows the naive version failing.
- **The Cash Pool is a hot spot.** Every top-up and withdraw touches it, so they serialize on that row. That's fine for v1, and it's a good thing to discuss later (sharded pool accounts and similar).

---

## 8. Failure scenarios

| What fails | What happens | Why it's safe |
|---|---|---|
| App crashes mid-transaction | Postgres rolls back | Journal is all-or-nothing |
| App crashes after commit, before the response reaches the client | Client retries with the same key | Stored response is replayed and no money moves again |
| App crashes after claiming the key, before finishing | Key stuck "in progress" | Claim has a timeout and can be re-claimed after it expires (detail in LLD) |
| Two requests deadlock anyway | Postgres aborts one | Caller gets a retryable error. Shouldn't happen with ordered locking |
| DB unavailable | 503 | Nothing was written |
| Invariant job finds a violation | Logged + stored, visible via endpoint | No auto-fix. A human investigates |

---

## 9. Data model (entities only)

```
Account 1───* Posting *───1 Journal
                              │
                              └── reverses / refunds ──▶ Journal (original)

IdempotencyKey ──(result)──▶ Journal   (optional link)
InvariantRun 1───* InvariantViolation
```

Columns, types, constraints and indexes are in the LLD.

---

## 10. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language / framework | Java 21, Spring Boot 4.1 | Your pick. Records and virtual threads are handy for the load test |
| Build | Maven | Common default. Gradle is fine too |
| DB access | **Spring JDBC (`JdbcClient`)**, not JPA | We want to *see* every SQL statement and lock. JPA's caching and dirty-checking hide exactly what we're learning *(ADR candidate)* |
| Migrations | Flyway | Schema versioned in git |
| DB | PostgreSQL 16 (Docker Compose) | Strong transactions, row locks, constraints, deferred triggers |
| Tests | JUnit 5 + Testcontainers | Concurrency bugs only show up on a real DB, not H2 |
| Load run | A Java test/CLI using virtual threads | No extra tool to learn |
| Observability | Structured logs with `journalId`, `idempotencyKey` | Enough for v1 |

---

## 11. Decisions the LLD will make (ADRs)

| ADR | Question |
|---|---|
| ADR-001 | Signed amounts vs separate debit/credit columns |
| ADR-002 | Spring JDBC vs JPA |
| ADR-003 | Locking strategy (pessimistic / optimistic / serializable) |
| ADR-004 | Idempotency: claim in a separate transaction vs same transaction as the ledger write |
| ADR-005 | How "balanced journal" and "append-only" are enforced in the DB |
