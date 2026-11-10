package com.hirdesh.wallet.account;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;

/** Wallet creation and read-only views. Never moves money. */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final JdbcClient jdbc;

    public AccountService(AccountRepository accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    /**
     * O-1: no "SELECT then INSERT" (racy). We just INSERT and let the UNIQUE(user_id)
     * constraint decide; Spring translates SQLState 23505 into DuplicateKeyException.
     */
    public Account createWallet(String userId) {
        try {
            return accounts.createUserWallet(userId);
        } catch (DuplicateKeyException e) {
            throw new WalletException(ErrorCode.WALLET_EXISTS, "User " + userId + " already has a wallet");
        }
    }

    public Account getWallet(long walletId) {
        return accounts.findById(walletId)
                .filter(a -> a.type() == AccountType.USER_WALLET)
                .orElseThrow(() -> WalletException.walletNotFound(walletId));
    }

    /**
     * O-1: keyset pagination — "give me postings older than id X". Unlike OFFSET it stays
     * fast on deep pages and doesn't skip/duplicate rows when new postings arrive.
     * Served by index postings(account_id, id DESC).
     */
    public List<HistoryItem> history(long walletId, long beforePostingId, int limit) {
        getWallet(walletId); // 404 if missing
        return jdbc.sql("""
                        SELECT p.id, p.journal_id, j.type, p.amount, p.created_at
                        FROM postings p JOIN journals j ON j.id = p.journal_id
                        WHERE p.account_id = :walletId AND p.id < :before
                        ORDER BY p.id DESC
                        LIMIT :limit""")
                .param("walletId", walletId)
                .param("before", beforePostingId)
                .param("limit", limit)
                .query((rs, i) -> {
                    long raw = rs.getLong("amount");
                    // wallet is a liability: credit (−) = money IN for the user, debit (+) = OUT
                    return new HistoryItem(
                            rs.getLong("id"),
                            rs.getLong("journal_id"),
                            rs.getString("type"),
                            raw < 0 ? HistoryItem.Direction.IN : HistoryItem.Direction.OUT,
                            Math.abs(raw),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .list();
    }
}
