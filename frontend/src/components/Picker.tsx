import { ChevronDown, Plus } from "lucide-react";
import { type KeyboardEvent, useId, useState } from "react";
import { INPUT, LABEL } from "./ui";

export interface PickerOption {
  id: string;
  label: string;
  /** A color the user chose for this entry, shown as a dot. */
  color?: string;
}

interface PickerProps {
  label: string;
  options: readonly PickerOption[];
  /** The chosen option's id, or `null` for nothing chosen. */
  value: string | null;
  onChange: (id: string | null) => void;
  /**
   * What one entry is called, e.g. "category". Used in `Add "…" as a new category`.
   */
  noun: string;
  /** When given, typing something that does not exist offers to add it as new. */
  onCreate?: (text: string) => void;
  /** Lets the choice be emptied by clearing the text. */
  clearable?: boolean;
  placeholder?: string;
}

/**
 * The type-to-filter picker used for every list the user builds (design/picker.png).
 * Typing filters what already exists, so nothing gets misspelled; if nothing matches,
 * the last option adds what was typed as a new entry.
 *
 * Keyboard: ↓/↑ move, Enter chooses, Esc closes without changing anything.
 */
export function Picker({ label, options, value, onChange, noun, onCreate, clearable, placeholder }: PickerProps) {
  const id = useId();
  const listId = `${id}-list`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  // True once something has been typed since the list opened; an untouched list changes nothing.
  const [edited, setEdited] = useState(false);

  const selected = options.find((option) => option.id === value);
  const typed = query.trim();
  const matches = typed === ""
    ? options
    : options.filter((option) => option.label.toLowerCase().includes(typed.toLowerCase()));
  const exists = options.some((option) => option.label.toLowerCase() === typed.toLowerCase());
  const canCreate = onCreate !== undefined && typed !== "" && !exists;
  // The "add as new" row is one more choice after the matches.
  const rowCount = matches.length + (canCreate ? 1 : 0);

  const openList = () => {
    setQuery("");
    setActive(0);
    setEdited(false);
    setOpen(true);
  };

  const close = () => {
    setOpen(false);
    setQuery("");
  };

  const choose = (index: number) => {
    const match = matches[index];
    if (match) {
      onChange(match.id);
    } else if (canCreate && index === matches.length) {
      onCreate(typed);
    } else {
      return;
    }
    close();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) {
        openList();
      } else if (rowCount > 0) {
        const step = event.key === "ArrowDown" ? 1 : -1;
        setActive((current) => (current + step + rowCount) % rowCount);
      }
    } else if (event.key === "Enter" && open) {
      // Never let Enter submit the surrounding form while a list is open.
      event.preventDefault();
      choose(active);
    } else if (event.key === "Escape" && open) {
      // Only close the list — not the dialog the picker may be sitting in.
      event.stopPropagation();
      close();
    }
  };

  const onBlur = () => {
    // Deleting the text and moving on is how a choice is emptied.
    if (open && clearable && edited && typed === "") {
      onChange(null);
    }
    close();
  };

  return (
    <div className="relative">
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <div className="relative">
        {selected?.color && !open && (
          <span
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-3 size-3 -translate-y-1/2 rounded-sm"
            style={{ backgroundColor: selected.color }}
          />
        )}
        <input
          id={id}
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && rowCount > 0 ? `${id}-row-${active}` : undefined}
          autoComplete="off"
          placeholder={placeholder ?? `Choose or type a ${noun}`}
          value={open ? query : (selected?.label ?? "")}
          onFocus={openList}
          onClick={() => !open && openList()}
          onChange={(event) => {
            setQuery(event.target.value);
            setActive(0);
            setEdited(true);
            setOpen(true);
          }}
          onKeyDown={onKeyDown}
          onBlur={onBlur}
          className={`${INPUT} pr-9 ${selected?.color && !open ? "pl-8" : ""}`}
        />
        <ChevronDown aria-hidden className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted" />
      </div>

      {/* Always in the page so aria-controls points at something; hidden until opened. */}
      <ul
        id={listId}
        role="listbox"
        aria-label={label}
        hidden={!open}
        // Keep focus in the text box while a row is clicked, so the click is not lost to a blur.
        onMouseDown={(event) => event.preventDefault()}
        className="absolute inset-x-0 z-20 mt-1 max-h-64 overflow-y-auto rounded-lg border border-line bg-surface p-1 shadow-lg"
      >
        {matches.map((option, index) => (
          <li
            key={option.id}
            id={`${id}-row-${index}`}
            role="option"
            aria-selected={index === active}
            onClick={() => choose(index)}
            onMouseEnter={() => setActive(index)}
            className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-md px-3 ${index === active ? "bg-bg" : ""}`}
          >
            {option.color && (
              <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: option.color }} />
            )}
            <span className="min-w-0 flex-1 truncate">
              <Highlighted text={option.label} match={typed} />
            </span>
            {index === active && (
              <span aria-hidden className="hidden text-sm text-muted sm:inline">
                Enter
              </span>
            )}
          </li>
        ))}
        {canCreate && (
          <li
            id={`${id}-row-${matches.length}`}
            role="option"
            aria-selected={active === matches.length}
            onClick={() => choose(matches.length)}
            onMouseEnter={() => setActive(matches.length)}
            className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-md px-3 font-semibold text-accent-ink ${
              matches.length > 0 ? "mt-1 border-t border-line" : ""
            } ${active === matches.length ? "bg-bg" : ""}`}
          >
            <Plus aria-hidden className="size-4 shrink-0" />
            <span className="min-w-0 flex-1 truncate">
              Add "{typed}" as a new {noun}
            </span>
          </li>
        )}
        {rowCount === 0 && (
          <li role="presentation" className="px-3 py-2 text-muted">
            {options.length === 0 ? `No ${noun} to choose from yet.` : "Nothing matches that."}
          </li>
        )}
      </ul>
    </div>
  );
}

/** The option's label with the part that matches what was typed in bold. */
function Highlighted({ text, match }: { text: string; match: string }) {
  const start = match === "" ? -1 : text.toLowerCase().indexOf(match.toLowerCase());
  if (start < 0) {
    return text;
  }
  const end = start + match.length;
  return (
    <>
      {text.slice(0, start)}
      <strong>{text.slice(start, end)}</strong>
      {text.slice(end)}
    </>
  );
}

interface TextPickerProps {
  label: string;
  /** Entries that already exist, to choose from. */
  suggestions: readonly string[];
  value: string;
  onChange: (text: string) => void;
  noun: string;
  placeholder?: string;
}

/**
 * A {@link Picker} over plain text values (a bank's name, an account label), where the
 * entry itself is the value. Anything typed that is not in the list can be added as new.
 */
export function TextPicker({ label, suggestions, value, onChange, noun, placeholder }: TextPickerProps) {
  // The current value is always an option, including one that was just added as new.
  const labels = [...new Set([...suggestions, value].filter((text) => text !== ""))];
  return (
    <Picker
      label={label}
      noun={noun}
      placeholder={placeholder}
      options={labels.map((text) => ({ id: text, label: text }))}
      value={value === "" ? null : value}
      onChange={(id) => onChange(id ?? "")}
      onCreate={onChange}
      clearable
    />
  );
}
