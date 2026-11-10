package com.hirdesh.wallet.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hirdesh.wallet.account.AccountService;
import com.hirdesh.wallet.account.HistoryItem;
import com.hirdesh.wallet.api.dto.Dtos.AmountRequest;
import com.hirdesh.wallet.api.dto.Dtos.CreateWalletRequest;
import com.hirdesh.wallet.api.dto.Dtos.HistoryPage;
import com.hirdesh.wallet.api.dto.Dtos.JournalResponse;
import com.hirdesh.wallet.api.dto.Dtos.WalletResponse;
import com.hirdesh.wallet.idempotency.IdempotencyService;
import com.hirdesh.wallet.idempotency.OperationResult;
import com.hirdesh.wallet.idempotency.RequestHasher;
import com.hirdesh.wallet.ledger.Journal;
import com.hirdesh.wallet.wallet.WalletOperations;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final AccountService accountService;
    private final WalletOperations ops;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final RequestValidator validate;

    public WalletController(AccountService accountService, WalletOperations ops, IdempotencyService idempotency,
                            RequestHasher hasher, RequestValidator validate) {
        this.accountService = accountService;
        this.ops = ops;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.validate = validate;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> create(@RequestBody CreateWalletRequest req) {
        String userId = validate.userId(req.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WalletResponse.from(accountService.createWallet(userId)));
    }

    @GetMapping("/{walletId}")
    public WalletResponse get(@PathVariable long walletId) {
        return WalletResponse.from(accountService.getWallet(walletId));
    }

    @GetMapping("/{walletId}/transactions")
    public HistoryPage history(@PathVariable long walletId,
                               @RequestParam(defaultValue = "20") int limit,
                               @RequestParam(required = false) Long before) {
        int pageSize = validate.limit(limit);
        List<HistoryItem> items = accountService.history(walletId, before == null ? Long.MAX_VALUE : before, pageSize);
        Long nextBefore = items.size() == pageSize ? items.getLast().postingId() : null;
        return new HistoryPage(items, nextBefore);
    }

    @PostMapping("/{walletId}/top-ups")
    public ResponseEntity<String> topUp(@PathVariable long walletId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @RequestBody AmountRequest req) {
        String idemKey = validate.idempotencyKey(key);
        long amount = validate.amount(req.amount());
        String hash = hasher.hash("TOP_UP", walletId, amount);
        return IdempotentResponses.toHttp(idempotency.execute(idemKey, hash,
                () -> created(ops.topUp(walletId, amount, idemKey))));
    }

    @PostMapping("/{walletId}/withdrawals")
    public ResponseEntity<String> withdraw(@PathVariable long walletId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody AmountRequest req) {
        String idemKey = validate.idempotencyKey(key);
        long amount = validate.amount(req.amount());
        String hash = hasher.hash("WITHDRAW", walletId, amount);
        return IdempotentResponses.toHttp(idempotency.execute(idemKey, hash,
                () -> created(ops.withdraw(walletId, amount, idemKey))));
    }

    static OperationResult created(Journal journal) {
        return OperationResult.created(JournalResponse.from(journal), journal.id());
    }
}
