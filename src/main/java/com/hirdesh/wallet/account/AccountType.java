package com.hirdesh.wallet.account;

public enum AccountType {
    /** Liability: money the platform owes a user. Raw balance ≤ 0 (ADR-001). */
    USER_WALLET,
    /** Asset: money the platform holds at its bank. Raw balance ≥ 0. */
    CASH_POOL
}
