package com.hirdesh.wallet.ledger.locking;

/**
 * Optimistic check failed: someone else changed a row we read. The transaction must be
 * rolled back and the WHOLE operation re-run (re-read, re-check, re-write).
 */
public class ConcurrencyConflictException extends RuntimeException {

    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
