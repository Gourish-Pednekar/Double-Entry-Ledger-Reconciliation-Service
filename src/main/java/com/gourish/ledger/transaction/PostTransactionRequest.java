package com.gourish.ledger.transaction;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostTransactionRequest(
        @NotBlank @Size(max = 255) String description,
        @NotNull @Size(min = 2) List<@Valid EntryRequest> entries) {
}