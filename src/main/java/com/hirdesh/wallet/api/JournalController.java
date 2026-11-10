package com.hirdesh.wallet.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hirdesh.wallet.api.dto.Dtos.AmountRequest;
import com.hirdesh.wallet.api.dto.Dtos.JournalResponse;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.idempotency.IdempotencyService;
import com.hirdesh.wallet.idempotency.RequestHasher;
import com.hirdesh.wallet.ledger.LedgerRepository;
import com.hirdesh.wallet.wallet.CorrectionOperations;

@RestController
@RequestMapping("/api/v1/journals")
public class JournalController {

    private final LedgerRepository ledger;
    private final CorrectionOperations corrections;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final RequestValidator validate;

    public JournalController(LedgerRepository ledger, CorrectionOperations corrections,
                             IdempotencyService idempotency, RequestHasher hasher, RequestValidator validate) {
        this.ledger = ledger;
        this.corrections = corrections;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.validate = validate;
    }

    @GetMapping("/{journalId}")
    public JournalResponse get(@PathVariable long journalId) {
        return ledger.findJournal(journalId)
                .map(JournalResponse::from)
                .orElseThrow(() -> new WalletException(ErrorCode.JOURNAL_NOT_FOUND, "Journal " + journalId + " not found"));
    }

    /** FR-7: full or partial refund of a P2P transfer. */
    @PostMapping("/{journalId}/refunds")
    public ResponseEntity<String> refund(@PathVariable long journalId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody AmountRequest req) {
        String idemKey = validate.idempotencyKey(key);
        long amount = validate.amount(req.amount());
        String hash = hasher.hash("REFUND", journalId, amount);
        return IdempotentResponses.toHttp(idempotency.execute(idemKey, hash,
                () -> WalletController.created(corrections.refund(journalId, amount, idemKey))));
    }

    /** FR-8: full reversal of a top-up or withdrawal. Body is ignored (may be empty). */
    @PostMapping("/{journalId}/reversals")
    public ResponseEntity<String> reverse(@PathVariable long journalId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String idemKey = validate.idempotencyKey(key);
        String hash = hasher.hash("REVERSAL", journalId);
        return IdempotentResponses.toHttp(idempotency.execute(idemKey, hash,
                () -> WalletController.created(corrections.reverse(journalId, idemKey))));
    }
}
