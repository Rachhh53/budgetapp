import { Banknote, ChevronLeft, ChevronRight, FileText, Trash2 } from "lucide-react";
import { type FormEvent, type ReactNode, useId, useState } from "react";
import { useNavigate } from "react-router";
import type { Account } from "../../api/accounts";
import {
  type DeductionInput,
  type IncomeSource,
  type IncomeSourceInput,
  type MatchMode,
  PAY_FREQUENCIES,
  type PayTerms,
  type PayFrequency,
  useDeleteIncomeSource,
  useSaveIncomeSource,
  WEEKEND_RULES,
  type WeekendRule,
} from "../../api/income";
import { formatDate, todayIso } from "../../lib/dates";
import { ACCEPTED_STUBS, problemWithStub, type StubRead, useReadStub, useUploadStub } from "../../api/payStubs";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { formatRate, parseRate, rateToInput } from "../../lib/rates";
import { ConfirmDelete } from "../ConfirmDelete";
import { DaySelect } from "../DaySelect";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker, TextPicker } from "../Picker";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_LINK, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { FREQUENCY_LABELS, WEEKEND_LABELS } from "./schedule";

const DEDUCTION_SUGGESTIONS = ["401(k)", "Roth 401(k)", "403(b)", "HSA", "FSA", "Pension"] as const;

/** Whether the employer adds anything, and how it is worked out. */
type MatchChoice = "none" | MatchMode;

const MATCH_OPTIONS: readonly { value: MatchChoice; label: string }[] = [
  { value: "none", label: "No match" },
  // The usual way a match is written comes first: "100% up to 6%".
  { value: "formula", label: "A percentage of what I put in" },
  { value: "fixed", label: "The same dollar amount every paycheck" },
];

/** How a deduction is given: dollars a paycheck, or a share of gross pay. */
type DeductionUnit = "dollars" | "percent";

const GROSS_NEEDED_FOR_PERCENT =
  "A deduction given as a percent of your pay needs the gross amount per paycheck. Please fill that in as well.";

/** The most that can be matched, as the backend takes it: 1,000% of a contribution, up to all of gross pay. */
const MAX_MATCH_BP = 100_000;
const MAX_CAP_BP = 10_000;

/** How many hand-picked paydays are shown at once: the form never scrolls, however many there are. */
const PAYDAYS_SHOWN = 3;

interface DeductionRow {
  /** Stable key for React; never sent to the backend. */
  key: number;
  name: string;
  unit: DeductionUnit;
  /** The dollars typed, when `unit` is dollars. */
  amount: string;
  /** The percent of gross pay typed, when `unit` is percent. */
  percent: string;
  toAccountId: string | null;
}

/** The screens of the form: who pays and where, when, how much, what comes out, what the employer adds, and deleting. */
type IncomePart = "who" | "when" | "pay" | "deductions" | "match" | "delete";

/** Something still to put right: the screen it is on and, for a deduction, which one. */
type Trouble = PartProblem<IncomePart> & { deduction?: number };

const GROUPS = {
  who: { label: "Name and account", parts: ["who"] },
  when: { label: "Paydays", parts: ["when"] },
  pay: { label: "Pay", parts: ["pay"] },
  deductions: { label: "Deductions", parts: ["deductions"] },
  match: { label: "Employer match", parts: ["match"] },
  delete: { label: "Delete", parts: ["delete"] },
} as const satisfies Record<IncomePart, FormGroup<IncomePart>>;

/** The amounts of a paycheck: what a pay change, or one entry of the pay history, is about. */
const AMOUNTS = [GROUPS.pay, GROUPS.deductions, GROUPS.match] as const;

interface IncomeSourceFormProps {
  /** The source being changed; leave out to add a new one. */
  source?: IncomeSource;
  /**
   * One entry of the source's pay history to change in place, instead of the amounts in force
   * today: the form opens on its amounts and saves back to it.
   */
  terms?: PayTerms;
  /** Recording a raise: the amounts apply from a date, and the paychecks before it keep theirs. */
  startAsPayChange?: boolean;
  /** Whether any income source exists yet. The first one always sets the pay periods. */
  isFirst: boolean;
  accounts: readonly Account[];
  onClose: () => void;
  /** Called after the source was deleted, so the caller can leave its screen. */
  onDeleted?: () => void;
}

/**
 * Add or change an income source: who pays, when, how much, and where it goes.
 *
 * It is shown a screen at a time (see `useFormParts`), so it never needs scrolling, and comes
 * four ways:
 *
 * - **Adding**: three steps (who, when, how much), then deductions and the match for those who have them.
 * - **Editing**: any one screen, chosen at the top. The amounts typed correct the ones in force today.
 * - **Recording a pay change**: the date and the new pay, then deductions and the match. Nothing else is asked.
 * - **Editing one entry of the pay history**: its amounts only.
 */
export function IncomeSourceForm({
  source,
  terms,
  startAsPayChange = false,
  isFirst,
  accounts,
  onClose,
  onDeleted,
}: IncomeSourceFormProps) {
  const id = useId();
  const save = useSaveIncomeSource(source?.id);
  const navigate = useNavigate();
  const readStub = useReadStub();
  const keepStub = useUploadStub();
  // A stub chosen to fill in a new source: kept with the source once it exists.
  const [stubFile, setStubFile] = useState<File>();
  const [stubProblem, setStubProblem] = useState<string>();
  // Set when the source was added but its stub could not be kept: the form then only says so, and closes.
  const [stubNotKept, setStubNotKept] = useState<string>();
  const schedule = source?.schedule;
  // The amounts the form opens on: the history entry asked for, or the ones in force today.
  const pay = terms ?? source;
  // A raise starts new amounts from a date; anything else corrects amounts already in use.
  const payChange = source !== undefined && !terms && startAsPayChange;
  const [effectiveFrom, setEffectiveFrom] = useState(todayIso());

  // Adding and recording a pay change go through as steps; editing picks a screen.
  const stepping = !source || payChange;
  const groups: readonly FormGroup<IncomePart>[] = !source
    ? [GROUPS.who, GROUPS.when, ...AMOUNTS]
    : payChange || terms
      ? AMOUNTS
      : [GROUPS.who, GROUPS.when, ...AMOUNTS, GROUPS.delete];
  const parts = useFormParts<IncomePart>({
    groups,
    adding: stepping,
    // Everyone is asked up to the pay itself; deductions and a match are for those who have them.
    required: groups.indexOf(GROUPS.pay) + 1,
    moreLabel: "Deductions and match",
  });

  const [name, setName] = useState(source?.name ?? "");
  const [depositAccountId, setDepositAccountId] = useState<string | null>(
    source ? String(source.depositAccountId) : null,
  );
  const [frequency, setFrequency] = useState<PayFrequency>(schedule?.frequency ?? "biweekly");
  const [day1, setDay1] = useState(schedule?.day1 ?? 1);
  const [day2, setDay2] = useState(schedule?.day2 ?? 15);
  const startOn = useDefaultDate();
  const [startDate, setStartDate] = useState(schedule?.startDate ?? startOn.date);
  const [manualDates, setManualDates] = useState<string[]>(
    schedule && schedule.manualDates.length > 0 ? schedule.manualDates : ["", ""],
  );
  const [paydaysFrom, setPaydaysFrom] = useState(0);
  const [weekendRule, setWeekendRule] = useState<WeekendRule>(schedule?.weekendRule ?? "previous_business_day");
  const [net, setNet] = useState(pay ? centsToInput(pay.netCents) : "");
  const [netIsEstimate, setNetIsEstimate] = useState(pay?.netIsEstimate ?? false);
  const [gross, setGross] = useState(pay?.grossCents != null ? centsToInput(pay.grossCents) : "");
  // The first source, and the current primary, must stay primary: something has to set the pay periods.
  const primaryLocked = isFirst || source?.primary === true;
  const [primary, setPrimary] = useState(primaryLocked);
  const [deductions, setDeductions] = useState<DeductionRow[]>(
    (pay?.deductions ?? []).map((deduction, index) => ({
      key: index,
      name: deduction.name,
      unit: deduction.percentBp === null ? "dollars" : "percent",
      amount: deduction.percentBp === null ? centsToInput(deduction.amountCents) : "",
      percent: deduction.percentBp === null ? "" : rateToInput(deduction.percentBp),
      toAccountId: deduction.toAccountId === null ? null : String(deduction.toAccountId),
    })),
  );
  // Deductions are shown one at a time; this is the one in view.
  const [chosenDeduction, setChosenDeduction] = useState(0);
  const deductionAt = Math.min(chosenDeduction, deductions.length - 1);
  const deduction = deductions[deductionAt];
  const match = pay?.match ?? null;
  const [matchChoice, setMatchChoice] = useState<MatchChoice>(match?.mode ?? "none");
  const [matchAccountId, setMatchAccountId] = useState<string | null>(match ? String(match.toAccountId) : null);
  const [matchFixed, setMatchFixed] = useState(match?.fixedCents != null ? centsToInput(match.fixedCents) : "");
  const [matchPercent, setMatchPercent] = useState(match?.matchBp != null ? rateToInput(match.matchBp) : "");
  const [capPercent, setCapPercent] = useState(match?.capBpOfGross != null ? rateToInput(match.capBpOfGross) : "");
  const [withinCap, setWithinCap] = useState(match?.contributionWithinCap ?? false);
  // Read as typed, for what a percent comes to and for whether the match can be checked at all.
  const grossTyped = gross.trim() === "" ? null : parseDollars(gross);

  // Archived accounts are left out, unless this source already points at one.
  const inUse = new Set([depositAccountId, matchAccountId, ...deductions.map((row) => row.toAccountId)]);
  const offered = accounts.filter((account) => !account.archived || inUse.has(String(account.id)));
  const accountOptions = offered.map((account) => ({ id: String(account.id), label: account.name, color: account.color }));
  // A match is retirement money: it can only go into an investment account.
  const investmentOptions = offered
    .filter((account) => account.type === "investment")
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const updateDeduction = (key: number, change: Partial<DeductionRow>) =>
    setDeductions((rows) => rows.map((row) => (row.key === key ? { ...row, ...change } : row)));

  const addDeduction = () => {
    setDeductions((rows) => [
      ...rows,
      { key: Math.max(-1, ...rows.map((row) => row.key)) + 1, name: "", unit: "dollars", amount: "", percent: "", toAccountId: null },
    ]);
    setChosenDeduction(deductions.length);
  };

  /** Turns the form into a request, or says what still needs attention and where. */
  const toInput = (): IncomeSourceInput | Trouble => {
    // The browser asks for the name only while its screen is in view.
    if (name.trim() === "") {
      return { part: "who", problem: "Please add a name, like your employer's." };
    }
    if (depositAccountId === null) {
      return { part: "who", problem: "Please choose the account this pay is deposited to." };
    }
    const pickedDates = manualDates.filter((date) => date !== "");
    if (frequency === "manual_dates" && pickedDates.length < 2) {
      return { part: "when", problem: "Please add at least two paydays, so there's a pay period between them." };
    }
    if (payChange && effectiveFrom === "") {
      return { part: "pay", problem: "Please choose the date your pay changes from." };
    }
    const netCents = parseDollars(net);
    const grossCents = gross.trim() === "" ? null : parseDollars(gross);
    if (netCents === null || (gross.trim() !== "" && grossCents === null)) {
      return { part: "pay", problem: AMOUNT_HELP };
    }
    const parsedDeductions: DeductionInput[] = [];
    for (const [at, row] of deductions.entries()) {
      const trouble = (problem: string): Trouble => ({ part: "deductions", problem, deduction: at });
      const toAccountId = row.toAccountId === null ? null : Number(row.toAccountId);
      if (row.unit === "percent") {
        const percentBp = parseRate(row.percent);
        if (row.name.trim() === "" || percentBp === null || percentBp > MAX_CAP_BP) {
          return trouble("Each deduction needs a name and a percent of your pay like 6.");
        }
        if (grossCents === null) {
          return trouble(GROSS_NEEDED_FOR_PERCENT);
        }
        parsedDeductions.push({ name: row.name, percentBp, toAccountId });
        continue;
      }
      const amountCents = parseDollars(row.amount);
      if (row.name.trim() === "" || amountCents === null) {
        return trouble("Each deduction needs a name and an amount like 150.00.");
      }
      parsedDeductions.push({ name: row.name, amountCents, toAccountId });
    }
    let matchInput: IncomeSourceInput["match"] = null;
    if (matchChoice !== "none") {
      const trouble = (problem: string): Trouble => ({ part: "match", problem });
      if (matchAccountId === null) {
        return trouble("Please choose the investment account the employer match is paid into.");
      }
      if (matchChoice === "fixed") {
        const fixedCents = parseDollars(matchFixed);
        if (fixedCents === null || fixedCents < 0) {
          return trouble("Please enter the match as an amount like 75.00.");
        }
        matchInput = {
          toAccountId: Number(matchAccountId),
          mode: "fixed",
          fixedCents,
          matchBp: null,
          capBpOfGross: null,
          contributionWithinCap: false,
        };
      } else {
        const matchBp = parseRate(matchPercent);
        const capBpOfGross = parseRate(capPercent);
        if (matchBp === null || matchBp > MAX_MATCH_BP) {
          return trouble("Please enter how much of what you put in is matched, as a percentage like 50.");
        }
        if (capBpOfGross === null || capBpOfGross > MAX_CAP_BP) {
          return trouble("Please enter how much of your pay is matched up to, as a percentage like 6.");
        }
        // With no gross pay there is nothing to check the contribution against, so it takes a confirmation.
        if (grossCents === null && !withinCap) {
          return trouble(
            `Please fill in the gross amount per paycheck, or tick the box to confirm that what you put in is within the ${formatRate(capBpOfGross)} that is matched.`,
          );
        }
        matchInput = {
          toAccountId: Number(matchAccountId),
          mode: "formula",
          fixedCents: null,
          matchBp,
          capBpOfGross,
          contributionWithinCap: grossCents === null && withinCap,
        };
      }
    }
    const byDayOfMonth = frequency === "semi_monthly" || frequency === "monthly";
    return {
      ...whichAmounts(),
      name,
      depositAccountId: Number(depositAccountId),
      grossCents,
      netCents,
      netIsEstimate,
      primary,
      schedule: {
        frequency,
        day1: byDayOfMonth ? day1 : null,
        day2: frequency === "semi_monthly" ? day2 : null,
        // Paydays on fixed days of the month need no anchor; start from January so the
        // pay period we are in right now already exists (January of the year new things start in).
        startDate: byDayOfMonth ? (schedule?.startDate ?? `${startOn.date.slice(0, 4)}-01-01`) : startDate,
        endDate: schedule?.endDate ?? null,
        weekendRule,
        manualDates: frequency === "manual_dates" ? pickedDates : [],
      },
      deductions: parsedDeductions,
      match: matchInput,
    };
  };

  /** Which of the source's amounts this save is about; nothing when adding, or correcting the ones in force today. */
  function whichAmounts(): Pick<IncomeSourceInput, "effectiveFrom" | "originalAmounts"> {
    if (!source) {
      return {};
    }
    if (terms) {
      return terms.effectiveDate === null ? { originalAmounts: true } : { effectiveFrom: terms.effectiveDate };
    }
    return payChange ? { effectiveFrom } : {};
  }

  const check = (): Trouble | null => {
    const input = toInput();
    if (!isTrouble(input)) {
      return null;
    }
    if (input.deduction !== undefined) {
      setChosenDeduction(input.deduction);
    }
    return input;
  };

  const send = () => {
    const input = toInput();
    if (!isTrouble(input)) {
      save.mutate(input, {
        onSuccess: (saved) => {
          if (source || !stubFile) {
            onClose();
            return;
          }
          // The stub that filled the form in is kept with the new source, and opens to be looked over:
          // that is where its lines get their accounts, and where they are remembered for the next stub.
          keepStub.mutate(
            { sourceId: saved.id, file: stubFile },
            {
              onSuccess: (stub) => {
                onClose();
                navigate(`/income/${saved.id}/stubs/${stub.id}`);
              },
              onError: (error) => setStubNotKept(error.message),
            },
          );
        },
      });
    }
  };

  const title = !source
    ? "Add an income source"
    : payChange
      ? "Record a pay change"
      : terms
        ? terms.effectiveDate === null
          ? "Edit the pay from the start"
          : `Edit the pay from ${formatDate(terms.effectiveDate)}`
        : "Edit income source";
  const failure = parts.problem ?? save.error?.message;

  /** Reads a stub on this PC and puts its figures in the form, to be looked over like anything typed. */
  const fillFromStub = (picked: readonly File[]) => {
    const file = picked[0];
    if (!file) {
      return;
    }
    const found = problemWithStub(picked);
    setStubProblem(found ?? undefined);
    if (found !== null) {
      return;
    }
    readStub.mutate(file, {
      onSuccess: (read: StubRead) => {
        setStubFile(file);
        if (read.lines.some((line) => line.section === "net")) {
          setNet(centsToInput(read.totals.netCents));
          setNetIsEstimate(false);
        }
        if (read.lines.some((line) => line.section === "earnings")) {
          setGross(centsToInput(read.totals.grossCents));
        }
        // What comes out before and after tax; each gets its account on the stub's own page.
        setDeductions(
          read.lines
            .filter((line) => (line.section === "pre_tax" || line.section === "after_tax") && line.amountCents > 0)
            .map((line, index) => ({
              key: index,
              name: line.label,
              unit: "dollars" as const,
              amount: centsToInput(line.amountCents),
              percent: "",
              toAccountId: null,
            })),
        );
      },
    });
  };
  const paydaysInView = manualDates.slice(paydaysFrom, paydaysFrom + PAYDAYS_SHOWN);

  return (
    <Modal title={title} icon={Banknote} onClose={onClose}>
      {stubNotKept !== undefined && (
        // The income is saved; only its stub is not. Said plainly, with the one thing left to do.
        <div className="space-y-4">
          <p role="alert" className="font-medium">
            {name} was added, but its pay stub could not be kept: {stubNotKept}
          </p>
          <p className="text-muted">You can add the stub again from {name}'s page, under Pay stubs.</p>
          <div className="flex justify-end">
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Close
            </button>
          </div>
        </div>
      )}
      <form
        ref={parts.ref}
        onSubmit={(event: FormEvent) => parts.submit(event, check, send)}
        hidden={stubNotKept !== undefined}
        className="space-y-4"
      >
        {source && terms && (
          <p className="rounded-lg border-l-4 border-accent bg-bg px-4 py-2 text-sm">
            {terms.effectiveDate === null
              ? "The amounts this income started with. Saving corrects them for the paychecks they cover."
              : `The amounts from ${formatDate(terms.effectiveDate)} on. Saving corrects them; earlier paychecks are not touched.`}
          </p>
        )}
        {parts.header()}

        {parts.shows("who") && (
          <Screen title="Who pays you" named={stepping}>
            <div>
              <label htmlFor={`${id}-name`} className={LABEL}>
                Name
              </label>
              <input
                id={`${id}-name`}
                value={name}
                onChange={(event) => setName(event.target.value)}
                maxLength={80}
                required
                autoComplete="off"
                placeholder="Employer or where the money comes from"
                className={INPUT}
              />
            </div>

            <Picker
              label="Deposited to"
              noun="account"
              placeholder="Choose or type an account"
              options={accountOptions}
              value={depositAccountId}
              onChange={setDepositAccountId}
            />

            <div>
              <label className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={primary}
                  disabled={primaryLocked}
                  onChange={(event) => setPrimary(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span className="font-medium">This paycheck sets my pay periods</span>
              </label>
              <p className={HINT}>
                {isFirst
                  ? "Your first income source always sets your pay periods. You can change that once you add another."
                  : source?.primary
                    ? "To hand this over, edit another income source and tick this box there."
                    : "Each pay period runs from one of these paydays to the day before the next."}
              </p>
            </div>
          </Screen>
        )}

        {parts.shows("when") && (
          <Screen title="When you're paid" named={stepping}>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor={`${id}-frequency`} className={LABEL}>
                  How often
                </label>
                <select
                  id={`${id}-frequency`}
                  value={frequency}
                  onChange={(event) => setFrequency(event.target.value as PayFrequency)}
                  className={INPUT}
                >
                  {PAY_FREQUENCIES.map((option) => (
                    <option key={option} value={option}>
                      {FREQUENCY_LABELS[option]}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor={`${id}-weekend`} className={LABEL}>
                  On a weekend or holiday
                </label>
                <select
                  id={`${id}-weekend`}
                  value={weekendRule}
                  onChange={(event) => setWeekendRule(event.target.value as WeekendRule)}
                  className={INPUT}
                >
                  {WEEKEND_RULES.map((option) => (
                    <option key={option} value={option}>
                      {WEEKEND_LABELS[option]}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {(frequency === "weekly" || frequency === "biweekly") && (
              <div>
                <label htmlFor={`${id}-start`} className={LABEL}>
                  A payday to count from
                </label>
                <input
                  id={`${id}-start`}
                  type="date"
                  value={startDate}
                  onChange={(event) => setStartDate(event.target.value)}
                  required
                  className={INPUT}
                />
                <p className={HINT}>Any real payday works — your most recent one is ideal. Later paydays repeat from it.</p>
                {!source && <DefaultDateHint value={startDate} />}
              </div>
            )}

            {(frequency === "semi_monthly" || frequency === "monthly") && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <DaySelect
                  id={`${id}-day1`}
                  label={frequency === "monthly" ? "Day of the month" : "First payday of the month"}
                  value={day1}
                  onChange={setDay1}
                />
                {frequency === "semi_monthly" && (
                  <DaySelect id={`${id}-day2`} label="Second payday of the month" value={day2} onChange={setDay2} />
                )}
              </div>
            )}

            {frequency === "manual_dates" && (
              <div>
                <p className={LABEL}>Paydays</p>
                <ul className="space-y-2">
                  {paydaysInView.map((date, offset) => {
                    const index = paydaysFrom + offset;
                    return (
                      // Rows have no identity beyond their position; the list only grows or shrinks at a known index.
                      <li key={index} className="flex gap-2">
                        <input
                          type="date"
                          aria-label={`Payday ${index + 1}`}
                          value={date}
                          onChange={(event) =>
                            setManualDates((dates) => dates.map((old, at) => (at === index ? event.target.value : old)))
                          }
                          className={INPUT}
                        />
                        <button
                          type="button"
                          aria-label={`Remove payday ${index + 1}`}
                          disabled={manualDates.length <= 2}
                          onClick={() => {
                            setManualDates((dates) => dates.filter((_, at) => at !== index));
                            // Removing the only one in view steps back to the ones before it.
                            setPaydaysFrom((from) => (from >= manualDates.length - 1 ? Math.max(0, from - PAYDAYS_SHOWN) : from));
                          }}
                          className="flex size-11 shrink-0 items-center justify-center rounded-lg border border-line disabled:opacity-40"
                        >
                          <Trash2 aria-hidden className="size-4" />
                        </button>
                      </li>
                    );
                  })}
                </ul>
                <div className="flex flex-wrap items-center justify-between gap-x-2">
                  <button
                    type="button"
                    onClick={() => {
                      setManualDates((dates) => [...dates, ""]);
                      // Bring the new, empty one into view.
                      setPaydaysFrom(Math.floor(manualDates.length / PAYDAYS_SHOWN) * PAYDAYS_SHOWN);
                    }}
                    className={BUTTON_LINK}
                  >
                    + Add a payday
                  </button>
                  {manualDates.length > PAYDAYS_SHOWN && (
                    <Pager
                      label="paydays"
                      status={`${paydaysFrom + 1}–${paydaysFrom + paydaysInView.length} of ${manualDates.length}`}
                      onPrevious={paydaysFrom === 0 ? undefined : () => setPaydaysFrom(paydaysFrom - PAYDAYS_SHOWN)}
                      onNext={
                        paydaysFrom + PAYDAYS_SHOWN >= manualDates.length ? undefined : () => setPaydaysFrom(paydaysFrom + PAYDAYS_SHOWN)
                      }
                    />
                  )}
                </div>
              </div>
            )}
          </Screen>
        )}

        {parts.shows("pay") && (
          <Screen title="How much" named={!source}>
            {payChange && (
              <div>
                <label htmlFor={`${id}-effective-from`} className={LABEL}>
                  Changes from
                </label>
                <input
                  id={`${id}-effective-from`}
                  type="date"
                  value={effectiveFrom}
                  onChange={(event) => setEffectiveFrom(event.target.value)}
                  className={INPUT}
                />
                <p className={HINT}>
                  The first paycheck on or after this day gets the new amounts; earlier ones keep theirs. Any day, past or future.
                </p>
              </div>
            )}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <MoneyField label="Take-home per paycheck" value={net} onChange={setNet} required />
                <label className="mt-1 flex min-h-11 items-center gap-3">
                  <input
                    type="checkbox"
                    checked={netIsEstimate}
                    onChange={(event) => setNetIsEstimate(event.target.checked)}
                    className="size-5 accent-(--accent)"
                  />
                  <span>Estimate, not final yet</span>
                </label>
              </div>
              <MoneyField label="Gross per paycheck (optional)" value={gross} onChange={setGross} hint="Before taxes and deductions." />
            </div>
            {!source && (
              <div>
                <label
                  htmlFor={`${id}-stub`}
                  className="flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg border border-dashed border-line px-3 py-2 text-center font-medium has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent-ink"
                >
                  <FileText aria-hidden className="size-4 shrink-0" />
                  {readStub.isPending ? "Reading the stub…" : stubFile ? "Use a different pay stub" : "Fill these in from a pay stub (PDF)"}
                  <input
                    id={`${id}-stub`}
                    type="file"
                    accept={ACCEPTED_STUBS}
                    disabled={readStub.isPending}
                    onChange={(event) => {
                      fillFromStub([...(event.target.files ?? [])]);
                      event.target.value = "";
                    }}
                    className="sr-only"
                  />
                </label>
                {stubFile && !readStub.isPending && (
                  <p role="status" className={HINT}>
                    Filled in from {stubFile.name}, read on this PC. Please check the amounts. It opens next, to say which accounts
                    its deductions go to.
                  </p>
                )}
                {(stubProblem ?? readStub.error?.message) && (
                  <p role="alert" className="mt-1 text-sm text-danger">
                    {stubProblem ?? readStub.error?.message}
                  </p>
                )}
              </div>
            )}
            {source && !terms && !payChange && (
              // Editing corrects; a raise is recorded with its own button, so the earlier paychecks keep what they had.
              <p className={HINT}>
                {correctingHint(source)} For a raise, or new deductions or a new match from a date, close this and choose{" "}
                <span className="font-semibold">Record a pay change</span>.
              </p>
            )}
          </Screen>
        )}

        {parts.shows("deductions") && (
          <Screen title="Deductions" named={stepping}>
            {deduction === undefined ? (
              <>
                <p className="text-muted">
                  Money taken out of each paycheck before it reaches you, like a 401(k) or HSA contribution. There are none yet.
                </p>
                <button type="button" onClick={addDeduction} className={BUTTON_SECONDARY}>
                  + Add a deduction
                </button>
              </>
            ) : (
              // One at a time: each asks four things, and the form never scrolls however many there are.
              <div key={deduction.key} className="space-y-3">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-semibold">
                    Deduction {deductionAt + 1} of {deductions.length}
                  </p>
                  {deductions.length > 1 && (
                    <Pager
                      label="deduction"
                      onPrevious={deductionAt === 0 ? undefined : () => setChosenDeduction(deductionAt - 1)}
                      onNext={deductionAt === deductions.length - 1 ? undefined : () => setChosenDeduction(deductionAt + 1)}
                    />
                  )}
                </div>
                <div className="grid grid-cols-[minmax(0,1fr)_7.5rem] gap-3">
                  <TextPicker
                    label="Name"
                    noun="deduction"
                    placeholder="e.g. 401(k)"
                    suggestions={DEDUCTION_SUGGESTIONS}
                    value={deduction.name}
                    onChange={(text) => updateDeduction(deduction.key, { name: text })}
                  />
                  <div>
                    <label htmlFor={`${id}-deduction-unit`} className={LABEL}>
                      Given as
                    </label>
                    <select
                      id={`${id}-deduction-unit`}
                      value={deduction.unit}
                      onChange={(event) => updateDeduction(deduction.key, { unit: event.target.value as DeductionUnit })}
                      className={INPUT}
                    >
                      <option value="dollars">Dollars</option>
                      <option value="percent">Percent</option>
                    </select>
                  </div>
                </div>
                {deduction.unit === "dollars" ? (
                  <MoneyField label="Amount" value={deduction.amount} onChange={(text) => updateDeduction(deduction.key, { amount: text })} />
                ) : (
                  <div>
                    <label htmlFor={`${id}-deduction-percent`} className={LABEL}>
                      Percent of gross pay
                    </label>
                    <input
                      id={`${id}-deduction-percent`}
                      value={deduction.percent}
                      onChange={(event) => updateDeduction(deduction.key, { percent: event.target.value })}
                      inputMode="decimal"
                      autoComplete="off"
                      placeholder="e.g. 6"
                      className={`${INPUT} tabular-nums`}
                    />
                    <p className={HINT}>{percentHint(deduction.percent, grossTyped)}</p>
                  </div>
                )}
                <Picker
                  label="Goes into (optional)"
                  noun="account"
                  placeholder="Not one of my accounts"
                  options={accountOptions}
                  value={deduction.toAccountId}
                  onChange={(accountId) => updateDeduction(deduction.key, { toAccountId: accountId })}
                  clearable
                />
                <div className="-mx-2 flex flex-wrap justify-between gap-x-2">
                  <button
                    type="button"
                    onClick={() => setDeductions((rows) => rows.filter((row) => row.key !== deduction.key))}
                    className={BUTTON_LINK}
                  >
                    Remove this deduction
                  </button>
                  <button type="button" onClick={addDeduction} className={BUTTON_LINK}>
                    + Add another
                  </button>
                </div>
              </div>
            )}
          </Screen>
        )}

        {parts.shows("match") && (
          <Screen title="Employer match" named={stepping}>
            {/* A dropdown, not a row of segments: three choices leave one stranded on a row of its own on a phone. */}
            <div>
              <label htmlFor={`${id}-match-choice`} className={LABEL}>
                Does your employer add anything?
              </label>
              <select
                id={`${id}-match-choice`}
                value={matchChoice}
                onChange={(event) => setMatchChoice(event.target.value as MatchChoice)}
                className={INPUT}
              >
                {MATCH_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
            {matchChoice !== "none" && investmentOptions.length === 0 && (
              <p role="status" className="rounded-lg border-l-4 border-accent bg-bg px-4 py-2">
                A match is paid into an investment account, and there is none yet. Add one on the Accounts screen first, with a label
                such as 401(k), then come back to this.
              </p>
            )}
            {matchChoice !== "none" && investmentOptions.length > 0 && (
              <Picker
                label="Paid into"
                noun="investment account"
                placeholder="Choose or type an investment account"
                options={investmentOptions}
                value={matchAccountId}
                onChange={setMatchAccountId}
              />
            )}
            {matchChoice === "fixed" && investmentOptions.length > 0 && (
              <MoneyField label="Match per paycheck" value={matchFixed} onChange={setMatchFixed} />
            )}
            {matchChoice === "formula" && investmentOptions.length > 0 && (
              <>
                <div>
                  {/* Side by side on a phone too, as it is said: "100% up to 6%". */}
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label htmlFor={`${id}-match-percent`} className={LABEL}>
                        They match, %
                      </label>
                      <input
                        id={`${id}-match-percent`}
                        value={matchPercent}
                        onChange={(event) => setMatchPercent(event.target.value)}
                        inputMode="decimal"
                        autoComplete="off"
                        placeholder="e.g. 100"
                        className={`${INPUT} tabular-nums`}
                      />
                    </div>
                    <div>
                      <label htmlFor={`${id}-match-cap`} className={LABEL}>
                        Up to, % of pay
                      </label>
                      <input
                        id={`${id}-match-cap`}
                        value={capPercent}
                        onChange={(event) => setCapPercent(event.target.value)}
                        inputMode="decimal"
                        autoComplete="off"
                        placeholder="e.g. 6"
                        className={`${INPUT} tabular-nums`}
                      />
                    </div>
                  </div>
                  <p className={HINT}>
                    "100% match up to 6%" is 100 and 6. What you put in is what your deductions send to the same account.
                  </p>
                </div>
                {grossTyped === null && (
                  // Nothing to measure the contribution against (no gross pay was given): ask, rather than refuse or guess.
                  <label className="flex min-h-11 items-start gap-3">
                    <input
                      type="checkbox"
                      checked={withinCap}
                      onChange={(event) => setWithinCap(event.target.checked)}
                      className="mt-0.5 size-5 shrink-0 accent-(--accent)"
                    />
                    <span className="font-medium">
                      What I put in is within the {capPercent.trim() === "" ? "share" : `${capPercent.trim()}%`} of my pay that is matched
                    </span>
                  </label>
                )}
              </>
            )}
          </Screen>
        )}

        {source && parts.shows("delete") && (
          <Screen title="Delete" named={false}>
            <DeleteSource
              source={source}
              onDeleted={() => {
                onClose();
                onDeleted?.();
              }}
            />
          </Screen>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        {parts.buttons({
          onClose,
          pending: save.isPending || keepStub.isPending,
          saveLabel: !source ? "Add income source" : payChange ? "Save pay change" : "Save changes",
        })}
      </form>
    </Modal>
  );
}

/**
 * One screen of the form. While stepping through, it says what it asks; when it was chosen by
 * name at the top of the form, that name is enough, and is kept for screen readers only.
 */
function Screen({ title, named, children }: { title: string; named: boolean; children: ReactNode }) {
  return (
    <fieldset className="min-w-0">
      <legend className={named ? "float-left mb-3 w-full text-lg font-semibold" : "sr-only"}>{title}</legend>
      <div className="clear-both space-y-4">{children}</div>
    </fieldset>
  );
}

/** Steps through a list shown a few at a time: the one before, and the one after. */
function Pager({ label, status, onPrevious, onNext }: { label: string; status?: string; onPrevious?: () => void; onNext?: () => void }) {
  const arrow = "flex size-11 shrink-0 items-center justify-center rounded-lg border border-line disabled:opacity-40";
  return (
    <div className="flex items-center gap-2">
      {status && (
        <p role="status" className="text-sm text-muted tabular-nums">
          {status}
        </p>
      )}
      <button type="button" onClick={onPrevious} disabled={!onPrevious} aria-label={`Previous ${label}`} className={arrow}>
        <ChevronLeft aria-hidden className="size-5" />
      </button>
      <button type="button" onClick={onNext} disabled={!onNext} aria-label={`Next ${label}`} className={arrow}>
        <ChevronRight aria-hidden className="size-5" />
      </button>
    </div>
  );
}

function isTrouble(input: IncomeSourceInput | Trouble): input is Trouble {
  return "problem" in input;
}

/** Which paychecks a correction reaches: the ones the amounts in force today already cover. */
function correctingHint(source: IncomeSource): string {
  const current = source.payHistory.find((entry) => entry.current);
  if (current?.effectiveDate) {
    return `Saving corrects every paycheck since ${formatDate(current.effectiveDate)}, when these amounts began.`;
  }
  return source.payHistory.length > 1
    ? "Saving corrects every paycheck up to your next pay change."
    : "Saving corrects every paycheck, past and future.";
}

/** Under a percent deduction: what it comes to a paycheck, or what is still needed to say. */
function percentHint(percent: string, grossCents: number | null): string {
  if (grossCents === null) {
    return "Fill in the gross amount per paycheck too, so this can be worked out.";
  }
  const percentBp = parseRate(percent);
  if (percentBp === null) {
    return "Of the gross amount per paycheck.";
  }
  // The backend's figure is the one that counts; this is the same sum, shown while typing.
  return `About ${formatCents(Math.round((grossCents * percentBp) / 10_000))} a paycheck.`;
}

function DeleteSource({ source, onDeleted }: { source: IncomeSource; onDeleted: () => void }) {
  const remove = useDeleteIncomeSource(source.id);
  return (
    <ConfirmDelete
      action="Delete income source…"
      question={`Delete ${source.name}?`}
      detail="Its schedule, deductions and any paycheck amounts you typed in go with it. You can undo this straight afterwards."
      confirmLabel="Yes, delete it"
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: onDeleted })}
    />
  );
}
