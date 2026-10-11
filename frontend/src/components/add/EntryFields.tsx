import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { useBuckets } from "../../api/buckets";
import type { Category, Tag } from "../../api/categories";
import { type BucketSplit, type Entry, type EntryInput, type EntryKind, useDeleteEntry, useSaveEntry } from "../../api/entries";
import type { PeriodDetail } from "../../api/periods";
import { todayIso } from "../../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { FormSection } from "../FormSection";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/** Accounts whose balance is what is owed: spending raises it and money in pays it down. */
const OWED: readonly Account["type"][] = ["credit_card", "loan"];

interface EntryFieldsProps {
  kind: EntryKind;
  /** The entry being changed; leave out to add a new one. */
  entry?: Entry;
  /** When adding: the account to start on, for a form opened for one account (a charge on a card). */
  accountId?: number;
  /** The pay period on screen, if any: it gives the form its first date and account, and the preview. */
  period?: PeriodDetail;
  accounts: readonly Account[];
  categories: readonly Category[];
  tags: readonly Tag[];
  onClose: () => void;
}

/**
 * The one-off part of the + Add item form (design/add-form.png): money out, money in or a
 * transfer. While adding to the pay period on screen it shows what that period's ending
 * balance becomes, before anything is saved.
 *
 * Money coming in (or a transfer arriving) can be partly set aside in the buckets of the
 * account it lands in. Money leaving an account that has buckets has to say where it comes
 * out of — one of them, or what is not in any — so a bucket and its account go down together. "Pays back an expense" and uploads arrive with expenses and pay
 * stubs (Phases 8 and 9).
 */
export function EntryFields({ kind, entry, accountId: startOn, period, accounts, categories, tags, onClose }: EntryFieldsProps) {
  const id = useId();
  const save = useSaveEntry(entry?.id);

  const [amount, setAmount] = useState(entry ? centsToInput(entry.amountCents) : "");
  const [estimate, setEstimate] = useState(entry?.estimate ?? false);
  const [description, setDescription] = useState(entry?.description ?? "");
  const [payee, setPayee] = useState(entry?.payee ?? "");
  const [date, setDate] = useState(entry?.date ?? firstDate(period));
  const [accountId, setAccountId] = useState<string | null>(
    entry ? String(entry.accountId) : startOn !== undefined ? String(startOn) : period?.primaryAccountId != null ? String(period.primaryAccountId) : null,
  );
  const [toAccountId, setToAccountId] = useState<string | null>(entry?.toAccountId != null ? String(entry.toAccountId) : null);
  const [categoryId, setCategoryId] = useState<number | null>(entry?.categoryId ?? null);
  const [tagIds, setTagIds] = useState<number[]>(entry?.tagIds ?? []);
  const [note, setNote] = useState(entry?.note ?? "");
  const [checkNumber, setCheckNumber] = useState(entry?.checkNumber ?? "");
  const [settingAside, setSettingAside] = useState((entry?.bucketSplits.length ?? 0) > 0);
  // What to set aside in each bucket, as typed, by bucket id.
  const [aside, setAside] = useState<Record<number, string>>(
    Object.fromEntries((entry?.bucketSplits ?? []).map((split) => [split.bucketId, centsToInput(split.amountCents)])),
  );
  // Where money going out comes from: a bucket's id, NOT_IN_A_BUCKET, or "" until it is chosen.
  const [chosenFrom, setChosenFrom] = useState<string>();
  const [problem, setProblem] = useState<string>();

  // Only money arriving can be set aside, and only in the buckets of the account it arrives in.
  const arrivesIn = kind === "money_in" ? accountId : kind === "transfer" ? toAccountId : null;
  const openBuckets = (useBuckets().data ?? []).filter((bucket) => !bucket.archived);
  const buckets = openBuckets.filter((bucket) => String(bucket.accountId) === arrivesIn);
  // Money leaving comes out of a bucket of the account it leaves, or out of what is in none.
  const payingBuckets = kind === "money_in" ? [] : openBuckets.filter((bucket) => String(bucket.accountId) === accountId);
  // An entry being changed already says where it came from; a new one has to be told.
  const savedFrom = entry
    ? String(entry.bucketSplits.find((split) => payingBuckets.some((bucket) => bucket.id === split.bucketId))?.bucketId ?? NOT_IN_A_BUCKET)
    : "";
  const asked = chosenFrom ?? savedFrom;
  // A bucket chosen before the account was changed no longer applies.
  const comesFrom = asked === NOT_IN_A_BUCKET || payingBuckets.some((bucket) => String(bucket.id) === asked) ? asked : "";

  // Archived accounts are left out, unless this entry already points at one.
  const accountOptions = accounts
    .filter((account) => !account.archived || [accountId, toAccountId].includes(String(account.id)))
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const amountCents = parseDollars(amount);
  // A check can only be written on a checking account, for money that leaves it.
  const paidFromChecking =
    kind !== "money_in" && accounts.some((account) => String(account.id) === accountId && account.type === "checking");

  /** Turns the form into a request, or explains what still needs attention. */
  const toInput = (): EntryInput | string => {
    if (amountCents === null || amountCents < 0) {
      return AMOUNT_HELP;
    }
    if (accountId === null) {
      return kind === "money_in" ? "Please choose the account this money goes into." : "Please choose the account this money comes from.";
    }
    if (kind === "transfer" && toAccountId === null) {
      return "Please choose the account this money goes to.";
    }
    if (kind === "transfer" && toAccountId === accountId) {
      return "Please choose two different accounts.";
    }
    const bucketSplits: BucketSplit[] = [];
    for (const bucket of settingAside ? buckets : []) {
      const typed = (aside[bucket.id] ?? "").trim();
      if (typed === "") {
        continue;
      }
      const cents = parseDollars(typed);
      if (cents === null || cents < 0) {
        return `Please enter an amount like 250.00 for ${bucket.name}, or leave it empty.`;
      }
      if (cents > 0) {
        bucketSplits.push({ bucketId: bucket.id, amountCents: cents });
      }
    }
    if (bucketSplits.reduce((sum, split) => sum + split.amountCents, 0) > amountCents) {
      return "That sets aside more than the amount itself. Please lower one of the buckets.";
    }
    if (payingBuckets.length > 0) {
      if (comesFrom === "") {
        return "Please say where this comes out of: one of the buckets, or money that is not in a bucket.";
      }
      if (comesFrom !== NOT_IN_A_BUCKET && amountCents > 0) {
        bucketSplits.push({ bucketId: Number(comesFrom), amountCents });
      }
    }
    return {
      kind,
      date,
      amountCents,
      estimate,
      description,
      payee: kind === "money_out" && payee.trim() !== "" ? payee : null,
      categoryId,
      accountId: Number(accountId),
      toAccountId: kind === "transfer" && toAccountId !== null ? Number(toAccountId) : null,
      note: note.trim() === "" ? null : note,
      checkNumber: paidFromChecking && checkNumber.trim() !== "" ? checkNumber.trim() : null,
      tagIds,
      bucketSplits,
    };
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const input = toInput();
    if (typeof input === "string") {
      setProblem(input);
      return;
    }
    setProblem(undefined);
    save.mutate(input, { onSuccess: onClose });
  };

  const preview = entry ? null : previewEnding(period, accounts, kind, date, accountId, amountCents);
  const failure = problem ?? save.error?.message;

  return (
    <>
      <form onSubmit={submit} className="space-y-4">
        <FormSection title="Amount">
          <div>
            <MoneyField label="Amount" value={amount} onChange={setAmount} required autoFocus hero />
            <label className="mt-1 flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                checked={estimate}
                onChange={(event) => setEstimate(event.target.checked)}
                className="size-5 accent-(--accent)"
              />
              <span>Estimate, not final yet</span>
            </label>
          </div>
        </FormSection>

        <FormSection title="What it is">
          <div>
            <label htmlFor={`${id}-description`} className={LABEL}>
              What is it
            </label>
            <input
              id={`${id}-description`}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={120}
              required
              autoComplete="off"
              placeholder={PLACEHOLDERS[kind]}
              className={INPUT}
            />
          </div>
          {/* Two side by side only when there are two: a lone picker gets the full width. */}
          <div className={`grid grid-cols-1 gap-4 ${kind === "money_out" ? "sm:grid-cols-2" : ""}`}>
            {kind === "money_out" && (
              <div>
                <label htmlFor={`${id}-payee`} className={LABEL}>
                  Paid to (optional)
                </label>
                <input
                  id={`${id}-payee`}
                  value={payee}
                  onChange={(event) => setPayee(event.target.value)}
                  maxLength={120}
                  autoComplete="off"
                  className={INPUT}
                />
              </div>
            )}
            <CategoryField categories={categories} value={categoryId} onChange={setCategoryId} />
          </div>
        </FormSection>

        <FormSection title={SECTION_TITLES[kind]}>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor={`${id}-date`} className={LABEL}>
                Date
              </label>
              <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} required className={INPUT} />
            </div>
            <Picker
              label={ACCOUNT_LABELS[kind]}
              noun="account"
              placeholder="Choose or type an account"
              options={accountOptions}
              value={accountId}
              onChange={setAccountId}
            />
            {kind === "transfer" && (
              <Picker
                label="To"
                noun="account"
                placeholder="Choose or type an account"
                options={accountOptions}
                value={toAccountId}
                onChange={setToAccountId}
              />
            )}
            {paidFromChecking && (
              <div>
                <label htmlFor={`${id}-check-number`} className={LABEL}>
                  Check number (optional)
                </label>
                <input
                  id={`${id}-check-number`}
                  value={checkNumber}
                  onChange={(event) => setCheckNumber(event.target.value)}
                  maxLength={20}
                  inputMode="numeric"
                  autoComplete="off"
                  className={INPUT}
                />
              </div>
            )}
          </div>

          {payingBuckets.length > 0 && (
            <div>
              <label htmlFor={`${id}-from-bucket`} className={LABEL}>
                Comes out of
              </label>
              <select
                id={`${id}-from-bucket`}
                value={comesFrom}
                onChange={(event) => setChosenFrom(event.target.value)}
                required
                className={INPUT}
              >
                <option value="">Choose where this comes from</option>
                <option value={NOT_IN_A_BUCKET}>Money that is not in a bucket</option>
                {payingBuckets.map((bucket) => (
                  <option key={bucket.id} value={bucket.id}>
                    {bucket.name} (holds {formatCents(bucket.balanceCents)} today)
                  </option>
                ))}
              </select>
              <p className={HINT}>
                {comesFrom !== "" && comesFrom !== NOT_IN_A_BUCKET
                  ? "The bucket and the account both go down by this amount."
                  : "This account has buckets, so say whether this is paid out of one of them."}
              </p>
            </div>
          )}

          {buckets.length > 0 && (
            <div>
              <label className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={settingAside}
                  onChange={(event) => setSettingAside(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span className="font-medium">Set some aside in a bucket</span>
              </label>
              {settingAside && (
                <div className="mt-2 grid grid-cols-1 gap-4 sm:grid-cols-2">
                  {buckets.map((bucket) => (
                    <MoneyField
                      key={bucket.id}
                      label={bucket.name}
                      value={aside[bucket.id] ?? ""}
                      onChange={(text) => setAside((current) => ({ ...current, [bucket.id]: text }))}
                      hint={`Holds ${formatCents(bucket.balanceCents)} today`}
                    />
                  ))}
                  <p className="text-sm text-muted sm:col-span-2">Whatever is not set aside stays available in the account.</p>
                </div>
              )}
            </div>
          )}
        </FormSection>

        <FormSection title="Tags and notes">
          <TagsField tags={tags} value={tagIds} onChange={setTagIds} />
          <div>
            <label htmlFor={`${id}-note`} className={LABEL}>
              Note (optional)
            </label>
            <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} autoComplete="off" className={INPUT} />
          </div>
        </FormSection>

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          {/* Announced as it changes, so the effect of what is being typed is known before saving. */}
          <p aria-live="polite" className="min-w-0 text-sm text-muted">
            {preview && (
              <>
                Projected ending for {preview.accountName} this period becomes{" "}
                <span data-amount className="font-semibold text-ink tabular-nums">
                  {formatCents(preview.endingCents)}
                </span>
              </>
            )}
          </p>
          <div className="flex shrink-0 flex-col-reverse gap-2 sm:flex-row">
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Cancel
            </button>
            <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
              {save.isPending ? "Saving…" : entry ? "Save changes" : "Add"}
            </button>
          </div>
        </div>
      </form>

      {entry && <DeleteEntry entry={entry} onDeleted={onClose} />}
    </>
  );
}

/** The choice that takes nothing out of any bucket. Not a number, so it can never be mistaken for a bucket's id. */
const NOT_IN_A_BUCKET = "none";

const ACCOUNT_LABELS: Record<EntryKind, string> = { money_out: "Paid from", money_in: "Into", transfer: "From" };

/** The heading over the date and accounts, which reads differently for each kind. */
const SECTION_TITLES: Record<EntryKind, string> = {
  money_out: "When and where from",
  money_in: "When and where to",
  transfer: "When, from and to",
};

const PLACEHOLDERS: Record<EntryKind, string> = {
  money_out: "e.g. Vet visit",
  money_in: "e.g. Q3 bonus",
  transfer: "e.g. Top up savings",
};

/** Today when it falls in the period on screen; otherwise that period's first day. */
function firstDate(period: PeriodDetail | undefined): string {
  const today = todayIso();
  return period === undefined || (today >= period.start && today <= period.end) ? today : period.start;
}

/**
 * What the chosen account ends the period on once this entry is in it; `null` when there is
 * nothing to show yet (no amount, a date outside the period, or no balance entered for the account).
 */
function previewEnding(
  period: PeriodDetail | undefined,
  accounts: readonly Account[],
  kind: EntryKind,
  date: string,
  accountId: string | null,
  amountCents: number | null,
): { accountName: string; endingCents: number } | null {
  if (period === undefined || accountId === null || amountCents === null || amountCents <= 0 || date < period.start || date > period.end) {
    return null;
  }
  const account = accounts.find((candidate) => String(candidate.id) === accountId);
  const balance = period.accounts.find((candidate) => String(candidate.accountId) === accountId);
  // A balance entered part-way through the period already includes anything dated before it.
  if (account === undefined || balance === undefined || date < balance.startingOn) {
    return null;
  }
  const arrives = kind === "money_in";
  const raisesBalance = OWED.includes(account.type) !== arrives;
  return { accountName: account.name, endingCents: balance.endingCents + (raisesBalance ? amountCents : -amountCents) };
}

/** No "are you sure?" for a one-off (spec §6): it is deleted, and Undo brings it back. */
function DeleteEntry({ entry, onDeleted }: { entry: Entry; onDeleted: () => void }) {
  const remove = useDeleteEntry(entry.id);
  return (
    <div className="mt-6 border-t border-line pt-4">
      <button
        type="button"
        onClick={() => remove.mutate(undefined, { onSuccess: onDeleted })}
        disabled={remove.isPending}
        className={BUTTON_DANGER_OUTLINE}
      >
        {remove.isPending ? "Deleting…" : "Delete this one-off"}
      </button>
      {remove.error && (
        <p role="alert" className="mt-2 text-danger">
          {remove.error.message}
        </p>
      )}
    </div>
  );
}
