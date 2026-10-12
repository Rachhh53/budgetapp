import { Check, Plus, Trash2, TriangleAlert } from "lucide-react";
import { type KeyboardEvent, useState } from "react";
import type { Account } from "../../api/accounts";
import type { StubLine, StubLineInput } from "../../api/payStubs";
import { centsToInput, formatCents, parseDollars } from "../../lib/money";
import { EditName } from "../EditButton";
import { AMOUNT_HELP } from "../MoneyField";
import { clickRowToEdit, ROW_OPENS } from "../rowEdit";
import { BUTTON_PRIMARY, BUTTON_SECONDARY } from "../ui";
import { goesToAnAccount, kindOf, LINE_KINDS, type LineKind, partsOf } from "./labels";

/** The columns of the lines table from `md` up (design/pay-stub.png); the heading row uses the same. */
export const LINE_COLUMNS = "md:grid-cols-[minmax(0,2fr)_6.5rem_7rem_minmax(0,3fr)_2.25rem]";

/** A box to type in: full touch height on a phone, shorter beside a pointer. */
const FIELD = "min-h-11 w-full min-w-0 rounded-lg border border-line bg-surface px-2 md:min-h-10";
const SMALL_LABEL = "mb-0.5 block text-sm text-muted";

const YTD_HELP = "Please enter the year to date as an amount like 1,250.00, or leave it empty.";

/** Why a line is pointed out, in a sentence: the word "Check" alone does not say (Rachel, 2026-10-12). */
export const CHECK_HELP =
  "Budget couldn't tell from the stub what this line is, so it made its best guess. Look at its amount and what it tracks as, fix anything that is off, then choose Looks right.";

/**
 * A box that shows what is saved until it is typed in, and hands back what was typed when it is
 * left or Enter is pressed. Escape puts back what was there.
 */
function InlineField({
  label,
  heading,
  value,
  onCommit,
  money,
  autoFocus,
}: {
  /** For screen readers: which line's what. */
  label: string;
  /** The word over the box. */
  heading: string;
  value: string;
  /** Says what is wrong with what was typed, or nothing when it was taken. */
  onCommit: (typed: string) => string | undefined;
  money?: boolean;
  autoFocus?: boolean;
}) {
  // What is being typed; `undefined` while the box only shows what is saved.
  const [typed, setTyped] = useState<string>();
  const [problem, setProblem] = useState<string>();

  const commit = () => {
    if (typed === undefined) {
      return;
    }
    if (typed.trim() === value.trim()) {
      setTyped(undefined);
      setProblem(undefined);
      return;
    }
    const found = onCommit(typed);
    setProblem(found);
    if (found === undefined) {
      setTyped(undefined);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape" && typed !== undefined) {
      // Only what was typed is put back; the row stays open.
      event.stopPropagation();
      setTyped(undefined);
      setProblem(undefined);
    }
  };

  return (
    <span className="block min-w-0">
      <span aria-hidden className={SMALL_LABEL}>
        {heading}
      </span>
      <input
        aria-label={label}
        aria-invalid={problem !== undefined}
        value={typed ?? value}
        inputMode={money ? "decimal" : undefined}
        autoComplete="off"
        autoFocus={autoFocus}
        maxLength={money ? 16 : 80}
        onChange={(event) => setTyped(event.target.value)}
        onBlur={commit}
        onKeyDown={onKeyDown}
        className={`${FIELD} ${money ? "text-right tabular-nums" : "font-medium"} ${problem ? "border-danger" : ""}`}
        {...(money ? { "data-amount": true } : {})}
      />
      {problem && (
        <span role="alert" className="mt-1 block text-sm text-danger">
          {problem}
        </span>
      )}
    </span>
  );
}

/** What a line is tracked as, and the account it goes to when it is a contribution or a match. */
function TracksAs({
  name,
  line,
  accounts,
  onChange,
}: {
  name: string;
  line: Pick<StubLineInput, "section" | "tracksAs" | "accountId">;
  accounts: readonly Account[];
  onChange: (change: Pick<StubLineInput, "section" | "tracksAs" | "accountId">) => void;
}) {
  // An employer's match is retirement money: it can only go into an investment account.
  const offered = accounts.filter(
    (account) => (!account.archived || account.id === line.accountId) && (line.tracksAs !== "match" || account.type === "investment"),
  );
  return (
    <span className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
      <span className="block min-w-0">
        <span aria-hidden className={SMALL_LABEL}>
          Tracks as
        </span>
        <select
          aria-label={`What ${name} tracks as`}
          value={kindOf(line)}
          onChange={(event) => {
            const next = partsOf(event.target.value as LineKind);
            const keeps =
              goesToAnAccount(next.tracksAs) &&
              (next.tracksAs !== "match" || accounts.find((account) => account.id === line.accountId)?.type === "investment");
            onChange({ ...next, accountId: keeps ? line.accountId : null });
          }}
          className={FIELD}
        >
          {LINE_KINDS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </span>
      {goesToAnAccount(line.tracksAs) && (
        <span className="block min-w-0">
          <span aria-hidden className={SMALL_LABEL}>
            {line.tracksAs === "match" ? "Paid into" : "Goes to"}
          </span>
          <select
            aria-label={line.tracksAs === "match" ? `Account ${name} is paid into` : `Account ${name} goes to`}
            value={line.accountId ?? ""}
            onChange={(event) =>
              onChange({ section: line.section, tracksAs: line.tracksAs, accountId: event.target.value === "" ? null : Number(event.target.value) })
            }
            className={FIELD}
          >
            <option value="">No account (only listed)</option>
            {offered.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name}
              </option>
            ))}
          </select>
        </span>
      )}
    </span>
  );
}

/** The mustard "Check" mark, in words with an icon: color alone would not say what to do. */
function CheckMark() {
  return (
    <span
      title={CHECK_HELP}
      className="inline-flex shrink-0 items-center gap-1 rounded-md border border-warn-line bg-warn px-1.5 text-xs font-semibold text-warn-ink uppercase"
    >
      <TriangleAlert aria-hidden className="size-3" />
      Check
    </span>
  );
}

interface StubLineRowProps {
  line: StubLine;
  /** The "Tracks as" column, in a few words. */
  tracksAs: string;
  accounts: readonly Account[];
  /** Whether this is the line open for changing; one at a time. */
  editing: boolean;
  /** Left out for a saved stub, whose lines are only shown. */
  onEdit?: () => void;
  onDone: () => void;
  onChange: (line: StubLineInput) => void;
  onRemove: () => void;
}

/**
 * One line of a stub (design/pay-stub.png). It reads as a plain row: name, this stub, year to
 * date, what it tracks as. In review the row opens where it stands (Rachel, 2026-10-12: no
 * pop-up) into the same four things as boxes, each saved as it is left, with Remove and Done.
 * A line the reader was unsure of says why underneath, and "Looks right" settles it as it is.
 */
export function StubLineRow({ line, tracksAs, accounts, editing, onEdit, onDone, onChange, onRemove }: StubLineRowProps) {
  const { id: _id, ...current } = line;
  const change = (part: Partial<StubLineInput>) => onChange({ ...current, ...part, sure: true });

  if (editing && onEdit) {
    return (
      <li
        className="-mx-2 my-1 rounded-lg border border-accent bg-bg/60 p-3"
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            onDone();
          }
        }}
      >
        <div className="grid grid-cols-2 gap-x-3 gap-y-2 md:grid-cols-[minmax(0,1fr)_8rem_8rem]">
          <span className="col-span-2 min-w-0 md:col-span-1">
            <InlineField
              label={`Name of ${line.label}`}
              heading="Line on the stub"
              value={line.label}
              autoFocus
              onCommit={(typed) => {
                if (typed.trim() === "") {
                  return "A line needs a name. To take it off the stub, use Remove line.";
                }
                change({ label: typed.trim() });
                return undefined;
              }}
            />
          </span>
          <InlineField
            label={`${line.label} this stub`}
            heading="This stub"
            value={centsToInput(line.amountCents)}
            money
            onCommit={(typed) => {
              const amountCents = parseDollars(typed);
              // Only earnings can be less than zero: a correction that takes pay back.
              if (amountCents === null || (amountCents < 0 && line.section !== "earnings")) {
                return AMOUNT_HELP;
              }
              change({ amountCents });
              return undefined;
            }}
          />
          <InlineField
            label={`${line.label} year to date`}
            heading="Year to date"
            value={line.ytdCents === null ? "" : centsToInput(line.ytdCents)}
            money
            onCommit={(typed) => {
              const ytdCents = typed.trim() === "" ? null : parseDollars(typed);
              if (typed.trim() !== "" && ytdCents === null) {
                return YTD_HELP;
              }
              change({ ytdCents });
              return undefined;
            }}
          />
          <span className="col-span-2 min-w-0 md:col-span-3">
            <TracksAs name={line.label} line={line} accounts={accounts} onChange={change} />
          </span>
        </div>
        {!line.sure && (
          <p className="mt-2 flex items-start gap-2 text-sm text-warn-ink">
            <TriangleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>{CHECK_HELP}</span>
          </p>
        )}
        <div className="mt-3 flex flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-end">
          <button
            type="button"
            onClick={onRemove}
            className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-3 font-semibold text-danger sm:mr-auto"
          >
            <Trash2 aria-hidden className="size-4" />
            Remove line
          </button>
          {!line.sure && (
            <button type="button" onClick={() => change({})} className={`${BUTTON_SECONDARY} inline-flex items-center justify-center gap-1`}>
              <Check aria-hidden className="size-4" />
              Looks right
            </button>
          )}
          <button type="button" onClick={onDone} className={BUTTON_PRIMARY}>
            Done
          </button>
        </div>
      </li>
    );
  }

  return (
    <li
      onClick={onEdit ? clickRowToEdit : undefined}
      className={`-mx-2 grid grid-cols-2 gap-x-3 gap-y-0.5 rounded-lg px-2 py-2 md:items-center ${LINE_COLUMNS} ${onEdit ? ROW_OPENS : ""} ${
        line.sure ? "" : "bg-warn/60"
      }`}
    >
      <span className="col-span-2 flex min-w-0 flex-wrap items-center gap-2 md:col-span-1">
        {onEdit ? <EditName name={line.label} onClick={onEdit} /> : <span className="font-medium wrap-break-word">{line.label}</span>}
        {!line.sure && <CheckMark />}
      </span>
      <span data-amount className="font-semibold whitespace-nowrap tabular-nums md:text-right">
        <span className="text-sm font-normal text-muted md:hidden">This stub </span>
        {formatCents(line.amountCents)}
      </span>
      <span data-amount className="text-right text-sm whitespace-nowrap text-muted tabular-nums md:text-base">
        {line.ytdCents !== null && (
          <>
            <span className="text-sm md:hidden">Year to date </span>
            {formatCents(line.ytdCents)}
          </>
        )}
      </span>
      <span className={`min-w-0 text-sm text-muted md:col-span-1 md:text-base ${onEdit ? "" : "col-span-2"}`}>{tracksAs}</span>
      {onEdit && (
        <span className="flex justify-end">
          <button
            type="button"
            onClick={(event) => {
              // The bin is its own button: it must not also open the row it sits on.
              event.stopPropagation();
              onRemove();
            }}
            aria-label={`Remove ${line.label}`}
            title={`Remove ${line.label}`}
            className="inline-flex size-11 items-center justify-center rounded-lg text-muted hover:text-danger md:size-9"
          >
            <Trash2 aria-hidden className="size-4" />
          </button>
        </span>
      )}
      {/* Said in a sentence under the line, since a mark alone leaves you guessing and a phone has no hover. */}
      {!line.sure && onEdit && (
        <span className="col-span-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-warn-ink md:col-span-5">
          <span className="min-w-0 flex-1 basis-64">{CHECK_HELP}</span>
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              change({});
            }}
            aria-label={`${line.label} looks right`}
            className="inline-flex min-h-11 shrink-0 items-center gap-1 rounded-lg border border-warn-line bg-surface px-3 font-semibold text-ink md:min-h-9"
          >
            <Check aria-hidden className="size-4" />
            Looks right
          </button>
        </span>
      )}
    </li>
  );
}

/**
 * A line the reader missed, typed in at the foot of the list: the same boxes as an open line
 * has, kept here until it has a name and an amount, then added with one button.
 */
export function NewStubLine({
  accounts,
  pending,
  onAdd,
  onCancel,
}: {
  accounts: readonly Account[];
  pending: boolean;
  onAdd: (line: StubLineInput) => void;
  onCancel: () => void;
}) {
  const [label, setLabel] = useState("");
  const [amount, setAmount] = useState("");
  const [ytd, setYtd] = useState("");
  const [kind, setKind] = useState<Pick<StubLineInput, "section" | "tracksAs" | "accountId">>({ section: "pre_tax", tracksAs: "benefit", accountId: null });
  const [problem, setProblem] = useState<string>();

  const add = () => {
    if (label.trim() === "") {
      setProblem("Please give the line a name, as it reads on the stub.");
      return;
    }
    const amountCents = parseDollars(amount);
    if (amountCents === null || (amountCents < 0 && kind.section !== "earnings")) {
      setProblem(AMOUNT_HELP);
      return;
    }
    const ytdCents = ytd.trim() === "" ? null : parseDollars(ytd);
    if (ytd.trim() !== "" && ytdCents === null) {
      setProblem(YTD_HELP);
      return;
    }
    setProblem(undefined);
    onAdd({ ...kind, label: label.trim(), amountCents, ytdCents, sure: true });
  };
  const onEnter = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault();
      add();
    }
  };

  return (
    <div role="group" aria-label="New line" className="mt-3 rounded-lg border border-accent bg-bg/60 p-3">
      <div className="grid grid-cols-2 gap-x-3 gap-y-2 md:grid-cols-[minmax(0,1fr)_8rem_8rem]">
        <label className="col-span-2 min-w-0 md:col-span-1">
          <span className={SMALL_LABEL}>Line on the stub</span>
          <input
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            onKeyDown={onEnter}
            maxLength={80}
            autoComplete="off"
            autoFocus
            placeholder="e.g. Dental"
            className={`${FIELD} font-medium`}
          />
        </label>
        <label className="min-w-0">
          <span className={SMALL_LABEL}>This stub</span>
          <input value={amount} onChange={(event) => setAmount(event.target.value)} onKeyDown={onEnter} inputMode="decimal" autoComplete="off" className={`${FIELD} text-right tabular-nums`} />
        </label>
        <label className="min-w-0">
          <span className={SMALL_LABEL}>Year to date</span>
          <input value={ytd} onChange={(event) => setYtd(event.target.value)} onKeyDown={onEnter} inputMode="decimal" autoComplete="off" className={`${FIELD} text-right tabular-nums`} />
        </label>
        <span className="col-span-2 min-w-0 md:col-span-3">
          <TracksAs name="the new line" line={kind} accounts={accounts} onChange={setKind} />
        </span>
      </div>
      {problem && (
        <p role="alert" className="mt-2 text-danger">
          {problem}
        </p>
      )}
      <div className="mt-3 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button type="button" onClick={onCancel} className={BUTTON_SECONDARY}>
          Cancel
        </button>
        <button type="button" disabled={pending} onClick={add} className={`${BUTTON_PRIMARY} inline-flex items-center justify-center gap-1`}>
          <Plus aria-hidden className="size-4" />
          {pending ? "Adding…" : "Add line"}
        </button>
      </div>
    </div>
  );
}
