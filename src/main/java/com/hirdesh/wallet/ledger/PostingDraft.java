package com.hirdesh.wallet.ledger;

/** One line of a journal to be written. amount: + debit, − credit (ADR-001). */
public record PostingDraft(long accountId, long amount) {

    public static PostingDraft debit(long accountId, long amount) {
        return new PostingDraft(accountId, amount);
    }

    public static PostingDraft credit(long accountId, long amount) {
        return new PostingDraft(accountId, -amount);
    }
}
