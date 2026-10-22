-- Paid ahead (Rachel, 2026-10-19: "if I pay 100 today and I attend a course that costs 25/week and
-- I want to draw down on that each week"): the mirror of billed later (V25). Money is paid first, as
-- one-off entries, and each session the schedule names uses some of it up (spec §4.6).

-- From this version on, what the schedule names are sessions: they move no money themselves, but
-- use up what was paid ahead for them.
ALTER TABLE item_versions ADD COLUMN paid_ahead INTEGER NOT NULL DEFAULT 0 CHECK (paid_ahead IN (0, 1));

-- Which one-off entries were paid ahead for an item. The entry is the payment itself (its date,
-- amount, account, receipts); this only says what it pays for. Removing either removes the link,
-- and a payment whose item is deleted stays as an ordinary one-off: the money did leave.
CREATE TABLE item_prepayments (
    entry_id INTEGER PRIMARY KEY REFERENCES entries (id) ON DELETE CASCADE,
    item_id  INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE
) STRICT;

CREATE INDEX item_prepayments_item ON item_prepayments (item_id);
