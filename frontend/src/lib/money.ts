/**
 * Money on the frontend is always a whole number of cents, exactly as the backend stores it.
 * Dollars only exist as text: what is shown, and what is typed.
 */

const DOLLARS = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });

/** `170510` → `"$1,705.10"`; negatives get a minus sign, never brackets. */
export function formatCents(cents: number): string {
  return DOLLARS.format(cents / 100);
}

/** `24000000` → `"$240k"`, `125000000` → `"$1.25M"`: short enough for a chart's axis, for figures that can run to millions. */
export function shortDollars(cents: number): string {
  const dollars = cents / 100;
  if (Math.abs(dollars) >= 1_000_000) {
    return `$${(dollars / 1_000_000).toFixed(2).replace(/\.?0+$/, "")}M`;
  }
  return Math.abs(dollars) >= 1000 ? `$${Math.round(dollars / 1000)}k` : `$${Math.round(dollars)}`;
}

/** `170510` → `"1705.10"`: the plain form that goes back into an amount field. */
export function centsToInput(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const whole = Math.trunc(Math.abs(cents) / 100);
  const fraction = String(Math.abs(cents) % 100).padStart(2, "0");
  return `${sign}${whole}.${fraction}`;
}

// Optional minus, digits, then at most two decimals. Parsed as text so no float ever touches the value.
const AMOUNT = /^(-?)(\d*)(?:\.(\d{0,2}))?$/;

// Two or more amounts added or taken away: "4005+3751", "100-50", "-20+5".
const SUM = /^-?[\d.]+(?:[+-][\d.]+)+$/;

/** What was typed, without the dollar signs, commas and spaces people add. */
function bare(text: string): string {
  return text.replace(/[$,\s]/g, "");
}

/** True when what was typed is a sum to work out (`"4005+3751"`) rather than one amount. */
export function isSum(text: string): boolean {
  return SUM.test(bare(text));
}

/**
 * Reads an amount someone typed (`"1,705.10"`, `"$45"`, `"-12.5"`) as cents. Amounts can be
 * added and taken away as they are typed (`"4005+3751"` is 7,756.00); each one is read as
 * cents first, so the sum is exact.
 * Returns `null` when it is not an amount, so the form can ask again kindly.
 */
export function parseDollars(text: string): number | null {
  const typed = bare(text);
  if (!SUM.test(typed)) {
    return parseOne(typed);
  }
  let total = 0;
  // A leading minus belongs to the first amount; every later sign joins two amounts.
  for (const term of typed.match(/(?:^-|[+-])?[\d.]+/g) ?? []) {
    const cents = parseOne(term.startsWith("+") ? term.slice(1) : term);
    if (cents === null) {
      return null;
    }
    total += cents;
  }
  return Number.isSafeInteger(total) ? total : null;
}

function parseOne(typed: string): number | null {
  const match = AMOUNT.exec(typed);
  if (!match) {
    return null;
  }
  const [, sign, whole = "", fraction = ""] = match;
  if (whole === "" && fraction === "") {
    return null;
  }
  const cents = Number(whole || "0") * 100 + Number(fraction.padEnd(2, "0") || "0");
  if (!Number.isSafeInteger(cents)) {
    return null;
  }
  return sign === "-" && cents !== 0 ? -cents : cents;
}

export const CALC_OPS = ["+", "−", "×", "÷"] as const;
export type CalcOp = (typeof CALC_OPS)[number];

// A plain number to multiply or divide by: up to four decimals, so 1.0825 works for a tax rate.
const FACTOR = /^(\d*)(?:\.(\d{0,4}))?$/;
const FACTOR_SCALE = 10000n;

/** `a / b` to the nearest whole number, a tie going to the even one, as money is rounded everywhere else. */
function divideHalfEven(a: bigint, b: bigint): bigint {
  const negative = a < 0n !== b < 0n;
  const top = a < 0n ? -a : a;
  const bottom = b < 0n ? -b : b;
  let quotient = top / bottom;
  const twiceRemainder = (top % bottom) * 2n;
  if (twiceRemainder > bottom || (twiceRemainder === bottom && quotient % 2n === 1n)) {
    quotient += 1n;
  }
  return negative ? -quotient : quotient;
}

/**
 * One step on the calculator: `cents` plus or minus an amount, or times or divided by a
 * plain number. Whole-number arithmetic throughout, so a float never touches the value.
 * Returns `null` when `typed` is not a number that fits, or asks to divide by nothing.
 */
export function calculate(cents: number, op: CalcOp, typed: string): number | null {
  let result: bigint;
  if (op === "+" || op === "−") {
    const amount = parseOne(typed);
    if (amount === null) {
      return null;
    }
    result = BigInt(cents) + BigInt(op === "+" ? amount : -amount);
  } else {
    const match = FACTOR.exec(typed);
    if (!match || ((match[1] ?? "") === "" && (match[2] ?? "") === "")) {
      return null;
    }
    const factor = BigInt(match[1] || "0") * FACTOR_SCALE + BigInt((match[2] ?? "").padEnd(4, "0") || "0");
    if (op === "×") {
      result = divideHalfEven(BigInt(cents) * factor, FACTOR_SCALE);
    } else if (factor === 0n) {
      return null;
    } else {
      result = divideHalfEven(BigInt(cents) * FACTOR_SCALE, factor);
    }
  }
  const whole = Number(result);
  return Number.isSafeInteger(whole) ? whole : null;
}
