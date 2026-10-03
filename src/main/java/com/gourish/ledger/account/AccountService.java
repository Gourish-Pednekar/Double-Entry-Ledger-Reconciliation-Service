package com.gourish.ledger.account;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository repo;

    public AccountService(AccountRepository repo) {
        this.repo = repo;
    }

        @Transactional
    public AccountResponse create(CreateAccountRequest req) {
        if (repo.existsByCode(req.code())) {
            throw new DuplicateAccountException(req.code());
        }
        if (req.parentId() != null) {
            Account parent = repo.findById(req.parentId())
                    .orElseThrow(() -> new AccountNotFoundException(req.parentId()));
            if (parent.getType() != req.type() || !parent.getCurrency().equals(req.currency())) {
                throw new InvalidHierarchyException(
                        "A child account must have the same type and currency as its parent ("
                                + parent.getType() + ", " + parent.getCurrency() + ")");
            }
        }
        return AccountResponse.from(repo.saveAndFlush(
                new Account(req.code(), req.name(), req.type(), req.currency(), req.parentId())));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return repo.findAll().stream().map(AccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long id) {
        return repo.findById(id).map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
}