package com.hirdesh.wallet.ledger;

import java.util.List;
import java.util.Objects;

/**
 * A journal not yet written. The constructor rejects anything that isn't a valid
 * double-entry journal, so an invalid draft can't exist at all.
 * Violations here are programming bugs (our recipes are wrong), not user errors.
 */
public record JournalDraft(
        JournalType type,
        long amount,
        Long referenceJournalId,
        String idempotencyKey,
        List<PostingDraft> postings) {

    public JournalDraft {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(postings, "postings");
        postings = List.copyOf(postings);

        if (amount <= 0) {
            throw new IllegalArgumentException("journal amount must be > 0, was " + amount);
        }
        if (type.requiresReference() != (referenceJournalId != null)) {
            throw new IllegalArgumentException(type + " reference mismatch: " + referenceJournalId);
        }
        if (postings.size() < 2) {
            throw new IllegalArgumentException("journal needs at least 2 postings, has " + postings.size());
        }
        long sum = 0;
        for (PostingDraft p : postings) {
            if (p.amount() == 0) {
                throw new IllegalArgumentException("posting amount must be non-zero: " + p);
            }
            sum = Math.addExact(sum, p.amount()); // overflow → exception, never silent wrap
        }
        if (sum != 0) {
            throw new IllegalArgumentException("journal unbalanced: postings sum to " + sum);
        }
    }
}
