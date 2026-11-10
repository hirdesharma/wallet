package com.hirdesh.wallet.ledger.locking;

import java.util.Map;
import java.util.SortedMap;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.ledger.JournalDraft;

/**
 * HOW the ledger protects balances from concurrent writers (ADR-003).
 * LedgerService decides WHAT to write; this decides how to read and write balances safely.
 *
 * <pre>
 *   load()   read every touched account   — pessimistic strategies lock here
 *   (ledger checks funds on what load() returned)
 *   apply()  persist the balance changes  — optimistic strategies detect conflicts here
 * </pre>
 */
public interface AccountLocking {

    /**
     * @param deltaByAccount net change per account, keys in ascending id order
     * @return every account in deltaByAccount, by id
     * @throws com.hirdesh.wallet.common.WalletException WALLET_NOT_FOUND if one doesn't exist
     */
    Map<Long, Account> load(SortedMap<Long, Long> deltaByAccount, JournalDraft draft);

    /** Persist the deltas for accounts returned by {@link #load}. */
    void apply(Map<Long, Account> loaded, SortedMap<Long, Long> deltaByAccount);
}
