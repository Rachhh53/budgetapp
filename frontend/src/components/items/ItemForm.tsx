import { CalendarClock, ChevronRight } from "lucide-react";
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
  useSaveItem,
  type UsesPer,
} from "../../api/items";
import { formatDate, todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { ArchivedField } from "../ArchivedField";
import { CategoryField, TagsField } from "../categories/LabelFields";
import { firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { DaySelect } from "../DaySelect";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { Segmented } from "../Segmented";
import { InfoTip } from "../help/InfoTip";
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

/** The most sessions a pack can have; the backend holds it to the same. */
const MAX_PACK_SESSIONS = 1000;

/** Days of the week as the API counts them, 1 (Monday) to 7 (Sunday), Monday first. */
const WEEKDAYS: readonly { day: number; name: string }[] = [
  { day: 1, name: "Monday" },
  { day: 2, name: "Tuesday" },
  { day: 3, name: "Wednesday" },
  { day: 4, name: "Thursday" },
  { day: 5, name: "Friday" },
  { day: 6, name: "Saturday" },
  { day: 7, name: "Sunday" },
];

/** All twelve months, 1 (January) to 12: what a schedule that isn't seasonal runs in. */
const EVERY_MONTH: readonly number[] = MONTH_NAMES.map((_, index) => index + 1);

/**
 * A row of toggles, each a checkbox named in full ("November, checked") showing a short name. At least
 * one stays ticked: the last can't be taken away, so there is never nothing to save.
 */
function ChipToggles({
  legend,
  options,
  chosen,
  onChange,
  hint,
  grid,
}: {
  legend: string;
  options: readonly { value: number; name: string; short: string }[];
  chosen: readonly number[];
  onChange: (chosen: readonly number[]) => void;
  hint?: string;
  /** The grid's columns, for how many fit in a row. */
  grid: string;
}) {
  const id = useId();
  const toggle = (value: number) =>
    chosen.includes(value) ? chosen.length > 1 && onChange(chosen.filter((other) => other !== value)) : onChange([...chosen, value]);
  return (
    <fieldset aria-describedby={hint === undefined ? undefined : `${id}-hint`}>
      <legend className={LABEL}>{legend}</legend>
      <div className={`grid gap-1 rounded-lg bg-bg p-1 ${grid}`}>
        {options.map(({ value, name, short }) => (
          <label
            key={value}
            className="flex min-h-11 cursor-pointer items-center justify-center rounded-md text-sm font-medium text-muted has-checked:bg-surface has-checked:font-semibold has-checked:text-ink has-checked:shadow-sm has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent-ink"
          >
            <input type="checkbox" checked={chosen.includes(value)} onChange={() => toggle(value)} aria-label={name} className="sr-only" />
            <span aria-hidden>{short}</span>
          </label>
        ))}
      </div>
      {hint !== undefined && (
        <p id={`${id}-hint`} className={HINT}>
          {hint}
        </p>
      )}
    </fieldset>
  );
}

const MONTH_CHIPS = MONTH_NAMES.map((name, index) => ({ value: index + 1, name, short: name.slice(0, 3) }));
const WEEKDAY_CHIPS = WEEKDAYS.map(({ day, name }) => ({ value: day, name, short: name.slice(0, 2) }));

/** The ISO day of the week, 1 (Monday) to 7 (Sunday), of a date like 2026-10-19. */
function isoWeekday(date: string): number {
  const day = new Date(`${date}T00:00:00`).getDay();
  return day === 0 ? 7 : day;
}

/** How a bill's dates are charged: one choice, since something is never two of these at once. */
type Charged = "each_date" | "billed_later" | "paid_ahead" | "by_use";

const CHARGED_OPTIONS: readonly { value: Charged; label: string }[] = [
  { value: "each_date", label: "Each date" },
  { value: "billed_later", label: "Billed later" },
  { value: "paid_ahead", label: "Paid ahead" },
  { value: "by_use", label: "By use" },
];

const USES_PER_OPTIONS: readonly { value: UsesPer; label: string }[] = [
  { value: "week", label: "Week" },
  { value: "month", label: "Month" },
];

/** Billed by use: at most this many times a week or a month. */
const MOST_USES = 1000;

/** One line under the choice, saying what it means. */
const CHARGED_HELP: Record<Charged, string> = {
  each_date: "Paid on each date in its schedule.",
  billed_later: "Each date is a visit billed afterwards. Visits add up on the pay period until you mark them invoiced.",
  paid_ahead: "Paid up front, like a course. Each date uses some of it.",
  by_use: "Like snowplowing: billed monthly for the times since the last bill.",
};

/** Paid ahead by the pack: said in place of the paid-ahead help, so the amount screen still fits a phone. */
const PACK_HELP = "Paid by the pack. Its schedule is when you go; each date uses a session.";

/** Weeks in a year, for how long a pack lasts: 52 and a day. */
const WEEKS_A_YEAR = 365.25 / 7;

/**
 * About how many sessions a week a schedule names, to say how long a pack lasts; `null` where it can't be
 * said (every paycheck depends on the pay schedule, and once isn't a pace).
 */
function sessionsAWeek(frequency: ItemFrequency, days: number, weeksApart: number, monthsApart: number): number | null {
  switch (frequency) {
    case "weekly":
      return days / weeksApart;
    case "biweekly":
      return days / 2;
    case "semi_monthly":
      return 24 / WEEKS_A_YEAR;
    case "monthly":
      return 12 / WEEKS_A_YEAR;
    case "every_n_months":
      return 12 / monthsApart / WEEKS_A_YEAR;
    case "yearly":
      return 1 / WEEKS_A_YEAR;
    default:
      return null;
  }
}

/** "about 10 weeks", or months once it is past half a year. */
function howLong(weeks: number): string {
  if (weeks < 26) {
    const rounded = Math.max(1, Math.round(weeks));
    return `about ${rounded} ${rounded === 1 ? "week" : "weeks"}`;
  }
  const months = Math.round((weeks * 7) / (365.25 / 12));
  return `about ${months} months`;
}


/** How far into a pay period a budget can be dated from the list; payday itself is 0. */
const DAYS_AFTER_PAYDAY = Array.from({ length: 15 }, (_, days) => days);

/**
 * The most months or weeks apart that can be typed, as the backend allows. Any number in between, not a short list
 * (Rachel, 2026-10-20: "just make all options possible with every x months").
 */
const MOST_MONTHS_APART = 60;
const MOST_WEEKS_APART = 52;

/** "Every few weeks" is a weekly schedule more than a week apart: a choice of its own in the list, sent as weekly. */
const EVERY_FEW_WEEKS = "every_few_weeks";

/** How often, as the list offers it: the schedules, with "Every few weeks" after every two weeks. */
const FREQUENCY_CHOICES: readonly { value: string; label: string }[] = ITEM_FREQUENCIES.flatMap((frequency) => [
  { value: frequency, label: ITEM_FREQUENCY_LABELS[frequency] },
  ...(frequency === "biweekly" ? [{ value: EVERY_FEW_WEEKS, label: "Every few weeks" }] : []),
]);

/** A whole number from `least` to `most` as typed, or `null` when it isn't one. */
function wholeNumberWithin(text: string, least: number, most: number): number | null {
  const value = Number(text);
  return text.trim() !== "" && Number.isInteger(value) && value >= least && value <= most ? value : null;
}

/**
 * The parts of an item that can be opened on their own from its page, each from the card that shows it:
 * what it is (type, name, archiving, deleting), its amount, the accounts it moves between, its schedule,
 * and how it is filed (category, tags, note).
 */
export type ItemPart = "item" | "amount" | "accounts" | "schedule" | "details";

/** The screens of Edit item. */
type EditItemScreen = "what" | "charged" | "away";

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
 * A bucket move never opens here: money reaches a bucket by a transfer that names it, and any
 * bucket move set up before is stopped on its bucket, on its account (Rachel, 2026-10-19).
 */
export function ItemForm(props: ItemFormProps) {
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
  const schedule = item?.schedule;

  const [kind, setKind] = useState<ItemKind>(item?.kind ?? starting?.kind ?? "bill");
  const [name, setName] = useState(item?.name ?? starting?.name ?? "");
  const [categoryId, setCategoryId] = useState<number | null>(item?.categoryId ?? null);
  // A new category reaches back over the item's whole history when asked (Rachel, 2026-10-25: "retroactively").
  const [categoryEveryDate, setCategoryEveryDate] = useState(false);
  // Offered for a new category, and also when earlier dates still have another one: a category already changed from a
  // date can be made the whole history's afterwards (Rachel, 2026-10-25: "I now don't have the option ... because it's
  // not a change").
  const categoryChanged =
    item !== undefined &&
    item !== null &&
    (categoryId !== item.categoryId || item.history.some((point) => point.categoryId !== categoryId));
  const [payFrom, setPayFrom] = useState<string | null>(item ? String(item.payFromAccountId) : null);
  const [payTo, setPayTo] = useState<string | null>(
    item?.payToAccountId != null ? String(item.payToAccountId) : starting ? String(starting.payToAccountId) : null,
  );
  // How much a card payment pays: an amount of its own, or whatever the card works out (its statement balance, say).
  const [cardMode, setCardMode] = useState<CardPaymentMode>(item?.cardPaymentMode ?? starting?.cardPaymentMode ?? "fixed");
  const [amountMode, setAmountMode] = useState<AmountMode>(item?.amountMode ?? "per_occurrence");
  const [billedLater, setBilledLater] = useState(item?.billedLater ?? false);
  const [paidAhead, setPaidAhead] = useState(item?.paidAhead ?? false);
  // Billed by use (Rachel, 2026-10-20: snowplowing, "how many times per month or week that might happen ... and then
  // it's billed once per month based on those two things"): about how many times, as typed, and per what.
  const [byUse, setByUse] = useState(item?.usesHundredths != null);
  const [uses, setUses] = useState(item?.usesHundredths != null ? String(item.usesHundredths / 100) : "");
  const [usesPer, setUsesPer] = useState<UsesPer>(item?.usesPer ?? "week");
  // Paid ahead as a pack: how many sessions the price buys, as typed; empty for a price per session.
  const [packSize, setPackSize] = useState(item?.packSessions != null ? String(item.packSessions) : "");
  const inPacks = paidAhead && packSize.trim() !== "";
  // The day a change to an existing item takes effect: today unless she says otherwise (Rachel, 2026-10-19).
  const [changesFrom, setChangesFrom] = useState(todayIso());
  // Billed later and paid ahead both count session by session, so each is an amount each time.
  const perSession = kind === "bill" && (billedLater || paidAhead || byUse);
  const charged: Charged = billedLater ? "billed_later" : paidAhead ? "paid_ahead" : byUse ? "by_use" : "each_date";
  const [amount, setAmount] = useState(item ? centsToInput(item.amountCents) : "");
  const [estimate, setEstimate] = useState(item?.estimate ?? false);
  const [frequency, setFrequency] = useState<ItemFrequency>(schedule?.frequency ?? "monthly");
  const [day1, setDay1] = useState(schedule?.day1 ?? starting?.day1 ?? 1);
  const [day2, setDay2] = useState(schedule?.day2 ?? 15);
  const [month, setMonth] = useState(schedule?.month ?? 1);
  // Months or weeks apart, as typed.
  const [monthsApart, setMonthsApart] = useState(
    String(schedule?.frequency === "every_n_months" && schedule.interval && schedule.interval > 1 ? schedule.interval : 3),
  );
  const [everyFewWeeks, setEveryFewWeeks] = useState(schedule?.frequency === "weekly" && (schedule.interval ?? 1) > 1);
  const [weeksApart, setWeeksApart] = useState(String(everyFewWeeks && schedule?.interval ? schedule.interval : 3));
  // Weekly or every two weeks on several days; empty until one is picked, which means just the start date's day.
  const [chosenWeekdays, setChosenWeekdays] = useState<readonly number[]>(schedule?.weekdays ?? []);
  // The months it runs in: all twelve, unless it is seasonal (snowplowing in winter only).
  const [seasonMonths, setSeasonMonths] = useState<readonly number[]>(
    schedule?.months !== undefined && schedule.months.length > 0 ? schedule.months : EVERY_MONTH,
  );
  // Something once, or yearly, already happens in one month: there is nothing to choose.
  const offersMonths = frequency !== "one_time" && frequency !== "yearly";
  // Whether the months open shown: only for something already seasonal. Fixed when the form opens, so ticking every
  // month back on doesn't fold them away mid-change.
  const [monthsShown] = useState(seasonMonths.length < EVERY_MONTH.length);
  // `null` until chosen: a new item then starts where its kind of schedule usually does (see `startsOn`).
  const [chosenStart, setChosenStart] = useState<string | null>(schedule?.startDate ?? null);
  // The last day it can happen, if it stops (Rachel, 2026-10-20: "how do I plan for a recurring payment to end on a
  // certain date"); empty for no end. Once that day has gone by, Recurring items lists it under Ended.
  const [endsOn, setEndsOn] = useState(schedule?.endDate ?? "");
  const [daysAfterPayday, setDaysAfterPayday] = useState(item?.daysAfterPayday ?? 0);
  const [autopay, setAutopay] = useState(item?.autopay ?? false);
  const [countsFromPaid, setCountsFromPaid] = useState(item?.countsFromPaid ?? false);
  const [fromLastYear, setFromLastYear] = useState(item?.fromLastYear ?? false);
  const [placement, setPlacement] = useState<Placement>(item?.placement ?? "due_period");
  const [tagIds, setTagIds] = useState<number[]>(item?.tagIds ?? []);
  const [note, setNote] = useState(item?.note ?? "");
  const [archived, setArchived] = useState(item?.archived ?? false);
  const [archivedOn, setArchivedOn] = useState(item?.archivedOn ?? null);
  const [screen, setScreen] = useState<EditItemScreen>("what");
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
  // An item being changed already says where it comes from; a new one starts on money in no bucket
  // (Rachel, 2026-10-20: "default the dropdown on an account with buckets to \"money not in a bucket\"").
  const askedFrom = chosenFrom ?? String(item?.fromBucketId ?? NOT_IN_A_BUCKET);
  // A bucket chosen before the account was changed no longer applies, so it goes back to money in no bucket.
  const comesFrom = payingBuckets.some((bucket) => String(bucket.id) === askedFrom) ? askedFrom : NOT_IN_A_BUCKET;

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
  // Where it's paid from, opened on its own for an item already saved, asks the day the change starts,
  // so autopay (say) can begin on a past date.
  const datesAccounts = item !== undefined && part === "accounts";
  // Autopay and which paycheck pays it; a budget isn't paid automatically, and every paycheck's has its own paycheck.
  const asksHowPaid = !isBucketMove && (kind !== "budget" || !everyPaycheck);
  // Weekly or every two weeks can fall on several days of the week; nothing picked means the start date's day.
  const onDays = frequency === "weekly" || frequency === "biweekly";
  const everyFewWeeksShown = frequency === "weekly" && everyFewWeeks;
  const startWeekday = /^\d{4}-\d{2}-\d{2}$/.test(startsOn) ? isoWeekday(startsOn) : null;
  const shownWeekdays: readonly number[] =
    chosenWeekdays.length > 0 ? chosenWeekdays : startWeekday !== null ? [startWeekday] : [];
  // Counting the next date from the day it was paid (Rachel, 2026-10-24: "if it's every 6 weeks, it should adjust upcoming
  // payments to 6 weeks after the latest one") needs a steady step: every so many weeks on one day, months or years.
  // Not something whose dates aren't payments (visits billed later, sessions paid ahead, uses) or that pays nobody.
  // Estimating what is still to come from the same month a year before (Rachel, 2026-10-24: "use last years amounts as a
  // setting"), for a bill or budget whose dates are payments: not visits billed later, sessions or uses.
  const offersLastYear = (kind === "bill" || kind === "budget") && !perSession;
  const canCountFromPaid =
    !isBucketMove &&
    !(kind === "bill" && (byUse || paidAhead || billedLater)) &&
    ((onDays && shownWeekdays.length <= 1) || frequency === "monthly" || frequency === "every_n_months" || frequency === "yearly");

  // A pack's price spread over its sessions, and how long one lasts at the dates on the Schedule (Rachel, 2026-10-20:
  // "if I pay 1275 for a 20 pack, you never asked me how often I use a session").
  const packMath = ((): string | null => {
    const size = wholeNumberWithin(packSize, 1, MAX_PACK_SESSIONS);
    const price = parseDollars(amount);
    if (!inPacks || size === null || price === null || price <= 0) {
      return null;
    }
    const each = `${formatCents(Math.round(price / size))} a session`;
    const pace = sessionsAWeek(
      frequency,
      Math.max(1, shownWeekdays.length),
      everyFewWeeks ? (wholeNumberWithin(weeksApart, 2, MOST_WEEKS_APART) ?? 2) : 1,
      wholeNumberWithin(monthsApart, 2, MOST_MONTHS_APART) ?? 2,
    );
    return pace === null || pace <= 0
      ? `${each}. Its schedule is when you go.`
      : `${each} · a pack of ${size} lasts ${howLong(size / pace)} at its schedule.`;
  })();

  // Billed by use: what a whole month of it comes to, so the guess can be checked before saving.
  const usesMath = ((): string | null => {
    const each = parseDollars(amount);
    const times = Number(uses);
    if (!byUse || each === null || each <= 0 || uses.trim() === "" || !(times > 0)) {
      return null;
    }
    const perMonth = usesPer === "week" ? (times * 52) / 12 : times;
    return `About ${formatCents(Math.round(each * perMonth))} in a full month.`;
  })();

  // Its last date, beside the date it starts or falls on. Checked on saving rather than by the browser, so the
  // message is ours.
  const lastDate = (
    <div>
      <label htmlFor={`${id}-ends`} className={LABEL}>
        Last date
      </label>
      <input id={`${id}-ends`} type="date" value={endsOn} onChange={(event) => setEndsOn(event.target.value)} className={INPUT} />
    </div>
  );

  // How a bill is charged (and, billed by use, how often), on the Amount screen and on Edit item alike (Rachel,
  // 2026-10-20: "that isn't available in the edit screen").
  const chargedFields = (
    <>
      <div>
        <Segmented
          legend="How it's charged"
          dense
          options={CHARGED_OPTIONS}
          value={charged}
          onChange={(choice) => {
            setBilledLater(choice === "billed_later");
            setPaidAhead(choice === "paid_ahead");
            setByUse(choice === "by_use");
            // Billed by use is billed monthly, on the day chosen under Schedule.
            if (choice === "by_use") {
              setFrequency("monthly");
              setEveryFewWeeks(false);
            }
          }}
        />
        <p className={HINT}>{inPacks ? (packMath ?? PACK_HELP) : CHARGED_HELP[charged]}</p>
      </div>
      {byUse && (
        <div className="grid grid-cols-2 items-end gap-4">
          <div>
            <label htmlFor={`${id}-uses`} className={LABEL}>
              How many times
            </label>
            <input
              id={`${id}-uses`}
              type="number"
              inputMode="decimal"
              min={0.01}
              max={MOST_USES}
              step="any"
              value={uses}
              onChange={(event) => setUses(event.target.value)}
              required
              placeholder="about 2"
              className={INPUT}
            />
          </div>
          <Segmented legend="Per" dense options={USES_PER_OPTIONS} value={usesPer} onChange={setUsesPer} />
          {usesMath !== null && <p className={`${HINT} col-span-2`}>{usesMath}</p>}
        </div>
      )}
    </>
  );

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
    if (kind === "bill" && inPacks && !(Number.isInteger(Number(packSize)) && Number(packSize) >= 1 && Number(packSize) <= MAX_PACK_SESSIONS)) {
      return { part: "amount", problem: `Please enter how many sessions the pack has, from 1 to ${MAX_PACK_SESSIONS}.` };
    }
    const usesCount = Number(uses);
    if (kind === "bill" && byUse && !(uses.trim() !== "" && usesCount > 0 && usesCount <= MOST_USES)) {
      return { part: "amount", problem: `Please enter about how many times, more than 0 and up to ${MOST_USES.toLocaleString("en-US")}.` };
    }
    if (kind === "bill" && byUse && frequency !== "monthly") {
      return { part: "schedule", problem: "Something billed by use is billed once a month. Please choose Monthly and the day it's due." };
    }
    if (item && changesFrom === "") {
      return { part: datesAccounts ? "accounts" : "amount", problem: "Please choose the day this change starts." };
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
    const fewWeeks = frequency === "weekly" && everyFewWeeks ? wholeNumberWithin(weeksApart, 2, MOST_WEEKS_APART) : null;
    if (frequency === "weekly" && everyFewWeeks && fewWeeks === null) {
      return { part: "schedule", problem: `Please enter how many weeks apart, from 2 to ${MOST_WEEKS_APART}.` };
    }
    const fewMonths = frequency === "every_n_months" ? wholeNumberWithin(monthsApart, 2, MOST_MONTHS_APART) : null;
    if (frequency === "every_n_months" && fewMonths === null) {
      return { part: "schedule", problem: `Please enter how many months apart, from 2 to ${MOST_MONTHS_APART}.` };
    }
    if (frequency !== "one_time" && endsOn !== "" && endsOn < startsOn) {
      return { part: "schedule", problem: "The last date can't be before the first one." };
    }
    const byDayOfMonth = frequency === "semi_monthly" || frequency === "monthly" || frequency === "every_n_months";
    return {
      name,
      kind,
      note: note.trim() === "" ? null : note,
      archived,
      archivedOn,
      tagIds,
      countsFromPaid: canCountFromPaid && countsFromPaid,
      fromLastYear: offersLastYear && fromLastYear,
      amountCents,
      // Something billed later or paid ahead is added up session by session, so it is always an amount each time.
      amountMode: followsCard || perSession ? "per_occurrence" : amountMode,
      billedLater: kind === "bill" && billedLater,
      paidAhead: kind === "bill" && paidAhead,
      packSessions: kind === "bill" && inPacks ? Number(packSize) : null,
      // When each pack is paid is set from the Paid ahead card; this form keeps it as it is.
      packSchedule: kind === "bill" && inPacks ? (item?.packSchedule ?? null) : null,
      usesHundredths: kind === "bill" && byUse ? Math.round(usesCount * 100) : null,
      usesPer: kind === "bill" && byUse ? usesPer : null,
      // Billed by use is always an estimate until the real bill is typed over it.
      estimate: followsCard || (kind === "bill" && byUse) || estimate,
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
        interval: fewMonths ?? fewWeeks,
        day1: byDayOfMonth || frequency === "yearly" ? day1 : null,
        day2: frequency === "semi_monthly" ? day2 : null,
        month: frequency === "yearly" ? month : null,
        startDate: startsOn,
        endDate: frequency === "one_time" || endsOn === "" ? null : endsOn,
        weekendRule: schedule?.weekendRule ?? "none",
        // Every month is sent as none chosen, which the backend reads the same way.
        months: offersMonths && seasonMonths.length < EVERY_MONTH.length ? [...seasonMonths].sort((a, b) => a - b) : [],
        weekdays: onDays ? [...shownWeekdays].sort((a, b) => a - b) : [],
      },
      daysAfterPayday: everyPaycheck ? daysAfterPayday : 0,
      bucketId: moveInto !== undefined ? moveInto.id : chosenTransferBucket !== null ? Number(chosenTransferBucket) : null,
      saveUpBucketId: chosenSaveUpBucket !== null ? Number(chosenSaveUpBucket) : null,
      fromBucketId: payingBuckets.length > 0 && comesFrom !== NOT_IN_A_BUCKET ? Number(comesFrom) : null,
      // A change takes effect from the day chosen with the amount (today when not changed); the backend reaches
      // further back only for an earlier start.
      ...(item ? { effectiveFrom: changesFrom, categoryEveryDate: categoryChanged && categoryEveryDate } : {}),
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

  const kindChanged = item !== undefined && kind !== item.kind;
  const failure = problem ?? save.error?.message;
  // A different type can need a second account (a transfer's "To"), so the accounts come along with it.
  const shows = (asked: ItemPart) =>
    item === undefined
      ? (STEPS[step]?.includes(asked) ?? false)
      : part === undefined || part === asked || (asked === "accounts" && part === "item" && kindChanged);
  // Edit item holds more than fits one screen, so what it is, how it's charged, and archiving or deleting each have
  // their own, chosen at the top as on the account form (Rachel's rule: pop-up forms fit one screen).
  const screens: { value: EditItemScreen; label: string }[] =
    item !== undefined && part === "item"
      ? [
          { value: "what", label: "What it is" },
          ...(kind === "bill" && !followsCard ? [{ value: "charged" as const, label: "How it's charged" }] : []),
          { value: "away", label: "Archive or delete" },
        ]
      : [];
  // A screen that went away (How it's charged, once the type is no longer a bill) falls back to the first.
  const onScreen = screens.find((choice) => choice.value === screen)?.value ?? "what";
  const showsScreen = (wanted: EditItemScreen) => screens.length === 0 || onScreen === wanted;

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
        {screens.length > 0 && <Segmented legend="What to change" dense options={screens} value={onScreen} onChange={setScreen} />}
        {shows("item") && showsScreen("what") && (
          <Segmented legend="Type" options={item?.kind === "bucket_move" ? KIND_OPTIONS : KINDS_TO_ADD} value={kind} onChange={changeKind} />
        )}
        {shows("item") && showsScreen("what") && <p className="text-sm text-muted">{KIND_HELP[kind]}</p>}

        {kindChanged && (
          // An item is one kind for its whole life, so this cannot apply "from today on" like other changes. Archiving
          // it and adding a new one used to be offered here, and left two items for one thing (Rachel, 2026-10-24:
          // "Just change it in place"); Save changes this one.
          <div role="status" className="rounded-lg border-l-4 border-accent bg-bg px-4 py-3">
            <p className="font-semibold">
              Changing the type changes {item.name} all the way back to when it started.
            </p>
            <p className="mt-1 text-muted">
              Its earlier dates will count as a {KIND_LABELS[kind].toLowerCase()} too, between the accounts you choose here. The dates and
              amounts in its history stay as they are.
            </p>
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
                {/* The day beside the amount where there is room, so the change reads as one line: this much, from then. */}
                <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2 sm:items-start">
                  {item && (
                    <ChangesFromField
                      id={`${id}-changes-from`}
                      value={changesFrom}
                      onChange={setChangesFrom}
                      hint={`Earlier dates keep the old amount.${replacedHint(item, changesFrom)}`}
                    />
                  )}
                  <div className={item ? undefined : "sm:col-span-2"}>
                    <MoneyField
                      label={
                        kind === "bill" && byUse
                          ? "Each time"
                          : kind === "bill" && billedLater
                          ? "Each visit"
                          : kind === "bill" && paidAhead
                            ? inPacks
                              ? "Pack price"
                              : "Each session"
                            : amountMode === "annual"
                              ? "Per year"
                              : "Amount each time"
                      }
                      value={amount}
                      onChange={setAmount}
                      hint={amountMode === "annual" && !perSession ? "Shared out evenly over its schedule." : undefined}
                      required
                      hero
                    />
                  </div>
                </div>
                {kind === "bill" && chargedFields}
                {/* Side by side where there is room, so the amount is one glance, not three rows. */}
                <div className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2 sm:items-end">
                  {!perSession && (
                    <Segmented legend="How you enter the amount" options={AMOUNT_MODE_OPTIONS} value={amountMode} onChange={setAmountMode} />
                  )}
                  {/* Bought as a pack (Rachel, 2026-10-19: "$1,275 for a 20 session pack"): the next one is planned as a bill. */}
                  {kind === "bill" && paidAhead && (
                    <div>
                      <label htmlFor={`${id}-pack`} className={LABEL}>
                        Sessions in a pack (optional)
                      </label>
                      <input
                        id={`${id}-pack`}
                        type="number"
                        inputMode="numeric"
                        min={1}
                        max={MAX_PACK_SESSIONS}
                        value={packSize}
                        onChange={(event) => setPackSize(event.target.value)}
                        placeholder="Empty: per session"
                        className={INPUT}
                      />
                    </div>
                  )}
                  {/* The two side by side, on a phone too, so the amount's screen still fits one screen there. */}
                  <div className="grid grid-cols-[auto_1fr] items-center gap-x-4 sm:col-span-2">
                    {!(kind === "bill" && byUse) && (
                      <label className="flex min-h-11 items-center gap-2">
                        <input
                          type="checkbox"
                          checked={estimate}
                          onChange={(event) => setEstimate(event.target.checked)}
                          className="size-5 accent-(--accent)"
                        />
                        <span>It's an estimate</span>
                      </label>
                    )}
                    {offersLastYear && (
                      <span className="flex items-center gap-1">
                        <label className="flex min-h-11 items-center gap-2">
                          <input
                            type="checkbox"
                            checked={fromLastYear}
                            onChange={(event) => setFromLastYear(event.target.checked)}
                            className="size-5 shrink-0 accent-(--accent)"
                          />
                          <span>Last year's amounts</span>
                        </label>
                        <InfoTip term="fromLastYear" />
                      </span>
                    )}
                  </div>
                </div>
              </>
            )}
          </FormSection>
        )}

        {((shows("item") && showsScreen("what")) || shows("details")) && (
          <FormSection title={shows("item") ? "What it is" : "Category"}>
            {shows("item") && showsScreen("what") && (
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
            {shows("details") && categoryChanged && (
              <label className="flex min-h-11 items-center gap-2">
                <input
                  type="checkbox"
                  checked={categoryEveryDate}
                  onChange={(event) => setCategoryEveryDate(event.target.checked)}
                  className="size-5 shrink-0 accent-(--accent)"
                />
                <span>Also change every earlier date</span>
              </label>
            )}
          </FormSection>
        )}

        {showsScreen("charged") && screens.length > 0 && <FormSection title="How it's charged">{chargedFields}</FormSection>}

        {shows("accounts") && showsScreen("what") && (
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
                    <option value={NOT_IN_A_BUCKET}>Money that is not in a bucket</option>
                    {payingBuckets.map((bucket) => (
                      <option key={bucket.id} value={bucket.id}>
                        {bucket.name} (holds {formatCents(bucket.balanceCents)} today)
                      </option>
                    ))}
                  </select>
                  <p className={HINT}>
                    {comesFrom !== NOT_IN_A_BUCKET
                      ? "Each time, the bucket and the account both go down. If the bucket runs out, the rest comes from money that is not in a bucket."
                      : "Say whether one of its buckets pays it."}
                  </p>
                </div>
              )}
            </div>
            {/* Which paycheck pays it sits beside Autopay, since both are how it gets paid, as one checkbox: the
                schedule screen has no room left once it names days and months (2026-10-19). With "Changes from"
                beside them, the two are stacked, which keeps the screen as short as the note it replaces. */}
            {(datesAccounts || asksHowPaid) && (
              <div className={datesAccounts ? "grid grid-cols-2 items-start gap-x-4" : undefined}>
                {item && datesAccounts && (
                  <ChangesFromField id={`${id}-accounts-from`} value={changesFrom} onChange={setChangesFrom} hint={replacedHint(item, changesFrom)} />
                )}
                {asksHowPaid && (
                <div className={datesAccounts ? "flex flex-col" : "grid grid-cols-[auto_1fr] gap-x-6 sm:flex"}>
                  {kind !== "budget" && (
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
                  {!everyPaycheck && (
                    // The tip says when it is paid early anyway; outside the label, so opening it doesn't tick the box.
                    <span className="flex items-center gap-2">
                      <label className="flex min-h-11 items-center gap-3">
                        <input
                          type="checkbox"
                          checked={placement === "one_early"}
                          onChange={(event) => setPlacement(event.target.checked ? "one_early" : "due_period")}
                          className="size-5 shrink-0 accent-(--accent)"
                        />
                        <span>Pay a period early</span>
                      </label>
                      <InfoTip term="payEarly" />
                    </span>
                  )}
                </div>
                )}
              </div>
            )}
            {/* With how it's paid, not on the schedule: it is about when it was paid, and the schedule's screen is full.
                A row of its own, as beside "Changes from" its words would be squeezed into a narrow column. */}
            {asksHowPaid && canCountFromPaid && (
              <span className="flex items-center gap-2">
                <label className="flex min-h-11 items-center gap-3">
                  <input
                    type="checkbox"
                    checked={countsFromPaid}
                    onChange={(event) => setCountsFromPaid(event.target.checked)}
                    className="size-5 shrink-0 accent-(--accent)"
                  />
                  <span>Count the next one from the day it's paid</span>
                </label>
                <InfoTip term="countsFromPaid" />
              </span>
            )}
          </FormSection>
        )}

        {shows("schedule") && (
          // Paid ahead, the dates are sessions that use what was paid, not payments: the title says so.
          <FormSection
            title={kind === "bill" && paidAhead ? "Sessions: how often you go" : kind === "bill" && byUse ? "Billed each month" : "Schedule"}
          >
            {/* How often beside the day or date that goes with it, on a phone too: it keeps the schedule to one screen. */}
            <div className="grid grid-cols-2 items-end gap-4">
              <div>
                <label htmlFor={`${id}-frequency`} className={LABEL}>
                  How often
                </label>
                <select
                  id={`${id}-frequency`}
                  value={frequency === "weekly" && everyFewWeeks ? EVERY_FEW_WEEKS : frequency}
                  onChange={(event) => {
                    const fewWeeks = event.target.value === EVERY_FEW_WEEKS;
                    setEveryFewWeeks(fewWeeks);
                    setFrequency(fewWeeks ? "weekly" : (event.target.value as ItemFrequency));
                  }}
                  className={INPUT}
                >
                  {FREQUENCY_CHOICES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>

              {everyFewWeeksShown && (
                <div>
                  <label htmlFor={`${id}-weeks-apart`} className={LABEL}>
                    Weeks apart
                  </label>
                  <input
                    id={`${id}-weeks-apart`}
                    type="number"
                    inputMode="numeric"
                    min={2}
                    max={MOST_WEEKS_APART}
                    value={weeksApart}
                    onChange={(event) => setWeeksApart(event.target.value)}
                    required
                    className={INPUT}
                  />
                </div>
              )}

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
                  {/* Beside the last date instead, where there is room for it. */}
                  {!item && <DefaultDateHint value={startsOn} />}
                </div>
              )}
              {/* Weeks apart takes the place beside How often, so the date sits alone: its hint goes beside it, keeping a phone to one screen. */}
              {countsFromADate && everyFewWeeksShown && <p className={`${HINT} self-center`}>It repeats from this date.</p>}

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
                      <input
                        id={`${id}-interval`}
                        type="number"
                        inputMode="numeric"
                        min={2}
                        max={MOST_MONTHS_APART}
                        value={monthsApart}
                        onChange={(event) => setMonthsApart(event.target.value)}
                        required
                        className={INPUT}
                      />
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

              {countsFromADate && frequency !== "one_time" && (
                <>
                  {lastDate}
                  <p className={`${HINT} self-center`}>
                    {everyFewWeeksShown ? "Leave the last date empty if it doesn't end." : "It repeats from the date above. Leave the last date empty if it doesn't end."}
                  </p>
                </>
              )}
            </div>

            {onDays && (
              <ChipToggles legend="On these days" options={WEEKDAY_CHIPS} chosen={shownWeekdays} onChange={setChosenWeekdays} grid="grid-cols-7" />
            )}

            {/* The schedules above name their own first date; these need to be told when they begin. */}
            {!countsFromADate && (
              <div>
                {/* The first and last dates side by side, on a phone too. */}
                <div className="grid grid-cols-2 items-end gap-4">
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
                  </div>
                  {lastDate}
                </div>
                <p className={HINT}>
                  {everyPaycheck
                    ? "Today or earlier: from that day's pay period. Later: from the first paycheck on or after it, which this date moves to."
                    : "Nothing is scheduled before this day. Leave the last date empty if it doesn't end."}
                  {isBucketMove && " The bucket counts from then too."}
                </p>
                {!item && (everyPaycheck || isBucketMove) && <DefaultDateHint value={startsOn} />}
              </div>
            )}


            {offersMonths && (
              // Most things run every month, so the months wait behind a summary until they are wanted (Rachel's rule:
              // pop-up forms fit one screen; every few weeks on a chosen day did not). Open from the start when it is
              // seasonal or billed by use, where the months are the point.
              <details open={monthsShown || (kind === "bill" && byUse)} className="group">
                <summary className="flex min-h-11 cursor-pointer items-center gap-2 font-medium group-open:hidden">
                  <ChevronRight aria-hidden className="size-4 shrink-0" />
                  Runs every month
                  <span className="font-normal text-muted">· choose months</span>
                </summary>
                <ChipToggles
                  legend={kind === "bill" && byUse ? "Can happen in" : "Runs in"}
                  options={MONTH_CHIPS}
                  chosen={seasonMonths}
                  onChange={setSeasonMonths}
                  grid="grid-cols-6 sm:grid-cols-12"
                  hint={
                    kind === "bill" && byUse
                      ? "Only days in these months count. The bill for the last of them still comes the month after."
                      : seasonMonths.length === EVERY_MONTH.length
                      ? "Untick the months it doesn't happen in."
                      : everyPaycheck
                        ? "Only the paychecks in these months have it."
                        : amountMode === "annual"
                          ? "Nothing happens in the other months. The yearly total is shared over these months' dates."
                          : "Nothing happens in the other months."
                  }
                />
              </details>
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
            <div>
              <TagsField tags={tags} value={tagIds} onChange={setTagIds} />
              {item && <p className={HINT}>Tags count for every date, earlier ones too.</p>}
            </div>

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
            {shows("item") && showsScreen("away") && (
              <>
                <ArchivedField
                  archived={archived}
                  archivedOn={archivedOn}
                  onChange={(on, day) => {
                    setArchived(on);
                    setArchivedOn(day);
                  }}
                  hint="It stays in your pay periods before that day, and nothing of it comes up from then on."
                />
              </>
            )}
            {/* With the amount or where it's paid from on screen, "Changes from" already says when; saying "today"
                here would contradict it. */}
            {!(shows("amount") && !followsCard) && !datesAccounts && (
              <p className={HINT}>
                {/* Kept to two lines on a phone, so the schedule screen fits one (Rachel's rule: pop-up forms fit one screen). */}
                {categoryChanged && categoryEveryDate
                  ? "Saved from today; earlier dates take the new category too."
                  : "Saved from today; earlier dates stay as they were."}
                {shows("schedule") && " A new start date counts from then."}
              </p>
            )}
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

      {item && shows("item") && showsScreen("away") && (
        <div className="mt-6 border-t border-line pt-4">
          <DeleteItem item={item} onDeleted={onDeleted ?? onClose} />
        </div>
      )}
    </>
  );
}

/**
 * "Changes from": the day a change to an existing item starts, any day, past or future (Rachel,
 * 2026-10-19: the amount, then "update a recurring bill from manual to autopay and back date that change").
 */
function ChangesFromField({ id, value, onChange, hint }: { id: string; value: string; onChange: (date: string) => void; hint: string }) {
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        Changes from
      </label>
      <input
        id={id}
        type="date"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-describedby={hint === "" ? undefined : `${id}-hint`}
        className={INPUT}
      />
      {hint !== "" && (
        <p id={`${id}-hint`} className={HINT}>
          {hint.trim()}
        </p>
      )}
    </div>
  );
}

/**
 * Under "Changes from": a change replaces any already set after its day ("this and all future"), so
 * one that would is named, not left as a surprise. Empty when nothing is replaced; otherwise it starts
 * with a space, to follow a sentence.
 */
function replacedHint(item: Item, changesFrom: string): string {
  const replaced = item.history.filter((point) => changesFrom !== "" && point.effectiveFrom > changesFrom);
  return replaced.length === 0 ? "" : replaced.length === 1
    ? ` The change set for ${formatDate(replaced[0]?.effectiveFrom ?? "")} is replaced by this one.`
    : ` The ${replaced.length} changes set after it are replaced by this one.`;
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
