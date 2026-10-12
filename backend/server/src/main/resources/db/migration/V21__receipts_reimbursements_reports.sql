-- Phase 7: receipts and documents, reimbursements, saved reports.

-- A reimbursement has had a status since V3. It also remembers the day it was sent in and who
-- to ("HSA", "Work"). The money that pays an expense back is one or more money-in entries
-- whose `reimburses_entry_id` names it, so part payments add up.
ALTER TABLE entries ADD COLUMN reimbursement_submitted_on TEXT;
ALTER TABLE entries ADD COLUMN reimbursement_to TEXT;
CREATE INDEX entries_reimburses ON entries (reimburses_entry_id);

-- One row per distinct file kept in the data folder's `files/`, named there by the SHA-256
-- of its contents, so the same receipt attached twice is stored once. `pages` is set for a PDF.
CREATE TABLE files (
    id         INTEGER PRIMARY KEY,
    hash       TEXT    NOT NULL UNIQUE CHECK (length(hash) = 64),
    mime       TEXT    NOT NULL CHECK (mime IN ('application/pdf', 'image/jpeg', 'image/png')),
    size       INTEGER NOT NULL CHECK (size > 0),
    pages      INTEGER CHECK (pages IS NULL OR pages > 0),
    created_at TEXT    NOT NULL
) STRICT;

-- A file attached to exactly one thing: a one-off entry, one time a recurring item came due
-- (the item and the date its schedule names, as in `occurrence_overrides`), or a card
-- statement. Each owner has a column of its own rather than a type and an id, so the database
-- itself removes the attachment with its owner and Undo can put both back. The name is the
-- attachment's, not the file's: the same file can be attached under two names.
CREATE TABLE attachments (
    id             INTEGER PRIMARY KEY,
    file_id        INTEGER NOT NULL REFERENCES files (id),
    name           TEXT    NOT NULL,
    entry_id       INTEGER REFERENCES entries (id) ON DELETE CASCADE,
    item_id        INTEGER REFERENCES items (id) ON DELETE CASCADE,
    scheduled_date TEXT,
    statement_id   INTEGER REFERENCES card_statements (id) ON DELETE CASCADE,
    created_at     TEXT    NOT NULL,
    CHECK ((entry_id IS NOT NULL) + (item_id IS NOT NULL) + (statement_id IS NOT NULL) = 1),
    CHECK ((item_id IS NULL) = (scheduled_date IS NULL))
) STRICT;

CREATE INDEX attachments_entry ON attachments (entry_id);
CREATE INDEX attachments_occurrence ON attachments (item_id, scheduled_date);
CREATE INDEX attachments_statement ON attachments (statement_id);
CREATE INDEX attachments_file ON attachments (file_id);

-- A report the user saved: its name and what it asks for (dates, what to include, filters,
-- grouping, how to show it), as JSON the server wrote after checking every part.
CREATE TABLE saved_reports (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    definition TEXT    NOT NULL,
    created_at TEXT    NOT NULL
) STRICT;
