import { describe, expect, it } from "vitest";
import { centsToInput, formatCents, parseDollars } from "../../src/lib/money";

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
