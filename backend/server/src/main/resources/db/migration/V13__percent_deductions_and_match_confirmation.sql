-- Phase 4 feedback (2026-10-10): a contribution can be a share of gross pay, and a match worked
-- out from pay can stand without a gross amount once the user has confirmed their contribution
-- is within the matched share.

-- "6% of my pay" instead of a dollar amount, in basis points. `amount` still holds what that
-- comes to for one paycheck: it is worked out from the gross amount every time the source is
-- saved, so everything that reads a deduction keeps reading cents.
ALTER TABLE paycheck_deductions
    ADD COLUMN percent_bp INTEGER CHECK (percent_bp IS NULL OR percent_bp BETWEEN 0 AND 10000);

-- 1 when there is no gross amount to check the contribution against and the user said it is
-- within the share of pay that is matched, so all of it is matched. Always 0 once gross is known.
ALTER TABLE employer_match
    ADD COLUMN contribution_within_cap INTEGER NOT NULL DEFAULT 0 CHECK (contribution_within_cap IN (0, 1));
