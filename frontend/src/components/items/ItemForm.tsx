import { CalendarClock } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type Account, holdsCash } from "../../api/accounts";
import { useBuckets, useSaveBucket } from "../../api/buckets";
import type { Category, Tag } from "../../api/categories";
import { usePayPeriods } from "../../api/income";
import {
  type AmountMode,
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
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { DaySelect } from "../DaySelect";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { ITEM_FREQUENCY_LABELS, KIND_ICONS, KIND_LABELS, MONTH_NAMES } from "./labels";

const KIND_OPTIONS = ITEM_KINDS.map((kind) => ({ value: kind, label: KIND_LABELS[kind], icon: KIND_ICONS[kind] }));

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

interface ItemFormProps {
  /** The item being changed; leave out to add a new one. */
  item?: Item;
  accounts: readonly Account[];
  categories: readonly Category[];
  tags: readonly Tag[];
  onClose: () => void;
}

/**
 * Add or change a recurring item, in the same centered dialog as every other form. Everything chosen
 * from a list is a dropdown or a type-to-filter picker; a category or tag that does not
 * exist yet can be added from its picker without leaving the form.
 *
 * A change to an existing item applies from a date on ("this and all future"): earlier
 * occurrences keep what they had.
 */
export function ItemForm(props: ItemFormProps) {
  return (
    <Modal
      title={props.item ? `Edit ${props.item.name}` : "Add a recurring item"}
      icon={props.item ? KIND_ICONS[props.item.kind] : CalendarClock}
      onClose={props.onClose}
    >
      <ItemFormBody {...props} />
    </Modal>
  );
}

/** The form itself, without a dialog around it, so the + Add item form can show it too. */
export function ItemFormBody({ item, accounts, categories, tags, onClose }: ItemFormProps) {
  const id = useId();
  const save = useSaveItem(item?.id);
  const replace = useReplaceItem(item?.id);
  const schedule = item?.schedule;

  const [kind, setKind] = useState<ItemKind>(item?.kind ?? "bill");
  const [name, setName] = useState(item?.name ?? "");
  const [categoryId, setCategoryId] = useState<number | null>(item?.categoryId ?? null);
  const [payFrom, setPayFrom] = useState<string | null>(item ? String(item.payFromAccountId) : null);
  const [payTo, setPayTo] = useState<string | null>(item?.payToAccountId != null ? String(item.payToAccountId) : null);
  const [amountMode, setAmountMode] = useState<AmountMode>(item?.amountMode ?? "per_occurrence");
  const [amount, setAmount] = useState(item ? centsToInput(item.amountCents) : "");
  const [estimate, setEstimate] = useState(item?.estimate ?? false);
  const [frequency, setFrequency] = useState<ItemFrequency>(schedule?.frequency ?? "monthly");
  const [day1, setDay1] = useState(schedule?.day1 ?? 1);
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
  const [problem, setProblem] = useState<string>();
  const addBucket = useSaveBucket();
  const currentPeriod = usePayPeriods().data?.periods.find((period) => period.current);

  // Archived buckets are left out, unless this item already points at one.
  const everyBucket = useBuckets().data ?? [];
  const openBuckets = everyBucket.filter(
    (bucket) => !bucket.archived || [bucketId, saveUpBucketId].includes(String(bucket.id)),
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

  const changeKind = (next: ItemKind) => {
    setKind(next);
    // Setting money aside is usually done with every paycheck, so start a new bucket move there.
    if (next === "bucket_move" && !item) {
      setFrequency("every_pay_period");
    }
  };

  const movesMoney = kind === "transfer" || kind === "card_payment";
  const everyPaycheck = frequency === "every_pay_period";
  // These repeat from a date the user gives; the rest name their own day of the month or year.
  const countsFromADate = frequency === "weekly" || frequency === "biweekly" || frequency === "one_time";
  // Where a new item starts until the user says otherwise. One that names its own day of the
  // month or year starts in January, so this year's occurrences exist; anything dated from
  // today starts today. Money set aside in a bucket never reaches back on its own: it would
  // fill the bucket with months of moves that never happened.
  const startsOn = chosenStart ?? (countsFromADate || everyPaycheck || isBucketMove ? todayIso() : `${todayIso().slice(0, 4)}-01-01`);

  // Archived accounts are left out, unless this item already points at one.
  const offered = accounts.filter((account) => !account.archived || [payFrom, payTo].includes(String(account.id)));
  const accountOptions = offered.map((account) => ({ id: String(account.id), label: account.name, color: account.color }));
  const cardOptions = offered
    .filter((account) => account.type === "credit_card")
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  /** Turns the form into a request, or explains what still needs attention. */
  const toInput = (): ItemInput | string => {
    const moveInto = isBucketMove ? openBuckets.find((bucket) => String(bucket.id) === bucketId) : undefined;
    if (isBucketMove && moveInto === undefined) {
      return "Please choose the bucket this money goes into.";
    }
    // A bucket move stays inside the bucket's own account.
    const paidFrom = moveInto !== undefined ? String(moveInto.accountId) : payFrom;
    if (paidFrom === null) {
      return kind === "transfer" ? "Please choose the account this money comes from." : "Please choose the account this is paid from.";
    }
    if (movesMoney && payTo === null) {
      return kind === "card_payment" ? "Please choose the card this payment goes to." : "Please choose the account this money goes to.";
    }
    if (movesMoney && payTo === payFrom) {
      return "Please choose two different accounts.";
    }
    const amountCents = parseDollars(amount);
    if (amountCents === null || amountCents < 0) {
      return AMOUNT_HELP;
    }
    const byDayOfMonth = frequency === "semi_monthly" || frequency === "monthly" || frequency === "every_n_months";
    return {
      name,
      kind,
      note: note.trim() === "" ? null : note,
      archived,
      tagIds,
      amountCents,
      amountMode,
      estimate,
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
      // A change is dated the day it is made; the backend reaches further back only for an earlier start.
      ...(item ? { effectiveFrom: todayIso() } : {}),
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

  /** Archives this item and adds the form as a new one from today, leaving the old one's past alone. */
  const addAsNew = () => {
    const input = toInput();
    if (typeof input === "string") {
      setProblem(input);
      return;
    }
    setProblem(undefined);
    // A new item: nothing to date a change from, and not archived whatever the old one was.
    const { effectiveFrom: _unused, ...fresh } = input;
    replace.mutate({ ...fresh, archived: false }, { onSuccess: onClose });
  };

  const kindChanged = item !== undefined && kind !== item.kind;
  // A bucket with a goal and a date knows what it is still short of: offer that as the amount.
  const goalBucket = isBucketMove ? openBuckets.find((bucket) => String(bucket.id) === bucketId) : undefined;
  const goalNeeds = goalBucket?.goalProgress?.morePerPaycheckCents ?? null;
  const failure = problem ?? save.error?.message ?? replace.error?.message;

  return (
    <>
      <form onSubmit={submit} className="space-y-4">
        <Segmented legend="Type" options={KIND_OPTIONS} value={kind} onChange={changeKind} />

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

        <FormSection title="Amount">
          <div>
            <MoneyField
              label={amountMode === "annual" ? "Per year" : "Amount each time"}
              value={amount}
              onChange={setAmount}
              hint={amountMode === "annual" ? "Shared out evenly over the schedule below." : undefined}
              required
              hero
            />
            <label className="mt-1 flex min-h-11 items-center gap-3">
              <input
                type="checkbox"
                checked={estimate}
                onChange={(event) => setEstimate(event.target.checked)}
                className="size-5 accent-(--accent)"
              />
              <span>It's an estimate</span>
            </label>
          </div>
          <Segmented legend="How you enter the amount" options={AMOUNT_MODE_OPTIONS} value={amountMode} onChange={setAmountMode} />
        </FormSection>

        <FormSection title="What it is">
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
          <CategoryField categories={categories} value={categoryId} onChange={setCategoryId} />
        </FormSection>

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
                {goalBucket?.goalDate && goalNeeds !== null && (
                  <div role="status" className="mt-2 rounded-lg border-l-4 border-accent bg-bg px-4 py-3">
                    <p>
                      {goalBucket.name} is short of its goal for {formatDate(goalBucket.goalDate)}.{" "}
                      <span data-amount className="font-semibold tabular-nums">
                        {formatCents(goalNeeds)}
                      </span>{" "}
                      more every paycheck would get there
                      {goalBucket.goalProgress?.morePerMonthCents != null && (
                        <>
                          , or{" "}
                          <span data-amount className="font-semibold tabular-nums">
                            {formatCents(goalBucket.goalProgress.morePerMonthCents)}
                          </span>{" "}
                          a month
                        </>
                      )}
                      .
                    </p>
                    <button
                      type="button"
                      onClick={() => {
                        setAmount(centsToInput(goalNeeds));
                        setAmountMode("per_occurrence");
                        setFrequency("every_pay_period");
                      }}
                      className={`${BUTTON_SECONDARY} mt-2`}
                    >
                      Use <span data-amount>{formatCents(goalNeeds)}</span> every paycheck
                    </button>
                  </div>
                )}
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
              <Picker label="To" noun="account" placeholder="Choose or type an account" options={accountOptions} value={payTo} onChange={setPayTo} />
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
          </div>
        </FormSection>

        <FormSection title="Schedule">
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
            </div>
          )}

          {(frequency === "semi_monthly" || frequency === "monthly" || frequency === "every_n_months" || frequency === "yearly") && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
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
            </div>
          )}

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
            </div>
          )}

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

          {!everyPaycheck && !isBucketMove && (
            <div>
              <Segmented legend="Which paycheck pays it" options={PLACEMENT_OPTIONS} value={placement} onChange={setPlacement} />
              <p className={HINT}>
                Something due on payday that you pay by hand always comes out of the paycheck before, so the money is there in time.
              </p>
            </div>
          )}
        </FormSection>

        {canSaveUp && (
          <FormSection title="Saving up">
          {/* Always shown on a bill or budget, so it can be found; when it cannot be used, it says why. */}
          {saveUpAccount === undefined && (
            <div>
              <p className={LABEL}>Save up for it in a bucket (optional)</p>
              <p className={HINT}>
                {payFrom === null
                  ? "Choose the account it is paid from first. A share can then be set aside in one of that account's buckets every paycheck."
                  : "Buckets live in checking and savings accounts, so there is nowhere to save up for something paid from this one."}
              </p>
            </div>
          )}
          {saveUpAccount !== undefined && (
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
                      openedOn: currentPeriod?.start ?? todayIso(),
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
              <p className={HINT}>To put a set amount in a bucket every paycheck or month instead, choose the type Bucket move.</p>
            </div>
          )}
          </FormSection>
        )}

        <FormSection title="Tags and notes">
          <TagsField tags={tags} value={tagIds} onChange={setTagIds} />

          <div>
            <label htmlFor={`${id}-note`} className={LABEL}>
              Note (optional)
            </label>
            <input id={`${id}-note`} value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} autoComplete="off" className={INPUT} />
          </div>
        </FormSection>

        {item && (
          <FormSection title="About this item">
            {/* Not a field: a fact about the item. When it begins is its own date, above. */}
            <div>
              <p className={LABEL}>Date added</p>
              <p className="tabular-nums">{formatDate(item.createdOn)}</p>
              <p className={HINT}>
                Changes you save now apply from today on; anything dated before today keeps what it had. A different start date is the
                exception, and applies from that date.
              </p>
            </div>

            <div>
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
            </div>
          </FormSection>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : item ? "Save changes" : "Add item"}
          </button>
        </div>
      </form>

      {item && (
        <>
          <PriceHistory item={item} />
          <div className="mt-6 border-t border-line pt-4">
            <DeleteItem item={item} onDeleted={onClose} />
          </div>
        </>
      )}
    </>
  );
}

/** What the item has been set to, version by version. "Amount", not "price": a budget or a bucket move has no price. */
function PriceHistory({ item }: { item: Item }) {
  return (
    <section aria-label="Amount history" className="mt-6 border-t border-line pt-4">
      <h3 className="font-semibold">Amount history</h3>
      <ul className="mt-2 divide-y divide-line">
        {item.history.map((point) => (
          <li key={point.effectiveFrom} className="flex items-center justify-between gap-3 py-2">
            <span className="text-muted">From {formatDate(point.effectiveFrom)}</span>
            <span data-amount className="tabular-nums">
              {formatCents(point.perOccurrenceCents)} · <span className="font-semibold">{formatCents(point.perYearCents)}</span> a year
            </span>
          </li>
        ))}
      </ul>
    </section>
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
