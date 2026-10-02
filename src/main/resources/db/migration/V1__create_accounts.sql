CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (
        type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')
    ),
    currency CHAR(3) NOT NULL,
    parent_id BIGINT REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);