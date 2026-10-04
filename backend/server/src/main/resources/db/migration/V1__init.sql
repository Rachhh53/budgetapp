-- Key/value store for app settings. Only values the user has changed are stored;
-- defaults live in code (SettingKey) so a new default never needs a data migration.
CREATE TABLE settings (
    key        TEXT NOT NULL PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
) STRICT, WITHOUT ROWID;
