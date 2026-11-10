package com.hirdesh.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hirdesh.wallet.support.ApiClient.Response;
import com.hirdesh.wallet.support.ConcurrentRun;
import com.hirdesh.wallet.support.IntegrationTest;
import com.hirdesh.wallet.support.LedgerCheck;

/** S-40 .. S-46: refunds and reversals are new linked journals; history is never edited. */
class CorrectionsApiTest extends IntegrationTest {

    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        alice = api.createWallet("alice");
        bob = api.createWallet("bob");
        api.topUp(alice, 50_000);
    }

    // ── refunds ──

    @Test
    void s40_fullRefundRestoresBalancesAndLinksJournals() {
        long transfer = api.transfer(alice, bob, 20_000).num("$.journalId");

        Response refund = api.refund(transfer, 20_000);

        assertThat(refund.status()).isEqualTo(201);
        assertThat(refund.str("$.type")).isEqualTo("REFUND");
        assertThat(refund.num("$.referenceJournalId")).isEqualTo(transfer);
        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertThat(api.balance(bob)).isZero();
        // history intact: the original transfer is still there, unchanged
        assertThat(api.get("/journals/" + transfer).num("$.amount")).isEqualTo(20_000);
        assertConsistent();
    }

    @Test
    void s41_partialRefundsWithinOriginal() {
        long transfer = api.transfer(alice, bob, 20_000).num("$.journalId");

        assertThat(api.refund(transfer, 5_000).status()).isEqualTo(201);
        assertThat(api.refund(transfer, 15_000).status()).isEqualTo(201);   // total = exactly the original

        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertThat(api.balance(bob)).isZero();
        assertConsistent();
    }

    @Test
    void s42_refundCrossingTheOriginalIsRejected() {
        long transfer = api.transfer(alice, bob, 20_000).num("$.journalId");
        api.refund(transfer, 15_000);

        Response r = api.refund(transfer, 5_001);   // 15_000 + 5_001 > 20_000

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("REFUND_EXCEEDS_ORIGINAL");
        assertThat(api.balance(bob)).isEqualTo(5_000);
        assertConsistent();
    }

    @Test
    void s43_concurrentRefundsNeverExceedTheOriginal() {
        long transfer = api.transfer(alice, bob, 10_000).num("$.journalId");
        // Bob has plenty of OTHER money, so his balance can't stop an over-refund:
        // only the refund limit (and the journal lock behind it) can.
        api.topUp(bob, 50_000);

        // 20 parallel refunds of ₹10 against a ₹100 transfer → only 10 can fit
        ConcurrentRun run = ConcurrentRun.of(20, i -> api.refund(transfer, 1_000));

        assertThat(run.count(201)).isEqualTo(10);
        assertThat(run.count(422)).isEqualTo(10);
        assertThat(run.responses()).filteredOn(r -> r.status() == 422)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("REFUND_EXCEEDS_ORIGINAL"));
        long refunded = jdbc.sql("SELECT SUM(amount) FROM journals WHERE reference_journal_id = :t AND type = 'REFUND'")
                .param("t", transfer).query(Long.class).single();
        assertThat(refunded).isEqualTo(10_000);
        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertThat(api.balance(bob)).isEqualTo(50_000);
        assertConsistent();
    }

    @Test
    void s44_refundWhenReceiverAlreadySpentIt() {
        long carol = api.createWallet("carol");
        long transfer = api.transfer(alice, bob, 20_000).num("$.journalId");
        api.transfer(bob, carol, 18_000);   // bob keeps only ₹20

        Response r = api.refund(transfer, 20_000);

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(api.refund(transfer, 2_000).status()).isEqualTo(201);   // what bob still has is refundable
        assertConsistent();
    }

    @Test
    void onlyTransfersAreRefundable() {
        long topUp = api.topUp(alice, 1_000).num("$.journalId");
        long transfer = api.transfer(alice, bob, 1_000).num("$.journalId");
        long refund = api.refund(transfer, 500).num("$.journalId");

        assertError(api.refund(topUp, 100), 422, "NOT_REFUNDABLE");
        assertError(api.refund(refund, 100), 422, "NOT_REFUNDABLE");   // no refund of a refund
        assertError(api.refund(9_999, 100), 404, "JOURNAL_NOT_FOUND");
        assertError(api.refund(transfer, 0), 400, "VALIDATION_FAILED");
    }

    // ── reversals ──

    @Test
    void s45_reverseWithdrawalPutsMoneyBack() {
        long withdrawal = api.withdraw(alice, 10_000).num("$.journalId");
        assertThat(api.balance(alice)).isEqualTo(40_000);

        Response r = api.reverse(withdrawal);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.str("$.type")).isEqualTo("REVERSAL");
        assertThat(r.num("$.referenceJournalId")).isEqualTo(withdrawal);
        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertThat(poolBalance()).isEqualTo(50_000);
        assertConsistent();
    }

    @Test
    void reverseTopUpTakesMoneyBack() {
        long topUp = api.topUp(alice, 10_000).num("$.journalId");

        assertThat(api.reverse(topUp).status()).isEqualTo(201);

        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertThat(poolBalance()).isEqualTo(50_000);
        assertConsistent();
    }

    @Test
    void reverseTopUpAfterMoneyWasSpent() {
        long topUp = api.topUp(alice, 10_000).num("$.journalId");   // alice now ₹600
        api.withdraw(alice, 55_000);                                // alice ₹50

        assertError(api.reverse(topUp), 422, "INSUFFICIENT_FUNDS");  // a chargeback we can't cover from the wallet
    }

    @Test
    void s46_reverseTwiceIsRejected() {
        long withdrawal = api.withdraw(alice, 10_000).num("$.journalId");
        api.reverse(withdrawal);

        assertError(api.reverse(withdrawal), 422, "ALREADY_REVERSED");
        assertThat(api.balance(alice)).isEqualTo(50_000);
    }

    @Test
    void s46_concurrentReversalsOnlyOneWins() {
        long withdrawal = api.withdraw(alice, 10_000).num("$.journalId");

        ConcurrentRun run = ConcurrentRun.of(10, i -> api.reverse(withdrawal));

        assertThat(run.count(201)).isEqualTo(1);
        assertThat(run.count(422)).isEqualTo(9);
        assertThat(api.balance(alice)).isEqualTo(50_000);
        assertConsistent();
    }

    @Test
    void onlyTopUpsAndWithdrawalsAreReversible() {
        long transfer = api.transfer(alice, bob, 1_000).num("$.journalId");
        long withdrawal = api.withdraw(alice, 1_000).num("$.journalId");
        long reversal = api.reverse(withdrawal).num("$.journalId");

        assertError(api.reverse(transfer), 422, "NOT_REVERSIBLE");   // transfers use refunds
        assertError(api.reverse(reversal), 422, "NOT_REVERSIBLE");   // no reversal of a reversal
    }

    private void assertConsistent() {
        LedgerCheck check = LedgerCheck.run(jdbc);
        assertThat(check.consistent()).as(check.toString()).isTrue();
    }

    private static void assertError(Response r, int status, String code) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.code()).isEqualTo(code);
    }
}
