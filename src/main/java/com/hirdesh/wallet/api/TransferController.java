package com.hirdesh.wallet.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hirdesh.wallet.api.dto.Dtos.TransferRequest;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.idempotency.IdempotencyService;
import com.hirdesh.wallet.idempotency.RequestHasher;
import com.hirdesh.wallet.wallet.WalletOperations;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final WalletOperations ops;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final RequestValidator validate;

    public TransferController(WalletOperations ops, IdempotencyService idempotency, RequestHasher hasher,
                              RequestValidator validate) {
        this.ops = ops;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.validate = validate;
    }

    @PostMapping
    public ResponseEntity<String> transfer(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody TransferRequest req) {
        String idemKey = validate.idempotencyKey(key);
        long from = validate.id(req.fromWalletId(), "fromWalletId");
        long to = validate.id(req.toWalletId(), "toWalletId");
        long amount = validate.amount(req.amount());
        if (from == to) { // BR-4
            throw new WalletException(ErrorCode.VALIDATION_FAILED, "Cannot transfer to the same wallet");
        }
        String hash = hasher.hash("TRANSFER", from, to, amount);
        return IdempotentResponses.toHttp(idempotency.execute(idemKey, hash,
                () -> WalletController.created(ops.transfer(from, to, amount, idemKey))));
    }
}
