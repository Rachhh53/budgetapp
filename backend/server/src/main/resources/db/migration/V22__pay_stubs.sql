-- Phase 8: pay stubs.

-- One stub PDF belonging to an income source. Its file is one of `files`, kept under `files/`
-- like a receipt; the stub names it directly, because a stub is nothing without its file and
-- the name it was uploaded under is shown with it. It waits in `review` until the lines have
-- been looked over, and `applied` records what saving it was asked to change: the amounts in
-- force (`correct`), the pay from its date on (`pay_change`), that one paycheck (`paycheck`),
-- or nothing (`none`).
CREATE TABLE pay_stubs (
    id               INTEGER PRIMARY KEY,
    income_source_id INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    file_id          INTEGER NOT NULL REFERENCES files (id),
    name             TEXT    NOT NULL,
    pay_date         TEXT,
    status           TEXT    NOT NULL CHECK (status IN ('review', 'saved')),
    applied          TEXT    CHECK (applied IN ('correct', 'pay_change', 'paycheck', 'none')),
    created_at       TEXT    NOT NULL,
    CHECK ((status = 'saved') = (applied IS NOT NULL)),
    CHECK (status = 'review' OR pay_date IS NOT NULL)
) STRICT;

CREATE INDEX pay_stubs_source ON pay_stubs (income_source_id, pay_date);
CREATE INDEX pay_stubs_file ON pay_stubs (file_id);

-- Every line read from a stub, in the order shown. What is taken out is a positive amount.
-- `sure` is 0 for a line the reader had to guess at, until someone has looked at it.
CREATE TABLE pay_stub_lines (
    id                 INTEGER PRIMARY KEY,
    pay_stub_id        INTEGER NOT NULL REFERENCES pay_stubs (id) ON DELETE CASCADE,
    position           INTEGER NOT NULL,
    section            TEXT    NOT NULL CHECK (section IN ('earnings', 'pre_tax', 'tax', 'after_tax', 'net', 'employer')),
    label              TEXT    NOT NULL,
    amount             INTEGER NOT NULL,
    ytd_amount         INTEGER,
    maps_to            TEXT    NOT NULL CHECK (maps_to IN ('gross', 'contribution', 'benefit', 'tax', 'net', 'match', 'ignore')),
    maps_to_account_id INTEGER REFERENCES accounts (id) ON DELETE SET NULL,
    sure               INTEGER NOT NULL DEFAULT 1 CHECK (sure IN (0, 1))
) STRICT;

CREATE INDEX pay_stub_lines_stub ON pay_stub_lines (pay_stub_id, position);

-- How each line of an employer's stub was filed the last time one was saved, so the next
-- stub from the same income source fills in by itself. A line is known by its label reduced
-- to letters and digits (`label_key`), which survives a change of spacing or capitals.
CREATE TABLE stub_layouts (
    id                 INTEGER PRIMARY KEY,
    income_source_id   INTEGER NOT NULL REFERENCES income_sources (id) ON DELETE CASCADE,
    label_key          TEXT    NOT NULL,
    section            TEXT    NOT NULL CHECK (section IN ('earnings', 'pre_tax', 'tax', 'after_tax', 'net', 'employer')),
    maps_to            TEXT    NOT NULL CHECK (maps_to IN ('gross', 'contribution', 'benefit', 'tax', 'net', 'match', 'ignore')),
    maps_to_account_id INTEGER REFERENCES accounts (id) ON DELETE SET NULL,
    UNIQUE (income_source_id, label_key)
) STRICT;
