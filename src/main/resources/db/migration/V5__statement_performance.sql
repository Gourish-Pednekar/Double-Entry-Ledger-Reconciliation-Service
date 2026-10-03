CREATE INDEX idx_entries_account_time ON entries (account_id, created_at, id) INCLUDE (direction, amount, transaction_id);
-- Redundant: idx_entries_account_time also leads with account_id and is
-- what the planner picks for every account-scoped query we measured.
DROP INDEX idx_entries_account;