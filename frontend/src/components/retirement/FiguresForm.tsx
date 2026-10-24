import { Landmark } from "lucide-react";
import { type FormEvent, type ReactNode, useId, useState } from "react";
import { type FilingFigures, type TaxFiguresView, useSetTaxFigures } from "../../api/retirement";
import { centsToInput, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { Modal } from "../Modal";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

type Part = "brackets" | "deductions" | "gains" | "medicare";
type Filing = "single" | "joint";


/** The deductions for one way of filing, each with what it is called. */
const DEDUCTIONS: { key: keyof FilingFigures; label: string }[] = [
  { key: "standardDeductionCents", label: "Standard deduction" },
  { key: "extraAt65Cents", label: "Extra at 65, each" },
  { key: "seniorDeductionCents", label: "Senior deduction, each" },
  { key: "seniorPhaseOutFromCents", label: "Senior phase-out from" },
];

/** Where gains, Social Security and the investment tax change, for one way of filing. */
const THRESHOLDS: { key: keyof FilingFigures; label: string }[] = [
  { key: "zeroGainsUpToCents", label: "0% gains up to" },
  { key: "fifteenGainsUpToCents", label: "15% gains up to" },
  { key: "socialSecurityBaseCents", label: "Social Security taxed from" },
  { key: "socialSecurityUpperCents", label: "85% of it taxed from" },
  { key: "niitFromCents", label: "Investment tax from" },
];

/**
 * Every figure as text, keyed by where it sits ("single.bracketTopsCents.2", "partBCents.0"), so any of them can be typed
 * over. Amounts end in Cents and rates in Bp, as everywhere else; anything else is a whole number.
 */
type Texts = Record<string, string>;

function flatten(value: unknown, path: string, kind: string, into: Texts) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => flatten(item, `${path}.${index}`, kind, into));
  } else if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      flatten(item, path === "" ? key : `${path}.${key}`, key, into);
    }
  } else if (typeof value === "number") {
    into[path] = kind.endsWith("Cents") ? centsToInput(value) : kind.endsWith("Bp") ? rateToInput(value) : String(value);
  }
}

/** The figures with each typed text in place of its number, or `null` when one of them doesn't read. */
function unflatten(value: unknown, path: string, kind: string, texts: Texts): unknown {
  if (Array.isArray(value)) {
    const items = value.map((item, index) => unflatten(item, `${path}.${index}`, kind, texts));
    return items.includes(null) ? null : items;
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      const read = unflatten(item, path === "" ? key : `${path}.${key}`, key, texts);
      if (read === null) {
        return null;
      }
      out[key] = read;
    }
    return out;
  }
  const text = texts[path] ?? "";
  const read = kind.endsWith("Cents") ? parseDollars(text) : kind.endsWith("Bp") ? parseRate(text) : /^\d{1,4}$/.test(text.trim()) ? Number(text) : null;
  return read === null || read < 0 ? null : read;
}

/**
 * The tax and Medicare figures every worked-out scenario uses (spec §5.12): built in for their year, each one changeable
 * when the IRS or Medicare publishes new ones, and back to the built-in ones with one button. In four parts, each
 * fitting one screen (§6). Amounts are in today's dollars, as the plan is.
 */
export function FiguresForm({ figures, builtIn, onClose }: { figures: TaxFiguresView; builtIn: boolean; onClose: () => void }) {
  const id = useId();
  const save = useSetTaxFigures();
  const [part, setPart] = useState<Part>("brackets");
  const [filing, setFiling] = useState<Filing>("single");
  const [texts, setTexts] = useState<Texts>(() => {
    const into: Texts = {};
    flatten(figures, "", "", into);
    return into;
  });
  const [problem, setProblem] = useState<string>();

  const field = (path: string, label: string) => (
    <div className="min-w-0">
      <label htmlFor={`${id}-${path}`} className="sr-only">
        {label}
      </label>
      <input
        id={`${id}-${path}`}
        value={texts[path] ?? ""}
        onChange={(event) => setTexts({ ...texts, [path]: event.target.value })}
        inputMode="decimal"
        autoComplete="off"
        className={`${INPUT} tabular-nums`}
      />
    </div>
  );

  const labelled = (path: string, label: string) => (
    <div className="min-w-0">
      <label htmlFor={`${id}-${path}`} className="mb-1 block text-sm text-muted">
        {label}
      </label>
      <input
        id={`${id}-${path}`}
        value={texts[path] ?? ""}
        onChange={(event) => setTexts({ ...texts, [path]: event.target.value })}
        inputMode="decimal"
        autoComplete="off"
        className={`${INPUT} tabular-nums`}
      />
    </div>
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const read = unflatten(figures, "", "", texts) as TaxFiguresView | null;
    if (read === null) {
      setProblem("Please check each figure: amounts like 16,100.00, rates like 12.00, and nothing below 0.");
      return;
    }
    setProblem(undefined);
    save.mutate(read, { onSuccess: onClose });
  };

  const rates = figures[filing].bracketRatesBp;
  const limits = figures[filing].medicareFromCents;

  return (
    <Modal title={`Tax & Medicare figures · ${figures.year}`} icon={Landmark} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {/* Selects side by side, so each part with its pickers fits a phone's screen (§6). */}
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor={`${id}-part`} className={LABEL}>
              Figures
            </label>
            <select id={`${id}-part`} value={part} onChange={(event) => setPart(event.target.value as Part)} className={INPUT}>
              <option value="brackets">Brackets</option>
              <option value="deductions">Deductions</option>
              <option value="gains">Gains & Social Security</option>
              <option value="medicare">Medicare</option>
            </select>
          </div>
          <div>
            <label htmlFor={`${id}-filing`} className={LABEL}>
              For
            </label>
            <select id={`${id}-filing`} value={filing} onChange={(event) => setFiling(event.target.value as Filing)} className={INPUT}>
              <option value="single">Single</option>
              <option value="joint">Married, jointly</option>
            </select>
          </div>
        </div>

        {part === "brackets" && (
          <fieldset>
            <legend className="sr-only">Federal brackets, {filing === "joint" ? "married filing jointly" : "single"}</legend>
            <div className="grid grid-cols-[5rem_1fr] gap-x-3 gap-y-2 text-sm text-muted sm:grid-cols-[6rem_1fr]">
              <span>Rate (%)</span>
              <span>Taxable income up to</span>
              {rates.map((_, index) => (
                <BracketRow key={index}>
                  {field(`${filing}.bracketRatesBp.${index}`, `Bracket ${index + 1} rate (%)`)}
                  {index < rates.length - 1 ? (
                    field(`${filing}.bracketTopsCents.${index}`, `Bracket ${index + 1} up to`)
                  ) : (
                    <span className="self-center text-ink">and above</span>
                  )}
                </BracketRow>
              ))}
            </div>
          </fieldset>
        )}

        {part === "deductions" && (
          <div className="grid grid-cols-2 gap-x-3 gap-y-2">
            {DEDUCTIONS.map(({ key, label }) => (
              <div key={key}>{labelled(`${filing}.${key}`, label)}</div>
            ))}
            {labelled("seniorPhaseOutBp", "Senior phase-out (%)")}
            {labelled("seniorDeductionLastYear", "Senior deduction until")}
          </div>
        )}

        {part === "gains" && (
          <div className="grid grid-cols-2 gap-x-3 gap-y-2">
            {THRESHOLDS.map(({ key, label }) => (
              <div key={key}>{labelled(`${filing}.${key}`, label)}</div>
            ))}
            {labelled("niitBp", "Investment tax (%)")}
          </div>
        )}

        {part === "medicare" && (
          <fieldset>
            <legend className="sr-only">Medicare premiums a month by income</legend>
            <div className="grid grid-cols-3 gap-x-3 gap-y-2 text-sm text-muted">
              <span>Income from</span>
              <span>Part B</span>
              <span>Part D added</span>
              {limits.map((_, index) => (
                <BracketRow key={index}>
                  {index === 0 ? <span className="self-center text-ink">$0.00</span> : field(`${filing}.medicareFromCents.${index}`, `Medicare bracket ${index} from`)}
                  {field(`partBCents.${index}`, `Part B a month, bracket ${index}`)}
                  {field(`partDCents.${index}`, `Part D added a month, bracket ${index}`)}
                </BracketRow>
              ))}
            </div>
            <p className={HINT}>A month a person, by income from two years before.</p>
          </fieldset>
        )}

        {problem || save.error ? (
          <p role="alert" className="text-danger">
            {problem ?? save.error?.message}
          </p>
        ) : null}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {!builtIn && (
            <button
              type="button"
              onClick={() => save.mutate(null, { onSuccess: onClose })}
              disabled={save.isPending}
              className={`${BUTTON_LINK} sm:mr-auto`}
            >
              Use built-in figures
            </button>
          )}
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** One row of the grid: its cells sit in the grid's columns. */
function BracketRow({ children }: { children: ReactNode }) {
  return <div className="contents">{children}</div>;
}
