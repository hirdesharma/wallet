package com.hirdesh.wallet.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement here is a single atomic SQL statement. The database, not Java,
 * decides who wins a race (ADR-004). All times use the DB clock (now()).
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if WE inserted the key (we own it). Exactly one concurrent caller can win. */
    public boolean tryClaim(String key, String requestHash, UUID token, int leaseSeconds) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (key, request_hash, status, owner_token, locked_until)
                        VALUES (:key, :hash, 'IN_PROGRESS', :token, now() + make_interval(secs => :lease))
                        ON CONFLICT (key) DO NOTHING""")
                .param("key", key)
                .param("hash", requestHash)
                .param("token", token)
                .param("lease", leaseSeconds)
                .update() == 1;
    }

    Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT key, request_hash, status, (locked_until < now()) AS lease_expired,
                               response_status, response_body
                        FROM idempotency_keys WHERE key = :key""")
                .param("key", key)
                .query((rs, i) -> new IdempotencyRecord(
                        rs.getString("key"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getBoolean("lease_expired"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body")))
                .optional();
    }

    /** Take over a key whose owner's lease expired (e.g. the app crashed). */
    public boolean tryTakeover(String key, UUID token, int leaseSeconds) {
        return jdbc.sql("""
                        UPDATE idempotency_keys
                           SET owner_token = :token, locked_until = now() + make_interval(secs => :lease)
                         WHERE key = :key AND status = 'IN_PROGRESS' AND locked_until < now()""")
                .param("key", key)
                .param("token", token)
                .param("lease", leaseSeconds)
                .update() == 1;
    }

    /**
     * Mark COMPLETED — only if we still own the key (fencing).
     * @return 1 if completed, 0 if someone else took over.
     */
    public int complete(String key, UUID token, int status, String body, Long journalId) {
        return jdbc.sql("""
                        UPDATE idempotency_keys
                           SET status = 'COMPLETED', response_status = :status, response_body = :body,
                               journal_id = :journalId, completed_at = now(),
                               owner_token = NULL, locked_until = NULL
                         WHERE key = :key AND status = 'IN_PROGRESS' AND owner_token = :token""")
                .param("key", key)
                .param("token", token)
                .param("status", status)
                .param("body", body)
                .param("journalId", journalId)
                .update();
    }

    /** Give the key back after an unexpected failure, so the client's retry runs again. */
    void release(String key, UUID token) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE key = :key AND status = 'IN_PROGRESS' AND owner_token = :token")
                .param("key", key)
                .param("token", token)
                .update();
    }
}
