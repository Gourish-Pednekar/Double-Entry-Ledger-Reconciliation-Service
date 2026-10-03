package com.gourish.ledger.account;

import java.math.BigDecimal;
import java.util.List;

public record AccountNode(Long id, String code, String name, AccountType type, String currency,
                          BigDecimal ownBalance, BigDecimal totalBalance, List<AccountNode> children) {}