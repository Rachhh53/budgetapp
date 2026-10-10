import { HandCoins, Landmark } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account, Rate } from "../../api/accounts";
import { type Loan, type LoanInput, type LoanSettings, useLoan, useSaveLoan } from "../../api/loans";
import { formatDate } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { formatRate } from "../../lib/rates";
import { RateCard } from "../accounts/RateCard";
import { Card } from "../Card";
import { dayOfMonth } from "../cards/labels";
import { DaySelect } from "../DaySelect";
import { EditButton } from "../EditButton";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** The longest loan the backend takes: 50 years. */
const MAX_TERM_YEARS = 50;
const MONTHS_IN_YEAR = 12;

/** A loan's terms as the backend takes them, starting from what is saved. */
function asInput(settings: LoanSettings): LoanInput {
  return {
    originalPrincipalCents: settings.originalPrincipalCents,
    termMonths: settings.termMonths,
    startDate: settings.startDate,
    paymentCents: settings.paymentCents,
    paymentDay: settings.paymentDay,
    escrowInsuranceCents: settings.escrowInsuranceCents,
    escrowTaxesCents: settings.escrowTaxesCents,
  };
}

/** A term in words: "30 years", "5 years 6 months", "18 months". */
function termInWords(months: number): string {
  const years = Math.floor(months / MONTHS_IN_YEAR);
  const rest = months % MONTHS_IN_YEAR;
  const inYears = years === 0 ? "" : years === 1 ? "1 year" : `${years} years`;
  const inMonths = rest === 0 ? "" : rest === 1 ? "1 month" : `${rest} months`;
  return [inYears, inMonths].filter((part) => part !== "").join(" ");
}

/**
 * What a loan account shows beyond a plain account (spec §5.7): its monthly payment shared out
 * into interest, principal and escrow, when it will be paid off, the terms it was taken out on,
 * and its rate. One card per topic, each with its own short form.
 */
export function LoanCards({ account, rates }: { account: Account; rates: readonly Rate[] }) {
  const loan = useLoan(account.id);
  const [open, setOpen] = useState<"payment" | "terms" | null>(null);
  const close = () => setOpen(null);

  if (loan.isPending) {
    return (
      <Card title="Monthly payment">
        <p className="text-muted">Loading this loan…</p>
      </Card>
    );
  }
  if (loan.isError) {
    return (
      <Card title="Monthly payment">
        <p role="alert" className="text-danger">
          {loan.error.message}
        </p>
      </Card>
    );
  }

  const { settings } = loan.data;
  return (
    <>
      <Card
        title="Monthly payment"
        action={settings ? <EditButton name="monthly payment" onClick={() => setOpen("payment")} /> : undefined}
      >
        {settings ? (
          <PaymentSummary loan={loan.data} settings={settings} />
        ) : (
          <>
            <p className="text-muted">
              Enter the monthly payment to see how each one is shared between interest, principal and escrow, and when the loan will be
              paid off.
            </p>
            <button type="button" onClick={() => setOpen("payment")} className={`${BUTTON_SECONDARY} mt-3`}>
              Add the payment
            </button>
          </>
        )}
      </Card>

      {settings && (
        <Card title="Loan terms" action={<EditButton name="loan terms" onClick={() => setOpen("terms")} />}>
          {settings.originalPrincipalCents === null && settings.termMonths === null && settings.startDate === null ? (
            <p className="text-muted">
              Optional: what was borrowed, for how long and from when. With them, the app can show the payment the loan was set up with.
            </p>
          ) : (
            <dl className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Fact label="Borrowed" value={settings.originalPrincipalCents === null ? "—" : formatCents(settings.originalPrincipalCents)} amount />
              <Fact label="Term" value={settings.termMonths === null ? "—" : termInWords(settings.termMonths)} />
              <Fact label="Started" value={settings.startDate === null ? "—" : formatDate(settings.startDate)} />
            </dl>
          )}
          {loan.data.standardPaymentCents !== null && (
            <p className={HINT}>
              At {loan.data.rateBp === null ? "this rate" : formatRate(loan.data.rateBp)}, those terms work out to{" "}
              {formatCents(loan.data.standardPaymentCents)} a month before insurance and taxes.
            </p>
          )}
        </Card>
      )}

      <RateCard
        account={account}
        rates={rates}
        title="Interest rate"
        unit="a year"
        about="A month's interest is added on the payment day, on what is owed then. Only the rest of the payment lowers the loan."
        missing="No rate entered yet. Without one, every payment is counted as paying down the loan, with no interest."
      />

      {open === "payment" && <PaymentForm account={account} settings={settings} onClose={close} />}
      {open === "terms" && settings && <TermsForm account={account} settings={settings} onClose={close} />}
    </>
  );
}

function Fact({ label, value, amount = false }: { label: string; value: string; amount?: boolean }) {
  return (
    <div>
      <dt className="text-muted">{label}</dt>
      <dd data-amount={amount || undefined} className="text-lg font-semibold tabular-nums">
        {value}
      </dd>
    </div>
  );
}

/** The next payment shared out, when the loan ends, and the years still to come. */
function PaymentSummary({ loan, settings }: { loan: Loan; settings: LoanSettings }) {
  const next = loan.nextPayment;
  return (
    <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <p className="text-muted">Payment</p>
          <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(settings.paymentCents)}</p>
          <p className="text-muted">a month, on {dayOfMonth(settings.paymentDay)}</p>
        </div>
        {next && (
          <div>
            <p className="text-muted">Next, {formatDate(next.date)}</p>
            <ul className="mt-1 space-y-0.5">
              <Share label="Principal" cents={next.principalCents} />
              <Share label="Interest" cents={next.interestCents} />
              {next.escrowCents > 0 && <Share label="Insurance and taxes" cents={next.escrowCents} />}
            </ul>
          </div>
        )}
        <div>
          <p className="text-muted">Paid off</p>
          {loan.payoffDate ? (
            <>
              <p className="text-2xl font-bold">{formatDate(loan.payoffDate)}</p>
              <p className="text-muted">at this payment and rate</p>
            </>
          ) : (
            <p className="mt-1 text-muted">{loan.paysOff ? "Nothing is owed." : "Not at this payment."}</p>
          )}
        </div>
        {loan.interestRemainingCents !== null && (
          <div>
            <p className="text-muted">Interest still to pay</p>
            <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(loan.interestRemainingCents)}</p>
            <p className="text-muted">from today until it is paid off</p>
          </div>
        )}
      </div>

      {!loan.paysOff && (
        <p role="alert" className="mt-3 rounded-lg border border-warn-line bg-warn p-3 font-medium text-warn-ink">
          This payment does not cover a month's interest, so the loan would grow rather than shrink. Please check the payment and the
          rate against your statement.
        </p>
      )}
      {settings.escrowInsuranceCents + settings.escrowTaxesCents > 0 && (
        <p className={HINT}>
          {formatCents(settings.escrowInsuranceCents)} for insurance and {formatCents(settings.escrowTaxesCents)} for taxes are part of the
          payment and never lower the loan.
        </p>
      )}

      {loan.years.length > 0 && (
        <details className="mt-3 border-t border-line pt-3">
          <summary className="min-h-11 cursor-pointer content-center font-semibold">Year by year</summary>
          <div aria-hidden className={`${YEAR_ROW} border-b border-line text-sm font-semibold text-muted`}>
            <span>Year</span>
            <span className="text-right">Interest</span>
            <span className="text-right">Principal</span>
            <span className="text-right">Owed at the end</span>
          </div>
          <ul className="divide-y divide-line">
            {loan.years.map((year) => (
              <li key={year.year} className={YEAR_ROW}>
                <span className="font-medium">{year.year}</span>
                <span data-amount className="text-right tabular-nums">
                  <span className="sr-only">Interest </span>
                  {formatCents(year.interestCents)}
                </span>
                <span data-amount className="text-right tabular-nums">
                  <span className="sr-only">Principal </span>
                  {formatCents(year.principalCents)}
                </span>
                <span data-amount className="text-right font-semibold tabular-nums">
                  <span className="sr-only">Owed at the end </span>
                  {formatCents(year.endingCents)}
                </span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}

/* Four short columns: narrow enough for a phone with the smaller type there. */
const YEAR_ROW = "grid grid-cols-[3rem_1fr_1fr_1fr] gap-x-2 py-2 text-sm sm:gap-x-4 sm:text-base";

function Share({ label, cents }: { label: string; cents: number }) {
  return (
    <li className="flex justify-between gap-3">
      <span className="text-muted">{label}</span>
      <span data-amount className="font-semibold tabular-nums">{formatCents(cents)}</span>
    </li>
  );
}

function Buttons({ onClose, pending }: { onClose: () => void; pending: boolean }) {
  return (
    <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
      <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
        Cancel
      </button>
      <button type="submit" disabled={pending} className={BUTTON_PRIMARY}>
        {pending ? "Saving…" : "Save"}
      </button>
    </div>
  );
}

/** An optional amount: empty is zero. `undefined` when what was typed is not an amount. */
function optionalCents(text: string): number | undefined {
  if (text.trim() === "") {
    return 0;
  }
  const cents = parseDollars(text);
  return cents === null || cents < 0 ? undefined : cents;
}

/** The monthly payment: how much, on which day, and how much of it is insurance and taxes. */
function PaymentForm({ account, settings, onClose }: { account: Account; settings: LoanSettings | null; onClose: () => void }) {
  const id = useId();
  const save = useSaveLoan(account.id);
  const [payment, setPayment] = useState(settings ? centsToInput(settings.paymentCents) : "");
  const [day, setDay] = useState(settings?.paymentDay ?? 1);
  const [insurance, setInsurance] = useState(settings && settings.escrowInsuranceCents > 0 ? centsToInput(settings.escrowInsuranceCents) : "");
  const [taxes, setTaxes] = useState(settings && settings.escrowTaxesCents > 0 ? centsToInput(settings.escrowTaxesCents) : "");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const paymentCents = parseDollars(payment);
    const escrowInsuranceCents = optionalCents(insurance);
    const escrowTaxesCents = optionalCents(taxes);
    if (paymentCents === null || paymentCents <= 0 || escrowInsuranceCents === undefined || escrowTaxesCents === undefined) {
      setProblem(AMOUNT_HELP);
      return;
    }
    if (escrowInsuranceCents + escrowTaxesCents > paymentCents) {
      setProblem("The insurance and taxes are part of the payment, so together they can't be more than it.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        originalPrincipalCents: settings?.originalPrincipalCents ?? null,
        termMonths: settings?.termMonths ?? null,
        startDate: settings?.startDate ?? null,
        paymentCents,
        paymentDay: day,
        escrowInsuranceCents,
        escrowTaxesCents,
      },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`Monthly payment — ${account.name}`} icon={HandCoins} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <MoneyField label="Whole payment a month" value={payment} onChange={setPayment} required />
          <DaySelect id={`${id}-day`} label="Due on" value={day} onChange={setDay} />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <MoneyField label="Of that, insurance (optional)" value={insurance} onChange={setInsurance} />
          <MoneyField label="Of that, taxes (optional)" value={taxes} onChange={setTaxes} />
        </div>
        <p className={HINT}>
          The payment as your lender takes it, with any insurance and taxes they collect included. Those two never lower the loan.
        </p>
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

/** What was borrowed, for how long, and from when. All optional. */
function TermsForm({ account, settings, onClose }: { account: Account; settings: LoanSettings; onClose: () => void }) {
  const id = useId();
  const save = useSaveLoan(account.id);
  const [borrowed, setBorrowed] = useState(settings.originalPrincipalCents === null ? "" : centsToInput(settings.originalPrincipalCents));
  const [years, setYears] = useState(settings.termMonths === null ? "" : String(Math.floor(settings.termMonths / MONTHS_IN_YEAR)));
  const [months, setMonths] = useState(settings.termMonths === null ? "" : String(settings.termMonths % MONTHS_IN_YEAR));
  const [startDate, setStartDate] = useState(settings.startDate ?? "");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const originalPrincipalCents = borrowed.trim() === "" ? null : parseDollars(borrowed);
    if (borrowed.trim() !== "" && (originalPrincipalCents === null || originalPrincipalCents <= 0)) {
      setProblem("Please enter the amount borrowed like 250,000.00, or leave it empty.");
      return;
    }
    const noTerm = years.trim() === "" && months.trim() === "";
    const termMonths = Number(years || 0) * MONTHS_IN_YEAR + Number(months || 0);
    if (!noTerm && (!Number.isInteger(termMonths) || termMonths < 1 || termMonths > MAX_TERM_YEARS * MONTHS_IN_YEAR)) {
      setProblem("Please enter how long the loan runs, from 1 month to 50 years, or leave it empty.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      { ...asInput(settings), originalPrincipalCents, termMonths: noTerm ? null : termMonths, startDate: startDate === "" ? null : startDate },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title={`Loan terms — ${account.name}`} icon={Landmark} tone={account.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField label="Amount borrowed (optional)" value={borrowed} onChange={setBorrowed} />
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <div>
            <label htmlFor={`${id}-years`} className={LABEL}>
              Term: years
            </label>
            <input
              id={`${id}-years`}
              value={years}
              onChange={(event) => setYears(event.target.value)}
              inputMode="numeric"
              autoComplete="off"
              placeholder="30"
              className={`${INPUT} tabular-nums`}
            />
          </div>
          <div>
            <label htmlFor={`${id}-months`} className={LABEL}>
              and months
            </label>
            <input
              id={`${id}-months`}
              value={months}
              onChange={(event) => setMonths(event.target.value)}
              inputMode="numeric"
              autoComplete="off"
              placeholder="0"
              className={`${INPUT} tabular-nums`}
            />
          </div>
          <div className="col-span-2 sm:col-span-1">
            <label htmlFor={`${id}-start`} className={LABEL}>
              Started on
            </label>
            <input id={`${id}-start`} type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} className={INPUT} />
          </div>
        </div>
        <p className={HINT}>
          All optional. What you owe always comes from the balance you enter; these only show the payment the loan was set up with.
        </p>
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
