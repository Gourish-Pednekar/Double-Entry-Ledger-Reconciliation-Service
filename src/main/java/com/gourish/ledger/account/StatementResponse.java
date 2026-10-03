package com.gourish.ledger.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StatementResponse(Long accountId, String code, AccountType type, String currency,
                                LocalDate from, LocalDate to,
                                BigDecimal openingBalance, BigDecimal closingBalance,
                                List<StatementLine> lines) {}