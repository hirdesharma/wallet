# 00 — Concepts Primer

These are the ideas the rest of the project is built on. Each section ends with **"In our wallet"**, which says how the concept shows up in the design.

---

## 1. Why not just a `balance` column?

The naive version keeps one table, `wallet(id, balance)`, and moves money with `UPDATE wallet SET balance = balance - 100`.

It has three problems:
- **No history.** Once a balance is wrong, you can't tell *why*.
- **No proof.** Nothing guarantees that money moved out of A actually arrived in B.
- **Easy to corrupt.** One buggy update silently creates or destroys money.

Real money systems record *movements* and derive balances from them. That's what a ledger is.

---

## 2. Double-entry bookkeeping

The rule is that **money is never created or destroyed. It only moves between accounts.**

So every movement touches at least two accounts, and the amounts going out equal the amounts coming in.

| Term | Meaning |
|---|---|
| **Debit (Dr)** | One side of an entry |
| **Credit (Cr)** | The other side |
| **The rule** | For every movement, total debits = total credits |

Whether a debit *increases* or *decreases* an account depends on the account's type:

| Account type | Debit | Credit | Example in our wallet |
|---|---|---|---|
| **Asset** (what we have) | increases | decreases | Cash pool at the bank |
| **Liability** (what we owe) | decreases | increases | A user's wallet (we owe them that money) |

> A user wallet is a **liability** for the platform. If Alice has ₹500 in her wallet, the platform owes Alice ₹500.

**In our wallet:** we will store amounts as **signed numbers**: debit = `+`, credit = `−`. The balance rule then becomes simply **the postings of every journal sum to 0**, which is easy to check in code and SQL. (We'll confirm this in an ADR.)

---

## 3. Accounts, journals, postings

```
Account   — a bucket that holds value          (Alice's wallet, Cash Pool)
Journal   — one business event                 ("Alice sends Bob ₹200")
Posting   — one line of a journal hitting one account (+200 / −200)
```

```
Journal #42  "P2P transfer"  idempotency_key=abc
 ├─ Posting: Alice wallet   +200  (debit → her liability goes down)
 └─ Posting: Bob wallet     −200  (credit → his liability goes up)
                            ────
                    sum  =     0  ✅ balanced
```

A journal is **atomic**: either all its postings are written or none are, so it maps to one DB transaction.

---

## 4. Worked example

| # | Event | Postings | Sum |
|---|---|---|---|
| J1 | Alice tops up ₹500 | Cash Pool `+500`, Alice `−500` | 0 |
| J2 | Alice → Bob ₹200 | Alice `+200`, Bob `−200` | 0 |
| J3 | Bob withdraws ₹50 | Bob `+50`, Cash Pool `−50` | 0 |

Balances (sum of postings; liabilities shown flipped so they read naturally):

| Account | Postings | Raw sum | As user sees it |
|---|---|---|---|
| Cash Pool (asset) | +500, −50 | +450 | ₹450 in bank |
| Alice (liability) | −500, +200 | −300 | ₹300 |
| Bob (liability) | −200, +50 | −150 | ₹150 |

Check: **₹300 + ₹150 = ₹450**, which is exactly the cash pool. That's the reconciliation invariant from item 5, already visible here.

---

## 5. Stored vs derived balance

- **Derived:** `SELECT SUM(amount) FROM postings WHERE account_id = ?`. Always true, but it gets slower as postings grow.
- **Stored:** a `balance` column on the account, updated **in the same transaction** as the postings. Fast to read.

We do **both**. The stored balance is a cache, and the postings are the source of truth. The daily invariant job checks `stored balance == SUM(postings)` for every account.

---

## 6. Money as integers

Never use `float`/`double` for money (`0.1 + 0.2 = 0.30000000000000004`).

**In our wallet:** amounts are `BIGINT` in **minor units** (paise), so ₹12.50 is stored as `1250`. In Java we use `long`. Single currency (INR) for v1.

---

## 7. Idempotency

Networks fail. A client sends "transfer ₹200", the server does it, and the response gets lost. The client retries. Without protection, **₹400 moves**.

The fix is that the client sends a unique **idempotency key** with each logical request.

```
first request  (key=abc) → do the work, save (key, request hash, response)
retry          (key=abc) → don't do the work again, return the saved response
same key, different body → reject (409), because it's a client bug
```

The subtle part is **two retries arriving at the same time**. Both check "is key abc used?", both see "no", and both execute. So the key check must be enforced by the database (a unique constraint), not by an `if` in Java. We'll design this carefully in the LLD.

---

## 8. Concurrency: what goes wrong

Alice has ₹100. Two transfers of ₹80 arrive at the same moment:

```
T1: read balance = 100   ✅ enough
T2: read balance = 100   ✅ enough
T1: write balance = 20
T2: write balance = 20   ← lost update
```

Both transfers succeeded and ₹160 left Alice's wallet, yet her balance shows ₹20. The second transfer should have been rejected for insufficient funds.

This is the **check-then-act** race. These are the usual fixes:

| Approach | How | Trade-off |
|---|---|---|
| **Pessimistic lock** | `SELECT ... FOR UPDATE` on the account rows | Simple and correct; waiting transactions queue up |
| **Optimistic lock** | `version` column, `UPDATE ... WHERE version = ?`, retry on conflict | No waiting; retries get expensive under contention |
| **SERIALIZABLE isolation** | Let Postgres detect conflicts | Least code; must handle serialization failures with retries |
| **Conditional update** | `UPDATE ... SET balance = balance - 80 WHERE balance >= 80` | Atomic and fast; less flexible |

There's also a **deadlock trap**. If T1 locks Alice then Bob while T2 locks Bob then Alice, each waits on the other forever. The fix is to **always lock accounts in a fixed order** (e.g., by account id).

**In our wallet:** we'll pick one approach in the LLD and an ADR. Then in milestone 3 we'll *first watch the naive version fail* under 100 parallel transfers, and fix it.

---

## 9. Immutability: reversals, not edits

Ledger rows are **append-only**. We never `UPDATE` or `DELETE` a posting.

To undo or refund, we write a **new journal** with opposite postings, linked to the original:

```
J2        Alice +200, Bob −200          (transfer)
J7  → J2  Bob +200,   Alice −200        (full reversal of J2)
J8  → J2  Bob +50,    Alice −50         (partial refund of J2)
```

That gives us a full audit trail, and history can't be rewritten. We'll also enforce rules like "total refunded ≤ original amount".

---

## 10. Invariants & reconciliation

An **invariant** is a statement that must *always* be true. If it isn't, something is broken.

| Invariant | Catches |
|---|---|
| Every journal's postings sum to 0 | Unbalanced writes |
| Account `balance` = `SUM(postings)` | Cache drift, buggy updates |
| Sum of all user wallets = Cash Pool | Money created or lost |
| No user wallet below 0 | Overdraft bugs |

**Reconciliation** means regularly checking these, and in real systems comparing your ledger against an external source (the bank statement). We'll do the internal version: a scheduled job that reports any violation.

---

## Glossary

| Term | One-liner |
|---|---|
| Ledger | The complete, append-only record of all journals and postings |
| Journal | One balanced business event |
| Posting | One signed line of a journal against one account |
| Minor units | Smallest currency unit (paise) stored as an integer |
| Idempotency key | Client-supplied id making a request safe to retry |
| Reversal | A new journal that undoes an earlier one |
| Invariant | A condition that must always hold |
| Reconciliation | Verifying the ledger against itself / external truth |
