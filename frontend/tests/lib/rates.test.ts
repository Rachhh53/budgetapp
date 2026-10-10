import { describe, expect, it } from "vitest";
import { formatRate, parseRate, rateToInput } from "../../src/lib/rates";

describe("formatRate", () => {
  it("writes basis points as a percentage with two decimals", () => {
    expect(formatRate(410)).toBe("4.10%");
    expect(formatRate(5)).toBe("0.05%");
    expect(formatRate(0)).toBe("0.00%");
    expect(formatRate(2499)).toBe("24.99%");
  });
});

describe("rateToInput", () => {
  it("gives the plain number that goes back into a field", () => {
    expect(rateToInput(410)).toBe("4.10");
  });
});

describe("parseRate", () => {
  it("reads what people type", () => {
    expect(parseRate("4.1")).toBe(410);
    expect(parseRate("4.10%")).toBe(410);
    expect(parseRate(" 5 ")).toBe(500);
    expect(parseRate(".25")).toBe(25);
    expect(parseRate("0")).toBe(0);
  });

  it("answers null for anything that is not a rate", () => {
    expect(parseRate("")).toBeNull();
    expect(parseRate("four")).toBeNull();
    expect(parseRate("4.105")).toBeNull();
    expect(parseRate("-1")).toBeNull();
  });
});
