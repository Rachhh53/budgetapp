-- Phase 10: plan next year.

-- One plan for one budget year. A `draft` changes nothing and can be deleted and redone until
-- `starts_on`; on that day it becomes `active`, its amounts become what each item and paycheck
-- comes to from then on, and the plan before it is `closed`. `setup` is 1 for the plan made
-- by first-time setup, which is of the year already under way.
--
-- Suggestions are worked out from history as it stood on `suggested_as_of`, so they do not
-- shift under someone part-way through the steps when a month ends.
CREATE TABLE plan_years (
    id              INTEGER PRIMARY KEY,
    year            INTEGER NOT NULL,
    status          TEXT    NOT NULL CHECK (status IN ('draft', 'active', 'closed')),
    basis           TEXT    NOT NULL CHECK (basis IN ('last_12', 'last_12_trend', 'prior_year')),
    starts_on       TEXT    NOT NULL,
    suggested_as_of TEXT    NOT NULL,
    setup           INTEGER NOT NULL DEFAULT 0 CHECK (setup IN (0, 1)),
    activated_on    TEXT,
    created_at      TEXT    NOT NULL,
    CHECK ((status = 'draft') = (activated_on IS NULL))
) STRICT;

-- One plan for a budget year, and one draft at a time.
CREATE UNIQUE INDEX plan_years_start ON plan_years (starts_on);
CREATE UNIQUE INDEX plan_years_one_draft ON plan_years (status) WHERE status = 'draft';

-- An amount in a plan, for a recurring item (its total for the year) or an income source (its
-- take-home pay for one paycheck). While the plan is a draft only the amounts typed in are
-- kept here (`chosen_by` is `user`); everything else follows its suggestion. When the plan
-- becomes active every amount it set is written down, the suggested ones too, as the record
-- of what the plan was.
CREATE TABLE plan_year_amounts (
    id               INTEGER PRIMARY KEY,
    plan_year_id     INTEGER NOT NULL REFERENCES plan_years (id) ON DELETE CASCADE,
    item_id          INTEGER REFERENCES items (id) ON DELETE CASCADE,
    income_source_id INTEGER REFERENCES income_sources (id) ON DELETE CASCADE,
    suggested_amount INTEGER NOT NULL CHECK (suggested_amount >= 0),
    chosen_amount    INTEGER NOT NULL CHECK (chosen_amount >= 0),
    chosen_by        TEXT    NOT NULL CHECK (chosen_by IN ('suggestion', 'user')),
    CHECK ((item_id IS NULL) <> (income_source_id IS NULL))
) STRICT;

CREATE UNIQUE INDEX plan_year_amounts_item ON plan_year_amounts (plan_year_id, item_id) WHERE item_id IS NOT NULL;
CREATE UNIQUE INDEX plan_year_amounts_source ON plan_year_amounts (plan_year_id, income_source_id) WHERE income_source_id IS NOT NULL;
