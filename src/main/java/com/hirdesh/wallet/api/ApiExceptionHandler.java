package com.hirdesh.wallet.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.hirdesh.wallet.common.ApiErrors;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;

/**
 * O-2: maps failures to HTTP.
 * <pre>
 *  WalletException                     → its own code/status
 *  malformed JSON / wrong param type   → 400 VALIDATION_FAILED
 *  40P01 deadlock, 55P03 lock timeout,
 *  40001 serialization failure         → PessimisticLockingFailureException → 503 TRY_AGAIN
 *  DB unreachable / other transient    → 503 TRY_AGAIN
 *  anything else (incl. DB guard
 *  triggers firing = our bug)          → 500
 * </pre>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WalletException.class)
    ResponseEntity<Map<String, Object>> wallet(WalletException e) {
        return problem(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badInput(Exception e) {
        return problem(400, ErrorCode.VALIDATION_FAILED.name(), "Malformed request");
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, TransientDataAccessException.class,
            CannotGetJdbcConnectionException.class})
    ResponseEntity<Map<String, Object>> retryable(Exception e) {
        log.warn("Retryable DB failure: {}", e.toString());
        return problem(503, ErrorCode.TRY_AGAIN.name(), "Temporary failure, retry with the same Idempotency-Key");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        if (e instanceof ErrorResponse er) { // Spring MVC's own errors: 404 unknown path, 405, 415...
            return problem(er.getStatusCode().value(), "REQUEST_ERROR", e.getMessage());
        }
        log.error("Unexpected error", e);
        return problem(500, "INTERNAL_ERROR", "Unexpected error");
    }

    private static ResponseEntity<Map<String, Object>> problem(int status, String code, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiErrors.body(status, code, detail));
    }
}
