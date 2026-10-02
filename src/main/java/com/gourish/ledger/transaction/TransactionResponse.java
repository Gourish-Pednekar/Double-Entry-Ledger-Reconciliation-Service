package com.gourish.ledger.transaction;

import java.time.Instant;
import java.util.List;

public record TransactionResponse(Long id, String description, Instant createdAt,
        List<EntryResponse> entries) {
}