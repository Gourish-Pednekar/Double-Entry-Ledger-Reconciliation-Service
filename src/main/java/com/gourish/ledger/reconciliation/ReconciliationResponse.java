package com.gourish.ledger.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ReconciliationResponse(Long statementId, Long accountId, LocalDate from, LocalDate to,
        Map<String, Long> counts,
        BigDecimal ledgerTotal, BigDecimal bankTotal, BigDecimal difference,
        List<ReconciliationItem> items) {
}