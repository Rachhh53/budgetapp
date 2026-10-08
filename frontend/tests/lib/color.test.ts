import { describe, expect, it } from "vitest";
import { accentForText, contrastRatio, DARK_TEXT, isHexColor, LIGHT_TEXT, readableTextOn } from "../../src/lib/color";

describe("readableTextOn", () => {
  it("puts light text on dark colors and dark text on light colors", () => {
    expect(readableTextOn("#0f766e")).toBe(LIGHT_TEXT);
    expect(readableTextOn("#1a2233")).toBe(LIGHT_TEXT);
    expect(readableTextOn("#f4c95d")).toBe(DARK_TEXT);
    expect(readableTextOn("#ffffff")).toBe(DARK_TEXT);
  });

  it("always reaches at least 4.5:1 contrast, whatever color the user picks", () => {
    // Sweep the color cube; the better of dark/light text must always be AA-readable.
    const steps = ["00", "33", "66", "99", "cc", "ff"];
    for (const r of steps) {
      for (const g of steps) {
        for (const b of steps) {
          const color = `#${r}${g}${b}`;
          expect(contrastRatio(color, readableTextOn(color)), color).toBeGreaterThanOrEqual(4.5);
        }
      }
    }
  });
});

describe("contrastRatio", () => {
  it("matches the WCAG reference values", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrastRatio("#ffffff", "#ffffff")).toBeCloseTo(1, 5);
  });
});

describe("accentForText", () => {
  const LIGHT = ["#ffffff", "#f1f3f5"];
  const DARK = ["#1a202b", "#11151c"];

  it("leaves an accent alone where it already reads well", () => {
    expect(accentForText("#0f766e", LIGHT)).toBe("#0f766e");
  });

  it("lightens a dark accent until it reads on the dark theme", () => {
    const text = accentForText("#0f766e", DARK);

    expect(text).not.toBe("#0f766e");
    DARK.forEach((background) => expect(contrastRatio(text, background)).toBeGreaterThanOrEqual(4.5));
  });

  it("darkens a pale accent until it reads on the light theme", () => {
    const text = accentForText("#f4c95d", LIGHT);

    LIGHT.forEach((background) => expect(contrastRatio(text, background)).toBeGreaterThanOrEqual(4.5));
  });

  it("reaches AA for any accent on either theme", () => {
    for (const accent of ["#000000", "#ffffff", "#808080", "#ff0000", "#7e22ce", "#1a202b"]) {
      for (const backgrounds of [LIGHT, DARK]) {
        const text = accentForText(accent, backgrounds);
        backgrounds.forEach((background) => expect(contrastRatio(text, background)).toBeGreaterThanOrEqual(4.5));
      }
    }
  });
});

describe("isHexColor", () => {
  it("accepts only six-digit hex codes", () => {
    expect(isHexColor("#0F766e")).toBe(true);
    expect(isHexColor("#fff")).toBe(false);
    expect(isHexColor("teal")).toBe(false);
  });
});
