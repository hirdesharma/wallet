package com.hirdesh.wallet.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Same scenarios with optimistic locking. Expectation: ALWAYS correct (never loses money),
 * but under heavy contention some requests may give up with 503 after exhausting retries.
 */
@TestPropertySource(properties = "wallet.ledger.locking=OPTIMISTIC")
class OptimisticConcurrencyTest extends ConcurrencyScenarios {

    @Test
    void s30_alwaysCorrect() {
        Outcome o = s30();
        assertThat(o.ok() + o.retryLater()).isEqualTo(100);
        assertThat(o.userBalances()[1]).isEqualTo(100 * o.ok());   // B got exactly what succeeded
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }

    @Test
    void s31_neverOverspends() {
        Outcome o = s31();
        assertThat(o.ok()).isLessThanOrEqualTo(60);
        assertThat(o.userBalances()[0]).isEqualTo(6_000 - 100 * o.ok());
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }

    @Test
    void s32_conservesMoney() {
        Outcome o = s32();
        assertThat(o.userBalances()[0] + o.userBalances()[1]).isEqualTo(10_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }

    @Test
    void s33_conservesMoney() {
        Outcome o = s33();
        assertThat(totalUserBalance(o)).isEqualTo(100_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }
}
