package com.gourish.ledger.transaction;

import java.math.BigDecimal;

public record EntryResponse(Long accountId, Direction direction, BigDecimal amount) {
}