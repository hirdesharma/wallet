# ADR-003: Locking strategy for balance updates

**Status:** Proposed. To be confirmed with evidence in Milestone 3.

## Context
Two transactions touching the same wallet must not both pass the "enough funds?" check based on the same old balance (lost update / check-then-act race). The app may run as several instances, so in-memory locks don't work.

## Options
| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| A. **Pessimistic, ordered** | `SELECT ... FOR UPDATE` on each account in ascending id order, READ COMMITTED | Simple to reason about. No retries needed. Ordered locking prevents deadlocks | Contending transactions wait. Hot accounts (cash pool) serialize |
| B. Optimistic | `version` column; `UPDATE ... WHERE id=? AND version=?`; retry on 0 rows | No waiting, good under low contention | Under the 100-parallel test most attempts conflict, so we get a retry storm and need retry/backoff code |
| C. SERIALIZABLE | Postgres SSI detects conflicts | Least locking code | Must retry on `40001` anywhere. Failures are harder to reason about. Still needs retry logic |
| D. Conditional update | `UPDATE accounts SET balance = balance + :d WHERE id=? AND balance + :d <= 0` | One statement, atomic, fast | Multi-posting journals need every update to succeed, so partial failure means rollback. Less general |

## Decision (proposed)
**Option A.** Pessimistic `FOR UPDATE`, accounts locked one by one in ascending id order. Refunds and reversals lock the original journal first (order: journal → accounts).

## Why
- Our test workload (S-30: 100 transfers on the *same* two wallets) is high contention, which is exactly where optimistic locking does worst.
- No retry loops in business code means fewer places for bugs.
- Ordered acquisition turns deadlocks (S-32) from "possible" into "impossible by construction".

## How M3 will validate this
1. Run S-30/S-31 with **no locking** and record the wrong balances.
2. Run with option A: correct, with recorded duration.
3. Run with option B (using the `version` column): correct with retries, with recorded retry counts and duration.
4. Update this ADR with the numbers and mark it Accepted.

## Consequences
- The Cash Pool row is locked by every top-up and withdraw, which puts a ceiling on throughput. That's acceptable for v1. Known fixes for later: split the pool into N sub-accounts, or batch pool updates.
- Lock waits need a bound: set `lock_timeout` (e.g., 5s) so a stuck transaction fails with 503 instead of hanging.
