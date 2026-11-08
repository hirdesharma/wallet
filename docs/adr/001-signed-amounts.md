# ADR-001: Signed amounts for postings

**Status:** Accepted

## Context
A posting is either a debit or a credit. We need to store which side it's on, and an account's balance.

## Options
1. **Two columns:** `debit BIGINT`, `credit BIGINT`, with one of them 0. This mirrors paper ledgers.
2. **`amount` + `direction` enum:** `amount > 0`, `direction IN ('DEBIT','CREDIT')`.
3. **Signed amount:** debit = `+`, credit = `−`.

## Decision
Option 3, signed amounts.

## Why
- "Journal balanced" becomes `SUM(amount) = 0`, one expression in SQL and in Java.
- Balance becomes `SUM(amount)`, with no `CASE WHEN direction` everywhere.
- Global sanity check: the sum of *all* account balances is always 0.

## Consequences
- Liability accounts (user wallets) have **negative raw balances** when the user has money. The API converts with `userBalance = −rawBalance`. This conversion happens **only** at the API boundary, never inside the ledger.
- The overdraft check reads backwards: a wallet is overdrawn when raw balance `> 0`. Comments in code must say so.
- Reports for accountants would need a debit/credit view. That's easy to derive (`amount > 0` means debit).
