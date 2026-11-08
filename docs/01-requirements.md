# 01 — Requirements & Scope

This doc says **what** the system does, not **how**. The how lives in the HLD and LLD.

---

## 1. Goal

A single-currency (INR) digital wallet backed by a double-entry ledger. Users can add money, send it to each other and withdraw it. Money is never created, lost or double-spent, even under retries and heavy concurrency.

---

## 2. Actors

| Actor | What they do |
|---|---|
| **User** (via client app) | Tops up, transfers, withdraws, views balance and history |
| **Ops / System** | Issues refunds and reversals, runs the invariant job |
| **External payment gateway / bank** | *Simulated.* Assumed to always succeed in v1 |

---

## 3. Functional requirements

### Accounts
- **FR-1** Create a user wallet. One wallet per user, starting balance 0.
- **FR-2** System accounts exist from startup: **Cash Pool** (asset, represents money held at our bank).

### Money movement
- **FR-3 Top-up.** Add money to a wallet from outside. Ledger: `Cash Pool +X, Wallet −X`.
- **FR-4 P2P transfer.** Move money from wallet A to wallet B. Ledger: `A +X, B −X`.
- **FR-5 Withdraw.** Send money from a wallet out to the user's bank. Ledger: `Wallet +X, Cash Pool −X`.
- **FR-6** Every money-movement request **requires an idempotency key** (header `Idempotency-Key`).

### Corrections
- **FR-7 Refund (transfer).** Return all or part of a P2P transfer from receiver to sender. This can be done several times, but the total refunded must be ≤ the original amount.
- **FR-8 Reversal (top-up / withdraw).** Fully undo a top-up (e.g., a card chargeback) or a withdrawal (e.g., the bank payout bounced). Each journal can be reversed at most once.
- **FR-9** Refunds and reversals are **new journals linked to the original**. Nothing is edited.

### Reads
- **FR-10** Get wallet balance.
- **FR-11** Get wallet transaction history, newest first and paginated.
- **FR-12** Get a single journal with its postings.

### Integrity
- **FR-13 Invariant job.** It runs on a schedule (daily) and can also be triggered via an endpoint. It checks:
  1. every journal sums to 0
  2. each account's stored balance = `SUM(postings)`
  3. sum of user wallets = Cash Pool
  4. no user wallet is negative

  It reports violations: it logs them, stores a run record, and exposes them via an endpoint. **It never auto-fixes.**

---

## 4. Business rules

| # | Rule | On violation |
|---|---|---|
| BR-1 | Amount is an integer in paise, `> 0` | 400 |
| BR-2 | Max single transaction ₹1,00,000 (`10_000_000` paise) | 400 |
| BR-3 | A wallet can't go below 0 (transfer, withdraw, refund, top-up reversal) | 422 `INSUFFICIENT_FUNDS` |
| BR-4 | You can't transfer to yourself | 400 |
| BR-5 | Both wallets must exist | 404 |
| BR-6 | Same idempotency key + same request body → return the original response, with no new work | 200 / 201 (replayed) |
| BR-7 | Same idempotency key + different body | 409 `IDEMPOTENCY_KEY_REUSED` |
| BR-8 | Same key while the first request is still in flight | 409 `REQUEST_IN_PROGRESS` (client retries later) |
| BR-9 | Total refunds on a transfer ≤ original amount | 422 `REFUND_EXCEEDS_ORIGINAL` |
| BR-10 | A journal can only be reversed once, and a reversal can't itself be reversed | 422 `ALREADY_REVERSED` |
| BR-11 | Posted journals and postings are never updated or deleted | Enforced at DB level |

---

## 5. Non-functional requirements

| # | Requirement | Target / how we'll verify |
|---|---|---|
| NFR-1 | **Correctness over everything** | Invariant job reports 0 violations after every test |
| NFR-2 | **Atomicity** | A journal is fully written or not at all (one DB transaction) |
| NFR-3 | **Concurrency safety** | 100 parallel transfers on the same wallets end with exact balances |
| NFR-4 | **Retry safety** | N retries of one request move money exactly once |
| NFR-5 | **No deadlock hangs** | Opposing transfers (A→B and B→A) running concurrently all complete |
| NFR-6 | **Sustained load** | 10-min random load run (mixed operations, many threads) → 0 invariant violations |
| NFR-7 | **Auditability** | Any balance can be explained from its postings |
| NFR-8 | Performance | *Not a goal for v1.* We'll measure and note throughput, but not optimise |

---

## 6. Out of scope (v1)

These are deliberately excluded, so you know what's *not* there. Each could be a later extension.

| Excluded | Why / later idea |
|---|---|
| Authentication / authorization | The user id is passed in the request. Spring Security could be added later |
| Multi-currency | Needs FX accounts and rates. Good v2 topic |
| Fees | Would add a **Revenue** account to journals. Easy v2 add |
| Real payment gateway / bank | Simulated as always-succeeds. Async/pending flows are a good v2 topic |
| Pending / two-phase transactions (holds) | v2: authorize → capture |
| KYC, limits per day, fraud | Not ledger concepts |
| Wallet freeze / close | Small addition later |
| Microservices, queues, caching | One service + Postgres is enough to learn every concept here |
| External reconciliation (vs bank statement) | We do internal reconciliation only |

---

## 7. Acceptance scenarios (our test suite)

These replace the "TST scenario tests". Each one becomes an automated test.

### Ledger core
| ID | Scenario | Expected |
|---|---|---|
| S-01 | Write a journal whose postings don't sum to 0 | Rejected, nothing persisted |
| S-02 | Write a journal with < 2 postings | Rejected |
| S-03 | Try to UPDATE/DELETE a posting directly in DB | Fails |

### Money movement
| ID | Scenario | Expected |
|---|---|---|
| S-10 | Top-up ₹500 to new wallet | Balance 500, pool 500 |
| S-11 | Transfer ₹200 A→B | A −200, B +200, pool unchanged |
| S-12 | Withdraw ₹50 | Wallet −50, pool −50 |
| S-13 | Transfer more than balance | 422, no balances change |
| S-14 | Withdraw more than balance | 422, no balances change |
| S-15 | Transfer to self / to missing wallet / amount 0 / negative / over max | 400 or 404 |

### Idempotency
| ID | Scenario | Expected |
|---|---|---|
| S-20 | Same transfer sent 5× sequentially with the same key | Money moves once, all 5 responses identical |
| S-21 | Same transfer sent 20× **concurrently** with the same key | Money moves once |
| S-22 | Same key, different amount | 409 |
| S-23 | Missing idempotency key | 400 |

### Concurrency
| ID | Scenario | Expected |
|---|---|---|
| S-30 | 100 parallel transfers of ₹1 A→B (A has ₹100) | A = 0, B = +100 |
| S-31 | 100 parallel transfers of ₹1 A→B where A has only ₹60 | Exactly 60 succeed, 40 get 422, A = 0 |
| S-32 | 50 A→B + 50 B→A in parallel | All complete (no deadlock hang), totals correct |
| S-33 | Many wallets, random parallel transfers | Sum of wallets unchanged |

### Refunds & reversals
| ID | Scenario | Expected |
|---|---|---|
| S-40 | Full refund of a transfer | Balances back to original, new journal linked |
| S-41 | Two partial refunds within the original amount | Both succeed |
| S-42 | Partial refunds exceeding the original amount | The one that crosses the limit → 422 |
| S-43 | Concurrent refunds that together exceed the original | Total refunded never exceeds original |
| S-44 | Refund when receiver already spent the money | 422 `INSUFFICIENT_FUNDS` |
| S-45 | Reverse a withdrawal | Money back in wallet and pool |
| S-46 | Reverse the same journal twice | Second → 422 |

### Invariants
| ID | Scenario | Expected |
|---|---|---|
| S-50 | Run job on a healthy ledger | 0 violations |
| S-51 | Manually corrupt a stored balance, run job | Violation reported for that account |
| S-52 | 10-min random load (all operations, incl. retries & refunds), then job | 0 violations |

---

## 8. Definition of done (whole project)

- [ ] All S-xx scenarios pass as automated tests (Testcontainers + real Postgres)
- [ ] S-52 load run: 10 minutes, 0 invariant violations
- [ ] HLD, LLD and ADRs match the final code
