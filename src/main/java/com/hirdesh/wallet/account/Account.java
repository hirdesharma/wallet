package com.hirdesh.wallet.account;

/**
 * @param balance RAW signed balance = SUM(postings). For a USER_WALLET this is ≤ 0
 *                when the user has money; use {@link #userFacingBalance()} for display.
 * @param version bumped on every optimistic update (ADR-003 experiment)
 */
public record Account(long id, AccountType type, String userId, String code, long balance, long version) {

    /** What a human expects to see: positive when the wallet holds money (ADR-001). */
    public long userFacingBalance() {
        return type == AccountType.USER_WALLET ? -balance : balance;
    }
}
