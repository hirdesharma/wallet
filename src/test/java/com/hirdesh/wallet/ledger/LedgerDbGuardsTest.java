package com.hirdesh.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hirdesh.wallet.support.IntegrationTest;

/**
 * S-01..S-03: these tests bypass Java entirely and write raw SQL,
 * proving the DATABASE refuses bad ledger data even if our code had a bug (ADR-005).
 */
class LedgerDbGuardsTest extends IntegrationTest {

    private long poolId;
    private long walletId;

    @BeforeEach
    void setUp() {
        poolId = jdbc.sql("SELECT id FROM accounts WHERE code = 'CASH_POOL'").query(Long.class).single();
        walletId = jdbc.sql("INSERT INTO accounts (type, user_id) VALUES ('USER_WALLET', 'alice') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void s01_unbalancedJournalIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long j = insertJournal();
            insertPosting(j, poolId, 100);
            insertPosting(j, walletId, -50);    // sum = 50, not 0
        }))
                .rootCause().hasMessageContaining("unbalanced");

        assertThat(count("journals")).isZero();
        assertThat(count("postings")).isZero();
    }

    @Test
    void s01_balancedJournalCommits() {
        tx.executeWithoutResult(status -> {
            long j = insertJournal();
            insertPosting(j, poolId, 100);
            insertPosting(j, walletId, -100);
        });

        assertThat(count("journals")).isEqualTo(1);
        assertThat(count("postings")).isEqualTo(2);
    }

    @Test
    void s02_journalWithNoPostingsIsRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertJournal()))
                .rootCause().hasMessageContaining("postings=0");

        assertThat(count("journals")).isZero();
    }

    @Test
    void s02_journalWithOnePostingIsRejected() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            long j = insertJournal();
            insertPosting(j, poolId, 100);
        }))
                .rootCause().hasMessageContaining("unbalanced");
    }

    @Test
    void s02_postingAddedLaterToAnOldJournalIsRejected() {
        long j = tx.execute(status -> {
            long id = insertJournal();
            insertPosting(id, poolId, 100);
            insertPosting(id, walletId, -100);
            return id;
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertPosting(j, poolId, 5)))
                .rootCause().hasMessageContaining("unbalanced");
    }

    @Test
    void s03_postingsCannotBeUpdatedOrDeleted() {
        tx.executeWithoutResult(status -> {
            long j = insertJournal();
            insertPosting(j, poolId, 100);
            insertPosting(j, walletId, -100);
        });

        assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount = 999").update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings").update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void s03_journalsCannotBeUpdatedOrDeleted() {
        tx.executeWithoutResult(status -> {
            long j = insertJournal();
            insertPosting(j, poolId, 100);
            insertPosting(j, walletId, -100);
        });

        assertThatThrownBy(() -> jdbc.sql("UPDATE journals SET amount = 1").update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journals").update())
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void walletCannotBeOverdrawnEvenByRawSql() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET balance = 1 WHERE id = :id")
                .param("id", walletId).update())
                .rootCause().hasMessageContaining("accounts_wallet_no_overdraft");
    }

    private long insertJournal() {
        return jdbc.sql("INSERT INTO journals (type, amount) VALUES ('TOP_UP', 100) RETURNING id")
                .query(Long.class).single();
    }

    private void insertPosting(long journalId, long accountId, long amount) {
        jdbc.sql("INSERT INTO postings (journal_id, account_id, amount) VALUES (:j, :a, :amt)")
                .param("j", journalId).param("a", accountId).param("amt", amount)
                .update();
    }
}
