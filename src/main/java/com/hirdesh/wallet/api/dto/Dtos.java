package com.hirdesh.wallet.api.dto;

import java.time.Instant;
import java.util.List;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.HistoryItem;
import com.hirdesh.wallet.ledger.Journal;

/** Request/response shapes from LLD §3.2. Wrapper types (Long) so "missing" ≠ 0. */
public final class Dtos {

    private Dtos() {
    }

    // ── requests ──
    public record CreateWalletRequest(String userId) {}

    public record AmountRequest(Long amount) {}

    public record TransferRequest(Long fromWalletId, Long toWalletId, Long amount) {}

    // ── responses ──
    public record WalletResponse(long walletId, String userId, long balance, String currency) {
        public static WalletResponse from(Account a) {
            return new WalletResponse(a.id(), a.userId(), a.userFacingBalance(), "INR");
        }
    }

    public record PostingResponse(long accountId, long amount) {}

    public record JournalResponse(long journalId, String type, long amount, Long referenceJournalId,
                                  Instant createdAt, List<PostingResponse> postings) {
        public static JournalResponse from(Journal j) {
            return new JournalResponse(j.id(), j.type().name(), j.amount(), j.referenceJournalId(), j.createdAt(),
                    j.postings().stream().map(p -> new PostingResponse(p.accountId(), p.amount())).toList());
        }
    }

    /** nextBefore: pass as ?before= to get the next (older) page; null when there are no more. */
    public record HistoryPage(List<HistoryItem> items, Long nextBefore) {}
}
