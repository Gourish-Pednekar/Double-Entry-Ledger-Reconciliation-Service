package com.gourish.ledger.account;

import jakarta.validation.constraints.*;

public record CreateAccountRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull AccountType type,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO code like INR") String currency,
        Long parentId) {
}