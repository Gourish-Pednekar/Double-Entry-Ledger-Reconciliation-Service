package com.gourish.ledger.transaction;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> post(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String key,
            @Valid @RequestBody PostTransactionRequest req) {

        PostResult result = service.post(key, req);
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.transaction());
        }
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.transaction());
    }
}