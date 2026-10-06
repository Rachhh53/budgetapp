import { describe, expect, it } from "vitest";
import { formatDate, formatDay, formatWeekdayDay, isWeekend, ordinal, todayIso, weekdayName } from "../../src/lib/dates";

describe("date formatting", () => {
  it("formats a calendar day without shifting it across time zones", () => {
    // Read as UTC, the 1st would show as Sep 30 anywhere west of Greenwich.
    expect(formatDay("2026-10-01")).toBe("Oct 1");
    expect(formatDate("2026-10-01")).toBe("Oct 1, 2026");
    expect(formatDate("2026-12-31")).toBe("Dec 31, 2026");
  });

  it("names the weekday", () => {
    expect(formatWeekdayDay("2026-10-30")).toBe("Fri Oct 30");
    expect(weekdayName("2026-11-01")).toBe("Sunday");
  });

  it("knows a weekend from a weekday", () => {
    expect(isWeekend("2026-10-31")).toBe(true);
    expect(isWeekend("2026-11-01")).toBe(true);
    expect(isWeekend("2026-11-02")).toBe(false);
  });

  it("gives today as a plain date", () => {
    expect(todayIso()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});

describe("ordinal", () => {
  it("adds the right ending", () => {
    expect([1, 2, 3, 4, 11, 12, 13, 15, 21, 22, 23, 30].map(ordinal)).toEqual([
      "1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "15th", "21st", "22nd", "23rd", "30th",
    ]);
  });
});
