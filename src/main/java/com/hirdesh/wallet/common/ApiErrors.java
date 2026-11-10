package com.hirdesh.wallet.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * One error body shape (RFC 7807 + "code") used both by the exception handler and by
 * idempotency when it stores a 4xx outcome — so a replayed error looks identical.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static Map<String, Object> body(ErrorCode code, String detail) {
        return body(code.httpStatus(), code.name(), detail);
    }

    public static Map<String, Object> body(int status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("code", code);
        body.put("detail", detail);
        return body;
    }
}
