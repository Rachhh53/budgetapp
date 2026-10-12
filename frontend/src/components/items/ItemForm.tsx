import { CalendarClock } from "lucide-react";
import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router";
import { type Account, holdsCash } from "../../api/accounts";
import { useBuckets, useSaveBucket } from "../../api/buckets";
import type { Category, Tag } from "../../api/categories";
import { usePayPeriods } from "../../api/income";
import {
  type AmountMode,
  CARD_PAYMENT_MODES,
  type CardPaymentMode,
  type Item,
  ITEM_FREQUENCIES,
  ITEM_KINDS,
  type ItemFrequency,
  type ItemInput,
  type ItemKind,
  type Placement,
  useDeleteItem,
  useReplaceItem,
  useSaveItem,
} from "../../api/items";
import { formatDate, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { firstUnusedColor } from "../ColorPicker";
import { BucketForm } from "../buckets/BucketForm";
import { ConfirmDelete } from "../ConfirmDelete";
import { DaySelect } from "../DaySelect";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { Segmented } from "../Segmented";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { PAYMENT_MODE_HELP, PAYMENT_MODE_LABELS } from "../cards/labels";
import { ITEM_FREQUENCY_LABELS, KIND_HELP, KIND_ICONS, KIND_LABELS, MONTH_NAMES } from "./labels";

const KIND_OPTIONS = ITEM_KINDS.map((kind) => ({ value: kind, label: KIND_LABELS[kind], icon: KIND_ICONS[kind] }));
/** What a new item can be. Setting money aside in a bucket is set up on the bucket itself, so it is not offered. */
const KINDS_TO_ADD = KIND_OPTIONS.filter((option) => option.value !== "bucket_move");

const AMOUNT_MODE_OPTIONS: readonly { value: AmountMode; label: string }[] = [
  { value: "per_occurrence", label: "Each time" },
  { value: "annual", label: "Yearly total" },
];

const PLACEMENT_OPTIONS: readonly { value: Placement; label: string }[] = [
  { value: "due_period", label: "The period it's due in" },
  { value: "one_early", label: "One period early" },
];

/** How far into a pay period a budget can be dated from the list; payday itself is 0. */
const DAYS_AFTER_PAYDAY = Array.from({ length: 15 }, (_, days) => days);

/** Months apart on offer for "every few months". */
const MONTHS_APART = [2, 3, 4, 6];

/**
 * The parts of an item that can be opened on their own from its page, each from the card that shows it:
 * what it is (type, name, archiving, deleting), its amount, the accounts it moves between, its schedule,
 * and how it is filed (category, tags, note).
 */
export type ItemPart = "item" | "amount" | "accounts" | "schedule" | "details";

const PART_TITLES: Record<ItemPart, string | null> = {
  item: null,
  amount: "Amount",
  accounts: "Paid from",
  schedule: "Schedule",
  details: "Details",
};

/**
 * A new item is asked for a screen at a time, so no dialog is taller than the window, on a laptop
 * or a phone, with or without the + Add form's own switch above it: what it is, its amount, the
 * accounts, the schedule, then how it is filed. Each step is one of the parts an existing item is
 * edited by.
 */
const STEPS: readonly (readonly ItemPart[])[] = [["item"], ["amount"], ["accounts"], ["schedule"], ["details"]];

/** Something still to put right, and the part of the form it is in. */
interface Problem {
  problem: string;
  part: ItemPart;
}

interface ItemFormProps {
  /** The item being changed; leave out to add a new one. */
  item?: Item;
  accounts: readonly Account[];
  categories: readonly Category[];
  tags: readonly Tag[];
  onClose: () => void;
  /** Where to go once the item is deleted, when closing the form is not enough (its own page is gone). */
  onDeleted?: () => void;
  /** Points at the item's page for pausing and its amount history. For a form opened anywhere but that page. */
  pageLink?: boolean;
  /**
   * Show only this part of an existing item. Everything else about it is sent back as it was, so
   * saving one part changes nothing in the others. Left out, the whole form shows.
   */
  part?: ItemPart;
  /**
   * A new item that is already known to be something: a card's payment added from the card's page
   * starts as a payment into that card, due on its due day. Everything can still be changed.
   */
  starting?: { kind: ItemKind; name: string; payToAccountId: number; day1?: number; cardPaymentMode?: CardPaymentMode };
  /** Told which screen of a new item is showing (0 is the first), so whatever frames the form can make room for it. */
  onStep?: (step: number) => void;
}

/**
 * Add or change a recurring item, in the same centered dialog as every other form. Everything chosen
 * from a list is a dropdown or a type-to-filter picker; a category or tag that does not
 * exist yet can be added from its picker without leaving the form.
 *
 * A change to an existing item applies from a date on ("this and all future"): earlier
 * occurrences keep what they had.
 *
 * The form holds only what is typed and saved. What an item is doing — its next date, pausing
 * it, the amounts it has had — is on its own page (`RecurringItemDetailPage`), which keeps this
 * short enough to take in.
 *
 * A bucket's own recurring amount is the exception: it belongs to its bucket, so editing it
 * from anywhere opens the bucket's form, the one place it is changed or stopped.
 */
export function ItemForm(props: ItemFormProps) {
  const buckets = useBuckets().data;
  const bucket = props.item?.kind === "bucket_move" ? buckets?.find((candidate) => candidate.id === props.item?.bucketId) : undefined;
  const account = bucket && props.accounts.find((candidate) => candidate.id === bucket.accountId);
  if (bucket && account && buckets) {
    return <BucketForm account={account} bucket={bucket} buckets={buckets} forAmount onClose={props.onClose} />;
  }
  return (
    <Modal
      title={props.item ? [`Edit ${props.item.name}`, ...(props.part && PART_TITLES[props.part] ? [PART_TITLES[props.part]] : [])].join(" · ") : "Add a recurring item"}
      icon={props.item ? KIND_ICONS[props.item.kind] : CalendarClock}
      onClose={props.onClose}
    >
      <ItemFormBody {...props} />
    </Modal>
  );
}

/** The choice that takes nothing out of any bucket. Not a number, so it can never be mistaken for a bucket's id. */
const NOT_IN_A_BUCKET = "none";

/** The form itself, without a dialog around it, so the + Add item form can show it too. */
export function ItemFormBody({ item, accounts, categories, tags, onClose, onDeleted, pageLink, part, starting, onStep }: ItemFormProps) {
  const id = useId();
  const save = useSaveItem(item?.id);
  const replace = useReplaceItem(item?.id);
  const schedule = item?.schedule;

  const [kind, setKind] = useState<ItemKind>(item?.kind ?? starting?.kind ?? "bill");
  const [name, setName] = useState(item?.name ?? starting?.name ?? "");
  const [categoryId, setCategoryId] = useState<number | null>(item?.categoryId ?? null);
  const [payFrom, setPayFrom] = useState<string | null>(item ? String(item.payFromAccountId) : null);
  const [payTo, setPayTo] = useState<string | null>(
    item?.payToAccountId != null ? String(item.payToAccountId) : starting ? String(starting.payToAccountId) : null,
  );
  // How much a card payment pays: an amount of its own, or whatever the card works out (its statement balance, say).
  const [cardMode, setCardMode] = useState<CardPaymentMode>(item?.cardPaymentMode ?? starting?.cardPaymentMode ?? "fixed");
  const [amountMode, setAmountMode] = useState<AmountMode>(item?.amountMode ?? "per_occurrence");
  const [amount, setAmount] = useState(item ? centsToInput(item.amountCents) : "");
  const [estimate, setEstimate] = useState(item?.estimate ?? false);
  const [frequency, setFrequency] = useState<ItemFrequency>(schedule?.frequency ?? "monthly");
  const [day1, setDay1] = useState(schedule?.day1 ?? starting?.day1 ?? 1);
  const [day2, setDay2] = useState(schedule?.day2 ?? 15);
  const [month, setMonth] = useState(schedule?.month ?? 1);
  const [monthsApart, setMonthsApart] = useState(schedule?.interval && schedule.interval > 1 ? schedule.interval : 3);
  // `null` until chosen: a new item then starts where its kind of schedule usually does (see `startsOn`).
  const [chosenStart, setChosenStart] = useState<string | null>(schedule?.startDate ?? null);
  const [daysAfterPayday, setDaysAfterPayday] = useState(item?.daysAfterPayday ?? 0);
  const [autopay, setAutopay] = useState(item?.autopay ?? false);
  const [placement, setPlacement] = useState<Placement>(item?.placement ?? "due_period");
  const [tagIds, setTagIds] = useState<number[]>(item?.tagIds ?? []);
  const [note, setNote] = useState(item?.note ?? "");
  const [archived, setArchived] = useState(item?.archived ?? false);
  const [bucketId, setBucketId] = useState<string | null>(item?.bucketId != null ? String(item.bucketId) : null);
  const [saveUpBucketId, setSaveUpBucketId] = useState<string | null>(
    item?.saveUpBucketId != null ? String(item.saveUpBucketId) : null,
  );
  // Where the money comes out of: a bucket's id, NOT_IN_A_BUCKET, or "" until it is chosen.
  const [chosenFrom, setChosenFrom] = useState<string>();
  const [problem, setProblem] = useState<string>();
  // Which screen of a new item is showing. An existing item is edited a part at a time instead.
  const [step, setStep] = useState(0);
  const formRef = useRef<HTMLFormElement>(null);
  // A new step starts at its top, for the keyboard and for screen readers. Not on the first one: the dialog places focus itself.
  useEffect(() => {
    if (step > 0) {
      formRef.current?.focus();
    }
    onStep?.(step);
    // Only a change of step is news; the callback itself may be a new function on every render.
  }, [step]);
  const addBucket = useSaveBucket();
  const startOn = useDefaultDate();
  const currentPeriod = usePayPeriods().data?.periods.find((period) => period.current);

  // Archived buckets are left out, unless this item already points at one.
  const everyBucket = useBuckets().data ?? [];
  const openBuckets = everyBucket.filter(
    (bucket) => !bucket.archived || [bucketId, saveUpBucketId, String(item?.fromBucketId)].includes(String(bucket.id)),
  );
  const bucketOptionsIn = (accountId: string | null) =>
    openBuckets
      .filter((bucket) => String(bucket.accountId) === accountId)
      .map((bucket) => ({ id: String(bucket.id), label: bucket.name, color: bucket.color }));
  // A bucket move can go into any bucket, so each one says which account it is in.
  const everyBucketOption = openBuckets.map((bucket) => ({
    id: String(bucket.id),
    label: `${bucket.name} · ${accounts.find((account) => account.id === bucket.accountId)?.name ?? "an account"}`,
    color: bucket.color,
  }));

  const isBucketMove = kind === "bucket_move";
  const canSaveUp = kind === "bill" || kind === "budget";
  const transferBuckets = kind === "transfer" ? bucketOptionsIn(payTo) : [];
  // Only an account that can hold buckets can be saved up in.
  const saveUpAccount = canSaveUp ? accounts.find((account) => String(account.id) === payFrom && holdsCash(account.type)) : undefined;
  const saveUpBuckets = saveUpAccount ? bucketOptionsIn(payFrom) : [];
  // A bucket chosen for another account, or another kind of item, no longer applies.
  const chosenTransferBucket = transferBuckets.some((option) => option.id === bucketId) ? bucketId : null;
  const chosenSaveUpBucket = saveUpBuckets.some((option) => option.id === saveUpBucketId) ? saveUpBucketId : null;
  // Money leaving an account that has buckets comes out of one of them, or out of what is in none.
  // A bill being saved up for is already paid out of its save-up bucket, so it is not asked.
  const payingBuckets = isBucketMove || chosenSaveUpBucket !== null ? [] : everyBucket.filter(
    (bucket) => String(bucket.accountId) === payFrom && (!bucket.archived || bucket.id === item?.fromBucketId),
  );
  // An item being changed already says where it comes from; a new one has to be told.
  const askedFrom = chosenFrom ?? (item ? String(item.fromBucketId ?? NOT_IN_A_BUCKET) : "");
  // A bucket chosen before the account was changed no longer applies.
  const comesFrom = askedFrom === NOT_IN_A_BUCKET || payingBuckets.some((bucket) => String(bucket.id) === askedFrom) ? askedFrom : "";

  const changeKind = (next: ItemKind) => {
    setKind(next);
    // Setting money aside is usually done with every paycheck, so start a new bucket move there.
    if (next === "bucket_move" && !item) {
      setFrequency("every_pay_period");
    }
  };

  const movesMoney = kind === "transfer" || kind === "card_payment";
  // A payment that follows its card has no amount to type: the card works it out each time.
  const followsCard = kind === "card_payment" && cardMode !== "fixed";
  const everyPaycheck = frequency === "every_pay_period";
  // These repeat from a date the user gives; the rest name their own day of the month or year.
  const countsFromADate = frequency === "weekly" || frequency === "biweekly" || frequency === "one_time";
  // Where a new item starts until the user says otherwise. One that names its own day of the
  // month or year starts in January, so this year's occurrences exist; anything dated from
  // a day starts on the day new things start on (today, unless Settings says another). Money set aside in a bucket never reaches back on its own: it would
  // fill the bucket with months of moves that never happened.
  const startsOn = chosenStart ?? (countsFromADate || everyPaycheck || isBucketMove ? startOn.date : `${startOn.date.slice(0, 4)}-01-01`);

  // Archived accounts are left out, unless this item already points at one.
  const offered = accounts.filter((account) => !account.archived || [payFrom, payTo].includes(String(account.id)));
  const accountOptions = offered.map((account) => ({ id: String(account.id), label: account.name, color: account.color }));
  const cardOptions = offered
    .filter((account) => account.type === "credit_card")
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  /** Turns the form into a request, or explains what still needs attention. */
  const toInput = (): ItemInput | Problem => {
    const amountCents = followsCard ? 0 : parseDollars(amount);
    if (amountCents === null || amountCents < 0) {
      return { part: "amount", problem: AMOUNT_HELP };
    }
    const moveInto = isBucketMove ? openBuckets.find((bucket) => String(bucket.id) === bucketId) : undefined;
    if (isBucketMove && moveInto === undefined) {
      return { part: "accounts", problem: "Please choose the bucket this money goes into." };
    }
    // A bucket move stays inside the bucket's own account.
    const paidFrom = moveInto !== undefined ? String(moveInto.accountId) : payFrom;
    if (paidFrom === null) {
      return {
        part: "accounts",
        problem: kind === "transfer" ? "Please choose the account this money comes from." : "Please choose the account this is paid from.",
      };
    }
    if (movesMoney && payTo === null) {
      return {
        part: "accounts",
        problem: kind === "card_payment" ? "Please choose the card this payment goes to." : "Please choose the account this money goes to.",
      };
    }
    if (movesMoney && payTo === payFrom) {
      return { part: "accounts", problem: "Please choose two different accounts." };
    }
    if (payingBuckets.length > 0 && comesFrom === "") {
      return { part: "accounts", problem: "Please say where this comes out of: one of the buckets, or money that is not in a bucket." };
    }
    const byDayOfMonth = frequency === "semi_monthly" || frequency === "monthly" || frequency === "every_n_months";
    return {
      name,
      kind,
      note: note.trim() === "" ? null : note,
      archived,
      tagIds,
      amountCents,
      amountMode: followsCard ? "per_occurrence" : amountMode,
      estimate: followsCard || estimate,
      // Only a card payment says how much it pays; nothing else has the choice.
      ...(kind === "card_payment" ? { cardPaymentMode: cardMode } : {}),
      categoryId,
      payFromAccountId: Number(paidFrom),
      payToAccountId: movesMoney && payTo !== null ? Number(payTo) : null,
      // A budget is money set aside to spend, not something that is paid automatically;
      // a bucket move needs nobody to pay anything.
      autopay: kind === "budget" ? false : isBucketMove ? true : autopay,
      placement: everyPaycheck ? "due_period" : placement,
      schedule: {
        frequency,
        interval: frequency === "every_n_months" ? monthsApart : null,
        day1: byDayOfMonth || frequency === "yearly" ? day1 : null,
        day2: frequency === "semi_monthly" ? day2 : null,
        month: frequency === "yearly" ? month : null,
        startDate: startsOn,
        endDate: schedule?.endDate ?? null,
        weekendRule: schedule?.weekendRule ?? "none",
      },
      daysAfterPayday: everyPaycheck ? daysAfterPayday : 0,
      bucketId: moveInto !== undefined ? moveInto.id : chosenTransferBucket !== null ? Number(chosenTransferBucket) : null,
      saveUpBucketId: chosenSaveUpBucket !== null ? Number(chosenSaveUpBucket) : null,
      fromBucketId: payingBuckets.length > 0 && comesFrom !== NOT_IN_A_BUCKET ? Number(comesFrom) : null,
      // A change is dated the day it is made; the backend reaches further back only for an earlier start.
      ...(item ? { effectiveFrom: todayIso() } : {}),
    };
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const input = toInput();
    const lastStep = item !== undefined || step === STEPS.length - 1;
    if ("problem" in input) {
      // On the way through a new item, only what this screen asks is held against it; the rest is still to come.
      if (lastStep || STEPS[step]?.includes(input.part)) {
        if (!item) {
          setStep(STEPS.findIndex((parts) => parts.includes(input.part)));
        }
        setProblem(input.problem);
        return;
      }
    }
    setProblem(undefined);
    if (!lastStep) {
      setStep(step + 1);
      return;
    }
    if (!("problem" in input)) {
      save.mutate(input, { onSuccess: onClose });
    }
  };

  /** Archives this item and adds the form as a new one from today, leaving the old one's past alone. */
  const addAsNew = () => {
    const input = toInput();
    if ("problem" in input) {
      setProblem(input.problem);
      return;
    }
    setProblem(undefined);
    // A new item: nothing to date a change from, and not archived whatever the old one was.
    const { effectiveFrom: _unused, ...fresh } = input;
    replace.mutate({ ...fresh, archived: false }, { onSuccess: onClose });
  };

  const kindChanged = item !== undefined && kind !== item.kind;
  const failure = problem ?? save.error?.message ?? replace.error?.message;
  // A different type can need a second account (a transfer's "To"), so the accounts come along with it.
  const shows = (asked: ItemPart) =>
    item === undefined
      ? (STEPS[step]?.includes(asked) ?? false)
      : part === undefined || part === asked || (asked === "accounts" && part === "item" && kindChanged);

  return (
    <>
      <form
        ref={formRef}
        onSubmit={submit}
        // A picker opens its list and clears its text when focused. Where one is the first field, the dialog
        // would open with the choice already made hidden, so focus rests on the form instead.
        data-autofocus={part === "details" || part === "accounts" ? true : undefined}
        tabIndex={-1}
        className="space-y-4 outline-none"
      >
        {!item && (
          <p role="status" className="text-sm font-semibold text-muted">
            Step {step + 1} of {STEPS.length}
          </p>
        )}
        {shows("item") && (
          <Segmented legend="Type" options={item?.kind === "bucket_move" ? KIND_OPTIONS : KINDS_TO_ADD} value={kind} onChange={changeKind} />
        )}
        {shows("item") && <p className="text-sm text-muted">{KIND_HELP[kind]}</p>}

        {kindChanged && (
          // An item is one kind for its whole life, so this cannot apply "from today on" like other changes.
          <div role="status" className="rounded-lg border-l-4 border-accent bg-bg px-4 py-3">
            <p className="font-semibold">
              Changing the type changes {item.name} all the way back to when it started.
            </p>
            <p className="mt-1 text-muted">
              Its earlier dates will count as a {KIND_LABELS[kind].toLowerCase()} too, between the accounts you choose here. The dates and
              amounts in its history stay as they are.
            </p>
            <p className="mt-2 text-muted">
              To leave its past alone, archive it and add this as a new item that starts today.
            </p>
            <button type="button" onClick={addAsNew} disabled={replace.isPending || save.isPending} className={`${BUTTON_SECONDARY} mt-2`}>
              {replace.isPending ? "Adding…" : "Archive this and add a new item"}
            </button>
          </div>
        )}

        {shows("amount") && (
          <FormSection title="Amount">
            {kind === "card_payment" && (
              <div>
                <label htmlFor={`${id}-card-mode`} className={LABEL}>
                  How much it pays
                </label>
                <select
                  id={`${id}-card-mode`}
                  value={cardMode}
                  onChange={(event) => setCardMode(event.target.value as CardPaymentMode)}
                  className={INPUT}
                >
                  {CARD_PAYMENT_MODES.map((choice) => (
                    <option key={choice} value={choice}>
                      {PAYMENT_MODE_LABELS[choice]}
                    </option>
                  ))}
                </select>
                <p className={HINT}>{PAYMENT_MODE_HELP[cardMode]}</p>
              </div>
            )}
            {!followsCard && (
              <>
                <div>
                  <MoneyField
                    label={amountMode === "annual" ? "Per year" : "Amount each time"}
                    value={amount}
                    onChange={setAmount}
                    hint={amountMode === "annual" ? "Shared out evenly over its schedule." : undefined}
                    required
                    hero
                  />
                </div>
                {/* Side by side where there is room, so the amount is one glance, not three rows. */}
                <div className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2 sm:items-end">
                  <Segmented legend="How you enter the amount" options={AMOUNT_MODE_OPTIONS} value={amountMode} onChange={setAmountMode} />
                  <label className="flex min-h-11 items-center gap-3">
                    <input
                      type="checkbox"
                      checked={estimate}
                      onChange={(event) => setEstimate(event.target.checked)}
                      className="size-5 accent-(--accent)"
                    />
                    <span>It's an estimate</span>
                  </label>
                </div>
              </>
            )}
          </FormSection>
        )}

        {(shows("item") || shows("details")) && (
          <FormSection title={shows("item") ? "What it is" : "Category"}>
            {shows("item") && (
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
                  placeholder={kind === "budget" ? "e.g. Groceries" : isBucketMove ? "e.g. Holiday gift fund" : "e.g. Electric"}
                  className={INPUT}
                />
              </div>
            )}
            {shows("details") && <CategoryField categories={categories} value={categoryId} onChange={setCategoryId} />}
          </FormSection>
        )}

        {shows("accounts") && (
          <FormSection title={isBucketMove ? "Where it goes" : movesMoney ? "From and to" : "Where it's paid from"}>
            {/* Two side by side only when there are two: a lone picker gets the full width. */}
            <div className={`grid grid-cols-1 gap-4 ${movesMoney ? "sm:grid-cols-2" : ""}`}>
              {isBucketMove ? (
                <div>
                  <Picker
                    label="Into bucket"
                    noun="bucket"
                    placeholder="Choose or type a bucket"
                    options={everyBucketOption}
                    value={bucketId}
                    onChange={setBucketId}
                  />
                  <p className={HINT}>
                    {everyBucketOption.length === 0
                      ? "There are no buckets yet. Add one on a checking or savings account's page first."
                      : "The money stays in its account. It is set aside, so less shows as available."}
                  </p>
                </div>
              ) : (
                <Picker
                  label={kind === "transfer" ? "From" : "Paid from"}
                  noun="account"
                  placeholder="Choose or type an account"
                  options={accountOptions}
                  value={payFrom}
                  onChange={setPayFrom}
                />
              )}
              {kind === "transfer" && (
                <div>
                  <Picker label="To" noun="account" placeholder="Choose or type an account" options={accountOptions} value={payTo} onChange={setPayTo} />
                  {accounts.some((account) => String(account.id) === payTo && account.type === "loan") && (
                    <p className={HINT}>
                      This is the loan's payment. Each one lowers what you owe, less the insurance and taxes entered on the loan's page.
                    </p>
                  )}
                </div>
              )}
              {transferBuckets.length > 0 && (
                <Picker
                  label="Into bucket (optional)"
                  noun="bucket"
                  placeholder="Leave unassigned"
                  options={transferBuckets}
                  value={chosenTransferBucket}
                  onChange={setBucketId}
                  clearable
                />
              )}
              {kind === "card_payment" && (
                <Picker label="Card" noun="card" placeholder="Choose or type a card" options={cardOptions} value={payTo} onChange={setPayTo} />
              )}
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
                      ? "Each time, the bucket and the account both go down. If the bucket runs out, the rest comes from money that is not in a bucket."
                      : "This account has buckets, so say whether this is paid out of one of them."}
                  </p>
                </div>
              )}
            </div>
            {kind !== "budget" && !isBucketMove && (
              <label className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={autopay}
                  onChange={(event) => setAutopay(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span>Autopay</span>
              </label>
            )}
          </FormSection>
        )}

        {shows("schedule") && (
          <FormSection title="Schedule">
            {/* How often beside the day or date that goes with it, on a phone too: it keeps the schedule to one screen. */}
            <div className="grid grid-cols-2 items-end gap-4">
              <div>
                <label htmlFor={`${id}-frequency`} className={LABEL}>
                  How often
                </label>
                <select
                  id={`${id}-frequency`}
                  value={frequency}
                  onChange={(event) => setFrequency(event.target.value as ItemFrequency)}
                  className={INPUT}
                >
                  {ITEM_FREQUENCIES.map((option) => (
                    <option key={option} value={option}>
                      {ITEM_FREQUENCY_LABELS[option]}
                    </option>
                  ))}
                </select>
              </div>

              {everyPaycheck && (
                <div>
                  <label htmlFor={`${id}-days`} className={LABEL}>
                    Dated
                  </label>
                  <select
                    id={`${id}-days`}
                    value={daysAfterPayday}
                    onChange={(event) => setDaysAfterPayday(Number(event.target.value))}
                    className={INPUT}
                  >
                    {[...new Set([...DAYS_AFTER_PAYDAY, daysAfterPayday])].map((days) => (
                      <option key={days} value={days}>
                        {days === 0 ? "On payday" : `${days} ${days === 1 ? "day" : "days"} after payday`}
                      </option>
                    ))}
                  </select>
                  <p className={HINT}>The day it comes out of the balance in each pay period.</p>
                </div>
              )}

              {countsFromADate && (
                <div>
                  <label htmlFor={`${id}-start`} className={LABEL}>
                    {frequency === "one_time" ? "Date" : "A date it falls on"}
                  </label>
                  <input
                    id={`${id}-start`}
                    type="date"
                    value={startsOn}
                    onChange={(event) => setChosenStart(event.target.value)}
                    required
                    className={INPUT}
                  />
                  {frequency !== "one_time" && <p className={HINT}>Any real due date works. It repeats from there.</p>}
                  {!item && <DefaultDateHint value={startsOn} />}
                </div>
              )}

              {(frequency === "semi_monthly" || frequency === "monthly" || frequency === "every_n_months" || frequency === "yearly") && (
                <>
                  {frequency === "yearly" && (
                    <div>
                      <label htmlFor={`${id}-month`} className={LABEL}>
                        Month
                      </label>
                      <select id={`${id}-month`} value={month} onChange={(event) => setMonth(Number(event.target.value))} className={INPUT}>
                        {MONTH_NAMES.map((monthName, index) => (
                          <option key={monthName} value={index + 1}>
                            {monthName}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}
                  {frequency === "every_n_months" && (
                    <div>
                      <label htmlFor={`${id}-interval`} className={LABEL}>
                        Months apart
                      </label>
                      <select
                        id={`${id}-interval`}
                        value={monthsApart}
                        onChange={(event) => setMonthsApart(Number(event.target.value))}
                        className={INPUT}
                      >
                        {[...new Set([...MONTHS_APART, monthsApart])].sort((a, b) => a - b).map((months) => (
                          <option key={months} value={months}>
                            Every {months} months
                          </option>
                        ))}
                      </select>
                    </div>
                  )}
                  <DaySelect
                    id={`${id}-day1`}
                    label={frequency === "semi_monthly" ? "First day of the month" : "Day of the month"}
                    value={day1}
                    onChange={setDay1}
                  />
                  {frequency === "semi_monthly" && <DaySelect id={`${id}-day2`} label="Second day of the month" value={day2} onChange={setDay2} />}
                </>
              )}
            </div>

            {/* The schedules above name their own first date; these need to be told when they begin. */}
            {!countsFromADate && (
              <div>
                <label htmlFor={`${id}-starts`} className={LABEL}>
                  Starts
                </label>
                <input
                  id={`${id}-starts`}
                  type="date"
                  value={startsOn}
                  onChange={(event) => setChosenStart(event.target.value)}
                  required
                  className={INPUT}
                />
                <p className={HINT}>
                  {everyPaycheck
                    ? "Today or earlier: it begins with the paycheck for the pay period that day falls in. A later day: it begins with the first paycheck on or after it, and this date moves to that payday when you save."
                    : "Nothing is scheduled before this day. Choose an earlier day to include earlier ones."}
                  {isBucketMove && " The bucket counts from then too."}
                </p>
                {!item && (everyPaycheck || isBucketMove) && <DefaultDateHint value={startsOn} />}
              </div>
            )}

            {!everyPaycheck && !isBucketMove && (
              <div>
                <Segmented legend="Which paycheck pays it" options={PLACEMENT_OPTIONS} value={placement} onChange={setPlacement} />
                <p className={HINT}>
                  Something due on payday that you pay by hand always comes out of the paycheck before, so the money is there in time.
                </p>
              </div>
            )}
          </FormSection>
        )}

        {/* A different thing from saving towards a goal, and easily mistaken for it: out of the way unless it is in use.
            Only there when there is something to choose (Rachel, 2026-10-12: opened on an account that cannot hold
            buckets, "More options" had no options, only an explanation). */}
        {canSaveUp && saveUpAccount !== undefined && shows("accounts") && (
          <details open={item?.saveUpBucketId != null} className="rounded-lg border border-line px-4 py-1">
            <summary className="min-h-11 cursor-pointer content-center font-medium">More options</summary>
            <div className="pb-3">
              <FormSection title="Saving up">
                <div>
                  <Picker
                    label="Save up for it in a bucket (optional)"
                    noun="bucket"
                    placeholder={saveUpBuckets.length === 0 ? "Type a name to add a bucket" : "Choose a bucket, or type a new one"}
                    options={saveUpBuckets}
                    value={chosenSaveUpBucket}
                    onChange={setSaveUpBucketId}
                    // A bucket that does not exist yet is added in place, in the account this is paid from.
                    onCreate={(text) =>
                      addBucket.mutate(
                        {
                          accountId: saveUpAccount.id,
                          name: text,
                          color: firstUnusedColor([...everyBucket].reverse().map((bucket) => bucket.color)),
                          goalCents: null,
                          goalDate: null,
                          note: null,
                          archived: false,
                          // From this paycheck's payday, as a bucket added on its own page would be.
                          openedOn: startOn.fromSetting ? startOn.date : (currentPeriod?.start ?? todayIso()),
                          startingCents: 0,
                        },
                        { onSuccess: (created) => setSaveUpBucketId(String(created.bucket.id)) },
                      )
                    }
                    clearable
                  />
                  {addBucket.error && (
                    <p role="alert" className="mt-1 text-sm text-danger">
                      {addBucket.error.message}
                    </p>
                  )}
                  <p className={HINT}>
                    This does not save anything extra. It spreads this {kind === "budget" ? "budget" : "bill"}'s own amount over the
                    paychecks before it is due, and holds each share in the bucket until then.
                    {item?.saveUpPerPaycheckCents != null && chosenSaveUpBucket !== null && (
                      <>
                        {" "}
                        The next paycheck sets aside{" "}
                        <span data-amount className="font-semibold text-ink tabular-nums">
                          {formatCents(item.saveUpPerPaycheckCents)}
                        </span>{" "}
                        of the <span data-amount>{formatCents(item.perOccurrenceCents)}</span>
                        {item.next ? ` due ${formatDate(item.next.dueDate)}` : ""}.
                      </>
                    )}
                  </p>
                  <p className={HINT}>
                    To put a set amount in a bucket every paycheck or month instead, open the bucket on its account's page and fill in
                    "Set aside".
                  </p>
                </div>
              </FormSection>
            </div>
          </details>
        )}

        {shows("details") && (
          <FormSection title="Tags and notes">
            <TagsField tags={tags} value={tagIds} onChange={setTagIds} />

            <div>
              <label htmlFor={`${id}-note`} className={LABEL}>
                Note (optional)
              </label>
              <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} autoComplete="off" className={INPUT} />
            </div>
          </FormSection>
        )}

        {item && (
          <div>
            {shows("item") && (
              <>
                <label className="flex min-h-11 items-center gap-3">
                  <input
                    type="checkbox"
                    checked={archived}
                    onChange={(event) => setArchived(event.target.checked)}
                    className="size-5 accent-(--accent)"
                  />
                  <span className="font-medium">Archived</span>
                </label>
                <p className={HINT}>An archived item keeps its history but stops coming up in your pay periods.</p>
              </>
            )}
            <p className={HINT}>
              Changes you save now apply from today on; anything dated before today keeps what it had.
              {shows("schedule") && " A different start date is the exception, and applies from that date."}
            </p>
            {pageLink && part === undefined && (
              <p className={HINT}>
                To pause it, or to see the amounts it has had,{" "}
                <Link to={`/recurring/${item.id}`} className="font-semibold text-accent-ink underline">
                  open {item.name}'s page
                </Link>
                .
              </p>
            )}
          </div>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          {!item && step > 0 ? (
            <button
              type="button"
              onClick={() => {
                setProblem(undefined);
                setStep(step - 1);
              }}
              className={BUTTON_SECONDARY}
            >
              Back
            </button>
          ) : (
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Cancel
            </button>
          )}
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : item ? "Save changes" : step < STEPS.length - 1 ? "Next" : "Add item"}
          </button>
        </div>
      </form>

      {item && shows("item") && (
        <div className="mt-6 border-t border-line pt-4">
          <DeleteItem item={item} onDeleted={onDeleted ?? onClose} />
        </div>
      )}
    </>
  );
}

function DeleteItem({ item, onDeleted }: { item: Item; onDeleted: () => void }) {
  const remove = useDeleteItem(item.id);
  return (
    <ConfirmDelete
      action="Delete item…"
      question={`Delete ${item.name}?`}
      detail="The item goes, along with its price history and everything you marked paid or typed over. To keep the history, archive it instead. You can undo this straight afterwards."
      confirmLabel="Yes, delete it"
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: onDeleted })}
    />
  );
}
