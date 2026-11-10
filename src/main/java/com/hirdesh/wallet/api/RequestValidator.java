package com.hirdesh.wallet.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;

/** Layer 1 of HLD §6: bad input never reaches business logic, and is never stored as an idempotent outcome. */
@Component
public class RequestValidator {

    private final long maxAmount;

    public RequestValidator(@Value("${wallet.limits.max-amount}") long maxAmount) {
        this.maxAmount = maxAmount;
    }

    /** BR-1, BR-2 */
    public long amount(Long amount) {
        if (amount == null) {
            throw invalid("amount is required");
        }
        if (amount <= 0) {
            throw invalid("amount must be > 0 (in paise)");
        }
        if (amount > maxAmount) {
            throw invalid("amount must be ≤ " + maxAmount + " paise");
        }
        return amount;
    }

    public long id(Long id, String field) {
        if (id == null) {
            throw invalid(field + " is required");
        }
        return id;
    }

    /** BR-6..8 need a key on every money-moving request. */
    public String idempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new WalletException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "Idempotency-Key header is required");
        }
        if (key.length() > 100) {
            throw invalid("Idempotency-Key must be at most 100 characters");
        }
        return key;
    }

    public String userId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 100) {
            throw invalid("userId is required (1-100 characters)");
        }
        return userId;
    }

    public int limit(int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid("limit must be between 1 and 100");
        }
        return limit;
    }

    private static WalletException invalid(String message) {
        return new WalletException(ErrorCode.VALIDATION_FAILED, message);
    }
}
