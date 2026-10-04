package com.gourish.ledger.reconciliation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class BankStatementController {

    private final BankStatementService importService;
    private final ReconciliationService reconciliationService;

    public BankStatementController(BankStatementService importService,
            ReconciliationService reconciliationService) {
        this.importService = importService;
        this.reconciliationService = reconciliationService;
    }

    /** Body is the raw CSV text. Send with Content-Type: text/csv. */
    @PostMapping(path = "/api/accounts/{id}/bank-statements", consumes = "text/csv")
    public ResponseEntity<ImportResult> importStatement(@PathVariable Long id, @RequestBody String csv) {
        return ResponseEntity.status(HttpStatus.CREATED).body(importService.importCsv(id, csv));
    }

    @GetMapping("/api/bank-statements/{id}/reconciliation")
    public ReconciliationResponse reconcile(@PathVariable Long id) {
        return reconciliationService.reconcile(id);
    }
}