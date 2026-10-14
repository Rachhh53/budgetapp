-- Billed later: a bill whose visits (a weekly appointment, say) build up and are paid when an
-- invoice for them arrives (spec §4.6).

-- From this version on, what the schedule names are visits: they move no money themselves, but
-- add up as "not billed yet" until an invoice covers them.
ALTER TABLE item_versions ADD COLUMN billed_later INTEGER NOT NULL DEFAULT 0 CHECK (billed_later IN (0, 1));

-- An invoice for a billed-later item's visits. It is paid like any bill paid by hand: it is due
-- on its due date, from the item's account, and is ticked when paid. Its receipts are kept as an
-- occurrence's are, by the item and the due date, which is why there is one invoice per item per due date.
CREATE TABLE item_invoices (
    id                INTEGER PRIMARY KEY,
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    invoice_date      TEXT    NOT NULL,
    due_date          TEXT    NOT NULL,
    -- The last visit it covers: every visit on or before it, after the invoice before, is billed.
    covers_through    TEXT    NOT NULL,
    amount            INTEGER NOT NULL CHECK (amount >= 0),
    -- What the visits it covers came to, for "projected vs. actual".
    projected_amount  INTEGER NOT NULL CHECK (projected_amount >= 0),
    paid              INTEGER NOT NULL DEFAULT 0 CHECK (paid IN (0, 1)),
    paid_date         TEXT,
    confirmation_code TEXT,
    check_number      TEXT,
    note              TEXT,
    created_at        TEXT    NOT NULL,
    UNIQUE (item_id, due_date),
    UNIQUE (item_id, covers_through)
) STRICT;
