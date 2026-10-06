/**
 * Money on the frontend is always a whole number of cents, exactly as the backend stores it.
 * Dollars only exist as text: what is shown, and what is typed.
 */

const DOLLARS = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });

/** `170510` → `"$1,705.10"`; negatives get a minus sign, never brackets. */
export function formatCents(cents: number): string {
  return DOLLARS.format(cents / 100);
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

/**
 * Reads an amount someone typed (`"1,705.10"`, `"$45"`, `"-12.5"`) as cents.
 * Returns `null` when it is not an amount, so the form can ask again kindly.
 */
export function parseDollars(text: string): number | null {
  const match = AMOUNT.exec(text.replace(/[$,\s]/g, ""));
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
