package com.hirdesh.wallet.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * Proves the concurrency tests can actually catch bugs: with NO locking the same scenarios
 * must corrupt the ledger. If these ever pass "cleanly", our tests aren't creating real races.
 */
@Import(BrokenLocking.NaiveConfig.class)
class NaiveLockingExperimentTest extends ConcurrencyScenarios {

    @Test
    void s30_losesUpdates() {
        Outcome o = s30();
        // Every request "succeeded"…
        assertThat(o.ok()).isEqualTo(100);
        // …but stored balances no longer match the postings: updates were overwritten.
        assertThat(o.check().balanceMismatches()).isPositive();
    }

    @Test
    void s31_overspends() {
        Outcome o = s31();
        // More transfers succeeded than A could afford: postings show A below zero.
        assertThat(o.ok()).isGreaterThan(60);
        assertThat(o.check().overdrawnWallets()).isPositive();
    }
}
