package com.hirdesh.wallet.idempotency;

/** A row of idempotency_keys as seen by a competing request. */
record IdempotencyRecord(
        String key,
        String requestHash,
        String status,
        boolean leaseExpired,      // computed in SQL with the DB clock, not the app clock
        Integer responseStatus,
        String responseBody) {

    boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
