package com.hirdesh.wallet.wallet;

import static com.hirdesh.wallet.ledger.PostingDraft.credit;
import static com.hirdesh.wallet.ledger.PostingDraft.debit;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.Journal;
import com.hirdesh.wallet.ledger.JournalDraft;
import com.hirdesh.wallet.ledger.JournalType;
import com.hirdesh.wallet.ledger.LedgerRepository;
import com.hirdesh.wallet.ledger.LedgerService;
import com.hirdesh.wallet.ledger.Posting;
import com.hirdesh.wallet.ledger.PostingDraft;

/**
 * Refunds and reversals (LLD §6.1 / §6.2). Corrections never edit history: each one is a
 * NEW journal pointing at the original via reference_journal_id.
 *
 * Lock order is always: original journal → accounts (ascending id). Transfers lock only
 * accounts, so no cycle is possible.
 * Must be called inside a transaction (the idempotency Tx2).
 */
@Service
public class CorrectionOperations {

    private final LedgerRepository journals;
    private final LedgerService ledger;

    public CorrectionOperations(LedgerRepository journals, LedgerService ledger) {
        this.journals = journals;
        this.ledger = ledger;
    }

    /**
     * Return all or part of a P2P transfer from receiver to sender (FR-7, BR-9).
     * Can be called several times, but the total refunded never exceeds the original amount.
     */
    public Journal refund(long originalId, long amount, String idempotencyKey) {
        // 1. Lock the original. Every refund of this transfer now queues here, so the
        //    "already refunded" sum below can't change under us (prevents S-43 over-refund).
        Journal original = lockOriginal(originalId);

        if (original.type() != JournalType.TRANSFER) {
            throw new WalletException(ErrorCode.NOT_REFUNDABLE,
                    "Only transfers can be refunded; journal " + originalId + " is " + original.type());
        }

        // 2. Read the running total AFTER taking the lock: it includes every refund committed before us.
        long alreadyRefunded = journals.refundedAmount(originalId);
        if (alreadyRefunded + amount > original.amount()) {
            throw new WalletException(ErrorCode.REFUND_EXCEEDS_ORIGINAL, "Transfer " + originalId + " was "
                    + original.amount() + ", already refunded " + alreadyRefunded + ", requested " + amount);
        }

        // 3. Money flows back: receiver pays, sender gets. The receiver may have spent it → INSUFFICIENT_FUNDS.
        long sender = accountWithSign(original, +1);    // the debit (+) side of the transfer
        long receiver = accountWithSign(original, -1);  // the credit (−) side
        return ledger.post(new JournalDraft(JournalType.REFUND, amount, originalId, idempotencyKey,
                List.of(debit(receiver, amount), credit(sender, amount))));
    }

    /**
     * Fully undo a top-up (e.g. card chargeback) or withdrawal (e.g. bank payout bounced),
     * at most once (FR-8, BR-10).
     */
    public Journal reverse(long originalId, String idempotencyKey) {
        Journal original = lockOriginal(originalId);

        if (original.type() != JournalType.TOP_UP && original.type() != JournalType.WITHDRAW) {
            throw new WalletException(ErrorCode.NOT_REVERSIBLE,
                    "Only top-ups and withdrawals can be reversed; journal " + originalId + " is " + original.type());
        }
        if (journals.isReversed(originalId)) {
            throw alreadyReversed(originalId);
        }

        // Mirror image of the original: every posting with its sign flipped.
        List<PostingDraft> mirrored = original.postings().stream()
                .map(p -> new PostingDraft(p.accountId(), -p.amount()))
                .toList();
        try {
            return ledger.post(new JournalDraft(JournalType.REVERSAL, original.amount(), originalId,
                    idempotencyKey, mirrored));
        } catch (DuplicateKeyException e) {
            // Backstop: the partial unique index journals_one_reversal_idx (ADR-005).
            // With the journal lock this shouldn't happen, but the DB guarantees it anyway.
            throw alreadyReversed(originalId);
        }
    }

    private Journal lockOriginal(long id) {
        return journals.lockJournal(id).orElseThrow(
                () -> new WalletException(ErrorCode.JOURNAL_NOT_FOUND, "Journal " + id + " not found"));
    }

    private static long accountWithSign(Journal j, int sign) {
        return j.postings().stream()
                .filter(p -> Long.signum(p.amount()) == sign)
                .map(Posting::accountId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Transfer " + j.id() + " has no posting with sign " + sign));
    }

    private static WalletException alreadyReversed(long id) {
        return new WalletException(ErrorCode.ALREADY_REVERSED, "Journal " + id + " was already reversed");
    }
}
