-- Paid for as a pack (Rachel, 2026-10-19: "I pay 1275 for a 20 session pack"): how many sessions the
-- price buys. The amount is then the pack's price, each session costs its share, and the next pack is
-- planned as a bill on the first session nothing paid covers. Empty for a price per session.
-- Its own migration because V28 had already reached a database when this was added.
ALTER TABLE item_versions ADD COLUMN pack_sessions INTEGER CHECK (pack_sessions BETWEEN 1 AND 1000);
