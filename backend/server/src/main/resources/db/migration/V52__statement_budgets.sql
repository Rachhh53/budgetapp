-- Statements, made simpler (Rachel, 2026-10-30: "if I could just select to add it to that budget or something, that would
-- be better. one offs are probably the only ones that even need category/tag"; "I basically want the budget to become the
-- actual in aggregate by adding together items from the statement"). Each charge is put on a budget, matched to a bill or
-- one-off already in Budget, or added as a one-off, and nothing changes in the pay periods until Apply. The category and
-- tag totals of V49 (statement_targets, statement_target_hints, counts_toward) are no longer used.

-- The budget a charge is put on; the date of it the charge counts toward is worked out from the charge's day. budget_date
-- is the date it counted toward when last applied, for listing a budget's charges on the pay period.
ALTER TABLE statement_upload_lines ADD COLUMN budget_item_id INTEGER REFERENCES items (id) ON DELETE SET NULL;
ALTER TABLE statement_upload_lines ADD COLUMN budget_date TEXT;
-- Chosen to be added as a one-off on Apply; one_off_entry_id is set once it has been.
ALTER TABLE statement_upload_lines ADD COLUMN add_one_off INTEGER NOT NULL DEFAULT 0 CHECK (add_one_off IN (0, 1));
-- A match (V51) is only a choice until Apply writes it to the bill's date; before_* are then what that date was.
ALTER TABLE statement_upload_lines ADD COLUMN match_applied INTEGER NOT NULL DEFAULT 0 CHECK (match_applied IN (0, 1));

-- The statement's own balances, read from the file when it says them or typed off the paper statement, to check that the
-- rows read add up (Rachel: "make sure the rows you show me for the statement add up to the statement balance"). On a
-- card, what is owed.
ALTER TABLE statement_uploads ADD COLUMN previous_balance INTEGER;
ALTER TABLE statement_uploads ADD COLUMN new_balance INTEGER;

-- The budget a payee's charges were last put on, on an account: offered as a suggestion, never chosen by itself.
CREATE TABLE statement_budget_hints (
    account_id INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    payee_key  TEXT    NOT NULL,
    item_id    INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    PRIMARY KEY (account_id, payee_key)
) STRICT;

-- "Still to come" typed for a budget date the statements only partly cover (Rachel: "I might need the ability to have a
-- placeholder amount too ... make this all editable of course"). Without one it is the budgeted amount less what was spent.
CREATE TABLE statement_still_to_come (
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    original_due_date TEXT    NOT NULL,
    amount            INTEGER NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (item_id, original_due_date)
) STRICT;

-- What an applied budget date was made of, for the pay period to show: actual spending and still to come
-- (base_amount is what was budgeted).
ALTER TABLE statement_replacements ADD COLUMN actual_amount INTEGER;
ALTER TABLE statement_replacements ADD COLUMN still_to_come INTEGER;

-- A bill's date whose applied match was let go of: put back as it was on the next Apply of that pay period.
CREATE TABLE statement_match_restores (
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    original_due_date TEXT    NOT NULL,
    account_id        INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    period_start      TEXT    NOT NULL,
    amount            INTEGER,
    paid              INTEGER NOT NULL CHECK (paid IN (0, 1)),
    paid_date         TEXT,
    projected         INTEGER,
    PRIMARY KEY (item_id, original_due_date)
) STRICT;
