package com.hirdesh.wallet.ledger;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountRepository;
import com.hirdesh.wallet.account.AccountType;
import com.hirdesh.wallet.common.WalletException;

/**
 * The ONLY writer of journals, postings and account balances (HLD §3).
 * Algorithm: LLD §5. Locking: ADR-003 (pessimistic, ascending account id).
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
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

        // Net change per account. TreeMap = ascending account id = our global lock order.
        SortedMap<Long, Long> deltaByAccount = new TreeMap<>();
        for (PostingDraft p : draft.postings()) {
            deltaByAccount.merge(p.accountId(), p.amount(), Math::addExact);
        }

        // Lock every touched account, one at a time, in ascending id order → no deadlocks.
        Map<Long, Account> locked = new TreeMap<>();
        for (long accountId : deltaByAccount.keySet()) {
            Account account = accounts.lockById(accountId)
                    .orElseThrow(() -> WalletException.walletNotFound(accountId));
            locked.put(accountId, account);
        }

        // With the locks held, nobody else can change these balances: check-then-act is safe.
        for (var entry : deltaByAccount.entrySet()) {
            Account account = locked.get(entry.getKey());
            long newBalance = Math.addExact(account.balance(), entry.getValue());
            // Wallets are liabilities: raw balance > 0 means the user owes us = overdraft.
            if (account.type() == AccountType.USER_WALLET && newBalance > 0) {
                throw WalletException.insufficientFunds(account.id(), account.userFacingBalance(), entry.getValue());
            }
        }

        long journalId = ledger.insertJournal(draft);
        for (PostingDraft p : draft.postings()) {
            ledger.insertPosting(journalId, p);
        }
        deltaByAccount.forEach((accountId, delta) -> {
            if (delta != 0) {
                accounts.addToBalance(accountId, delta);
            }
        });

        // The deferred DB trigger re-checks that this journal balances when the caller commits.
        return ledger.findJournal(journalId).orElseThrow();
    }
}
