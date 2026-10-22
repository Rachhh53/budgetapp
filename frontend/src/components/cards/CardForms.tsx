import { CalendarDays, Calculator, FileText, Percent } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import {
  type CardSettings,
  type CardStatement,
  type CardSettingsInput,
  type MinimumRule,
  type Promo,
  PROMO_KINDS,
  type PromoKind,
  useSaveCardSettings,
  useSaveMinimumRule,
  useSavePromo,
  useSaveStatement,
} from "../../api/cards";
import { addDays, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { DaySelect } from "../DaySelect";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { Segmented } from "../Segmented";
import { PROMO_KIND_LABELS } from "./labels";

/** The highest rate the backend takes: 1,000% a year, in basis points. */
const MAX_RATE_BP = 100_000;

/** The usual days until the user says otherwise: closes on the 20th, due on the 15th. */
const DEFAULT_CLOSE_DAY = 20;
const DEFAULT_DUE_DAY = 15;

/** Everything in a card's settings as the backend takes it, starting from what is saved (or the defaults). */
function asInput(settings: CardSettings | null): CardSettingsInput {
  return {
    statementCloseDay: settings?.statementCloseDay ?? DEFAULT_CLOSE_DAY,
    dueDay: settings?.dueDay ?? DEFAULT_DUE_DAY,
    monthlyEstimateCents: settings?.monthlyEstimateCents ?? null,
  };
}

function Buttons({ onClose, pending, save = "Save" }: { onClose: () => void; pending: boolean; save?: string }) {
  return (
    <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
      <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
        Cancel
      </button>
      <button type="submit" disabled={pending} className={BUTTON_PRIMARY}>
        {pending ? "Saving…" : save}
      </button>
    </div>
  );
}

/**
 * When a card's statement closes and its payment is due, and — for a card whose charges are not
 * entered one by one — what a month's spending is expected to come to. How it is paid is not
 * here: the payment is a recurring item, and is set on that item's page like any other.
 */
export function BillingForm({ account, settings, onClose }: { account: Account; settings: CardSettings | null; onClose: () => void }) {
  const id = useId();
  const save = useSaveCardSettings(account.id);
  const start = asInput(settings);
  const [closeDay, setCloseDay] = useState(start.statementCloseDay);
  const [dueDay, setDueDay] = useState(start.dueDay);
  const [estimate, setEstimate] = useState(start.monthlyEstimateCents === null ? "" : centsToInput(start.monthlyEstimateCents));
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const monthlyEstimateCents = estimate.trim() === "" ? null : parseDollars(estimate);
    if (estimate.trim() !== "" && (monthlyEstimateCents === null || monthlyEstimateCents < 0)) {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    save.mutate({ ...start, statementCloseDay: closeDay, dueDay, monthlyEstimateCents }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`Statement and due dates — ${account.name}`} icon={CalendarDays} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <DaySelect id={`${id}-close`} label="Statement closes on" value={closeDay} onChange={setCloseDay} />
          <DaySelect id={`${id}-due`} label="Payment is due on" value={dueDay} onChange={setDueDay} />
        </div>
        <p className={HINT}>Both are on your statement. The due day is the first one after the statement closes.</p>
        <MoneyField
          label="Estimated spending a month (optional)"
          value={estimate}
          onChange={setEstimate}
          hint="What the month comes to in all. Charges you do enter count toward it, and only the rest is added when each statement closes; the real statement replaces it."
        />
        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}
        <Buttons onClose={onClose} pending={save.isPending} />
      </form>
    </Modal>
  );
}

/** The whole balance: 100%, in basis points. */
const WHOLE_BALANCE_BP = 10_000;

/**
 * How the card's minimum payment is estimated until a statement with the real one is entered.
 * Card companies work it out differently, so the rule is the card's own: a share of the statement
 * balance, with or without the month's interest on top, and never less than a floor.
 */
export function MinimumForm({ account, rule, onClose }: { account: Account; rule: MinimumRule; onClose: () => void }) {
  const id = useId();
  const save = useSaveMinimumRule(account.id);
  const [share, setShare] = useState(rateToInput(rule.shareBp));
  const [floor, setFloor] = useState(centsToInput(rule.floorCents));
  const [plusInterest, setPlusInterest] = useState(rule.plusInterest);
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const shareBp = parseRate(share);
    if (shareBp === null || shareBp > WHOLE_BALANCE_BP) {
      setProblem("Please enter the share of the balance as a percentage from 0 to 100, like 1.00.");
      return;
    }
    const floorCents = floor.trim() === "" ? 0 : parseDollars(floor);
    if (floorCents === null || floorCents < 0) {
      setProblem(AMOUNT_HELP);
      return;
    }
    setProblem(undefined);
    save.mutate({ shareBp, floorCents, plusInterest }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`Minimum payment estimate — ${account.name}`} icon={Calculator} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className={HINT}>
          Your card agreement or a statement says how the minimum is worked out. This is only used until you enter a statement with its
          real minimum.
        </p>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-share`} className={LABEL}>
              Share of the statement balance (%)
            </label>
            <input
              id={`${id}-share`}
              value={share}
              onChange={(event) => setShare(event.target.value)}
              inputMode="decimal"
              autoComplete="off"
              required
              className={INPUT}
            />
          </div>
          <MoneyField label="At least" value={floor} onChange={setFloor} />
        </div>
        <label className="flex min-h-11 items-center gap-3">
          <input
            type="checkbox"
            checked={plusInterest}
            onChange={(event) => setPlusInterest(event.target.checked)}
            className="size-5 accent-(--accent)"
          />
          <span className="font-medium">Plus that month's interest</span>
        </label>
        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}
        <Buttons onClose={onClose} pending={save.isPending} />
      </form>
    </Modal>
  );
}

/** What a promotional rate is on. */
type PromoScope = "everything" | "amount";

const PROMO_SCOPE_OPTIONS: readonly { value: PromoScope; label: string }[] = [
  { value: "everything", label: "Everything on the card" },
  { value: "amount", label: "A set amount" },
];

/**
 * The latest day of the month `day` (31 = last) on or before `today`: the statement most likely being entered.
 * `today` is the day new things start on, so entering a year from its start opens on that month's statement.
 */
function lastClose(day: number, today: string): string {
  const [year = 0, month = 1] = today.split("-").map(Number);
  const thisMonth = closeIn(day, today);
  if (thisMonth <= today) {
    return thisMonth;
  }
  return month === 1 ? onDay(day, year - 1, 12) : onDay(day, year, month - 1);
}

/** The card's closing day in the month of `date`. */
function closeIn(day: number, date: string): string {
  const [year = 0, month = 1] = date.split("-").map(Number);
  return onDay(day, year, month);
}

function onDay(day: number, year: number, month: number): string {
  // Day 0 of the next month is the last day of this one.
  const length = new Date(year, month, 0).getDate();
  return `${year}-${String(month).padStart(2, "0")}-${String(Math.min(day, length)).padStart(2, "0")}`;
}

/**
 * The closing date a new statement opens on. With a default day chosen in Settings, the statement closing in that
 * day's month, the one being worked through (Rachel, 2026-10-22: "add a statement in account is defaulting to 2025
 * when my default date everywhere else is 01012026"); never after today. Otherwise the last one that closed.
 */
function openingClose(day: number, start: { date: string; fromSetting: boolean }, today: string): string {
  if (!start.fromSetting) {
    return lastClose(day, start.date);
  }
  const close = closeIn(day, start.date);
  return close <= today ? close : lastClose(day, today);
}

/**
 * A statement as it arrived: the real numbers, which take over from the estimate. With `statement`, changes one
 * already entered (Rachel, 2026-10-21: "I want the ability to edit basically everything I enter").
 */
export function StatementForm({
  account,
  settings,
  statement,
  onClose,
}: {
  account: Account;
  settings: CardSettings | null;
  statement?: CardStatement;
  onClose: () => void;
}) {
  const id = useId();
  const save = useSaveStatement(account.id, statement?.id ?? undefined);
  const today = todayIso();
  const startOn = useDefaultDate();
  const [opensOn] = useState(statement?.closeDate ?? (settings ? openingClose(settings.statementCloseDay, startOn, today) : startOn.date));
  const [closeDate, setCloseDate] = useState(opensOn);
  const [balance, setBalance] = useState(statement ? centsToInput(statement.balanceCents) : "");
  const [minimum, setMinimum] = useState(statement?.minimumDueCents != null ? centsToInput(statement.minimumDueCents) : "");
  const [dueDate, setDueDate] = useState(statement?.dueDate ?? "");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const balanceCents = parseDollars(balance);
    if (balanceCents === null) {
      setProblem(AMOUNT_HELP);
      return;
    }
    const minimumDueCents = minimum.trim() === "" ? null : parseDollars(minimum);
    if (minimum.trim() !== "" && (minimumDueCents === null || minimumDueCents < 0)) {
      setProblem("Please enter the minimum payment like 35.00, or leave it empty.");
      return;
    }
    setProblem(undefined);
    save.mutate({ closeDate, balanceCents, minimumDueCents, dueDate: dueDate === "" ? null : dueDate }, { onSuccess: onClose });
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`${statement ? "Change statement" : "Add a statement"} — ${account.name}`} icon={FileText} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField label="Statement balance" value={balance} onChange={setBalance} required hero />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-close`} className={LABEL}>
              Closed on
            </label>
            <input
              id={`${id}-close`}
              type="date"
              value={closeDate}
              max={today}
              onChange={(event) => setCloseDate(event.target.value)}
              required
              className={INPUT}
            />
            <DefaultDateHint value={closeDate} openedOn={opensOn} />
          </div>
          <div>
            <label htmlFor={`${id}-due`} className={LABEL}>
              Due on{settings ? " (optional)" : ""}
            </label>
            <input
              id={`${id}-due`}
              type="date"
              value={dueDate}
              min={closeDate === "" ? undefined : addDays(closeDate, 1)}
              onChange={(event) => setDueDate(event.target.value)}
              required={settings === null}
              className={INPUT}
            />
          </div>
        </div>
        <MoneyField
          label="Minimum payment (optional)"
          value={minimum}
          onChange={setMinimum}
          hint={
            statement
              ? undefined
              : settings
                ? "Leave the due date empty to use the card's usual due day. A closing date you already entered is replaced."
                : "A closing date you already entered is replaced."
          }
        />
        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}
        <Buttons onClose={onClose} pending={save.isPending} save="Save statement" />
      </form>
    </Modal>
  );
}

/**
 * Add or change a promotional rate. Two short steps, so neither is longer than the screen:
 * what the promotion is and how long it runs, then its rates.
 */
export function PromoForm({ account, promo, onClose }: { account: Account; promo?: Promo; onClose: () => void }) {
  const id = useId();
  const save = useSavePromo(account.id, promo?.id);
  const [step, setStep] = useState(0);
  const [label, setLabel] = useState(promo?.label ?? "");
  const [kind, setKind] = useState<PromoKind>(promo?.kind ?? "balance_transfer");
  // Most offers are on the whole card ("0% for 12 months"); a balance transfer or one financed purchase is on a set amount.
  const [scope, setScope] = useState<PromoScope>(promo && promo.originalBalanceCents !== null ? "amount" : "everything");
  const [original, setOriginal] = useState(promo && promo.originalBalanceCents !== null ? centsToInput(promo.originalBalanceCents) : "");
  const [startDate, setStartDate] = useState(promo?.startDate ?? useDefaultDate().date);
  const [endDate, setEndDate] = useState(promo?.endDate ?? "");
  const [rate, setRate] = useState(promo ? rateToInput(promo.rateBp) : "0.00");
  const [rateAfter, setRateAfter] = useState(promo?.rateAfterBp != null ? rateToInput(promo.rateAfterBp) : "");
  const [deferred, setDeferred] = useState(promo?.deferredInterest ?? false);
  const [problem, setProblem] = useState<string>();

  const originalCents = parseDollars(original);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (step === 0) {
      // Each step is checked before the next opens; nothing is sent until the last one.
      if (scope === "amount" && (originalCents === null || originalCents <= 0)) {
        setProblem("Please enter the amount this rate is on, like 3,000.00.");
        return;
      }
      if (endDate <= startDate) {
        setProblem("A promotion ends after it starts. Please check the two dates.");
        return;
      }
      setProblem(undefined);
      setStep(1);
      return;
    }
    const rateBp = parseRate(rate);
    if (rateBp === null || rateBp > MAX_RATE_BP) {
      setProblem("Please enter the promotional rate like 0.00.");
      return;
    }
    const rateAfterBp = rateAfter.trim() === "" ? null : parseRate(rateAfter);
    if (rateAfter.trim() !== "" && (rateAfterBp === null || rateAfterBp > MAX_RATE_BP)) {
      setProblem("Please enter the rate afterwards like 24.49, or leave it empty.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        label,
        kind,
        rateBp,
        startDate,
        endDate,
        originalBalanceCents: scope === "amount" ? originalCents : null,
        rateAfterBp,
        // Deferred interest is charged on the amount a promotion started with, so it needs one.
        deferredInterest: scope === "amount" && deferred,
      },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={promo ? `Edit ${promo.label}` : `Add a promo rate — ${account.name}`} icon={Percent} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-sm font-semibold text-muted">
          Step {step + 1} of 2 · {step === 0 ? "What it is" : "Its rates"}
        </p>
        {step === 0 ? (
          <>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor={`${id}-label`} className={LABEL}>
                  Name
                </label>
                <input
                  id={`${id}-label`}
                  value={label}
                  onChange={(event) => setLabel(event.target.value)}
                  maxLength={80}
                  required
                  autoComplete="off"
                  placeholder="e.g. Balance transfer"
                  className={INPUT}
                />
              </div>
              <div>
                <label htmlFor={`${id}-kind`} className={LABEL}>
                  Kind
                </label>
                <select id={`${id}-kind`} value={kind} onChange={(event) => setKind(event.target.value as PromoKind)} className={INPUT}>
                  {PROMO_KINDS.map((choice) => (
                    <option key={choice} value={choice}>
                      {PROMO_KIND_LABELS[choice]}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <Segmented legend="What the rate applies to" options={PROMO_SCOPE_OPTIONS} value={scope} onChange={setScope} />
              <p className={HINT}>
                {scope === "everything"
                  ? "The whole balance, and anything charged while the rate runs."
                  : "Only this much of the balance, like a balance transfer or one purchase. The rest is at the regular APR."}
              </p>
            </div>
            {scope === "amount" && <MoneyField label="Amount on this rate" value={original} onChange={setOriginal} required />}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor={`${id}-start`} className={LABEL}>
                  Started on
                </label>
                <input
                  id={`${id}-start`}
                  type="date"
                  value={startDate}
                  onChange={(event) => setStartDate(event.target.value)}
                  required
                  className={INPUT}
                />
                {!promo && <DefaultDateHint value={startDate} />}
              </div>
              <div>
                <label htmlFor={`${id}-end`} className={LABEL}>
                  Rate ends on
                </label>
                <input
                  id={`${id}-end`}
                  type="date"
                  value={endDate}
                  onChange={(event) => setEndDate(event.target.value)}
                  required
                  className={INPUT}
                />
              </div>
            </div>
          </>
        ) : (
          <>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor={`${id}-rate`} className={LABEL}>
                  Promo rate a year (%)
                </label>
                <input
                  id={`${id}-rate`}
                  value={rate}
                  onChange={(event) => setRate(event.target.value)}
                  inputMode="decimal"
                  autoComplete="off"
                  required
                  data-autofocus
                  className={`${INPUT} tabular-nums`}
                />
              </div>
              <div>
                <label htmlFor={`${id}-after`} className={LABEL}>
                  Rate afterwards (%, optional)
                </label>
                <input
                  id={`${id}-after`}
                  value={rateAfter}
                  onChange={(event) => setRateAfter(event.target.value)}
                  inputMode="decimal"
                  autoComplete="off"
                  placeholder="The card's regular APR"
                  className={`${INPUT} tabular-nums`}
                />
                <p className={HINT}>Empty means the card's regular APR. On a card with none yet, this becomes it.</p>
              </div>
            </div>
            {scope === "amount" && (
            <div>
              <label className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={deferred}
                  onChange={(event) => setDeferred(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span className="font-medium">Deferred interest</span>
              </label>
              <p className={HINT}>
                Tick this if the offer says interest is charged for the whole promotion unless the balance is paid in full by the end date.
                It gets a stronger warning.
              </p>
            </div>
            )}
          </>
        )}
        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {step === 0 ? (
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Cancel
            </button>
          ) : (
            <button
              type="button"
              onClick={() => {
                setProblem(undefined);
                setStep(0);
              }}
              className={BUTTON_SECONDARY}
            >
              Back
            </button>
          )}
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {step === 0 ? "Next" : save.isPending ? "Saving…" : promo ? "Save changes" : "Add promo rate"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
