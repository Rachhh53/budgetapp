import { Check } from "lucide-react";
import { useId, useState } from "react";
import { isHexColor, readableTextOn } from "../lib/color";
import { BUTTON_SECONDARY, INPUT, LABEL } from "./ui";

/**
 * The default swatches, warm-leaning. Every one is offered by name as well as by color,
 * so the choice never depends on seeing the color.
 */
export const PRESET_COLORS: readonly { hex: string; name: string }[] = [
  { hex: "#0f766e", name: "Teal" },
  { hex: "#be185d", name: "Raspberry" },
  { hex: "#c2410c", name: "Terracotta" },
  { hex: "#b45309", name: "Amber" },
  { hex: "#a16207", name: "Gold" },
  { hex: "#4d7c0f", name: "Olive" },
  { hex: "#15803d", name: "Forest" },
  { hex: "#0369a1", name: "Ocean" },
  { hex: "#4338ca", name: "Indigo" },
  { hex: "#7e22ce", name: "Plum" },
  { hex: "#9f1239", name: "Wine" },
  { hex: "#475569", name: "Slate" },
];

/** The first preset not in use yet, so new accounts, categories and tags start out easy to tell apart. */
export function firstUnusedColor(used: readonly string[]): string {
  const free = PRESET_COLORS.find((preset) => !used.includes(preset.hex));
  return (free ?? PRESET_COLORS[0])?.hex ?? "#0f766e";
}

const RECENT_SHOWN = 6;

interface ColorPickerProps {
  /** What the color is for, e.g. "Account color". */
  label: string;
  value: string;
  onChange: (hex: string) => void;
  /** Colors already in use elsewhere, most recent first. Shown ahead of the presets. */
  recent?: readonly string[];
}

/**
 * The color picker behind every color dot: the user's recently used colors first, a row of
 * preset swatches, and a custom box that takes any hex code. The chosen color is used
 * exactly as picked — text on top of it switches between dark and light instead.
 */
export function ColorPicker({ label, value, onChange, recent = [] }: ColorPickerProps) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [custom, setCustom] = useState("");

  const presets = new Set(PRESET_COLORS.map((preset) => preset.hex));
  const recentOnly = [...new Set(recent.map((hex) => hex.toLowerCase()))]
    .filter((hex) => !presets.has(hex))
    .slice(0, RECENT_SHOWN);
  const customHex = custom.startsWith("#") ? custom : `#${custom}`;
  const customValid = isHexColor(customHex);

  const pick = (hex: string) => {
    onChange(hex.toLowerCase());
    setOpen(false);
    setCustom("");
  };

  return (
    <div>
      <span id={`${id}-label`} className={LABEL}>
        {label}
      </span>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={`${id}-panel`}
        aria-labelledby={`${id}-label ${id}-value`}
        onClick={() => setOpen((current) => !current)}
        className={`${BUTTON_SECONDARY} flex items-center gap-3 font-normal sm:w-full`}
      >
        <span aria-hidden className="size-5 shrink-0 rounded-full border border-line" style={{ backgroundColor: value }} />
        <span id={`${id}-value`}>{colorName(value)}</span>
        <span className="ml-auto text-sm text-muted">{open ? "Close" : "Change"}</span>
      </button>

      {open && (
        <div id={`${id}-panel`} className="mt-2 space-y-3 rounded-lg border border-line p-3">
          {recentOnly.length > 0 && (
            <Swatches title="Recently used" colors={recentOnly.map((hex) => ({ hex, name: hex }))} value={value} onPick={pick} />
          )}
          <Swatches title="Colors" colors={PRESET_COLORS} value={value} onPick={pick} />
          <div>
            <label htmlFor={`${id}-custom`} className="mb-1 block text-sm font-medium">
              Custom hex code
            </label>
            <div className="flex gap-2">
              <input
                id={`${id}-custom`}
                value={custom}
                onChange={(event) => setCustom(event.target.value.trim())}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    // Enter here uses the color; it must not submit the form around the picker.
                    event.preventDefault();
                    if (customValid) {
                      pick(customHex);
                    }
                  }
                }}
                placeholder="#0f766e"
                maxLength={7}
                autoComplete="off"
                spellCheck={false}
                className={`${INPUT} font-mono`}
              />
              <button
                type="button"
                disabled={!customValid}
                onClick={() => pick(customHex)}
                className={`${BUTTON_SECONDARY} w-auto shrink-0`}
              >
                Use
              </button>
            </div>
            {custom !== "" && !customValid && (
              <p className="mt-1 text-sm text-muted">A hex code is # followed by six letters or digits, like #0f766e.</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Swatches({
  title,
  colors,
  value,
  onPick,
}: {
  title: string;
  colors: readonly { hex: string; name: string }[];
  value: string;
  onPick: (hex: string) => void;
}) {
  return (
    <div role="group" aria-label={title}>
      <p className="mb-1 text-sm font-medium">{title}</p>
      <div className="flex flex-wrap gap-2">
        {colors.map(({ hex, name }) => {
          const chosen = hex === value.toLowerCase();
          return (
            <button
              key={hex}
              type="button"
              aria-label={name}
              aria-pressed={chosen}
              title={name}
              onClick={() => onPick(hex)}
              // 44px on a phone for an easy tap; smaller where there is a mouse.
              className="flex size-11 items-center justify-center rounded-full border border-line sm:size-9"
              style={{ backgroundColor: hex, color: readableTextOn(hex) }}
            >
              {chosen && <Check aria-hidden className="size-5" />}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** A preset's name, or the hex code itself for a custom color. */
function colorName(hex: string): string {
  return PRESET_COLORS.find((preset) => preset.hex === hex.toLowerCase())?.name ?? hex;
}
