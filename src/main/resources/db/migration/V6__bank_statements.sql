CREATE TABLE bank_statements (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE bank_lines (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL REFERENCES bank_statements(id),
    reference VARCHAR(64) NOT NULL,
    booked_on DATE NOT NULL,
    amount NUMERIC(19, 4) NOT NULL CHECK (amount <> 0),
    description VARCHAR(255),
    UNIQUE (statement_id, reference)
);