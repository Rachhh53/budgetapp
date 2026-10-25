import { describe, expect, it } from "vitest";
import { calculate, centsToInput, formatCents, isSum, parseDollars, shortDollars } from "../../src/lib/money";

describe("formatCents", () => {
  it("shows dollars with separators and two decimals", () => {
    expect(formatCents(170510)).toBe("$1,705.10");
    expect(formatCents(0)).toBe("$0.00");
    expect(formatCents(5)).toBe("$0.05");
  });

  it("uses a minus sign for negatives, never brackets", () => {
    expect(formatCents(-4200)).toBe("-$42.00");
  });
});

describe("centsToInput", () => {
  it("gives the plain form for an amount field", () => {
    expect(centsToInput(170510)).toBe("1705.10");
    expect(centsToInput(7)).toBe("0.07");
    expect(centsToInput(-1250)).toBe("-12.50");
  });
});

describe("parseDollars", () => {
  it("reads what people actually type", () => {
    expect(parseDollars("1705.10")).toBe(170510);
    expect(parseDollars("$1,705.10")).toBe(170510);
    expect(parseDollars(" 45 ")).toBe(4500);
    expect(parseDollars("12.5")).toBe(1250);
    expect(parseDollars(".99")).toBe(99);
    expect(parseDollars("0")).toBe(0);
  });

  it("keeps cents exact where floating point would not", () => {
    // 1.15 * 100 is 114.99999999999999 in floating point.
    expect(parseDollars("1.15")).toBe(115);
    expect(parseDollars("4.35")).toBe(435);
  });

  it("allows a negative amount", () => {
    expect(parseDollars("-42")).toBe(-4200);
    expect(parseDollars("-0")).toBe(0);
  });

  it("returns null for anything that is not an amount", () => {
    for (const text of ["", " ", "abc", "1.234", "1.2.3", "--5", "$", ".", "12 apples"]) {
      expect(parseDollars(text)).toBeNull();
    }
  });

  it("round-trips through the input form", () => {
    for (const cents of [0, 1, 99, 100, 248620, -31840]) {
      expect(parseDollars(centsToInput(cents))).toBe(cents);
    }
  });
});

describe("sums typed into an amount", () => {
  it("adds and takes away, exactly", () => {
    expect(parseDollars("4005+3751")).toBe(775600);
    expect(parseDollars("100-50")).toBe(5000);
    expect(parseDollars("$1,000 + 250.50 - .5")).toBe(125000);
    // 0.1 + 0.2 is 0.30000000000000004 in floating point.
    expect(parseDollars("0.1+0.2")).toBe(30);
  });

  it("still reads a lone negative amount as negative", () => {
    expect(parseDollars("-50")).toBe(-5000);
    expect(parseDollars("-20+5")).toBe(-1500);
    expect(isSum("-50")).toBe(false);
    expect(isSum("100-50")).toBe(true);
  });

  it("returns null for a sum that is not finished or not a sum", () => {
    expect(parseDollars("40+")).toBeNull();
    expect(parseDollars("40++5")).toBeNull();
    expect(parseDollars("40+-5")).toBeNull();
    expect(parseDollars("40+1.234")).toBeNull();
    expect(parseDollars("40*2")).toBeNull();
  });
});

describe("calculate", () => {
  it("adds and takes away an amount", () => {
    expect(calculate(400500, "+", "3751")).toBe(775600);
    expect(calculate(30000, "−", "450.25")).toBe(-15025);
  });

  it("multiplies and divides by a plain number", () => {
    expect(calculate(30000, "÷", "12")).toBe(2500);
    expect(calculate(2500, "×", "2")).toBe(5000);
    // A tax rate has more than two decimals.
    expect(calculate(10000, "×", "1.0825")).toBe(10825);
  });

  it("rounds to the cent, a tie going to the even cent", () => {
    expect(calculate(10000, "÷", "3")).toBe(3333);
    expect(calculate(20000, "÷", "3")).toBe(6667);
    expect(calculate(5, "÷", "2")).toBe(2);
    expect(calculate(15, "÷", "2")).toBe(8);
    expect(calculate(-5, "÷", "2")).toBe(-2);
  });

  it("returns null when there is nothing to work out", () => {
    expect(calculate(30000, "÷", "0")).toBeNull();
    expect(calculate(30000, "×", "")).toBeNull();
    expect(calculate(30000, "+", "1.234")).toBeNull();
    expect(calculate(30000, "×", "abc")).toBeNull();
  });
});

describe("shortDollars", () => {
  it("keeps a tenth under $10k, so ticks a few hundred apart read apart", () => {
    expect([80000, 160000, 240000, 300000].map(shortDollars)).toEqual(["$800", "$1.6k", "$2.4k", "$3k"]);
    expect(shortDollars(2400000)).toBe("$24k");
    expect(shortDollars(125000000)).toBe("$1.25M");
    expect(shortDollars(-2000000)).toBe("-$20k");
    expect(shortDollars(0)).toBe("$0");
  });
});
