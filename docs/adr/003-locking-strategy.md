# ADR-003: Locking strategy for balance updates

**Status:** Accepted (2026-11-10, after the M3 experiment)

## Context
Two transactions touching the same wallet must not both pass the "enough funds?" check based on the same old balance (lost update / check-then-act race). The app may run as several instances, so in-memory locks don't work.

## Options
| Option | Mechanism |
|---|---|
| **Naive** (baseline, to prove the tests catch bugs) | Plain `SELECT`, check in Java, `UPDATE ... SET balance = <computed>` |
| **A. Pessimistic, ordered** | `SELECT ... FOR UPDATE` per account, ascending id |
| A′. Pessimistic, *unordered* (deadlock demo) | Same locks, but in posting order (sender first) |
| **B. Optimistic** | Plain read; `UPDATE ... WHERE version = :seen`; 0 rows → roll back, re-run the whole operation (max 10 attempts, random backoff) |
| C. SERIALIZABLE | Not measured: same retry-on-conflict behaviour as B, so the B numbers predict it |

## Evidence (M3 experiment, local Docker Postgres 16, HTTP end-to-end)

Code: `src/test/java/com/hirdesh/wallet/experiment/`. Pool = 10 connections unless noted.

### Correctness
| Strategy | S-30 100×₹1 A→B (A has ₹100) | S-31 same, A has ₹60 | Ledger consistent? |
|---|---|---|---|
| **Naive** | 100 "succeeded", but A still shows **₹73** and B only **₹27**. 73 updates overwritten | **100** succeeded (should be 60). True balance from postings: A **−₹40** | ❌ stored ≠ postings, A overdrawn |
| A. Pessimistic | 100 ok → A ₹0, B ₹100 | exactly 60 ok, 40 × 422 | ✅ |
| A′. Unordered | — | — | ✅ money correct, but see S-32 |
| B. Optimistic | 89 ok, **11 × 503** (retries exhausted) | 60 ok, 40 × 422 | ✅ |

### S-32: 50 A→B + 50 B→A in parallel
| Strategy | Result | Time |
|---|---|---|
| A. Pessimistic (ordered) | 100/100 ok | ~270 ms |
| A′. Unordered | **10–21 ok, 79–90 × 503** (deadlock victims, lock timeouts, pool exhaustion) | **32–38 s** |
| B. Optimistic | 86 ok, 14 × 503 | ~490 ms |

### Effect of connection-pool size
| Pool | Pessimistic S-30 | Optimistic S-30: ok / 503 / conflicts |
|---|---|---|
| 2 | 100 ok, 219 ms | 100 / 0 / 98 |
| 10 | 100 ok, 252 ms | 89 / 11 / 509 |
| 50 | 100 ok, 299 ms | **72 / 28 / 622** |

## Decision
**Option A: pessimistic `FOR UPDATE`, accounts locked one at a time in ascending id order.** Refunds and reversals lock the original journal first (order: journal → accounts). Option B stays in the code (`wallet.ledger.locking=OPTIMISTIC`) as a documented alternative. Naive and unordered exist only in test sources.

## Why
- **Only A was correct *and* complete in every scenario.** B never lost money, but it turned contention into failed requests (up to 28%).
- **Optimistic gets worse as concurrency grows.** More connections mean more transactions read the same version, so more of them lose. Our workload (one user paying many, a hot cash-pool row) is exactly the high-contention case.
- **Lock ordering matters as much as locking.** The same locks taken in the wrong order made S-32 ~130× slower, with 80–90% of requests failing.
- **The tests are proven to catch bugs.** The naive run corrupted the ledger and our checks flagged it, so a green run means something.

## Consequences
- **Hot rows serialize.** All writes to one account (and every top-up/withdraw on the Cash Pool) run one at a time. A bigger pool doesn't help, and pessimistic stayed ~200–300 ms at every pool size. Fixes for later: split the pool into N sub-accounts, or batch pool updates.
- **A waiting transaction holds a DB connection.** Pool size bounds DB concurrency, not request concurrency. Kept at **10** (sized to the DB, not to request threads), with `lock_timeout = 5s` so nothing waits forever.
- **Errors found by the experiment and fixed:**
  - Spring doesn't translate `55P03` lock timeouts into a locking exception.
  - Pool exhaustion surfaces as `CannotCreateTransactionException`.

  Both returned 500 and now return 503 `TRY_AGAIN` (see `ApiExceptionHandler.isRetryable`).
- If we ever switch to B: add idempotency-safe client retries and expect 503s under load.
