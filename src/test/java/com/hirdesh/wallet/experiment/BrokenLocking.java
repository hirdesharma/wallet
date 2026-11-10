package com.hirdesh.wallet.experiment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountRepository;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.JournalDraft;
import com.hirdesh.wallet.ledger.PostingDraft;
import com.hirdesh.wallet.ledger.locking.AccountLocking;

/**
 * ⚠ DELIBERATELY BROKEN strategies for the M3 experiment. Test sources only, so
 * production can never be configured to use them.
 */
final class BrokenLocking {

    private BrokenLocking() {
    }

    /**
     * NAIVE: plain SELECT, check in Java, then write the absolute new balance.
     * Two transactions read the same old balance → both pass the check → the second
     * write overwrites the first ("lost update").
     */
    static class Naive implements AccountLocking {
        private final AccountRepository accounts;
        private final JdbcClient jdbc;

        Naive(AccountRepository accounts, JdbcClient jdbc) {
            this.accounts = accounts;
            this.jdbc = jdbc;
        }

        @Override
        public Map<Long, Account> load(SortedMap<Long, Long> deltas, JournalDraft draft) {
            Map<Long, Account> read = new TreeMap<>();
            for (long id : deltas.keySet()) {
                read.put(id, accounts.findById(id).orElseThrow(() -> WalletException.walletNotFound(id)));
            }
            return read;
        }

        @Override
        public void apply(Map<Long, Account> loaded, SortedMap<Long, Long> deltas) {
            deltas.forEach((id, delta) -> jdbc.sql("UPDATE accounts SET balance = :b WHERE id = :id")
                    .param("b", loaded.get(id).balance() + delta)   // computed from a possibly stale read
                    .param("id", id)
                    .update());
        }
    }

    /**
     * UNORDERED: correct FOR UPDATE locks, but taken in posting order (sender first)
     * instead of ascending id. A→B locks A then B; B→A locks B then A → deadlock.
     */
    static class Unordered implements AccountLocking {
        private final AccountRepository accounts;

        Unordered(AccountRepository accounts) {
            this.accounts = accounts;
        }

        @Override
        public Map<Long, Account> load(SortedMap<Long, Long> deltas, JournalDraft draft) {
            Map<Long, Account> locked = new LinkedHashMap<>();
            for (PostingDraft p : draft.postings()) {
                long id = p.accountId();
                if (!locked.containsKey(id)) {
                    locked.put(id, accounts.lockById(id).orElseThrow(() -> WalletException.walletNotFound(id)));
                }
            }
            return locked;
        }

        @Override
        public void apply(Map<Long, Account> loaded, SortedMap<Long, Long> deltas) {
            deltas.forEach((id, delta) -> accounts.addToBalance(id, delta));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NaiveConfig {
        @Bean
        @Primary
        AccountLocking naiveLocking(AccountRepository accounts, JdbcClient jdbc) {
            return new Naive(accounts, jdbc);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UnorderedConfig {
        @Bean
        @Primary
        AccountLocking unorderedLocking(AccountRepository accounts) {
            return new Unordered(accounts);
        }
    }
}
