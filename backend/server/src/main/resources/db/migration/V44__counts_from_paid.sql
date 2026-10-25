-- A recurring item can count its next dates from the day it was last paid (Rachel, 2026-10-24: "if it's every 6 weeks,
-- it should adjust upcoming payments to 6 weeks after the latest one"). Off by default, so a bill with a fixed due date
-- keeps it; she chose a setting per item, with every date after the latest paid one counted on from that day.
ALTER TABLE items ADD COLUMN counts_from_paid INTEGER NOT NULL DEFAULT 0 CHECK (counts_from_paid IN (0, 1));
