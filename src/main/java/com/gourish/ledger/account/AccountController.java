package com.gourish.ledger.account;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;
    private final BalanceService balanceService;

    public AccountController(AccountService service, BalanceService balanceService) {
        this.service = service;
        this.balanceService = balanceService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest req) {
        AccountResponse created = service.create(req);
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable Long id) {
        return balanceService.balanceOf(id);
    }
}