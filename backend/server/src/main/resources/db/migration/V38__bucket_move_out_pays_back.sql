-- Money moved out of a bucket can pay back an expense, as money coming in can (Rachel, 2026-10-21: "if I am
-- reimbursing myself from a bucket, this doesn't work"). Set on a move out of a bucket only; it counts toward what
-- has come back for that expense. Deleting the expense leaves the move in place, paying nothing back.
ALTER TABLE bucket_changes ADD COLUMN reimburses_entry_id INTEGER REFERENCES entries (id) ON DELETE SET NULL;

CREATE INDEX bucket_changes_reimburses ON bucket_changes (reimburses_entry_id) WHERE reimburses_entry_id IS NOT NULL;
