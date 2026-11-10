package com.hirdesh.wallet.account;

import java.time.Instant;

/** One line of a wallet's statement, from the user's point of view. */
public record HistoryItem(long postingId, long journalId, String type, Direction direction, long amount,
                          Instant createdAt) {

    public enum Direction { IN, OUT }
}
