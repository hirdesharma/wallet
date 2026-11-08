# ADR-004: Idempotency key design

**Status:** Accepted

## Context
Clients retry. We must move money **exactly once** per idempotency key, even when retries run concurrently (S-21), the app crashes mid-request, or a slow request overlaps its own retry.

## Options
1. **Check-then-insert in Java** (`if (!exists(key)) {...}`). Racy: two threads both see "not exists". ❌
2. **Single transaction:** insert the key + do the work + store the response, all in one tx. Concurrent duplicates block on the unique index until the first commits, then fail with `23505`. Simple, but concurrent duplicates *wait* holding a connection, and "in progress" can't be reported.
3. **Claim → work → complete with a lease + fencing token.** A separate short tx claims the key. The work and the completion commit together.

## Decision
Option 3 (see LLD §7).

## Why
- The claim is decided by the DB (`INSERT ... ON CONFLICT DO NOTHING` on the primary key): exactly one winner.
- Completion is in the **same transaction** as the ledger write, so there's never a state where "money moved but no response was stored", or the reverse.
- The **lease** recovers from crashes between claim and completion.
- The **fencing token** stops a slow original request (lease expired, key taken over) from committing a second money movement. Its completing UPDATE matches 0 rows, so it rolls back.
- It mirrors how payment APIs (e.g., Stripe) behave, which makes it worth learning.

## Consequences
- 4xx business outcomes are stored and replayed. A client that wants a fresh attempt must use a new key.
- 5xx / unexpected failures release the key, so a retry re-executes.
- Concurrent duplicates get an immediate `409 REQUEST_IN_PROGRESS` instead of waiting.
- Keys are kept forever in v1. A TTL cleanup job (e.g., delete keys older than 24h) is a later addition.
