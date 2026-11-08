package com.hirdesh.wallet.ledger;

import static com.hirdesh.wallet.ledger.PostingDraft.credit;
import static com.hirdesh.wallet.ledger.PostingDraft.debit;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** First line of defense: an invalid journal can't even be constructed in Java. */
class JournalDraftTest {

    @Test
    void balancedJournalIsAccepted() {
        assertThatCode(() -> new JournalDraft(JournalType.TRANSFER, 100, null, null,
                List.of(debit(1, 100), credit(2, 100))))
                .doesNotThrowAnyException();
    }

    @Test
    void unbalancedJournalIsRejected() {
        assertThatThrownBy(() -> new JournalDraft(JournalType.TRANSFER, 100, null, null,
                List.of(debit(1, 100), credit(2, 50))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void singlePostingIsRejected() {
        assertThatThrownBy(() -> new JournalDraft(JournalType.TRANSFER, 100, null, null,
                List.of(debit(1, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void zeroPostingIsRejected() {
        assertThatThrownBy(() -> new JournalDraft(JournalType.TRANSFER, 100, null, null,
                List.of(debit(1, 0), credit(2, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-zero");
    }

    @Test
    void refundWithoutReferenceIsRejected() {
        assertThatThrownBy(() -> new JournalDraft(JournalType.REFUND, 100, null, null,
                List.of(debit(1, 100), credit(2, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void overflowIsRejectedNotWrapped() {
        assertThatThrownBy(() -> new JournalDraft(JournalType.TRANSFER, 1, null, null,
                List.of(debit(1, Long.MAX_VALUE), debit(2, 1), credit(3, 1))))
                .isInstanceOf(ArithmeticException.class);
    }
}
