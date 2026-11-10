package com.hirdesh.wallet.idempotency;

import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hirdesh.wallet.common.ApiErrors;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;

import tools.jackson.databind.ObjectMapper;

/**
 * Runs a money operation at most once per idempotency key. LLD §7 / ADR-004.
 *
 * <pre>
 *  Tx1 CLAIM      insert key (or replay / 409 / take over expired lease)   — commits alone
 *  Tx2 WORK       ledger write + mark COMPLETED with response             — ONE transaction
 *  Tx3 ON FAILURE 4xx → store as COMPLETED (replayed later); else → release key
 * </pre>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final IdempotencyRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final int leaseSeconds;

    public IdempotencyService(IdempotencyRepository repo, TransactionTemplate tx, ObjectMapper json,
                              @Value("${wallet.idempotency.lease-seconds}") int leaseSeconds) {
        this.repo = repo;
        this.tx = tx;
        this.json = json;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * @param work runs INSIDE the Tx2 transaction; it must do all its DB writes through
     *             the current transaction (e.g. LedgerService.post).
     */
    public IdempotentResponse execute(String key, String requestHash, Supplier<OperationResult> work) {
        UUID token = UUID.randomUUID();

        // ── Tx1: claim ─────────────────────────────────────────────
        IdempotentResponse replay = claim(key, requestHash, token);
        if (replay != null) {
            return replay;
        }

        // ── Tx2: work + complete, atomically ───────────────────────
        try {
            return tx.execute(status -> {
                OperationResult result = work.get();
                String body = json.writeValueAsString(result.body());
                if (repo.complete(key, token, result.status(), body, result.journalId()) == 0) {
                    // FENCING: our lease expired and someone took over. Throwing rolls back
                    // the money movement we just made, so it can't happen twice.
                    throw new WalletException(ErrorCode.TRY_AGAIN, "Lost ownership of idempotency key " + key);
                }
                return new IdempotentResponse(result.status(), body, false);
            });
        } catch (WalletException e) {
            // ── Tx3a: business outcome (4xx) is final → store it, replay it on retry ──
            if (e.code().httpStatus() < 500) {
                String body = json.writeValueAsString(ApiErrors.body(e.code(), e.getMessage()));
                repo.complete(key, token, e.code().httpStatus(), body, null);
                return new IdempotentResponse(e.code().httpStatus(), body, false);
            }
            repo.release(key, token);
            throw e;
        } catch (RuntimeException e) {
            // ── Tx3b: unexpected (lock timeout, DB down, bug) → free the key, client may retry ──
            log.warn("Releasing idempotency key {} after failure: {}", key, e.toString());
            repo.release(key, token);
            throw e;
        }
    }

    /** @return a stored response to replay, or null if WE now own the key. */
    private IdempotentResponse claim(String key, String requestHash, UUID token) {
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            if (repo.tryClaim(key, requestHash, token, leaseSeconds)) {
                return null;
            }
            IdempotencyRecord existing = repo.find(key).orElse(null);
            if (existing == null) {
                continue; // released between our INSERT and SELECT → try to claim again
            }
            if (!existing.requestHash().equals(requestHash)) {
                throw new WalletException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key " + key + " was already used for a different request");
            }
            if (existing.isCompleted()) {
                return new IdempotentResponse(existing.responseStatus(), existing.responseBody(), true);
            }
            if (!existing.leaseExpired()) {
                throw new WalletException(ErrorCode.REQUEST_IN_PROGRESS,
                        "A request with idempotency key " + key + " is still being processed");
            }
            if (repo.tryTakeover(key, token, leaseSeconds)) {
                log.warn("Took over idempotency key {} after its lease expired", key);
                return null;
            }
            // someone else took it over or completed it first → look again
        }
        throw new WalletException(ErrorCode.REQUEST_IN_PROGRESS,
                "A request with idempotency key " + key + " is still being processed");
    }
}
