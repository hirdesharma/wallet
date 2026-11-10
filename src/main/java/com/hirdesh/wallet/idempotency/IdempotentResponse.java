package com.hirdesh.wallet.idempotency;

/**
 * The response to send. {@code body} is the exact JSON stored in the DB, so a replay
 * is byte-for-byte identical to the original.
 */
public record IdempotentResponse(int status, String body, boolean replayed) {
}
