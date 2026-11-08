package com.hirdesh.wallet.ledger;

import static com.hirdesh.wallet.ledger.PostingDraft.credit;
import static com.hirdesh.wallet.ledger.PostingDraft.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountRepository;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.support.IntegrationTest;

/** LedgerService.post() — the single write path for money (LLD §5). */
class LedgerServiceTest extends IntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired AccountRepository accounts;

    private long pool;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        pool = accounts.getByCode("CASH_POOL").id();
        alice = accounts.createUserWallet("alice").id();
        bob = accounts.createUserWallet("bob").id();
    }

    @Test
    void topUpWritesJournalPostingsAndBalances() {
        Journal j = post(JournalType.TOP_UP, 50_000, debit(pool, 50_000), credit(alice, 50_000));

        assertThat(j.postings()).hasSize(2);
        assertThat(account(alice).userFacingBalance()).isEqualTo(50_000);   // ₹500
        assertThat(account(alice).balance()).isEqualTo(-50_000);            // raw (liability)
        assertThat(account(pool).balance()).isEqualTo(50_000);
        assertStoredBalancesMatchPostings();
    }

    @Test
    void primerWorkedExample() {
        // docs/00-concepts.md §4
        post(JournalType.TOP_UP,   50_000, debit(pool, 50_000),  credit(alice, 50_000));
        post(JournalType.TRANSFER, 20_000, debit(alice, 20_000), credit(bob, 20_000));
        post(JournalType.WITHDRAW,  5_000, debit(bob, 5_000),    credit(pool, 5_000));

        assertThat(account(alice).userFacingBalance()).isEqualTo(30_000);
        assertThat(account(bob).userFacingBalance()).isEqualTo(15_000);
        assertThat(account(pool).userFacingBalance()).isEqualTo(45_000);   // = alice + bob
        assertStoredBalancesMatchPostings();
    }

    @Test
    void insufficientFundsRejectsAndChangesNothing() {
        post(JournalType.TOP_UP, 10_000, debit(pool, 10_000), credit(alice, 10_000));

        assertThatThrownBy(() -> post(JournalType.TRANSFER, 20_000, debit(alice, 20_000), credit(bob, 20_000)))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((WalletException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        assertThat(count("journals")).isEqualTo(1);   // only the top-up
        assertThat(account(alice).userFacingBalance()).isEqualTo(10_000);
        assertThat(account(bob).userFacingBalance()).isZero();
    }

    @Test
    void unknownAccountIsRejected() {
        assertThatThrownBy(() -> post(JournalType.TOP_UP, 100, debit(pool, 100), credit(9_999, 100)))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((WalletException) e).code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
        assertThat(count("journals")).isZero();
    }

    @Test
    void postOutsideTransactionIsAProgrammingError() {
        var draft = new JournalDraft(JournalType.TOP_UP, 100, null, null,
                List.of(debit(pool, 100), credit(alice, 100)));

        assertThatThrownBy(() -> ledger.post(draft)).isInstanceOf(IllegalStateException.class);
    }

    // --- helpers ---

    private Journal post(JournalType type, long amount, PostingDraft... postings) {
        var draft = new JournalDraft(type, amount, null, null, List.of(postings));
        return tx.execute(status -> ledger.post(draft));
    }

    private Account account(long id) {
        return accounts.findById(id).orElseThrow();
    }

    /** Mini invariant check (the real job comes in M5): stored balance == SUM(postings). */
    private void assertStoredBalancesMatchPostings() {
        long mismatches = jdbc.sql("""
                SELECT COUNT(*) FROM (
                  SELECT a.id FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                  GROUP BY a.id HAVING a.balance <> COALESCE(SUM(p.amount), 0)
                ) x""").query(Long.class).single();
        assertThat(mismatches).isZero();
    }
}
