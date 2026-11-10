package com.hirdesh.wallet.ledger;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountType;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.locking.AccountLocking;

/**
 * The ONLY writer of journals, postings and account balances (HLD §3).
 * Algorithm: LLD §5. Locking: pluggable {@link AccountLocking}, default pessimistic (ADR-003).
 */
@Service
public class LedgerService {

    private final AccountLocking locking;
    private final LedgerRepository ledger;

    public LedgerService(AccountLocking locking, LedgerRepository ledger) {
        this.locking = locking;
        this.ledger = ledger;
    }

    /**
     * Writes a balanced journal and updates stored balances.
     * Must run inside a transaction owned by the caller: the locks taken here
     * have to be held until the caller commits.
     */
    public Journal post(JournalDraft draft) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LedgerService.post must be called inside a transaction");
        }

        // Net change per account. TreeMap = ascending account id = the global lock order.
        SortedMap<Long, Long> deltaByAccount = new TreeMap<>();
        for (PostingDraft p : draft.postings()) {
            deltaByAccount.merge(p.accountId(), p.amount(), Math::addExact);
        }

        // 1. Read the accounts. Pessimistic: locks them (ascending id → no deadlocks).
        Map<Long, Account> loaded = locking.load(deltaByAccount, draft);

        // 2. Check funds on what we read. Safe only because of step 1 (locks) or step 3 (version check).
        for (var entry : deltaByAccount.entrySet()) {
            Account account = loaded.get(entry.getKey());
            long newBalance = Math.addExact(account.balance(), entry.getValue());
            // Wallets are liabilities: raw balance > 0 means the user owes us = overdraft.
            if (account.type() == AccountType.USER_WALLET && newBalance > 0) {
                throw WalletException.insufficientFunds(account.id(), account.userFacingBalance(), entry.getValue());
            }
        }

        // 3. Write balances first, so an optimistic conflict is detected before doing more work.
        locking.apply(loaded, deltaByAccount);

        // 4. Append the journal and its postings.
        long journalId = ledger.insertJournal(draft);
        for (PostingDraft p : draft.postings()) {
            ledger.insertPosting(journalId, p);
        }

        // The deferred DB trigger re-checks that this journal balances when the caller commits.
        return ledger.findJournal(journalId).orElseThrow();
    }
}
