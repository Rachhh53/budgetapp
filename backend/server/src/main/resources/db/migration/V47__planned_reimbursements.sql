-- Planning money that comes back (Rachel, 2026-10-24: "I still don't see where I can plan for a reimbursement to happen on
-- this", "a whole payment might not be reimbursed. partials are possible"; she chose to flag each pack, and to plan one-off
-- expenses too).

-- A pack of something paid ahead that Budget plans, flagged to be paid back. A planned pack's date moves as sessions are
-- skipped or put back, so it is named by its place in line: 1 is the next pack to buy. Recording a payment buys pack 1; its
-- flag goes onto that payment and the rest move up one. What comes back is set when flagged, the pack's price to begin with.
CREATE TABLE pack_reimbursements (
    item_id     INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    pack_number INTEGER NOT NULL CHECK (pack_number >= 1),
    amount      INTEGER NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (item_id, pack_number)
) STRICT, WITHOUT ROWID;

-- A one-off expense to be paid back: how much of it comes back (NULL for all of it), into which account and about when.
-- With an account and a day, what is still owed is planned as money coming in.
ALTER TABLE entries ADD COLUMN reimbursement_amount INTEGER CHECK (reimbursement_amount IS NULL OR reimbursement_amount >= 0);
ALTER TABLE entries ADD COLUMN reimbursement_into_account_id INTEGER REFERENCES accounts (id);
ALTER TABLE entries ADD COLUMN reimbursement_expected_on TEXT;
