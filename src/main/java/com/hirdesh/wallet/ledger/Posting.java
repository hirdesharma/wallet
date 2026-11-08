package com.hirdesh.wallet.ledger;

public record Posting(long id, long journalId, long accountId, long amount) {
}
