# ADR-002: Spring JDBC (`JdbcClient`) instead of JPA/Hibernate

**Status:** Accepted

## Context
We need precise control over SQL: `SELECT ... FOR UPDATE` in a specific order, `ON CONFLICT DO NOTHING`, conditional updates with a fencing token, `REPEATABLE READ` snapshots. The project's goal is to learn what the database is doing.

## Options
1. **JPA/Hibernate:** entities, repositories, `@Lock(PESSIMISTIC_WRITE)`, `@Version`.
2. **Spring JDBC (`JdbcClient`):** hand-written SQL mapped to Java records.
3. **jOOQ:** type-safe SQL DSL. Adds a code-generation step.

## Decision
Option 2, `JdbcClient`.

## Why
- Every statement and lock is visible in the code. Nothing happens implicitly.
- JPA features that get in the way here: the first-level cache can return a stale balance from memory, dirty checking issues UPDATEs at flush time in an order you don't control, and `saveAll` ordering isn't lock ordering.
- The append-only tables map naturally to "INSERT and never update", which JPA's entity lifecycle doesn't express well.

## Consequences
- More boilerplate: row mappers and SQL strings. Records keep this small.
- We handle SQL state codes ourselves (`23505` unique violation, `40P01` deadlock, `40001` serialization failure).
- Switching to JPA later is possible but wouldn't teach anything new.
