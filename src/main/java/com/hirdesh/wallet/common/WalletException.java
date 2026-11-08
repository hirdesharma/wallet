package com.hirdesh.wallet.common;

/**
 * An expected business failure (bad input, not enough money, ...).
 * Programming bugs use IllegalArgumentException / IllegalStateException instead.
 */
public class WalletException extends RuntimeException {

    private final ErrorCode code;

    public WalletException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static WalletException walletNotFound(long accountId) {
        return new WalletException(ErrorCode.WALLET_NOT_FOUND, "Account " + accountId + " not found");
    }

    /** @param available user-facing amount (paise) the wallet has; @param required amount it needs */
    public static WalletException insufficientFunds(long accountId, long available, long required) {
        return new WalletException(ErrorCode.INSUFFICIENT_FUNDS,
                "Wallet " + accountId + " has " + available + ", needs " + required);
    }
}
