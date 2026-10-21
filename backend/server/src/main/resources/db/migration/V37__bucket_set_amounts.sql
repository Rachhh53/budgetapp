-- A bucket's amount set for a day is kept as that amount, not as the difference from what Budget worked out when it
-- was set (Rachel, 2026-10-20: "I have been back entering balances and changing dates, but this should adjust",
-- "everything needs to be robust enough that it recalculates"). On a row with set_to, the bucket holds exactly that
-- at the end of its day, whatever is entered or moved before it; its amount is 0 and unused. Earlier
-- "Amount adjusted" rows stay the differences they were.
--
-- amount had to be non-zero, and SQLite cannot change a CHECK in place, so bucket_changes is rebuilt with the same
-- columns in the same order plus set_to, and its rows copied across unchanged. bucket_change_tags refers to it with
-- ON DELETE CASCADE, so it is rebuilt alongside and dropped first (as V26 did for pay stubs); renaming the new tables
-- to the old names carries the reference along. The Undo triggers are rebuilt from the schema when the database is
-- next opened.
CREATE TABLE bucket_changes_rebuilt (
    id           INTEGER PRIMARY KEY,
    bucket_id    INTEGER NOT NULL REFERENCES buckets (id) ON DELETE CASCADE,
    date         TEXT    NOT NULL,
    amount       INTEGER NOT NULL,
    source       TEXT    NOT NULL DEFAULT 'manual'
                 CHECK (source IN ('manual', 'transfer', 'bucket_move', 'interest', 'income_split')),
    note         TEXT,
    to_available INTEGER NOT NULL DEFAULT 0 CHECK (to_available IN (0, 1)),
    category_id  INTEGER REFERENCES categories (id) ON DELETE SET NULL,
    set_to       INTEGER CHECK (set_to IS NULL OR set_to >= 0),
    -- Money put in or taken out moves something; an amount set moves nothing itself.
    CHECK ((set_to IS NULL AND amount <> 0) OR (set_to IS NOT NULL AND amount = 0 AND to_available = 0))
) STRICT;

INSERT INTO bucket_changes_rebuilt (id, bucket_id, date, amount, source, note, to_available, category_id)
SELECT id, bucket_id, date, amount, source, note, to_available, category_id FROM bucket_changes;

CREATE TABLE bucket_change_tags_rebuilt (
    change_id INTEGER NOT NULL REFERENCES bucket_changes_rebuilt (id) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (change_id, tag_id)
) STRICT, WITHOUT ROWID;

INSERT INTO bucket_change_tags_rebuilt (change_id, tag_id) SELECT change_id, tag_id FROM bucket_change_tags;

-- The tags first: nothing then points at bucket_changes when it goes.
DROP TABLE bucket_change_tags;
DROP TABLE bucket_changes;
ALTER TABLE bucket_changes_rebuilt RENAME TO bucket_changes;
ALTER TABLE bucket_change_tags_rebuilt RENAME TO bucket_change_tags;

CREATE INDEX bucket_changes_bucket ON bucket_changes (bucket_id, date);
