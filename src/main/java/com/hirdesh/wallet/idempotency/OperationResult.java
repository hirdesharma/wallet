package com.hirdesh.wallet.idempotency;

/** What a successful money operation produced: the HTTP status + body to return (and store). */
public record OperationResult(int status, Object body, Long journalId) {

    public static OperationResult created(Object body, long journalId) {
        return new OperationResult(201, body, journalId);
    }
}
