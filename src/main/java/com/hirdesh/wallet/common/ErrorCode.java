package com.hirdesh.wallet.common;

/** Business error codes from LLD §3.3, each with its HTTP status. */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    IDEMPOTENCY_KEY_MISSING(400),
    WALLET_NOT_FOUND(404),
    JOURNAL_NOT_FOUND(404),
    WALLET_EXISTS(409),
    IDEMPOTENCY_KEY_REUSED(409),
    REQUEST_IN_PROGRESS(409),
    INSUFFICIENT_FUNDS(422),
    REFUND_EXCEEDS_ORIGINAL(422),
    NOT_REFUNDABLE(422),
    NOT_REVERSIBLE(422),
    ALREADY_REVERSED(422),
    TRY_AGAIN(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
