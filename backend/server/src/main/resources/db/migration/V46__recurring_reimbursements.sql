-- Paying back recurring payments (Rachel, 2026-10-24: "how should I plan for something to be reimbursed? like If I have
-- a recurring payment and I want to budget for it to be reimbursed", "only payments I flag and a set amount defaulted to
-- the payment being flagged").

-- How a recurring item is paid back, set once on the item: by whom, about how many days after each payment, and into
-- which account. Each payment flagged below is planned to come back that way.
CREATE TABLE item_reimbursement_settings (
    item_id         INTEGER PRIMARY KEY REFERENCES items (id) ON DELETE CASCADE,
    reimbursed_by   TEXT,
    days_after      INTEGER NOT NULL CHECK (days_after BETWEEN 0 AND 365),
    into_account_id INTEGER NOT NULL REFERENCES accounts (id)
) STRICT;

-- One payment of a recurring item flagged to be paid back, keyed like occurrence_overrides by the date its schedule
-- names. `amount` is what is to come back, set when it is flagged to the payment's amount and changed only by hand. No row
-- means nobody pays this one back: there is no 'none'.
CREATE TABLE payment_reimbursements (
    id                INTEGER PRIMARY KEY,
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    original_due_date TEXT    NOT NULL,
    amount            INTEGER NOT NULL CHECK (amount >= 0),
    status            TEXT    NOT NULL CHECK (status IN ('to_submit', 'submitted', 'reimbursed')),
    submitted_on      TEXT,
    reimbursement_to  TEXT,
    UNIQUE (item_id, original_due_date)
) STRICT;

-- Money coming in, or moved out of a bucket, that pays one of them back, as reimburses_entry_id does for a one-off.
-- Unflagging the payment leaves the money where it is, paying nothing back.
ALTER TABLE entries ADD COLUMN reimburses_payment_id INTEGER REFERENCES payment_reimbursements (id) ON DELETE SET NULL;
CREATE INDEX entries_reimburses_payment ON entries (reimburses_payment_id) WHERE reimburses_payment_id IS NOT NULL;
ALTER TABLE bucket_changes ADD COLUMN reimburses_payment_id INTEGER REFERENCES payment_reimbursements (id) ON DELETE SET NULL;
CREATE INDEX bucket_changes_reimburses_payment ON bucket_changes (reimburses_payment_id) WHERE reimburses_payment_id IS NOT NULL;
