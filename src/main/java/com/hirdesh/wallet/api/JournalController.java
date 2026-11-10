package com.hirdesh.wallet.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hirdesh.wallet.api.dto.Dtos.JournalResponse;
import com.hirdesh.wallet.common.ErrorCode;
import com.hirdesh.wallet.common.WalletException;
import com.hirdesh.wallet.ledger.LedgerRepository;

@RestController
@RequestMapping("/api/v1/journals")
public class JournalController {

    private final LedgerRepository ledger;

    public JournalController(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/{journalId}")
    public JournalResponse get(@PathVariable long journalId) {
        return ledger.findJournal(journalId)
                .map(JournalResponse::from)
                .orElseThrow(() -> new WalletException(ErrorCode.JOURNAL_NOT_FOUND, "Journal " + journalId + " not found"));
    }
}
