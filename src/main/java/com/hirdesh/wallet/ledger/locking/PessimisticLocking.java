package com.hirdesh.wallet.ledger.locking;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountRepository;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.JournalDraft;

/**
 * SELECT ... FOR UPDATE on each account, one at a time, in ascending id order.
 * Other writers wait. Ordered acquisition makes deadlocks impossible. (Default.)
 */
public class PessimisticLocking implements AccountLocking {

    private final AccountRepository accounts;

    public PessimisticLocking(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Map<Long, Account> load(SortedMap<Long, Long> deltaByAccount, JournalDraft draft) {
        Map<Long, Account> locked = new TreeMap<>();
        for (long id : deltaByAccount.keySet()) {          // ascending → global lock order
            locked.put(id, accounts.lockById(id).orElseThrow(() -> WalletException.walletNotFound(id)));
        }
        return locked;
    }

    @Override
    public void apply(Map<Long, Account> loaded, SortedMap<Long, Long> deltaByAccount) {
        // We hold the row locks, so a relative update can't interleave with anyone.
        deltaByAccount.forEach((id, delta) -> {
            if (delta != 0) {
                accounts.addToBalance(id, delta);
            }
        });
    }
}
