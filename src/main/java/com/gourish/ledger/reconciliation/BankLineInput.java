package com.gourish.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BankLineInput(String reference, LocalDate bookedOn, BigDecimal amount, String description) {
}