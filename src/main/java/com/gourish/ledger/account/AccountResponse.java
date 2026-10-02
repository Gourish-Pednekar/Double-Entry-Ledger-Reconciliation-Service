package com.gourish.ledger.account;

import java.time.Instant;

public record AccountResponse(Long id, String code, String name, AccountType type,
        String currency, Long parentId, Instant createdAt) {

    static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getCode(), a.getName(), a.getType(),
                a.getCurrency(), a.getParentId(), a.getCreatedAt());
    }
}