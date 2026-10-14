-- Phase 11: debt & decisions.

-- An account left out of the Debt & decisions comparison. Every loan, card with a balance,
-- investment and savings account is compared unless it is listed here. Nothing else about the
-- comparison is kept: it is worked out afresh each time from the accounts as they stand.
CREATE TABLE planner_hidden_accounts (
    account_id INTEGER PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE
) STRICT;
