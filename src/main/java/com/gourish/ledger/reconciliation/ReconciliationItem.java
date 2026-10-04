package com.gourish.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReconciliationItem(String reference, String status, Long transactionId,
        BigDecimal ledgerAmount, BigDecimal bankAmount,
        LocalDate bankDate, BigDecimal difference) {
}