-- Statement uploads (Rachel, 2026-10-25: "upload a statement from a cc or account and aggregate charges by category and
-- tag and have it replace certain budget or recurring items on a pay period"; "I want the option to select a budget or
-- recurring item from a pay period - that shouldnt just happen"; "replace or add should be options"). Called uploads
-- here because card_statements is already a card's own statement balance.

-- A statement file added to an account. A PDF is kept in files/ to be looked at again; a CSV or OFX file is read and
-- not kept, since its lines below hold everything in it.
CREATE TABLE statement_uploads (
    id         INTEGER PRIMARY KEY,
    account_id INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    file_id    INTEGER REFERENCES files (id),
    name       TEXT    NOT NULL,
    format     TEXT    NOT NULL CHECK (format IN ('csv', 'ofx', 'pdf')),
    from_date  TEXT    NOT NULL,
    to_date    TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    applied_at TEXT
) STRICT;
CREATE INDEX statement_uploads_account ON statement_uploads (account_id);
CREATE INDEX statement_uploads_file ON statement_uploads (file_id) WHERE file_id IS NOT NULL;

-- One line of a statement. `amount` is spent as positive and money back as negative. A line counts toward totals
-- while it is not left out, not a repeat of a line an earlier upload of the same account has (`duplicate_of`), and not
-- added as a one-off of its own. `counts_toward` is the choice made when its category and one of its tags are both
-- pointed at items.
CREATE TABLE statement_upload_lines (
    id                   INTEGER PRIMARY KEY,
    upload_id            INTEGER NOT NULL REFERENCES statement_uploads (id) ON DELETE CASCADE,
    position             INTEGER NOT NULL,
    txn_date             TEXT    NOT NULL,
    posted_date          TEXT,
    description          TEXT    NOT NULL,
    payee_key            TEXT    NOT NULL,
    amount               INTEGER NOT NULL,
    kind                 TEXT    NOT NULL CHECK (kind IN ('charge', 'refund', 'payment', 'transfer')),
    left_out             INTEGER NOT NULL DEFAULT 0 CHECK (left_out IN (0, 1)),
    sure                 INTEGER NOT NULL DEFAULT 1 CHECK (sure IN (0, 1)),
    external_id          TEXT,
    duplicate_of         INTEGER REFERENCES statement_upload_lines (id) ON DELETE SET NULL,
    category_id          INTEGER REFERENCES categories (id) ON DELETE SET NULL,
    counts_toward        TEXT CHECK (counts_toward IN ('category', 'tag')),
    counts_toward_tag_id INTEGER REFERENCES tags (id) ON DELETE SET NULL,
    one_off_entry_id     INTEGER REFERENCES entries (id) ON DELETE SET NULL
) STRICT;
CREATE INDEX statement_upload_lines_upload ON statement_upload_lines (upload_id);
CREATE INDEX statement_upload_lines_duplicate ON statement_upload_lines (duplicate_of) WHERE duplicate_of IS NOT NULL;
CREATE INDEX statement_upload_lines_entry ON statement_upload_lines (one_off_entry_id) WHERE one_off_entry_id IS NOT NULL;

CREATE TABLE statement_upload_line_tags (
    line_id INTEGER NOT NULL REFERENCES statement_upload_lines (id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (line_id, tag_id)
) STRICT;

-- Which column of an account's CSV downloads holds what, asked once and remembered by the header row (or, for a file
-- with no header row, by how many columns it has). `layout` is JSON: the date, posted date, description and amount (or
-- money out and money in) columns, how dates are written, and which way spending is signed.
CREATE TABLE statement_csv_layouts (
    account_id INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    signature  TEXT    NOT NULL,
    layout     TEXT    NOT NULL,
    PRIMARY KEY (account_id, signature)
) STRICT;

-- The category and tags a payee was last given, by its key (engine PayeeKey), filled in on the next statement.
CREATE TABLE payee_rules (
    payee_key   TEXT PRIMARY KEY,
    category_id INTEGER REFERENCES categories (id) ON DELETE SET NULL
) STRICT;

CREATE TABLE payee_rule_tags (
    payee_key TEXT    NOT NULL REFERENCES payee_rules (payee_key) ON DELETE CASCADE,
    tag_id    INTEGER NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (payee_key, tag_id)
) STRICT;

-- Rachel's choice, for one account's charges in one pay period, of the item a category's or a tag's total changes, and
-- whether it takes the item's amount's place or is added to it. Every upload of the account shares these, since its
-- statements add together within a period. Exactly one of category_id and tag_id is set.
CREATE TABLE statement_targets (
    id           INTEGER PRIMARY KEY,
    account_id   INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    period_start TEXT    NOT NULL,
    category_id  INTEGER REFERENCES categories (id) ON DELETE CASCADE,
    tag_id       INTEGER REFERENCES tags (id) ON DELETE CASCADE,
    item_id      INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    mode         TEXT    NOT NULL CHECK (mode IN ('replace', 'add')),
    CHECK ((category_id IS NULL) <> (tag_id IS NULL))
) STRICT;
CREATE UNIQUE INDEX statement_targets_category ON statement_targets (account_id, period_start, category_id) WHERE category_id IS NOT NULL;
CREATE UNIQUE INDEX statement_targets_tag ON statement_targets (account_id, period_start, tag_id) WHERE tag_id IS NOT NULL;

-- The item a category's or tag's total was last pointed at on an account: shown beside the next period's total as a
-- suggestion, never chosen by itself.
CREATE TABLE statement_target_hints (
    id          INTEGER PRIMARY KEY,
    account_id  INTEGER NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES categories (id) ON DELETE CASCADE,
    tag_id      INTEGER REFERENCES tags (id) ON DELETE CASCADE,
    item_id     INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    CHECK ((category_id IS NULL) <> (tag_id IS NULL))
) STRICT;
CREATE UNIQUE INDEX statement_target_hints_category ON statement_target_hints (account_id, category_id) WHERE category_id IS NOT NULL;
CREATE UNIQUE INDEX statement_target_hints_tag ON statement_target_hints (account_id, tag_id) WHERE tag_id IS NOT NULL;

-- An item's date whose amount a statement set, keyed like occurrence_overrides. `base_amount` is what it was before
-- any statement changed it, which Add adds to so that applying again never adds twice; `applied_amount` is what the
-- statement made it, so a change typed by hand since is noticed and becomes the new base.
CREATE TABLE statement_replacements (
    item_id           INTEGER NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    original_due_date TEXT    NOT NULL,
    base_amount       INTEGER NOT NULL,
    applied_amount    INTEGER NOT NULL,
    applied_at        TEXT    NOT NULL,
    PRIMARY KEY (item_id, original_due_date)
) STRICT;
