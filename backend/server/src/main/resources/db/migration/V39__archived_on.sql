-- The day each archived account, recurring item, bucket and asset was put away (Rachel, 2026-10-22: "I archived a
-- bucket once it was empty and it basically removed all the transactions and bucket balance from previous pay
-- periods ... the previous activity needs to remain"; "this should be true for all things that can be archived").
-- Before that day it counts exactly as it did; from that day on it is put away. NULL while it is not archived.
ALTER TABLE accounts ADD COLUMN archived_on TEXT;
ALTER TABLE items ADD COLUMN archived_on TEXT;
ALTER TABLE buckets ADD COLUMN archived_on TEXT;
ALTER TABLE assets ADD COLUMN archived_on TEXT;

-- What was archived before this: the day it was archived, from the Undo log while it still has that change. The log
-- keeps the last 100 changes only, so failing that each takes the guess that adds nothing that wasn't there:
--   an account or asset: the day after the last balance or value entered for it (today when there is none);
--   a recurring item: the day after the last occurrence ticked, typed over or moved (the day it was added when
--     there is none, so nothing is planned for it that wasn't shown before);
--   a bucket: today. Nothing can move into an archived bucket, so it keeps what it had and adds nothing.
-- Each can be changed afterwards where it is archived.
UPDATE accounts
SET archived_on = COALESCE(
        (SELECT date(g.created_at, 'localtime')
         FROM change_log l JOIN change_groups g ON g.id = l.group_id
         WHERE l.table_name = 'accounts' AND l.op = 'update' AND json_extract(l.after, '$.id') = accounts.id
           AND json_extract(l.after, '$.archived') = 1 AND json_extract(l.before, '$.archived') = 0
         ORDER BY g.id DESC LIMIT 1),
        (SELECT date(MAX(date), '+1 day') FROM balance_entries WHERE account_id = accounts.id),
        date('now', 'localtime'))
WHERE archived = 1;

UPDATE assets
SET archived_on = COALESCE(
        (SELECT date(g.created_at, 'localtime')
         FROM change_log l JOIN change_groups g ON g.id = l.group_id
         WHERE l.table_name = 'assets' AND l.op = 'update' AND json_extract(l.after, '$.id') = assets.id
           AND json_extract(l.after, '$.archived') = 1 AND json_extract(l.before, '$.archived') = 0
         ORDER BY g.id DESC LIMIT 1),
        (SELECT date(MAX(date), '+1 day') FROM asset_values WHERE asset_id = assets.id),
        date('now', 'localtime'))
WHERE archived = 1;

UPDATE items
SET archived_on = COALESCE(
        (SELECT date(g.created_at, 'localtime')
         FROM change_log l JOIN change_groups g ON g.id = l.group_id
         WHERE l.table_name = 'items' AND l.op = 'update' AND json_extract(l.after, '$.id') = items.id
           AND json_extract(l.after, '$.archived') = 1 AND json_extract(l.before, '$.archived') = 0
         ORDER BY g.id DESC LIMIT 1),
        (SELECT date(MAX(original_due_date), '+1 day') FROM occurrence_overrides WHERE item_id = items.id),
        date(created_at, 'localtime'))
WHERE archived = 1;

UPDATE buckets
SET archived_on = COALESCE(
        (SELECT date(g.created_at, 'localtime')
         FROM change_log l JOIN change_groups g ON g.id = l.group_id
         WHERE l.table_name = 'buckets' AND l.op = 'update' AND json_extract(l.after, '$.id') = buckets.id
           AND json_extract(l.after, '$.archived') = 1 AND json_extract(l.before, '$.archived') = 0
         ORDER BY g.id DESC LIMIT 1),
        date('now', 'localtime'))
WHERE archived = 1;
