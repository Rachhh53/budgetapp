/**
 * Color helpers for text drawn on a color the user chose. The user's color is never
 * altered — only the text on top of it switches between dark and light, whichever reads better.
 */

// Pure black and white on purpose: for any background, one of the two reaches at least
// 4.58:1. A softer "almost black" drops below 4.5:1 on mid-tone colors such as #009900.
export const DARK_TEXT = "#000000";
export const LIGHT_TEXT = "#ffffff";

const HEX_COLOR = /^#[0-9a-f]{6}$/i;

export function isHexColor(value: string): boolean {
  return HEX_COLOR.test(value);
}

/** WCAG relative luminance of a `#rrggbb` color, from 0 (black) to 1 (white). */
export function luminance(hex: string): number {
  const channel = (offset: number): number => {
    const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

/** WCAG contrast ratio between two `#rrggbb` colors, from 1 to 21. */
export function contrastRatio(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (lighter + 0.05) / (darker + 0.05);
}

/** The text color (dark or light) with the higher contrast against `background`. */
export function readableTextOn(background: string): string {
  return contrastRatio(background, LIGHT_TEXT) >= contrastRatio(background, DARK_TEXT) ? LIGHT_TEXT : DARK_TEXT;
}
