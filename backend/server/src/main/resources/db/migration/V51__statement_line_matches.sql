-- A statement line can be matched to what it is: a date of a recurring item or a one-off already in Budget (Rachel,
-- 2026-10-30: "just select a row from the statement and just select that it is a charge from that period that already
-- exists and if it doesn't, then we add it"). Matching a recurring item's date sets that date's amount to the charge and
-- ticks it paid on the charge's day; what the date was before is kept here so unmatching puts it back.
ALTER TABLE statement_upload_lines ADD COLUMN matched_item_id INTEGER REFERENCES items (id) ON DELETE SET NULL;
ALTER TABLE statement_upload_lines ADD COLUMN matched_date TEXT;
ALTER TABLE statement_upload_lines ADD COLUMN matched_entry_id INTEGER REFERENCES entries (id) ON DELETE SET NULL;
-- The date as it was before the match: its typed amount (NULL for none), paid tick, paid day and the amount it showed first.
ALTER TABLE statement_upload_lines ADD COLUMN before_amount INTEGER;
ALTER TABLE statement_upload_lines ADD COLUMN before_paid INTEGER NOT NULL DEFAULT 0 CHECK (before_paid IN (0, 1));
ALTER TABLE statement_upload_lines ADD COLUMN before_paid_date TEXT;
ALTER TABLE statement_upload_lines ADD COLUMN before_projected INTEGER;
