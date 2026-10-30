import { type FormEvent, useEffect, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { useBuckets } from "../../api/buckets";
import { ACCEPTED_FILES, DEFAULT_MAX_BYTES, problemWith, useAttachToEntry } from "../../api/attachments";
import type { Category, Tag } from "../../api/categories";
import {
  type BucketSplit,
  type Entry,
  type EntryInput,
  type EntryKind,
  useDeleteEntry,
  useNames,
  useSaveEntry,
} from "../../api/entries";
import type { PeriodDetail } from "../../api/periods";
import { todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { FormSection } from "../FormSection";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker, TextPicker } from "../Picker";
import { type IntoLine, IntoBuckets, NOT_IN_A_BUCKET } from "./IntoBuckets";
import { choiceFor, choiceOf, intoOf, owedOn, type PaysBackTarget, paysBackIds, targetName, useOwedExpenses } from "./owedExpenses";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, HINT, INPUT, LABEL } from "../ui";

/** Accounts whose balance is what is owed: spending raises it and money in pays it down. */
const OWED: readonly Account["type"][] = ["credit_card", "loan"];

interface EntryFieldsProps {
  kind: EntryKind;
  /** The entry being changed; leave out to add a new one. */
  entry?: Entry;
  /** When adding: the account to start on, for a form opened for one account (a charge on a card). */
  accountId?: number;
  /** When adding money coming in: the expense or flagged payment it pays back, for a form opened from it. */
  paysBack?: PaysBackTarget;
  /** The pay period on screen, if any: it gives the form its first date and account, and the preview. */
  period?: PeriodDetail;
  accounts: readonly Account[];
  categories: readonly Category[];
  tags: readonly Tag[];
  onClose: () => void;
  /** When changing: a part of the group to open on, for a form reached from the card that shows it. */
  startAt?: EntryPart;
  /** Told which screen of the form is showing (0 is the first), so whatever frames it can make room. */
  onStep?: (step: number) => void;
}

/**
 * The one-off part of the + Add item form (design/add-form.png): money out, money in or a
 * transfer. While adding to the pay period on screen it shows what that period's ending
 * balance becomes, before anything is saved.
 *
 * Money coming in (or a transfer arriving) goes into the buckets of the account it lands in,
 * chosen beside the accounts: the whole amount into one, or split over several. Money leaving an account that has buckets has to say where it comes
 * out of — one of them, or what is not in any — so a bucket and its account go down together.
 *
 * Money going out can be marked as something that will be paid back, and money coming in can
 * say which expense it pays back. A new entry can take its receipts with it: they are attached
 * as soon as it has been added. An existing one keeps its receipts on its own page.
 *
 * The form is shown a screen at a time (see `useFormParts`), so it never needs scrolling.
 */
export function EntryFields({ kind, entry, accountId: startOn, paysBack, period, accounts, categories, tags, onClose, onStep, startAt }: EntryFieldsProps) {
  const id = useId();
  const save = useSaveEntry(entry?.id);
  const attach = useAttachToEntry();
  const names = useNames();

  // Opened from an expense to record what came back: what is still owed is the likely amount.
  const owed = entry === undefined ? owedOn(paysBack) : undefined;
  const [amount, setAmount] = useState(entry ? centsToInput(entry.amountCents) : owed ? centsToInput(owed) : "");
  const [estimate, setEstimate] = useState(entry?.estimate ?? false);
  const [description, setDescription] = useState(
    entry?.description ?? (paysBack ? `Reimbursement: ${targetName(paysBack)}` : ""),
  );
  const [payee, setPayee] = useState(entry?.payee ?? "");
  const opensOn = useDefaultDate();
  const [date, setDate] = useState(entry?.date ?? firstDate(period, opensOn.date));
  const [accountId, setAccountId] = useState<string | null>(
    entry
      ? String(entry.accountId)
      : startOn !== undefined
        ? String(startOn)
        : // What is paid back says which account its money comes back into.
          paysBack && intoOf(paysBack) !== null
          ? String(intoOf(paysBack))
          : period?.primaryAccountId != null
            ? String(period.primaryAccountId)
            : null,
  );
  // Paid back out of one of her own accounts, like an FSA, it is a transfer into the account it comes back into.
  const [toAccountId, setToAccountId] = useState<string | null>(
    entry?.toAccountId != null
      ? String(entry.toAccountId)
      : kind === "transfer" && paysBack && intoOf(paysBack) !== null
        ? String(intoOf(paysBack))
        : null,
  );
  const [categoryId, setCategoryId] = useState<number | null>(entry?.categoryId ?? null);
  const [tagIds, setTagIds] = useState<number[]>(entry?.tagIds ?? []);
  const [note, setNote] = useState(entry?.note ?? "");
  const [checkNumber, setCheckNumber] = useState(entry?.checkNumber ?? "");
  // Where money going out comes from: a bucket's id, NOT_IN_A_BUCKET, or "" until it is chosen.
  const [chosenFrom, setChosenFrom] = useState<string>();
  const [toBeRepaid, setToBeRepaid] = useState(false);
  const [paysBackId, setPaysBackId] = useState<string | null>(
    entry ? choiceOf(entry.reimbursesEntryId, entry.reimbursesPaymentId) : paysBack ? choiceFor(paysBack) : null,
  );
  // Receipts chosen for an entry that does not exist yet: sent once it does.
  const [files, setFiles] = useState<File[]>([]);
  // Set when the entry was added but its files were not: the form then only says so, and closes.
  const [notAttached, setNotAttached] = useState<string>();

  // What money coming in, or a transfer out of an account like an FSA (Rachel, 2026-10-25), could be paying back: expenses
  // still owed, and whichever one it already pays back.
  const paysBackKind = kind === "money_in" || kind === "transfer";
  const owedExpenses = useOwedExpenses(paysBackKind, paysBackId, paysBack);

  // Only money arriving can be set aside, and only in the buckets of the account it arrives in.
  const arrivesIn = kind === "money_in" ? accountId : kind === "transfer" ? toAccountId : null;
  const openBuckets = (useBuckets().data ?? []).filter((bucket) => !bucket.archived);
  const buckets = openBuckets.filter((bucket) => String(bucket.accountId) === arrivesIn);
  // Money leaving comes out of a bucket of the account it leaves, or out of what is in none.
  const payingBuckets = kind === "money_in" ? [] : openBuckets.filter((bucket) => String(bucket.accountId) === accountId);
  // An entry being changed already says where it came from; a new one starts on money in no bucket
  // (Rachel, 2026-10-20: "default the dropdown on an account with buckets to \"money not in a bucket\"").
  const savedFrom = entry
    ? String(entry.bucketSplits.find((split) => payingBuckets.some((bucket) => bucket.id === split.bucketId))?.bucketId ?? NOT_IN_A_BUCKET)
    : NOT_IN_A_BUCKET;
  const asked = chosenFrom ?? savedFrom;
  // A bucket chosen before the account was changed no longer applies, so it goes back to money in no bucket.
  const comesFrom = payingBuckets.some((bucket) => String(bucket.id) === asked) ? asked : NOT_IN_A_BUCKET;

  // Where money arriving goes (Rachel, 2026-10-20: on the accounts' screen, the whole amount unless it is split).
  // The first line takes what the others leave; a saved entry's leftover, if any, is money in no bucket.
  // Until something is changed here, a saved entry's lines come from its splits into the buckets it arrives in (known
  // once the buckets have loaded).
  const [changedLines, setIntoLines] = useState<IntoLine[]>();
  const savedLines = ((): IntoLine[] => {
    const arriving = (entry?.bucketSplits ?? []).filter(
      (split) => split.amountCents > 0 && buckets.some((bucket) => bucket.id === split.bucketId),
    );
    const typed = arriving.map((split) => ({ bucket: String(split.bucketId), amount: centsToInput(split.amountCents) }));
    // All of it in buckets: the first of them takes the rest. Otherwise the rest was money in no bucket.
    if (arriving.length > 0 && arriving.reduce((sum, split) => sum + split.amountCents, 0) === entry?.amountCents) {
      return [{ bucket: typed[0]!.bucket, amount: "" }, ...typed.slice(1)];
    }
    return [{ bucket: NOT_IN_A_BUCKET, amount: "" }, ...typed];
  })();
  // A bucket no longer in the account it arrives in (the To account changed) is dropped; the first line then
  // goes back to money in no bucket.
  const intoLines = changedLines ?? savedLines;
  const inArriving = (line: IntoLine) => buckets.some((bucket) => String(bucket.id) === line.bucket);
  const shownLines: IntoLine[] = [
    intoLines[0] !== undefined && inArriving(intoLines[0]) ? intoLines[0] : { bucket: NOT_IN_A_BUCKET, amount: "" },
    ...intoLines.slice(1).filter(inArriving),
  ];
  const splitCents = shownLines.slice(1).reduce((sum, line) => sum + Math.max(0, parseDollars(line.amount) ?? 0), 0);


  // Archived accounts are left out, unless this entry already points at one.
  const accountOptions = accounts
    .filter((account) => !account.archived || [accountId, toAccountId].includes(String(account.id)))
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const amountCents = parseDollars(amount);
  // A check can only be written on a checking account, for money that leaves it.
  const paidFromChecking =
    kind !== "money_in" && accounts.some((account) => String(account.id) === accountId && account.type === "checking");

  // What money coming in can be said to pay back; nothing to ask when there is no such expense.
  const canPayBack = paysBackKind && (owedExpenses.length > 0 || paysBackId !== null);
  const adding = entry === undefined;
  const labelsTitle = kind === "money_out" ? "Paid to, tags and note" : "Category, tags and note";
  // Adding: two short steps everyone takes, and the rest behind "More options". Changing: one group at a
  // time, and the groups are the cards of the entry's page ("Details" holds what the Details card shows),
  // so a form opened from a card opens on that card's fields.
  const groups: FormGroup<EntryPart>[] = adding
    ? [
        { label: "Amount", parts: ["amount"] },
        { label: "When and where", parts: ["where", "from", "check"] },
        { label: labelsTitle, parts: ["payee", "category", "tags"] },
        { label: "Receipt and paying back", parts: ["extras"] },
      ]
    : [
        { label: "Amount", parts: ["amount"] },
        { label: "When and where", parts: ["where", "payee"] },
        { label: "Details", parts: ["category", "tags", "check"] },
        // Those that are only there once the buckets and expenses have loaded come after the ones a
        // card opens on, so the group a form opened on does not shift under it.
        ...(payingBuckets.length > 0 ? [{ label: BUCKET_TITLE, parts: ["from"] } as const] : []),
        ...(canPayBack ? [{ label: "Pays back", parts: ["extras"] } as const] : []),
        { label: "Delete", parts: ["delete"] },
      ];
  const parts = useFormParts<EntryPart>({ groups, adding, required: 2, startAt });
  const step = parts.step;
  useEffect(() => {
    onStep?.(step);
    // Only a change of screen is news; the callback itself may be a new function on every render.
  }, [step]);


  /** Turns the form into a request, or names what still needs attention and the screen it is on. */
  const toInput = (): EntryInput | PartProblem<EntryPart> => {
    if (amountCents === null || amountCents < 0) {
      return { part: "amount", problem: AMOUNT_HELP };
    }
    if (description.trim() === "") {
      return { part: "amount", problem: "Please say what this is, in a word or two." };
    }
    if (date === "") {
      return { part: "where", problem: "Please choose the date." };
    }
    if (accountId === null) {
      return {
        part: "where",
        problem: kind === "money_in" ? "Please choose the account this money goes into." : "Please choose the account this money comes from.",
      };
    }
    if (kind === "transfer" && toAccountId === null) {
      return { part: "where", problem: "Please choose the account this money goes to." };
    }
    if (kind === "transfer" && toAccountId === accountId) {
      // Out of a bucket into the rest of the same account is a move inside it, not a transfer (Rachel, 2026-10-20).
      return {
        part: "where",
        problem:
          payingBuckets.length > 0
            ? "That's the same account. To move money out of one of its buckets, or pay yourself back from one, choose Out of a bucket from + Add item."
            : "Please choose two different accounts.",
      };
    }
    const bucketSplits: BucketSplit[] = [];
    if (buckets.length > 0) {
      for (const [index, line] of shownLines.entries()) {
        if (index === 0) {
          continue;
        }
        const cents = parseDollars(line.amount);
        if (cents === null || cents <= 0) {
          const name = buckets.find((bucket) => String(bucket.id) === line.bucket)?.name ?? "that bucket";
          return { part: "where", problem: `Please enter how much goes into ${name}, like 50.00, or remove it.` };
        }
        bucketSplits.push({ bucketId: Number(line.bucket), amountCents: cents });
      }
      if (splitCents > amountCents) {
        return { part: "where", problem: "The buckets add up to more than the amount itself. Please lower one of them." };
      }
      // The first line takes the rest: into its bucket, or left as money in no bucket.
      if (shownLines[0]!.bucket !== NOT_IN_A_BUCKET && amountCents - splitCents > 0) {
        bucketSplits.unshift({ bucketId: Number(shownLines[0]!.bucket), amountCents: amountCents - splitCents });
      }
    }
    if (payingBuckets.length > 0) {
      if (comesFrom !== NOT_IN_A_BUCKET && amountCents > 0) {
        bucketSplits.push({ bucketId: Number(comesFrom), amountCents });
      }
    }
    const unfit = problemWith(files, 0, DEFAULT_MAX_BYTES, MAX_FILES);
    if (unfit !== null) {
      return { part: "extras", problem: unfit };
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
      ...paysBackIds(paysBackKind ? paysBackId : null),
      // Only said when adding: from then on where it stands is kept on the expense's own page.
      ...(entry === undefined && kind === "money_out" && toBeRepaid ? { reimbursementStatus: "to_submit" as const } : {}),
    };
  };

  const check = (): PartProblem<EntryPart> | null => {
    const input = toInput();
    return "part" in input ? input : null;
  };

  const send = () => {
    const input = toInput();
    if ("part" in input) {
      // Checked a moment ago by the same rules; nothing to send if it somehow no longer passes.
      return;
    }
    save.mutate(input, {
      onSuccess: (saved) => {
        if (files.length === 0) {
          onClose();
          return;
        }
        attach.mutate({ entryId: saved.id, files }, { onSuccess: onClose, onError: (error) => setNotAttached(error.message) });
      },
    });
  };

  if (notAttached !== undefined) {
    // Nothing is lost and nothing should be sent twice: the entry is in, only its files are not.
    return (
      <div className="space-y-4">
        <p role="alert" className="font-medium">
          {description} was added, but {files.length === 1 ? "its file" : "its files"} could not be attached.
        </p>
        <p className="text-danger">{notAttached}</p>
        <p className="text-muted">You can attach {files.length === 1 ? "it" : "them"} from its page, under Expenses &amp; receipts.</p>
        <div className="flex justify-end">
          <button type="button" data-autofocus onClick={onClose} className={BUTTON_PRIMARY}>
            Close
          </button>
        </div>
      </div>
    );
  }

  // Asked on one screen when adding and another when changing, so each is made once and placed where it shows.
  const payeeField = kind === "money_out" && (
    <TextPicker
      label="Paid to (optional)"
      noun="payee"
      placeholder="Type to search or add new"
      suggestions={names.data?.payees ?? []}
      value={payee}
      onChange={setPayee}
    />
  );
  const checkField = paidFromChecking && (
    <div>
      <label htmlFor={`${id}-check-number`} className={LABEL}>
        {/* Beside the note on the Details screen, where half a phone's width has no room for "(optional)". */}
        {parts.shows("tags") ? "Check number" : "Check number (optional)"}
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
  );

  const preview = entry ? null : previewEnding(period, accounts, kind, date, accountId, amountCents);
  const failure = parts.problem ?? save.error?.message;
  const saving = save.isPending || attach.isPending;

  return (
    <form ref={parts.ref} onSubmit={(event: FormEvent) => parts.submit(event, check, send)} className="space-y-4">
      {parts.header()}

      {parts.shows("amount") && (
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
        </FormSection>
      )}

      {(parts.shows("where") || parts.shows("from")) && (
        <FormSection title={parts.shows("where") ? SECTION_TITLES[kind] : BUCKET_TITLE}>
          {parts.shows("where") && (
            <div className="grid grid-cols-2 gap-x-3 gap-y-4 sm:gap-x-4">
              <div>
                <label htmlFor={`${id}-date`} className={LABEL}>
                  Date
                </label>
                <input
                  id={`${id}-date`}
                  type="date"
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                  required
                  className={INPUT}
                />
                {!entry && <DefaultDateHint value={date} />}
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
              {parts.shows("payee") && payeeField}
              {parts.shows("check") && checkField}
              {buckets.length > 0 && (
                <IntoBuckets
                  id={id}
                  buckets={buckets}
                  lines={shownLines}
                  onChange={setIntoLines}
                  amountCents={amountCents === null || amountCents < 0 ? null : amountCents}
                  splitCents={splitCents}
                />
              )}
            </div>
          )}

          {parts.shows("from") && payingBuckets.length > 0 && (
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
                <option value={NOT_IN_A_BUCKET}>Money that is not in a bucket</option>
                {payingBuckets.map((bucket) => (
                  <option key={bucket.id} value={bucket.id}>
                    {bucket.name} (holds {formatCents(bucket.balanceCents)} today)
                  </option>
                ))}
              </select>
              <p className={HINT}>
                {comesFrom !== NOT_IN_A_BUCKET
                  ? "The bucket and the account both go down by this amount."
                  : "This account has buckets, so say whether this is paid out of one of them."}
              </p>
            </div>
          )}
        </FormSection>
      )}

      {parts.shows("tags") && (
        <FormSection title={adding ? labelsTitle : "Details"}>
          {/* Two side by side only when there are two: a lone picker gets the full width. */}
          <div className={`grid grid-cols-1 gap-4 ${parts.shows("payee") && payeeField ? "sm:grid-cols-2" : ""}`}>
            {parts.shows("payee") && payeeField}
            <CategoryField categories={categories} value={categoryId} onChange={setCategoryId} />
          </div>
          <TagsField tags={tags} value={tagIds} onChange={setTagIds} />
          <div className={`grid gap-4 ${parts.shows("check") && checkField ? "grid-cols-2" : "grid-cols-1"}`}>
            <div>
              <label htmlFor={`${id}-note`} className={LABEL}>
                Note (optional)
              </label>
              <input
                id={`${id}-note`}
                value={note}
                onChange={(event) => setNote(event.target.value)}
                maxLength={500}
                autoComplete="off"
                className={INPUT}
              />
            </div>
            {parts.shows("check") && checkField}
          </div>
        </FormSection>
      )}

      {parts.shows("extras") && (
        <FormSection title={entry === undefined ? "Receipt and paying back" : "Pays back"}>
          {/* The receipt first: the screen opens with the keyboard on its first field, and a picker there would open its list. */}
          {entry === undefined && (
            <div>
              <label htmlFor={`${id}-files`} className={LABEL}>
                Receipt or document (optional)
              </label>
              <input
                id={`${id}-files`}
                type="file"
                multiple
                accept={ACCEPTED_FILES}
                onChange={(event) => setFiles([...(event.target.files ?? [])])}
                className="block w-full min-w-0 text-sm file:mr-3 file:min-h-11 file:cursor-pointer file:rounded-lg file:border file:border-solid file:border-line file:bg-surface file:px-4 file:font-semibold file:text-ink"
              />
              {/* The chooser forgets what it held when this screen is left, so say what is still coming along. */}
              <p className={HINT}>
                {files.length > 0
                  ? `Chosen: ${files.map((file) => file.name).join(", ")}. Attached when this is added.`
                  : "PDFs and JPEG or PNG pictures. Kept on this PC only."}
              </p>
            </div>
          )}
          {entry === undefined && kind === "money_out" && (
            <label className="flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                checked={toBeRepaid}
                onChange={(event) => setToBeRepaid(event.target.checked)}
                className="size-5 accent-(--accent)"
              />
              <span>I'll be paid back for this</span>
            </label>
          )}
          {canPayBack && (
            <Picker
              label="Pays back an expense (optional)"
              noun="expense"
              placeholder="Choose the expense this pays back"
              options={owedExpenses}
              value={paysBackId}
              onChange={setPaysBackId}
              clearable
            />
          )}
        </FormSection>
      )}


      {entry && parts.shows("delete") && <DeleteEntry entry={entry} onDeleted={onClose} />}

      {failure && (
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}

      {/* Announced as it changes, so the effect of what is being typed is known before saving. */}
      {preview && (
        <p aria-live="polite" className="text-sm text-muted">
          Projected ending for {preview.accountName} this period becomes{" "}
          <span data-amount className="font-semibold text-ink tabular-nums">
            {formatCents(preview.endingCents)}
          </span>
        </p>
      )}
      {parts.buttons({ onClose, pending: saving, saveLabel: entry ? "Save changes" : "Add" })}
    </form>
  );
}

/** The screens of the form (see `useFormParts`). */
export type EntryPart = "amount" | "where" | "from" | "check" | "payee" | "category" | "tags" | "extras" | "delete";

/** The heading over the one question an account with buckets adds. */
const BUCKET_TITLE = "Which bucket it comes out of";

/** As many files as one entry can have (the backend's own limit). */
const MAX_FILES = 20;

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

/**
 * The day a new entry opens on. Looking at another pay period, that period's first day: the entry is
 * meant for the period on screen. Otherwise the day new things start on, which is today unless
 * Settings says another.
 */
function firstDate(period: PeriodDetail | undefined, startOn: string): string {
  const today = todayIso();
  return period === undefined || (today >= period.start && today <= period.end) ? startOn : period.start;
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
    <FormSection title="Delete">
      <p className="text-muted">It is taken out of every total at once. Undo brings it back.</p>
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
    </FormSection>
  );
}
