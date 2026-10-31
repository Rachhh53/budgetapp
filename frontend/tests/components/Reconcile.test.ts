import { describe, expect, it } from "vitest";
import { between } from "../../src/components/accounts/Reconcile";

/** Which days' movements lie between two balances entered (Rachel, 2026-10-30: "balances should always be end of day"). */
describe("between two balances entered", () => {
  it("leaves out the days each balance already holds when both ended their day", () => {
    expect(between({ date: "2026-10-15", endOfDay: true }, { date: "2026-10-30", endOfDay: true })).toEqual({
      from: "2026-10-16",
      through: "2026-10-30",
    });
  });

  it("keeps the old meaning of a balance entered before the change: the start of its day", () => {
    expect(between({ date: "2026-09-15", endOfDay: false }, { date: "2026-10-30", endOfDay: true })).toEqual({
      from: "2026-09-15",
      through: "2026-10-30",
    });
    expect(between({ date: "2026-09-15", endOfDay: false }, { date: "2026-09-30", endOfDay: false })).toEqual({
      from: "2026-09-15",
      through: "2026-09-29",
    });
  });
});
