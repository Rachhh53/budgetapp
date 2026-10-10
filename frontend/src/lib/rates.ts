/**
 * Rates on the frontend are whole basis points, exactly as the backend stores them: 4.10% is
 * `410`. Percentages only exist as text: what is shown, and what is typed.
 */

/** `410` → `"4.10%"`. */
export function formatRate(basisPoints: number): string {
  const whole = Math.trunc(basisPoints / 100);
  const fraction = String(basisPoints % 100).padStart(2, "0");
  return `${whole}.${fraction}%`;
}

/** `410` → `"4.10"`: the plain form that goes back into a rate field. */
export function rateToInput(basisPoints: number): string {
  return formatRate(basisPoints).slice(0, -1);
}

// Digits, then at most two decimals. Parsed as text so no float ever touches the value.
const RATE = /^(\d*)(?:\.(\d{0,2}))?$/;

/**
 * Reads a percentage someone typed (`"4.1"`, `"4.10%"`, `"5"`) as basis points.
 * Returns `null` when it is not a rate, so the form can ask again kindly.
 */
export function parseRate(text: string): number | null {
  const match = RATE.exec(text.replace(/[%\s]/g, ""));
  if (!match) {
    return null;
  }
  const [, whole = "", fraction = ""] = match;
  if (whole === "" && fraction === "") {
    return null;
  }
  const basisPoints = Number(whole || "0") * 100 + Number(fraction.padEnd(2, "0") || "0");
  return Number.isSafeInteger(basisPoints) ? basisPoints : null;
}
