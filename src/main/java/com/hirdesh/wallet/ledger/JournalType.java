package com.hirdesh.wallet.ledger;

public enum JournalType {
    TOP_UP,
    TRANSFER,
    WITHDRAW,
    REFUND,
    REVERSAL;

    /** Corrections must point at the journal they correct. */
    public boolean requiresReference() {
        return this == REFUND || this == REVERSAL;
    }
}
