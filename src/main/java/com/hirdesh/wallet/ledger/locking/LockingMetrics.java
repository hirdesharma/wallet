package com.hirdesh.wallet.ledger.locking;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/** Counters for the M3 experiment (later these could become Micrometer metrics). */
@Component
public class LockingMetrics {

    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong exhausted = new AtomicLong();

    public void conflict() {
        conflicts.incrementAndGet();
    }

    public void exhausted() {
        exhausted.incrementAndGet();
    }

    public long conflicts() {
        return conflicts.get();
    }

    public long exhaustedRetries() {
        return exhausted.get();
    }

    public void reset() {
        conflicts.set(0);
        exhausted.set(0);
    }
}
