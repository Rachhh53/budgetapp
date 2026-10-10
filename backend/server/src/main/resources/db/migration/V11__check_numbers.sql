-- Something paid from a checking account can carry the number of the check it was paid with:
-- a one-off entry, or one occurrence of a recurring item. NULL when no check was written.
ALTER TABLE entries ADD COLUMN check_number TEXT;
ALTER TABLE occurrence_overrides ADD COLUMN check_number TEXT;
