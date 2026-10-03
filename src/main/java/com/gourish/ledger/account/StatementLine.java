package com.gourish.ledger.account;

import java.math.BigDecimal;
import java.time.Instant;

import com.gourish.ledger.transaction.Direction;

public record StatementLine(Long entryId, Long transactionId, String description, Instant createdAt,
                            Direction direction, BigDecimal amount, BigDecimal balanceAfter) {}