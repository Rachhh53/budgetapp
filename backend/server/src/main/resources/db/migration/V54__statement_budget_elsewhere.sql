-- Charges from another card put on a budget (Rachel, 2026-10-30: "decrese the budget on the account it was budgeted on
-- and add as one-offs or even an aggregate with the same category and tags ... keep the total budget aligned until all
-- statements are in on the budgeted account"). Each budget date has at most one one-off on each other account, holding
-- what was spent on it there; applying again updates that one-off rather than adding another, and it goes when its
-- charges are taken off. Deleting the one-off by hand lets go of the link, and the next Apply adds it again.
CREATE TABLE statement_budget_elsewhere (
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    original_due_date TEXT    NOT NULL,
    account_id        INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    entry_id          INTEGER NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, original_due_date, account_id)
) STRICT;
