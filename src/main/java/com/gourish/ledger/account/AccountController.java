package com.gourish.ledger.account;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;
    private final BalanceService balanceService;
    private final StatementService statementService;

    public AccountController(AccountService service, BalanceService balanceService,
                             StatementService statementService) {
        this.service = service;
        this.balanceService = balanceService;
        this.statementService = statementService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest req) {
        AccountResponse created = service.create(req);
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    @GetMapping
    public List<AccountResponse> list() { return service.list(); }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) { return service.get(id); }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable Long id) { return balanceService.balanceOf(id); }

    @GetMapping("/{id}/statement")
    public StatementResponse statement(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statementService.statement(id, from, to);
    }
}