package com.hirdesh.wallet.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * Locks are correct but taken in the wrong ORDER. Postgres detects the deadlock
 * (after deadlock_timeout = 1s), kills one transaction (SQLState 40P01) → 503 TRY_AGAIN.
 * Money stays correct (the victim rolls back), but requests fail and everything is slow.
 */
@Import(BrokenLocking.UnorderedConfig.class)
class UnorderedLockingExperimentTest extends ConcurrencyScenarios {

    @Test
    void s32_deadlocks() {
        Outcome o = s32();
        assertThat(o.retryLater()).as("deadlock victims").isPositive();
        assertThat(o.ok() + o.retryLater()).as("every failure is a retryable 503, never a 500").isEqualTo(100);
        assertThat(o.userBalances()[0] + o.userBalances()[1]).isEqualTo(10_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }
}
