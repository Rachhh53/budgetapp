-- Editing a saved pay stub can be cancelled (Rachel, 2026-10-24: "there's no like, cancel on a stub. if I go in to edit
-- it and just want to not save those edits, there's no option for that"). Edit stub puts a saved stub back in review,
-- and each change to a line is kept as it is made, so the stub as it was saved is copied aside first: what saving it
-- changed (`saved_applied`), its date (`saved_pay_date`) and its lines (`pay_stub_saved_lines`). Cancel puts the copy
-- back; saving again or removing the stub lets it go. `saved_applied` is set only while a saved stub is being edited.
ALTER TABLE pay_stubs ADD COLUMN saved_applied TEXT CHECK (saved_applied IN ('correct', 'pay_change', 'paycheck', 'one_off', 'none'));
ALTER TABLE pay_stubs ADD COLUMN saved_pay_date TEXT;

-- The same columns as pay_stub_lines, as they were when the stub was saved.
CREATE TABLE pay_stub_saved_lines (
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

CREATE INDEX pay_stub_saved_lines_stub ON pay_stub_saved_lines (pay_stub_id, position);
