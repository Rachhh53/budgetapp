/**
 * A row of pills that narrows a list to one choice, with All first: tags on Expenses, the account a pay period's
 * bills are paid from. Each pill is a toggle button, so a screen reader hears which one is on.
 */
export function FilterPills<T extends number | string>({
  label,
  name,
  choices,
  value,
  onChange,
}: {
  /** What the group narrows by, said for screen readers: "Show expenses with tag". */
  label: string;
  /** The short word shown before the pills: "Tag". */
  name: string;
  choices: readonly { value: T; label: string }[];
  /** `null` for All. */
  value: T | null;
  onChange: (value: T | null) => void;
}) {
  const pill = "min-h-11 cursor-pointer rounded-full border px-4 font-medium md:min-h-9";
  const on = "border-ink bg-ink text-surface";
  const off = "border-line bg-surface";
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-2">
      <span aria-hidden className="text-sm text-muted">
        {name}
      </span>
      <button type="button" aria-pressed={value === null} onClick={() => onChange(null)} className={`${pill} ${value === null ? on : off}`}>
        All
      </button>
      {choices.map((choice) => (
        <button
          key={choice.value}
          type="button"
          aria-pressed={value === choice.value}
          onClick={() => onChange(choice.value)}
          className={`${pill} ${value === choice.value ? on : off}`}
        >
          {choice.label}
        </button>
      ))}
    </div>
  );
}
