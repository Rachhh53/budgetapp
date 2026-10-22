import { describe, expect, it } from "vitest";
import { formatDate, formatDay, formatWeekdayDay, isWeekend, ordinal, paidDateWithin, todayIso, weekdayName, yearTicks } from "../../src/lib/dates";

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

describe("yearTicks", () => {
  const jan1 = (year: number) => Date.UTC(year, 0, 1);

  it("puts one label on each January 1st in the span", () => {
    expect(yearTicks(Date.UTC(2026, 9, 1), Date.UTC(2030, 1, 1))).toEqual([jan1(2027), jan1(2028), jan1(2029), jan1(2030)]);
  });

  it("includes the first day when it is a January 1st", () => {
    expect(yearTicks(jan1(2026), jan1(2027))).toEqual([jan1(2026), jan1(2027)]);
  });

  it("labels every second or fifth year over a long span", () => {
    expect(yearTicks(jan1(2026), jan1(2036))).toEqual([2026, 2028, 2030, 2032, 2034, 2036].map(jan1));
    expect(yearTicks(jan1(2026), jan1(2056))).toEqual([2026, 2031, 2036, 2041, 2046, 2051, 2056].map(jan1));
  });
});

describe("the day a bill ticked paid is dated", () => {
  // Rachel, 2026-10-22: "it should default to within the pay period".
  it("is today while the pay period runs", () => {
    expect(paidDateWithin("2026-03-13", "2026-03-30", "2026-03-20", "2026-03-18")).toBe("2026-03-18");
  });

  it("is the day the bill counts on when the period is over or still to come", () => {
    expect(paidDateWithin("2026-03-13", "2026-03-30", "2026-03-20", "2026-10-22")).toBe("2026-03-20");
    expect(paidDateWithin("2026-11-13", "2026-11-30", "2026-11-20", "2026-10-22")).toBe("2026-11-20");
  });

  it("stays inside the period for a bill carried into it from before", () => {
    expect(paidDateWithin("2026-03-13", "2026-03-30", "2026-02-20", "2026-10-22")).toBe("2026-03-13");
  });
});
