import { CalendarDays, FileText, HandCoins, Percent } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Account, holdsCash } from "../../api/accounts";
import {
  type CardSettings,
  type CardSettingsInput,
  PAYMENT_MODES,
  type PaymentMode,
  type Promo,
  PROMO_KINDS,
  type PromoKind,
  useSaveCardSettings,
  useSavePromo,
  useSaveStatement,
} from "../../api/cards";
import { addDays, todayIso } from "../../lib/dates";
import { centsToInput, parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { DaySelect } from "../DaySelect";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { PAYMENT_MODE_HELP, PAYMENT_MODE_LABELS, PROMO_KIND_LABELS } from "./labels";

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
    paymentMode: settings?.paymentMode ?? "statement_balance",
    fixedPaymentCents: settings?.fixedPaymentCents ?? null,
    autopay: settings?.autopay ?? false,
    payFromAccountId: settings?.payFromAccountId ?? null,
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
 * entered one by one — what a month's spending is expected to come to. How it is paid is its
 * own form, so neither is longer than the screen.
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
          hint="For a card you don't enter charge by charge: one estimate is added when each statement closes, and the real statement replaces it. Leave it empty if you add charges as they happen."
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

/** How a card is paid: how much, from which account, and whether the bank takes it by itself. */
export function PaymentForm({
  account,
  settings,
  accounts,
  onClose,
}: {
  account: Account;
  settings: CardSettings;
  /** Every account, for the one the payment comes out of. */
  accounts: readonly Account[];
  onClose: () => void;
}) {
  const id = useId();
  const save = useSaveCardSettings(account.id);
  const [mode, setMode] = useState<PaymentMode>(settings.paymentMode);
  const [fixed, setFixed] = useState(settings.fixedPaymentCents === null ? "" : centsToInput(settings.fixedPaymentCents));
  const [payFrom, setPayFrom] = useState<string | null>(settings.payFromAccountId === null ? null : String(settings.payFromAccountId));
  const [autopay, setAutopay] = useState(settings.autopay);
  const [problem, setProblem] = useState<string>();

  // Archived accounts are left out, unless the card is already paid from one.
  const payFromOptions = accounts
    .filter((candidate) => holdsCash(candidate.type) && (!candidate.archived || String(candidate.id) === payFrom))
    .map((candidate) => ({ id: String(candidate.id), label: candidate.name, color: candidate.color }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const fixedPaymentCents = mode === "fixed" ? parseDollars(fixed) : null;
    if (mode === "fixed" && (fixedPaymentCents === null || fixedPaymentCents <= 0)) {
      setProblem("Please enter the amount you pay each month, like 240.00.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      { ...asInput(settings), paymentMode: mode, fixedPaymentCents, payFromAccountId: payFrom === null ? null : Number(payFrom), autopay },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`Payment setting — ${account.name}`} icon={HandCoins} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label htmlFor={`${id}-mode`} className={LABEL}>
            What you pay each month
          </label>
          <select id={`${id}-mode`} value={mode} onChange={(event) => setMode(event.target.value as PaymentMode)} className={INPUT}>
            {PAYMENT_MODES.map((choice) => (
              <option key={choice} value={choice}>
                {PAYMENT_MODE_LABELS[choice]}
              </option>
            ))}
          </select>
          <p className={HINT}>{PAYMENT_MODE_HELP[mode]}</p>
        </div>
        {mode === "fixed" && <MoneyField label="Amount each month" value={fixed} onChange={setFixed} required />}
        <div>
          <Picker
            label="Paid from"
            noun="account"
            placeholder={payFromOptions.length === 0 ? "No checking or savings account yet" : "No scheduled payment"}
            options={payFromOptions}
            value={payFrom}
            onChange={setPayFrom}
            clearable
          />
          <p className={HINT}>
            Choosing an account adds this payment to your recurring items, due on the card's due day, so it shows in each pay period.
          </p>
        </div>
        <label className="flex min-h-11 items-center gap-3">
          <input
            type="checkbox"
            checked={autopay}
            onChange={(event) => setAutopay(event.target.checked)}
            className="size-5 accent-(--accent)"
          />
          <span className="font-medium">Autopay: the card takes it without me doing anything</span>
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

/** The latest day of the month `day` (31 = last) on or before today: the statement most likely being entered. */
function lastClose(day: number): string {
  const today = todayIso();
  const [year = 0, month = 1] = today.split("-").map(Number);
  const onDay = (y: number, m: number) => {
    // Day 0 of the next month is the last day of this one.
    const length = new Date(y, m, 0).getDate();
    return `${y}-${String(m).padStart(2, "0")}-${String(Math.min(day, length)).padStart(2, "0")}`;
  };
  const thisMonth = onDay(year, month);
  if (thisMonth <= today) {
    return thisMonth;
  }
  return month === 1 ? onDay(year - 1, 12) : onDay(year, month - 1);
}

/** A statement as it arrived: the real numbers, which take over from the estimate. */
export function StatementForm({ account, settings, onClose }: { account: Account; settings: CardSettings | null; onClose: () => void }) {
  const id = useId();
  const save = useSaveStatement(account.id);
  const today = todayIso();
  const [closeDate, setCloseDate] = useState(settings ? lastClose(settings.statementCloseDay) : today);
  const [balance, setBalance] = useState("");
  const [minimum, setMinimum] = useState("");
  const [dueDate, setDueDate] = useState("");
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
    <Modal title={`Add a statement — ${account.name}`} icon={FileText} tone={account.color} onClose={onClose}>
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
            settings
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
  const today = todayIso();
  const [step, setStep] = useState(0);
  const [label, setLabel] = useState(promo?.label ?? "");
  const [kind, setKind] = useState<PromoKind>(promo?.kind ?? "balance_transfer");
  const [original, setOriginal] = useState(promo ? centsToInput(promo.originalBalanceCents) : "");
  const [startDate, setStartDate] = useState(promo?.startDate ?? today);
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
      if (originalCents === null || originalCents <= 0) {
        setProblem("Please enter the balance this promotion started with, like 3,000.00.");
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
      { label, kind, rateBp, startDate, endDate, originalBalanceCents: originalCents ?? 0, rateAfterBp, deferredInterest: deferred },
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
            <MoneyField label="Balance it started with" value={original} onChange={setOriginal} required />
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
              </div>
            </div>
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
