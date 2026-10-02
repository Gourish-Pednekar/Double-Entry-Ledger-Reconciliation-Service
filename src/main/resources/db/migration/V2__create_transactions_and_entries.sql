CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE,
    -- used in Phase B
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_transaction ON entries (transaction_id);
CREATE INDEX idx_entries_account ON entries (account_id, id);
-- 1. Reject unbalanced transactions (checked at COMMIT, not per row)
CREATE FUNCTION check_transaction_balanced() RETURNS trigger AS $$
DECLARE total_debits NUMERIC;
total_credits NUMERIC;
entry_count INT;
BEGIN
SELECT COALESCE(
        SUM(amount) FILTER (
            WHERE direction = 'DEBIT'
        ),
        0
    ),
    COALESCE(
        SUM(amount) FILTER (
            WHERE direction = 'CREDIT'
        ),
        0
    ),
    COUNT(*) INTO total_debits,
    total_credits,
    entry_count
FROM entries
WHERE transaction_id = NEW.transaction_id;
IF entry_count < 2 THEN RAISE EXCEPTION 'Transaction % needs at least 2 entries',
NEW.transaction_id;
END IF;
IF total_debits <> total_credits THEN RAISE EXCEPTION 'Transaction % is unbalanced: debits=% credits=%',
NEW.transaction_id,
total_debits,
total_credits;
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_entries_balanced
AFTER
INSERT ON entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();
-- 2. Append-only: ledger rows can never be edited or deleted
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION '% on % is not allowed: the ledger is append-only',
TG_OP,
TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_entries_immutable BEFORE
UPDATE
    OR DELETE ON entries FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_transactions_immutable BEFORE
UPDATE
    OR DELETE ON transactions FOR EACH ROW EXECUTE FUNCTION forbid_mutation();