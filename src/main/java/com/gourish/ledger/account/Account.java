package com.gourish.ledger.account;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String code, String name, AccountType type, String currency, Long parentId) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.parentId = parentId;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getParentId() {
        return parentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}