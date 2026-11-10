package com.hirdesh.wallet.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A mini version of the M5 invariant job, for tests. Reads in one statement each;
 * call it only when no writes are running.
 *
 * @param balanceMismatches accounts whose stored balance ≠ SUM(postings)  (lost updates show here)
 * @param unbalancedJournals journals whose postings don't sum to 0
 * @param walletsPlusPool    raw SUM(wallet balances) + pool balance; must be 0 (wallets = pool)
 * @param overdrawnWallets   wallets whose TRUE balance (from postings) is negative for the user
 */
public record LedgerCheck(long balanceMismatches, long unbalancedJournals, long walletsPlusPool,
                          long overdrawnWallets) {

    public boolean consistent() {
        return balanceMismatches == 0 && unbalancedJournals == 0 && walletsPlusPool == 0 && overdrawnWallets == 0;
    }

    public static LedgerCheck run(JdbcClient jdbc) {
        long mismatches = jdbc.sql("""
                SELECT COUNT(*) FROM (
                  SELECT a.id FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                  GROUP BY a.id HAVING a.balance <> COALESCE(SUM(p.amount), 0)) x""").query(Long.class).single();
        long unbalanced = jdbc.sql("""
                SELECT COUNT(*) FROM (
                  SELECT journal_id FROM postings GROUP BY journal_id HAVING SUM(amount) <> 0) x""")
                .query(Long.class).single();
        long walletsPlusPool = jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM accounts").query(Long.class).single();
        long overdrawn = jdbc.sql("""
                SELECT COUNT(*) FROM (
                  SELECT a.id FROM accounts a JOIN postings p ON p.account_id = a.id
                  WHERE a.type = 'USER_WALLET' GROUP BY a.id HAVING SUM(p.amount) > 0) x""")
                .query(Long.class).single();
        return new LedgerCheck(mismatches, unbalanced, walletsPlusPool, overdrawn);
    }
}
