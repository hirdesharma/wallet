package com.hirdesh.wallet.ledger;

import java.time.Instant;
import java.util.List;

public record Journal(
        long id,
        JournalType type,
        long amount,
        Long referenceJournalId,
        String idempotencyKey,
        Instant createdAt,
        List<Posting> postings) {
}
