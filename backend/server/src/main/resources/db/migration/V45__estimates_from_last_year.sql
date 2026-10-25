-- A bill or budget can estimate what is still to come from what it really came to a year before (Rachel, 2026-10-24:
-- "if I have a years worth of recurring payments, can I get the option to use last years amounts as a setting"). She
-- chose the same month a year before, so a bill that swings with the seasons is estimated from its own season, and the
-- item's own amount for a date with nothing to match. Off by default.
ALTER TABLE items ADD COLUMN estimates_from_last_year INTEGER NOT NULL DEFAULT 0 CHECK (estimates_from_last_year IN (0, 1));
