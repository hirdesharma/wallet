package com.hirdesh.wallet.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.hirdesh.wallet.idempotency.IdempotentResponse;

/** Turns a stored/fresh idempotent response into HTTP, returning the stored JSON verbatim. */
final class IdempotentResponses {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private IdempotentResponses() {
    }

    static ResponseEntity<String> toHttp(IdempotentResponse r) {
        var builder = ResponseEntity.status(r.status())
                .contentType(r.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
        if (r.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(r.body());
    }
}
