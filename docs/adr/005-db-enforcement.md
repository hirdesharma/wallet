# ADR-005: Enforcing ledger rules in the database

**Status:** Accepted

## Context
Java code has bugs. Someone may also run SQL by hand. The core ledger rules should hold even then.

## Rules and how each is enforced

| Rule | Mechanism | Why this one |
|---|---|---|
| Journal balanced (Σ = 0, ≥ 2 postings) | **Deferred constraint triggers** on `journals` and `postings` (checked at COMMIT) | Postings are inserted one by one, so the journal is legitimately unbalanced mid-transaction. A plain `CHECK` can't look at other rows, and an immediate trigger would fire too early |
| Append-only | `BEFORE UPDATE OR DELETE` trigger raising an exception | Works for every role. Alternative: `REVOKE UPDATE, DELETE` from the app's DB user, which is stronger in production (could do both) |
| No wallet overdraft | `CHECK (type <> 'USER_WALLET' OR balance <= 0)` | Single-row rule, so a plain CHECK is enough |
| Reverse once | Partial unique index on `journals(reference_journal_id) WHERE type='REVERSAL'` | A uniqueness rule is exactly what unique indexes do, and they're race-free |
| Idempotency key unique | Primary key on `idempotency_keys.key` | Same as above |

## Not enforced in the DB (on purpose)
- **Refund total ≤ original.** It needs a cross-row sum. A trigger could do it, but we enforce it in code under a row lock (LLD §6.1) and *detect* problems via the invariant job. This is a deliberate example of "app-enforced + DB-detected".
- **Stored balance = SUM(postings).** A trigger maintaining balances would work, but it hides the logic. The ledger code updates balances explicitly and the invariant job verifies them.

## Consequences
- Deferred trigger cost: one `SUM` per inserted posting at commit. Fine at our scale. At high scale you'd check per journal once, or trust the app plus detection.
- Test cleanup uses `TRUNCATE` (row triggers don't fire).
- Error messages from triggers surface as `PSQLException`. If one is ever hit in the normal flow, that's a bug in our code and maps to 500.
