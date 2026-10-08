-- The change log behind Undo (spec §4.1, §6).
--
-- Every request that changes something is one "change": a row in change_groups, with one
-- change_log row for each table row it added, altered or removed. The rows are written by
-- triggers that the server (re)creates from the live schema each time it starts (see
-- ChangeLog), so a table added by a later migration is covered without touching this file.

CREATE TABLE change_groups (
    id         INTEGER PRIMARY KEY,
    created_at TEXT NOT NULL
) STRICT;

CREATE TABLE change_log (
    id         INTEGER PRIMARY KEY,
    group_id   INTEGER NOT NULL REFERENCES change_groups (id) ON DELETE CASCADE,
    table_name TEXT    NOT NULL,
    op         TEXT    NOT NULL CHECK (op IN ('insert', 'update', 'delete')),
    -- The whole row as JSON: before the change (NULL for an insert) and after it (NULL for a delete).
    before     TEXT,
    after      TEXT
) STRICT;

CREATE INDEX change_log_group ON change_log (group_id);

-- The change being recorded right now, if any. The triggers write nothing while it is NULL,
-- which is how reads, and Undo itself, stay out of the log.
CREATE TABLE change_recording (
    id       INTEGER PRIMARY KEY CHECK (id = 1),
    group_id INTEGER REFERENCES change_groups (id) ON DELETE SET NULL
) STRICT;

INSERT INTO change_recording (id, group_id) VALUES (1, NULL);
