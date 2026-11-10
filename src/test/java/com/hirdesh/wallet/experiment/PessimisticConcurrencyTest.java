package com.hirdesh.wallet.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The real acceptance tests S-30..S-33, against the production default (ordered FOR UPDATE).
 * Everything must be exactly right.
 */
class PessimisticConcurrencyTest extends ConcurrencyScenarios {

    @Test
    void s30_hundredParallelTransfers() {
        Outcome o = s30();
        assertThat(o.ok()).isEqualTo(100);
        assertThat(o.userBalances()).containsExactly(0, 10_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }

    @Test
    void s31_onlyAffordableTransfersSucceed() {
        Outcome o = s31();
        assertThat(o.ok()).isEqualTo(60);
        assertThat(o.rejected()).isEqualTo(40);
        assertThat(o.userBalances()).containsExactly(0, 6_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }

    @Test
    void s32_opposingTransfersDontDeadlock() {
        Outcome o = s32();
        assertThat(o.ok()).isEqualTo(100);
        assertThat(o.userBalances()).containsExactly(5_000, 5_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }

    @Test
    void s33_randomTransfersConserveMoney() {
        Outcome o = s33();
        assertThat(o.retryLater()).isZero();
        assertThat(o.ok() + o.rejected()).isEqualTo(200);
        assertThat(totalUserBalance(o)).isEqualTo(100_000);
        assertThat(o.check().consistent()).as(o.check().toString()).isTrue();
    }
}
