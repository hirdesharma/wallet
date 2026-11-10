package com.hirdesh.wallet.wallet;

import static com.hirdesh.wallet.ledger.PostingDraft.credit;
import static com.hirdesh.wallet.ledger.PostingDraft.debit;

import java.util.List;

import org.springframework.stereotype.Service;

import com.hirdesh.wallet.account.Account;
import com.hirdesh.wallet.account.AccountRepository;
import com.hirdesh.wallet.account.AccountType;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.Journal;
import com.hirdesh.wallet.ledger.JournalDraft;
import com.hirdesh.wallet.ledger.JournalType;
import com.hirdesh.wallet.ledger.LedgerService;
import com.hirdesh.wallet.ledger.PostingDraft;

/**
 * Turns business actions into journal recipes (HLD §5 / LLD §6).
 * Knows WHICH accounts and signs; the ledger knows HOW to write safely.
 * Every method must be called inside a transaction (the idempotency Tx2).
 */
@Service
public class WalletOperations {

    public static final String CASH_POOL = "CASH_POOL";

    private final AccountRepository accounts;
    private final LedgerService ledger;

    public WalletOperations(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /** Money enters from outside: Pool +x (asset ↑), Wallet −x (liability ↑). */
    public Journal topUp(long walletId, long amount, String idempotencyKey) {
        long wallet = requireUserWallet(walletId);
        long pool = poolId();
        return post(JournalType.TOP_UP, amount, idempotencyKey, debit(pool, amount), credit(wallet, amount));
    }

    /** Money leaves to the user's bank: Wallet +x (liability ↓), Pool −x (asset ↓). */
    public Journal withdraw(long walletId, long amount, String idempotencyKey) {
        long wallet = requireUserWallet(walletId);
        long pool = poolId();
        return post(JournalType.WITHDRAW, amount, idempotencyKey, debit(wallet, amount), credit(pool, amount));
    }

    /** Sender +x (we owe them less), Receiver −x (we owe them more). */
    public Journal transfer(long fromWalletId, long toWalletId, long amount, String idempotencyKey) {
        if (fromWalletId == toWalletId) {
            throw new WalletException(ErrorCode.VALIDATION_FAILED, "Cannot transfer to the same wallet");
        }
        long from = requireUserWallet(fromWalletId);
        long to = requireUserWallet(toWalletId);
        return post(JournalType.TRANSFER, amount, idempotencyKey, debit(from, amount), credit(to, amount));
    }

    private Journal post(JournalType type, long amount, String key, PostingDraft... postings) {
        return ledger.post(new JournalDraft(type, amount, null, key, List.of(postings)));
    }

    /** Also stops callers from using the cash pool's id as a "wallet". Account type never changes, so no lock needed. */
    private long requireUserWallet(long id) {
        return accounts.findById(id)
                .filter(a -> a.type() == AccountType.USER_WALLET)
                .map(Account::id)
                .orElseThrow(() -> WalletException.walletNotFound(id));
    }

    private long poolId() {
        return accounts.getByCode(CASH_POOL).id();
    }
}
