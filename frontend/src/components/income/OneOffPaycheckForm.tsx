import { Banknote } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import {
  type IncomeSource,
  type OneOffPaycheck,
  type OneOffPaycheckInput,
  useDeleteOneOffPaycheck,
  useSaveOneOffPaycheck,
} from "../../api/income";
import { useDefaultDate } from "../../api/settings";
import { formatDate } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { formatRate } from "../../lib/rates";
import { ConfirmDelete } from "../ConfirmDelete";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { HINT, INPUT, LABEL } from "../ui";
import { type DeductionRow, DeductionsEditor, deductionRows, parseDeductions, Screen } from "./Deductions";

/** The screens of the form: what and when, how much, what comes out, the match, and deleting. */
export type OneOffPart = "what" | "pay" | "deductions" | "match" | "delete";

/** Something still to put right: the screen it is on and, for a deduction, which one. */
type Trouble = PartProblem<OneOffPart> & { deduction?: number };

const GROUPS = {
  what: { label: "Name and date", parts: ["what"] },
  pay: { label: "Pay", parts: ["pay"] },
  deductions: { label: "Deductions", parts: ["deductions"] },
  match: { label: "Employer match", parts: ["match"] },
  delete: { label: "Delete", parts: ["delete"] },
} as const satisfies Record<OneOffPart, FormGroup<OneOffPart>>;

/**
 * Add or change a one-off paycheck: a bonus, or any extra pay, from `source` that arrives once
 * on its own day, with a paycheck's figures (Rachel, 2026-10-19: "it has all the same inputs as a
 * paycheck … but it's a one-off").
 *
 * Adding goes through all four screens, because the deductions start as a copy of the regular
 * paycheck's (decided with Rachel) and should be looked at, not saved unseen. Changing shows one
 * screen at a time, chosen at the top.
 */
export function OneOffPaycheckForm({
  source,
  oneOff,
  accounts,
  startAt,
  onClose,
}: {
  source: IncomeSource;
  /** The one being changed; leave out to add one. */
  oneOff?: OneOffPaycheck;
  accounts: readonly Account[];
  /** When changing: the screen to open on. */
  startAt?: OneOffPart;
  onClose: () => void;
}) {
  const id = useId();
  const save = useSaveOneOffPaycheck(source.id, oneOff?.id);
  const adding = oneOff === undefined;
  const groups: readonly FormGroup<OneOffPart>[] = adding
    ? [GROUPS.what, GROUPS.pay, GROUPS.deductions, GROUPS.match]
    : [GROUPS.what, GROUPS.pay, GROUPS.deductions, GROUPS.match, GROUPS.delete];
  const parts = useFormParts<OneOffPart>({ groups, adding, startAt });

  const startOn = useDefaultDate();
  const [name, setName] = useState(oneOff?.name ?? "");
  const [payDate, setPayDate] = useState(oneOff?.payDate ?? startOn.date);
  const [gross, setGross] = useState(oneOff?.grossCents != null ? centsToInput(oneOff.grossCents) : "");
  const [net, setNet] = useState(oneOff ? centsToInput(oneOff.netCents) : "");
  // A new one starts with the regular paycheck's deductions: a share of pay is then worked out on this paycheck's own gross.
  const [deductions, setDeductions] = useState<DeductionRow[]>(deductionRows(oneOff?.deductions ?? source.deductions));
  const [chosenDeduction, setChosenDeduction] = useState(0);
  const [matched, setMatched] = useState(oneOff?.matched ?? true);
  const grossTyped = gross.trim() === "" ? null : parseDollars(gross);

  // Archived accounts are left out, unless this paycheck already sends money to one.
  const inUse = new Set(deductions.map((row) => row.toAccountId));
  const accountOptions = accounts
    .filter((account) => !account.archived || inUse.has(String(account.id)))
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  /** Turns the form into a request, or says what still needs attention and where. */
  const toInput = (): OneOffPaycheckInput | Trouble => {
    if (name.trim() === "") {
      return { part: "what", problem: "Please add a name, like Year-end bonus." };
    }
    if (payDate === "") {
      return { part: "what", problem: "Please choose the day it was paid." };
    }
    const netCents = parseDollars(net);
    const grossCents = gross.trim() === "" ? null : parseDollars(gross);
    if (netCents === null || (gross.trim() !== "" && grossCents === null)) {
      return { part: "pay", problem: AMOUNT_HELP };
    }
    const parsed = parseDeductions(deductions, grossCents);
    if ("problem" in parsed) {
      return { part: "deductions", problem: parsed.problem, deduction: parsed.at };
    }
    return { payDate, name, grossCents, netCents, deductions: parsed.deductions, matched: source.match !== null && matched };
  };

  const check = (): Trouble | null => {
    const input = toInput();
    if (!("problem" in input)) {
      return null;
    }
    if (input.deduction !== undefined) {
      setChosenDeduction(input.deduction);
    }
    return input;
  };

  const send = () => {
    const input = toInput();
    if (!("problem" in input)) {
      save.mutate(input, { onSuccess: onClose });
    }
  };

  const failure = parts.problem ?? save.error?.message;

  return (
    <Modal title={adding ? "Add a one-off paycheck" : `Edit ${oneOff.name}`} icon={Banknote} onClose={onClose}>
      <form ref={parts.ref} onSubmit={(event: FormEvent) => parts.submit(event, check, send)} className="space-y-4">
        {oneOff?.payStubId != null && (
          <p className="rounded-lg border-l-4 border-accent bg-bg px-4 py-2 text-sm">
            Saved from a pay stub. Saving that stub again puts its figures back here.
          </p>
        )}
        {parts.header()}

        {parts.shows("what") && (
          <Screen title="What it is" named={adding}>
            <div>
              <label htmlFor={`${id}-name`} className={LABEL}>
                Name
              </label>
              <input
                id={`${id}-name`}
                value={name}
                onChange={(event) => setName(event.target.value)}
                maxLength={80}
                autoComplete="off"
                placeholder="e.g. Year-end bonus"
                required
                className={INPUT}
              />
            </div>
            <div>
              <label htmlFor={`${id}-date`} className={LABEL}>
                Paid on
              </label>
              <input
                id={`${id}-date`}
                type="date"
                value={payDate}
                onChange={(event) => setPayDate(event.target.value)}
                required
                className={INPUT}
              />
              {adding && <DefaultDateHint value={payDate} />}
            </div>
            <p className={HINT}>
              Paid once, from {source.name}, into the same account as its paychecks. Your regular paydays and pay periods stay as they are.
            </p>
          </Screen>
        )}

        {parts.shows("pay") && (
          <Screen title="How much" named={adding}>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <MoneyField label="Take-home" value={net} onChange={setNet} required hint="What reached your account." />
              <MoneyField label="Gross (optional)" value={gross} onChange={setGross} hint="Before taxes and deductions." />
            </div>
          </Screen>
        )}

        {parts.shows("deductions") && (
          <Screen title="Deductions" named={adding}>
            {adding && deductions.length > 0 && (
              <p className={HINT}>
                Copied from your regular paycheck. Change or remove any that don't come out of this one; a percent is worked out on this
                paycheck's gross.
              </p>
            )}
            <DeductionsEditor
              rows={deductions}
              onRowsChange={setDeductions}
              at={chosenDeduction}
              onAtChange={setChosenDeduction}
              accountOptions={accountOptions}
              grossCents={grossTyped}
              emptyText="Money taken out of this paycheck before it reached you, like a 401(k) contribution. There are none yet."
            />
          </Screen>
        )}

        {parts.shows("match") && (
          <Screen title="Employer match" named={adding}>
            <MatchChoice source={source} oneOff={oneOff} grossCents={grossTyped} matched={matched} onMatchedChange={setMatched} />
          </Screen>
        )}

        {oneOff && parts.shows("delete") && (
          <Screen title="Delete" named={false}>
            <DeleteOneOff source={source} oneOff={oneOff} onDeleted={onClose} />
          </Screen>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        {parts.buttons({ onClose, pending: save.isPending, saveLabel: adding ? "Add paycheck" : "Save changes" })}
      </form>
    </Modal>
  );
}

/**
 * Whether the employer's match is added. It is the source's own rule, worked out on this
 * paycheck (decided with Rachel, 2026-10-19), so there is nothing to type: only yes or no.
 */
function MatchChoice({
  source,
  oneOff,
  grossCents,
  matched,
  onMatchedChange,
}: {
  source: IncomeSource;
  oneOff?: OneOffPaycheck;
  /** The gross pay typed so far: a match by percentage needs it. */
  grossCents: number | null;
  matched: boolean;
  onMatchedChange: (matched: boolean) => void;
}) {
  const match = source.match;
  if (match === null) {
    return <p className="text-muted">{source.name} has no employer match set up, so nothing is added to this paycheck.</p>;
  }
  const rule =
    match.mode === "formula"
      ? `${formatRate(match.matchBp ?? 0)} of what you put in, up to ${formatRate(match.capBpOfGross ?? 0)} of this paycheck's gross`
      : `${formatCents(match.fixedCents ?? 0)}, as on every paycheck`;
  return (
    <>
      <label className="flex min-h-11 items-start gap-3">
        <input
          type="checkbox"
          checked={matched}
          onChange={(event) => onMatchedChange(event.target.checked)}
          className="mt-0.5 size-5 shrink-0 accent-(--accent)"
        />
        <span className="font-medium">Add the employer's match</span>
      </label>
      <p className={HINT}>
        By {source.name}'s rule: {rule}.
        {matched && oneOff?.matchCents != null && ` As saved, that comes to ${formatCents(oneOff.matchCents)}.`}
        {match.mode === "formula" && grossCents === null && " Without a gross amount, a percentage can't be worked out, and nothing is added."}
      </p>
    </>
  );
}

function DeleteOneOff({ source, oneOff, onDeleted }: { source: IncomeSource; oneOff: OneOffPaycheck; onDeleted: () => void }) {
  const remove = useDeleteOneOffPaycheck(source.id, oneOff.id);
  return (
    <ConfirmDelete
      action="Delete this paycheck…"
      question={`Delete ${oneOff.name} from ${formatDate(oneOff.payDate)}?`}
      detail="It comes out of your balances, reports and the year's figures. You can undo this straight afterwards."
      confirmLabel="Yes, delete it"
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: onDeleted })}
    />
  );
}
