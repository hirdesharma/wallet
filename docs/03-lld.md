# 03 — Low-Level Design

This doc is precise enough to code from. Design choices are justified in `docs/adr/`, and links to them appear inline.

---

## 1. Conventions

| Convention | Rule | ADR |
|---|---|---|
| Money | `BIGINT` / `long`, minor units (paise), INR only | — |
| Sign | Posting `amount` is signed: **debit = `+`, credit = `−`** | [ADR-001](adr/001-signed-amounts.md) |
| Stored balance | `accounts.balance` = raw `SUM(postings.amount)` (signed) | ADR-001 |
| User-facing balance | `−balance` for user wallets (liability), `balance` for pool (asset). Converted **only at the API boundary** | ADR-001 |
| DB access | Spring `JdbcClient`, explicit SQL | [ADR-002](adr/002-jdbc-over-jpa.md) |
| Transactions | `TransactionTemplate` (explicit), isolation READ COMMITTED unless stated | — |
| IDs | `BIGSERIAL`. Account id order = lock order | [ADR-003](adr/003-locking-strategy.md) |

> Quick reading aid: wallet raw balance `−30000` means the user sees **₹300.00**. A wallet raw balance **> 0** would mean overdraft and is forbidden.

---

## 2. Database schema

### 2.1 `accounts`
```sql
CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    type        TEXT   NOT NULL CHECK (type IN ('USER_WALLET', 'CASH_POOL')),
    user_id     TEXT   UNIQUE,                 -- set for USER_WALLET, NULL for system
    code        TEXT   UNIQUE,                 -- e.g. 'CASH_POOL' for system accounts
    balance     BIGINT NOT NULL DEFAULT 0,     -- raw signed sum of postings (cache)
    version     BIGINT NOT NULL DEFAULT 0,     -- only used in the optimistic-lock experiment (M3)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK ((type = 'USER_WALLET') = (user_id IS NOT NULL)),
    CHECK (type <> 'USER_WALLET' OR balance <= 0),   -- no overdraft (BR-3), last line of defense
    CHECK (type <> 'CASH_POOL'   OR balance >= 0)
);
```

### 2.2 `journals`
```sql
CREATE TABLE journals (
    id                    BIGSERIAL PRIMARY KEY,
    type                  TEXT   NOT NULL CHECK (type IN ('TOP_UP','TRANSFER','WITHDRAW','REFUND','REVERSAL')),
    amount                BIGINT NOT NULL CHECK (amount > 0),     -- business amount, for display & refund math
    reference_journal_id  BIGINT REFERENCES journals(id),         -- the original, for REFUND / REVERSAL
    idempotency_key       TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK ((type IN ('REFUND','REVERSAL')) = (reference_journal_id IS NOT NULL))
);

CREATE INDEX journals_reference_idx ON journals(reference_journal_id);

-- BR-10: a journal can be reversed at most once (enforced by DB, not just code)
CREATE UNIQUE INDEX journals_one_reversal_idx
    ON journals(reference_journal_id) WHERE type = 'REVERSAL';
```

### 2.3 `postings`
```sql
CREATE TABLE postings (
    id          BIGSERIAL PRIMARY KEY,
    journal_id  BIGINT NOT NULL REFERENCES journals(id),
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    amount      BIGINT NOT NULL CHECK (amount <> 0),   -- signed: + debit, − credit
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX postings_account_idx ON postings(account_id, id DESC);  -- history + SUM per account
CREATE INDEX postings_journal_idx ON postings(journal_id);
```

### 2.4 DB-level ledger guards ([ADR-005](adr/005-db-enforcement.md))
```sql
-- (a) Append-only: journals and postings can never be updated or deleted (BR-11)
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER journals_append_only BEFORE UPDATE OR DELETE ON journals
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER postings_append_only BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- (b) Balanced journal, checked at COMMIT (deferred), because postings
--     are inserted one by one and are temporarily unbalanced mid-transaction.
CREATE FUNCTION check_journal_balanced(jid BIGINT) RETURNS void AS $$
DECLARE s BIGINT; c INT;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*) INTO s, c FROM postings WHERE journal_id = jid;
    IF s <> 0 OR c < 2 THEN
        RAISE EXCEPTION 'journal % unbalanced (sum=%, postings=%)', jid, s, c;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE FUNCTION trg_journal_balanced() RETURNS trigger AS $$
BEGIN
    PERFORM check_journal_balanced(CASE TG_TABLE_NAME WHEN 'journals' THEN NEW.id ELSE NEW.journal_id END);
    RETURN NULL;
END $$ LANGUAGE plpgsql;

-- on journals: catches a journal with 0 postings
CREATE CONSTRAINT TRIGGER journals_balanced AFTER INSERT ON journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_journal_balanced();
-- on postings: catches a posting added later to an existing journal
CREATE CONSTRAINT TRIGGER postings_balanced AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trg_journal_balanced();
```

### 2.5 `idempotency_keys` ([ADR-004](adr/004-idempotency.md))
```sql
CREATE TABLE idempotency_keys (
    key              TEXT PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 100),
    request_hash     TEXT NOT NULL,          -- SHA-256 of (operation + path + canonical body)
    status           TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    owner_token      UUID,                   -- fencing token of current owner (while IN_PROGRESS)
    locked_until     TIMESTAMPTZ,            -- lease expiry (while IN_PROGRESS)
    response_status  INT,                    -- set when COMPLETED
    response_body    TEXT,                   -- exact JSON bytes returned; replayed verbatim (byte-identical)
    journal_id       BIGINT REFERENCES journals(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);
```

### 2.6 `invariant_runs`, `invariant_violations`
```sql
CREATE TABLE invariant_runs (
    id                BIGSERIAL PRIMARY KEY,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    status            TEXT NOT NULL CHECK (status IN ('RUNNING','PASSED','FAILED')),
    violation_count   INT NOT NULL DEFAULT 0
);

CREATE TABLE invariant_violations (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT NOT NULL REFERENCES invariant_runs(id),
    check_name   TEXT NOT NULL,     -- JOURNAL_BALANCED | BALANCE_MATCHES_POSTINGS | WALLETS_EQUAL_POOL | NO_NEGATIVE_WALLET
    account_id   BIGINT,
    journal_id   BIGINT,
    expected     BIGINT,
    actual       BIGINT,
    details      TEXT
);
```

### 2.7 Seed
```sql
INSERT INTO accounts (type, code) VALUES ('CASH_POOL', 'CASH_POOL');
```

### 2.8 Flyway files
```
V1__accounts.sql           2.1 + seed
V2__ledger.sql             2.2, 2.3
V3__ledger_guards.sql      2.4
V4__idempotency.sql        2.5
V5__invariants.sql         2.6
```

---

## 3. REST API

Base path `/api/v1`. Amounts are integers in paise. Errors use Spring `ProblemDetail` (RFC 7807) with an extra `code` field.

### 3.1 Endpoints

| Method & path | Body | Idem. key | Success |
|---|---|---|---|
| `POST /wallets` | `{ "userId": "alice" }` | no | 201 Wallet |
| `GET /wallets/{walletId}` | — | — | 200 Wallet |
| `GET /wallets/{walletId}/transactions?limit=20&before={postingId}` | — | — | 200 Page |
| `POST /wallets/{walletId}/top-ups` | `{ "amount": 50000 }` | **yes** | 201 Journal |
| `POST /wallets/{walletId}/withdrawals` | `{ "amount": 5000 }` | **yes** | 201 Journal |
| `POST /transfers` | `{ "fromWalletId": 2, "toWalletId": 3, "amount": 20000 }` | **yes** | 201 Journal |
| `POST /journals/{journalId}/refunds` | `{ "amount": 5000 }` | **yes** | 201 Journal |
| `POST /journals/{journalId}/reversals` | `{}` | **yes** | 201 Journal |
| `GET /journals/{journalId}` | — | — | 200 Journal |
| `POST /admin/invariant-runs` | — | no | 200 Run (synchronous) |
| `GET /admin/invariant-runs/{runId}` | — | — | 200 Run |

### 3.2 Shapes
```jsonc
// Wallet
{ "walletId": 2, "userId": "alice", "balance": 30000, "currency": "INR" }   // user-facing (positive)

// Journal
{
  "journalId": 42, "type": "TRANSFER", "amount": 20000,
  "referenceJournalId": null, "createdAt": "2026-11-06T10:00:00Z",
  "postings": [
    { "accountId": 2, "amount":  20000 },
    { "accountId": 3, "amount": -20000 }
  ]
}

// Transaction history item (from the wallet's perspective)
{ "postingId": 901, "journalId": 42, "type": "TRANSFER", "direction": "OUT", "amount": 20000, "createdAt": "..." }
// direction: "IN" (money in) | "OUT" (money out), derived from posting sign

// Error
{ "type": "about:blank", "title": "Unprocessable Entity", "status": 422,
  "code": "INSUFFICIENT_FUNDS", "detail": "Wallet 2 has 10000, needs 20000" }
```

Replayed idempotent responses carry the **same status and body** as the original, plus the header `Idempotent-Replayed: true`.

### 3.3 Error codes

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | amount ≤ 0, > max, missing fields, self-transfer |
| 400 | `IDEMPOTENCY_KEY_MISSING` | header absent on a money endpoint |
| 404 | `WALLET_NOT_FOUND` / `JOURNAL_NOT_FOUND` | |
| 409 | `WALLET_EXISTS` | userId already has a wallet |
| 409 | `IDEMPOTENCY_KEY_REUSED` | same key, different request |
| 409 | `REQUEST_IN_PROGRESS` | same key, first request still running |
| 422 | `INSUFFICIENT_FUNDS` | |
| 422 | `REFUND_EXCEEDS_ORIGINAL` | |
| 422 | `NOT_REFUNDABLE` / `NOT_REVERSIBLE` | wrong journal type |
| 422 | `ALREADY_REVERSED` | |
| 503 | `TRY_AGAIN` | deadlock / DB error; safe to retry with same key |

---

## 4. Code structure

```
com.hirdesh.wallet
├── api/           WalletController, TransferController, JournalController, AdminController,
│                  dto/ (request/response records), ApiExceptionHandler
├── idempotency/   IdempotencyService, IdempotencyRepository, RequestHasher, IdempotentResult
├── wallet/        WalletOperations            ← builds journal recipes + business rules
├── ledger/        LedgerService (post), LedgerRepository,
│                  JournalDraft, PostingDraft, JournalType, Journal, Posting
├── account/       AccountService, AccountRepository, Account, AccountType
├── invariants/    InvariantChecker, InvariantJob (@Scheduled), InvariantRepository
└── common/        WalletException hierarchy (with code + HTTP status)
```

Core types:
```java
record PostingDraft(long accountId, long amount) {}
record JournalDraft(JournalType type, long amount, Long referenceJournalId,
                    String idempotencyKey, List<PostingDraft> postings) {}

// ledger — the ONLY writer of money tables. Must be called inside a transaction.
Journal LedgerService.post(JournalDraft draft);

// idempotency — wraps any money operation
<T> IdempotentResult<T> IdempotencyService.execute(String key, String requestHash,
                                                   Supplier<T> work);
```

---

## 5. Ledger `post()` algorithm

Runs **inside** the caller's transaction ([ADR-003](adr/003-locking-strategy.md): pessimistic, ordered).

```
post(draft):
  1. validate draft:  ≥ 2 postings, every amount ≠ 0, SUM(amounts) == 0     → else IllegalStateException (bug)
  2. ids = distinct accountIds, sorted ascending
  3. for id in ids:                               -- one at a time, in order → no deadlock
        SELECT id, type, balance FROM accounts WHERE id = ? FOR UPDATE   → missing? WALLET_NOT_FOUND
  4. for each account: newBalance = balance + Σ(its postings in draft)
        if type == USER_WALLET and newBalance > 0  → INSUFFICIENT_FUNDS
  5. INSERT INTO journals ... RETURNING id
  6. INSERT INTO postings ... (batch)
  7. for each account: UPDATE accounts SET balance = balance + :delta WHERE id = :id
  8. return Journal                               -- deferred trigger re-checks balance at COMMIT
```

Why the check in step 4 is safe: once we hold `FOR UPDATE` on the row, nobody else can change its balance until we commit, so *check-then-act* is no longer a race.

---

## 6. Operation recipes (`WalletOperations`)

`W` = wallet account, `P` = cash pool, `x` = amount.

| Op | Pre-checks (before `post`) | Postings |
|---|---|---|
| `topUp(W, x)` | W exists | `P +x`, `W −x` |
| `withdraw(W, x)` | W exists | `W +x`, `P −x` |
| `transfer(A, B, x)` | A ≠ B, both exist | `A +x`, `B −x` |
| `refund(J, r)` | see 6.1 | `receiver +r`, `sender −r` |
| `reverse(J)` | see 6.2 | each posting of J with sign flipped |

### 6.1 Refund (concurrency-safe)
```
1. SELECT * FROM journals WHERE id = J FOR UPDATE      ← serializes all refunds of J
     (FOR UPDATE does NOT fire the append-only UPDATE trigger, so it's allowed)
2. J.type must be TRANSFER                               → else NOT_REFUNDABLE
3. refunded = SELECT COALESCE(SUM(amount),0) FROM journals WHERE reference_journal_id = J AND type = 'REFUND'
4. refunded + r ≤ J.amount                               → else REFUND_EXCEEDS_ORIGINAL
5. post(REFUND, r, ref=J, postings: receiver +r, sender −r)   ← receiver may have spent it → INSUFFICIENT_FUNDS
```
The step 3 read runs *after* the lock in step 1. Under READ COMMITTED each statement sees the latest committed data, so it sees refunds committed by whoever held the lock before us. **Lock order is always journal → accounts**, so there's no deadlock.

### 6.2 Reversal
```
1. SELECT * FROM journals WHERE id = J FOR UPDATE
2. J.type must be TOP_UP or WITHDRAW                       → else NOT_REVERSIBLE
3. post(REVERSAL, J.amount, ref=J, postings = J.postings negated)
4. unique index journals_one_reversal_idx → a second reversal fails with 23505 → ALREADY_REVERSED
```

---

## 7. Idempotency flow ([ADR-004](adr/004-idempotency.md))

There are three transactions, and the important one (**Tx2**) writes the money **and** marks the key completed atomically.

```
execute(key, hash, work):

  ── Tx1: CLAIM (short, commits immediately) ─────────────────────────────
  token = random UUID
  INSERT INTO idempotency_keys(key, request_hash, status, owner_token, locked_until)
       VALUES (?, ?, 'IN_PROGRESS', token, now() + 30s)
  ON CONFLICT (key) DO NOTHING
  → inserted?  we own it, go to Tx2
  → else SELECT row:
       hash differs                          → 409 IDEMPOTENCY_KEY_REUSED
       COMPLETED                             → replay stored response   (done)
       IN_PROGRESS and locked_until > now()  → 409 REQUEST_IN_PROGRESS
       IN_PROGRESS and expired               → try takeover:
           UPDATE ... SET owner_token = token, locked_until = now()+30s
           WHERE key = ? AND status = 'IN_PROGRESS' AND locked_until < now()
           1 row → we own it, go to Tx2;  0 rows → someone else won, re-read & decide again

  ── Tx2: WORK + COMPLETE (one transaction) ──────────────────────────────
  result = work.get()                          -- e.g. ledger.post(...)
  UPDATE idempotency_keys SET status='COMPLETED', response_status=?, response_body=?,
         journal_id=?, completed_at=now(), owner_token=NULL, locked_until=NULL
   WHERE key = ? AND owner_token = token       ← FENCING: 0 rows ⇒ we lost ownership
  0 rows → throw → whole Tx2 rolls back (money is NOT moved twice)
  COMMIT

  ── Tx3: on failure of Tx2 ──────────────────────────────────────────────
  business error (4xx, e.g. INSUFFICIENT_FUNDS) → store it as COMPLETED response (deterministic replay)
  unexpected error (5xx, deadlock, DB down)     → DELETE key WHERE owner_token = token  (client may retry)
```

Why each piece exists:
- **`ON CONFLICT DO NOTHING` on the primary key.** It's what makes S-21 (20 concurrent identical requests) safe. Exactly one insert wins, decided by the DB.
- **Lease (`locked_until`).** If the app crashes after the claim, the key isn't stuck forever.
- **Fencing token (`owner_token`).** A slow original request whose lease expired can't complete after someone else took over. Its Tx2 matches 0 rows and rolls back its own money movement.
- **Storing 4xx outcomes.** A retry gets the same answer, which is the behaviour Stripe uses. To try again for real, the client uses a new key.

Request hash = `SHA-256(operation + "\n" + parsed params in fixed order)`, e.g. `TRANSFER\n2\n3\n20000`. Hashing parsed values (not raw JSON) means whitespace/field order in the client body doesn't matter.

Only requests that pass validation (layer 1) claim a key, so a 400 is never stored and the key stays usable.

---

## 8. Invariant checker

All four checks run in **one `REPEATABLE READ, READ ONLY` transaction**, so they see a single consistent snapshot even while transfers continue. Under READ COMMITTED, check 2 could compare a balance and a SUM taken at different moments and report false violations.

```sql
-- 1. JOURNAL_BALANCED
SELECT j.id, COALESCE(SUM(p.amount),0) AS s, COUNT(p.id) AS c
FROM journals j LEFT JOIN postings p ON p.journal_id = j.id
GROUP BY j.id HAVING COALESCE(SUM(p.amount),0) <> 0 OR COUNT(p.id) < 2;

-- 2. BALANCE_MATCHES_POSTINGS
SELECT a.id, a.balance, COALESCE(SUM(p.amount),0) AS derived
FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
GROUP BY a.id HAVING a.balance <> COALESCE(SUM(p.amount),0);

-- 3. WALLETS_EQUAL_POOL      (raw signs: pool + wallets must be 0)
SELECT COALESCE(SUM(balance) FILTER (WHERE type='USER_WALLET'),0) AS wallets,
       COALESCE(SUM(balance) FILTER (WHERE type='CASH_POOL'),0)   AS pool
FROM accounts;                                  -- violation if pool <> -wallets

-- 4. NO_NEGATIVE_WALLET     (raw > 0 ⇒ user-facing negative)
SELECT id, balance FROM accounts WHERE type='USER_WALLET' AND balance > 0;
```

Then the job writes an `invariant_runs` row and one `invariant_violations` row per finding, and logs at ERROR if anything is found. It's scheduled with `@Scheduled(cron = "0 0 2 * * *")` and can also be triggered via `POST /admin/invariant-runs`.

> Note: checks 1 and 4 also have DB guards, so they "can't" fail. We check anyway, because detection shouldn't rely on prevention working.

---

## 9. Testing strategy

| Level | Tool | Covers |
|---|---|---|
| Unit | JUnit 5 | `JournalDraft` validation, recipe building, request hashing |
| Integration | Testcontainers Postgres + `@SpringBootTest` | Every S-xx scenario, through HTTP (`RestClient`) or the service layer |
| Concurrency | `ExecutorService` with virtual threads + `CountDownLatch` start gate | S-21, S-30–33, S-43 |
| DB guards | Raw SQL in tests | S-01–03 (bypass Java, prove the DB refuses) |
| Load | Separate `LoadRunIT` (tagged, not in default build) | S-52 |

Every integration test ends by **running the invariant checker and asserting 0 violations**, so each test also checks the ledger.

Test isolation: `TRUNCATE ... RESTART IDENTITY CASCADE` between tests, then re-seed the pool. `TRUNCATE` doesn't fire row-level `DELETE` triggers, so the append-only guard doesn't block cleanup.

---

## 10. Milestone mapping

| Milestone | LLD sections built |
|---|---|
| M1 Ledger core | 2.1–2.4, 2.7, 5 (+ S-01–03) |
| M2 APIs + idempotency | 2.5, 3, 4, 6 (top-up/transfer/withdraw), 7 |
| M3 Concurrency | Remove locks → watch S-30/31 fail → restore §5, try optimistic variant, finalize ADR-003 |
| M4 Refunds & reversals | 6.1, 6.2 |
| M5 Invariants + load | 2.6, 8, S-50–52 |

---

## 11. Open items (decided in the milestone that needs them)

| # | Item | Decide in |
|---|---|---|
| O-1 | ✅ Wallet creation: plain INSERT, `DuplicateKeyException` (23505) → `WALLET_EXISTS`. History: keyset pagination `p.id < :before ORDER BY p.id DESC LIMIT n`, response has `nextBefore` | M2 |
| O-2 | ✅ `PessimisticLockingFailureException` (40P01 deadlock, 55P03 lock timeout, 40001) + transient/connection errors → 503 `TRY_AGAIN`; trigger/CHECK violations reaching the handler = our bug → 500. See `ApiExceptionHandler` | M2 |
| O-3 | ✅ partly: `lock_timeout = 5s` via Hikari `connection-init-sql`. Pool size vs. thread count → M3 | M2 / M3 |
| O-4 | How to switch locking strategy for the experiment (strategy interface vs config flag) | M3 |
| O-5 | Load-run design: operation mix, duplicate-retry injection, wallet count, thread count | M5 |
| O-6 | Running the scheduled job on multiple instances (ShedLock / advisory lock). Out of scope for v1, noted only | — |
