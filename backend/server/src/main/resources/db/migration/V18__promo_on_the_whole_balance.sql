-- A promotional rate can be on everything the card owes ("0% on the card until March"), not
-- only on a set amount such as one balance transfer (spec §4.9, §5.6). Such a promotion has no
-- amount of its own, so original_balance has to be allowed to be empty: NULL means the whole
-- balance, new charges included, for as long as the promotion runs.
--
-- SQLite cannot loosen a column's NOT NULL or CHECK in place, so the table is rebuilt with the
-- same columns, in the same order, and its rows copied across unchanged. Nothing refers to
-- card_promos, and the column names are the same, so everything recorded for Undo still fits;
-- the Undo triggers are rebuilt from the schema when the database is next opened.
CREATE TABLE card_promos_rebuilt (
    id                INTEGER PRIMARY KEY,
    account_id        INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    label             TEXT    NOT NULL,
    kind              TEXT    NOT NULL CHECK (kind IN ('balance_transfer', 'purchase', 'other')),
    rate_bp           INTEGER NOT NULL CHECK (rate_bp BETWEEN 0 AND 100000),
    start_date        TEXT    NOT NULL,
    end_date          TEXT    NOT NULL,
    -- The amount the promotional rate is on. NULL = everything the card owes.
    original_balance  INTEGER CHECK (original_balance IS NULL OR original_balance > 0),
    -- The rate on what is left once the promotion ends. NULL = the card's regular APR.
    rate_after_bp     INTEGER CHECK (rate_after_bp IS NULL OR rate_after_bp BETWEEN 0 AND 100000),
    -- Deferred interest: unless it is cleared by the end date, interest for the whole
    -- promotion is charged at once.
    deferred_interest INTEGER NOT NULL DEFAULT 0 CHECK (deferred_interest IN (0, 1)),
    -- The recurring payment the payoff helper added for it. Deleting that item only unlinks it.
    payoff_item_id    INTEGER REFERENCES items (id) ON DELETE SET NULL,
    CHECK (end_date > start_date),
    -- Deferred interest is charged on the amount the promotion started with, so it needs one.
    CHECK (deferred_interest = 0 OR original_balance IS NOT NULL)
) STRICT;

INSERT INTO card_promos_rebuilt
    (id, account_id, label, kind, rate_bp, start_date, end_date, original_balance, rate_after_bp, deferred_interest, payoff_item_id)
SELECT id, account_id, label, kind, rate_bp, start_date, end_date, original_balance, rate_after_bp, deferred_interest, payoff_item_id
FROM card_promos;

DROP TABLE card_promos;
ALTER TABLE card_promos_rebuilt RENAME TO card_promos;

CREATE INDEX card_promos_account ON card_promos (account_id, end_date);
