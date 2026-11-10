package com.hirdesh.wallet.ledger.locking;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountRepository;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.JournalDraft;

/**
 * No locks while reading. On write: "UPDATE ... WHERE version = the version I read".
 * If anyone changed the row since we read it, 0 rows match → {@link ConcurrencyConflictException},
 * the whole transaction rolls back and the caller retries from scratch.
 */
public class OptimisticLocking implements AccountLocking {

    private final AccountRepository accounts;

    public OptimisticLocking(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Map<Long, Account> load(SortedMap<Long, Long> deltaByAccount, JournalDraft draft) {
        Map<Long, Account> read = new TreeMap<>();
        for (long id : deltaByAccount.keySet()) {
            read.put(id, accounts.findById(id).orElseThrow(() -> WalletException.walletNotFound(id)));
        }
        return read;
    }

    @Override
    public void apply(Map<Long, Account> loaded, SortedMap<Long, Long> deltaByAccount) {
        deltaByAccount.forEach((id, delta) -> {
            Account seen = loaded.get(id);
            if (!accounts.addToBalanceIfVersion(id, delta, seen.version())) {
                throw new ConcurrencyConflictException(
                        "Account " + id + " changed since it was read (version " + seen.version() + ")");
            }
        });
    }
}
