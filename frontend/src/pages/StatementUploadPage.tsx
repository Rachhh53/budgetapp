import { Check, ChevronDown, ChevronUp, ReceiptText, TriangleAlert } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { type Category, type Tag, useCategories, useTags } from "../api/categories";
import {
  type BudgetChoice,
  type BudgetDateChange,
  isMatch,
  isOpen,
  type Matchable,
  type PeriodChanges,
  type PeriodMatch,
  type StatementChanges,
  type StatementLine,
  type StatementUpload,
  statementPreviewUrl,
  useApplyStatement,
  useChangeStatementLine,
  useDeleteStatementUpload,
  useMatchStatementLine,
  usePutOnBudget,
  useStatementBalances,
  useStatementChanges,
  useStatementOneOff,
  useStatementStillToCome,
  useStatementUpload,
  useUnmatchStatementLine,
} from "../api/statementUploads";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { categoryLabel } from "../components/categories/LabelFields";
import { EditButton } from "../components/EditButton";
import { TagChips } from "../components/expenses/TagChips";
import { FilePreview } from "../components/files/Receipts";
import { FilterPills } from "../components/FilterPills";
import { HelpLink } from "../components/help/HelpLink";
import { Modal } from "../components/Modal";
import { AMOUNT_HELP, MoneyField } from "../components/MoneyField";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { FilingDialog } from "../components/statements/FilingDialog";
import { KIND_LABELS, StatementLineForm } from "../components/statements/StatementLineForm";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, INPUT, PAGE_TITLE } from "../components/ui";
import { readableTextOn } from "../lib/color";
import { formatDate, formatDay } from "../lib/dates";
import { centsToInput, formatCents, parseDollars } from "../lib/money";

/**
 * One statement upload (Rachel, 2026-10-25, made simpler 2026-10-30: "I basically want the budget to become the actual in
 * aggregate by adding together items from the statement"). Each charge is put on a budget of its pay period, matched to a
 * bill or one-off already in Budget, or added as a one-off; a budget becomes what was spent on it plus what is still to
 * come. Nothing changes in the pay periods until Apply, and the rows are checked against the statement's own balances.
 */
export function StatementUploadPage() {
  const params = useParams();
  const accountId = Number(params.id);
  const uploadId = Number(params.uploadId);
  const navigate = useNavigate();
  const upload = useStatementUpload(Number.isInteger(uploadId) ? uploadId : undefined);
  const changes = useStatementChanges(Number.isInteger(uploadId) ? uploadId : undefined);
  const categories = useCategories().data ?? [];
  const tags = useTags().data ?? [];
  const remove = useDeleteStatementUpload();
  const [applying, setApplying] = useState(false);
  const [search, setSearch] = useState("");
  const searchId = useId();

  if (upload.isPending) {
    return <p className="text-muted">Loading the statement…</p>;
  }
  if (upload.isError || upload.data.accountId !== accountId) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Statement</h1>
        <Card>
          <p role="alert" className="font-medium">
            {upload.isError && upload.error.message !== "We couldn't find that."
              ? upload.error.message
              : "We couldn't find that statement. It may have been removed."}
          </p>
          <Link to={`/accounts/${accountId}`} className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to the account
          </Link>
        </Card>
      </>
    );
  }

  const data = upload.data;
  const back = `/accounts/${data.accountId}`;
  const toApply = (changes.data?.periods ?? []).filter((period) => !period.upToDate);

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/accounts" className="text-accent-ink underline">
              Accounts
            </Link>{" "}
            /{" "}
            <Link to={back} className="text-accent-ink underline">
              {data.accountName}
            </Link>{" "}
            / Statement
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} min-w-0 truncate`}>
              Statement · {formatDay(data.from)} – {formatDate(data.to)}
            </h1>
            <HelpLink />
          </div>
          <p className="text-muted">{data.name} · read on this PC. Nothing changes in your pay periods until you apply it.</p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <button
            type="button"
            disabled={remove.isPending}
            onClick={() => remove.mutate(data.id, { onSuccess: () => navigate(back) })}
            className={BUTTON_SECONDARY}
          >
            {remove.isPending ? "Removing…" : "Remove statement"}
          </button>
          <button type="button" disabled={toApply.length === 0} onClick={() => setApplying(true)} className={BUTTON_PRIMARY}>
            Apply…
          </button>
        </div>
      </div>

      <div className="space-y-4">
        <Notices upload={data} />
        {remove.isError && (
          <p role="alert" className="text-danger">
            {remove.error.message}
          </p>
        )}
        <CheckCard upload={data} />
        {/* One search over both cards below (Rachel, 2026-10-30: "add a search feature for both charges and what changes -
            it can be one search that searches both"). */}
        <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-2">
          <label htmlFor={searchId} className="font-medium">
            Search
          </label>
          <input
            id={searchId}
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Payee, amount, budget, category, tag"
            autoComplete="off"
            className={`${INPUT} sm:w-80`}
          />
        </div>
        <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-2">
          <ChargesCard upload={data} changes={changes.data} categories={categories} tags={tags} search={search} />
          {changes.isPending && (
            <Card title="What changes">
              <p className="text-muted">Adding up the pay periods…</p>
            </Card>
          )}
          {changes.isError && (
            <Card title="What changes">
              <p role="alert" className="text-danger">
                {changes.error.message}
              </p>
            </Card>
          )}
          {changes.data && <WhatChangesCard upload={data} changes={changes.data} search={search} />}
        </div>
        {data.format === "pdf" && (
          <Card title="The statement">
            <FilePreview name={data.name} pages={data.pages} src={(page) => statementPreviewUrl(data.id, page)} size="column" />
          </Card>
        )}
      </div>

      {applying && changes.data && <ApplyDialog uploadId={data.id} periods={toApply} onClose={() => setApplying(false)} />}
    </>
  );
}

/** What wants a look: lines read with a guess, CSV rows that couldn't be read, charges not put anywhere yet. */
function Notices({ upload }: { upload: StatementUpload }) {
  const waiting = upload.lines.filter((line) => isOpen(line) && line.budgetItemId === null && line.periodStart !== null).length;
  const notes: string[] = [];
  if (upload.toCheck > 0) {
    notes.push(`${upload.toCheck === 1 ? "One line was" : `${upload.toCheck} lines were`} hard to read; each is marked Check this.`);
  }
  if (upload.skippedRows.length > 0) {
    notes.push(`Rows ${upload.skippedRows.join(", ")} of the file had no date or amount Budget could read, so they were left out.`);
  }
  if (notes.length === 0) {
    return (
      <p role="status" className="flex items-start gap-2 rounded-lg border border-line bg-surface p-3">
        <Check aria-hidden className="mt-0.5 size-5 shrink-0 text-accent-ink" />
        <span>
          {waiting === 0
            ? "Every charge is put somewhere. Apply when you're ready."
            : `${waiting === 1 ? "One charge isn't" : `${waiting} charges aren't`} put anywhere yet: put it on a budget, say what it already is, or add it as a one-off. Charges you leave are fine too.`}
        </span>
      </p>
    );
  }
  return (
    <div role="status" className="flex items-start gap-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
      <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
      <ul className="space-y-1">
        {notes.map((note) => (
          <li key={note}>{note}</li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Whether every row was read (Rachel, 2026-10-30: "make sure the rows you show me for the statement add up to the
 * statement balance"; "do a different between statement balance ... and the summation of all the rows you extracted ...
 * you can call that missing records"). What the statement says, typed off it or read from the file; what the rows Budget
 * read add up to, each total set against the statement's own; and the missing records between the balances and the rows.
 */
function CheckCard({ upload }: { upload: StatementUpload }) {
  const [editing, setEditing] = useState(false);
  const check = upload.check;
  const card = upload.onCard;
  // What every row comes to together, as the statement's balance moves: on a card charges add to what is owed, on a bank
  // account deposits add to what it holds.
  const rowsCents = card ? check.spentCents - check.backCents : check.backCents - check.spentCents;
  const chargesLabel = card ? "Total charges" : "Withdrawals";
  const creditsLabel = card ? "Payments and credits" : "Deposits and credits";
  const missing = check.differenceCents;
  return (
    <Card title="Check against the statement" tip="statementCheck" action={<EditButton name="what the statement says" onClick={() => setEditing(true)} />}>
      <h3 className="text-sm font-semibold tracking-wide text-muted uppercase">From the statement</h3>
      <dl className="mt-1 grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 sm:max-w-md">
        <CheckLine label="Beginning balance" cents={check.previousCents} />
        <CheckLine label={chargesLabel} cents={check.statementChargesCents} />
        <CheckLine label={creditsLabel} cents={check.statementCreditsCents} />
        <CheckLine label="Statement balance" cents={check.newCents} />
      </dl>
      {(check.previousCents === null || check.newCents === null) && (
        <p className="mt-1 text-sm text-muted">Type what the statement says with Edit; this file doesn't say it all.</p>
      )}
      <h3 className="mt-4 text-sm font-semibold tracking-wide text-muted uppercase">From the rows Budget read</h3>
      <dl className="mt-1 grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 sm:max-w-md">
        <CheckLine label={card ? "Charges" : "Withdrawals"} cents={check.spentCents} against={check.statementChargesCents} />
        <CheckLine label={creditsLabel} cents={check.backCents} against={check.statementCreditsCents} />
        <CheckLine label="Rows added up" cents={rowsCents} strong />
      </dl>
      <dl className="mt-4 grid grid-cols-[1fr_auto] gap-x-4 border-t border-line pt-3 sm:max-w-md">
        <dt className="font-semibold">Missing records</dt>
        <dd className="text-right font-semibold">{missing === null ? "—" : <Amount cents={Math.abs(missing)} />}</dd>
      </dl>
      <p className="text-sm text-muted">Statement balance − beginning balance − rows added up</p>
      {missing !== null && (
        <p role="status" className={`mt-2 flex items-start gap-2 font-semibold ${missing === 0 ? "text-accent-ink" : "text-warn-ink"}`}>
          {missing === 0 ? <Check aria-hidden className="mt-0.5 size-5 shrink-0" /> : <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />}
          {missing === 0 ? "Every row was read." : "A row may be missing or misread."}
        </p>
      )}
      {editing && <BalancesDialog upload={upload} onClose={() => setEditing(false)} />}
    </Card>
  );
}

/** One figure of the check; a row total set against the statement's own says whether they match, in words. */
function CheckLine({ label, cents, against, strong = false }: { label: string; cents: number | null; against?: number | null; strong?: boolean }) {
  return (
    <>
      <dt className={strong ? "font-medium" : "text-muted"}>
        {label}
        {against !== undefined && against !== null && (
          <span className={`block text-sm ${against === cents ? "text-accent-ink" : "font-semibold text-warn-ink"}`}>
            {against === cents ? "Matches the statement" : `Off by ${formatCents(Math.abs((cents ?? 0) - against))}`}
          </span>
        )}
      </dt>
      <dd className={`text-right ${strong ? "font-medium" : ""}`}>{cents === null ? "—" : <Amount cents={cents} />}</dd>
    </>
  );
}

function BalancesDialog({ upload, onClose }: { upload: StatementUpload; onClose: () => void }) {
  const save = useStatementBalances(upload.id);
  const asInput = (cents: number | null) => (cents === null ? "" : centsToInput(cents));
  const [previous, setPrevious] = useState(asInput(upload.check.previousCents));
  const [next, setNext] = useState(asInput(upload.check.newCents));
  const [charges, setCharges] = useState(asInput(upload.check.statementChargesCents));
  const [credits, setCredits] = useState(asInput(upload.check.statementCreditsCents));
  const [error, setError] = useState<string>();
  const card = upload.onCard;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const typed = [previous, next, charges, credits].map((text) => (text.trim() === "" ? null : parseDollars(text)));
    if ([previous, next, charges, credits].some((text, at) => text.trim() !== "" && typed[at] === null)) {
      setError(AMOUNT_HELP);
      return;
    }
    const [previousCents = null, newCents = null, chargesCents = null, creditsCents = null] = typed;
    if ((chargesCents ?? 0) < 0 || (creditsCents ?? 0) < 0) {
      setError("Please type the totals without a minus sign.");
      return;
    }
    setError(undefined);
    save.mutate({ previousCents, newCents, chargesCents, creditsCents }, { onSuccess: onClose });
  };

  return (
    <Modal title="What the statement says" icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className={HINT}>As the statement prints them{card ? ", what is owed" : ""}. Leave any it doesn't say empty.</p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <MoneyField label="Beginning balance" value={previous} onChange={setPrevious} error={error} />
          <MoneyField label="Statement balance" value={next} onChange={setNext} />
          <MoneyField label={card ? "Total charges" : "Withdrawals"} value={charges} onChange={setCharges} />
          <MoneyField label={card ? "Payments and credits" : "Deposits and credits"} value={credits} onChange={setCredits} />
        </div>
        {save.error && (
          <p role="alert" className="text-danger">
            {save.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

type LineFilter = "waiting" | "left_out";

/**
 * Whether what was typed in the search is in any of these words, or (typed as money, with or without $ and commas) in
 * any of these amounts. Nothing typed matches everything.
 */
function matchesSearch(search: string, texts: readonly (string | null | undefined)[], amounts: readonly number[] = []): boolean {
  const typed = search.trim().toLowerCase();
  if (typed === "") {
    return true;
  }
  if (texts.some((text) => text?.toLowerCase().includes(typed))) {
    return true;
  }
  const money = typed.replace(/[$,\s]/g, "").replace(/^[-−]/, "");
  return /^\d*\.?\d+$|^\d+\.$/.test(money) && amounts.some((cents) => centsToInput(Math.abs(cents)).includes(money));
}

/** The budgets a line's pay period has, from the period figures. */
function budgetsFor(changes: StatementChanges | undefined, line: StatementLine): BudgetChoice[] {
  return changes?.periods.find((period) => period.start === line.periodStart)?.budgets ?? [];
}

/**
 * A budget's name, saying which account pays it when that isn't the statement's (Rachel, 2026-10-30: "add them to a budget
 * that is flagged for another account"): the charges come off its still to come and land as one one-off on this account.
 */
function budgetName(budget: { name: string; accountId: number; accountName: string }, accountId: number): string {
  return budget.accountId === accountId ? budget.name : `${budget.name} · paid from ${budget.accountName}`;
}

/**
 * Every line, by pay period. Each open charge can be put on a budget (several at once, ticked), said to be something
 * already in Budget, or added as a one-off; each with a visible Edit for what was read. Left-out lines (payments,
 * transfers, repeats of an earlier statement) stay listed, saying why, with Put back where that applies.
 */
function ChargesCard({
  upload,
  changes,
  categories,
  tags,
  search,
}: {
  upload: StatementUpload;
  changes: StatementChanges | undefined;
  categories: readonly Category[];
  tags: readonly Tag[];
  search: string;
}) {
  const change = useChangeStatementLine(upload.id);
  const putOn = usePutOnBudget(upload.id);
  const unmatch = useUnmatchStatementLine(upload.id);
  const oneOff = useStatementOneOff(upload.id);
  const [editing, setEditing] = useState<StatementLine>();
  const [matching, setMatching] = useState<StatementLine>();
  const [choosing, setChoosing] = useState<StatementLine[]>();
  const [filing, setFiling] = useState<StatementLine[]>();
  const [ticked, setTicked] = useState<number[]>([]);
  const [filter, setFilter] = useState<LineFilter | null>(null);
  const [filed, setFiled] = useState<string>();
  const failed = change.error ?? putOn.error ?? unmatch.error ?? oneOff.error;
  const pending = change.isPending || putOn.isPending || unmatch.isPending || oneOff.isPending;
  const candidates = (line: StatementLine): Matchable[] => changes?.periods.find((period) => period.start === line.periodStart)?.matchable ?? [];
  // A charge is found by its payee, the budget it is on, what it is matched to, its category and tags, or its amount.
  const found = (line: StatementLine) => {
    const category = categories.find((each) => each.id === line.categoryId);
    const matched = line.matched === null ? undefined : candidates(line).find((candidate) => isMatch(line.matched, candidate));
    return matchesSearch(
      search,
      [
        line.description,
        budgetsFor(changes, line).find((budget) => budget.itemId === line.budgetItemId)?.name,
        matched?.name,
        category && categoryLabel(category, categories),
        ...line.tagIds.map((tagId) => tags.find((tag) => tag.id === tagId)?.name),
      ],
      [line.amountCents],
    );
  };
  const shown = upload.lines.filter(
    (line) =>
      (filter === null ? true : filter === "left_out" ? line.leftOut || line.duplicateOf !== null : isOpen(line) && line.budgetItemId === null) &&
      found(line),
  );
  const tickedLines = upload.lines.filter((line) => ticked.includes(line.id) && isOpen(line));
  const toggle = (lineId: number) => setTicked((before) => (before.includes(lineId) ? before.filter((each) => each !== lineId) : [...before, lineId]));
  // By pay period, in date order; lines in none at the end.
  const periods = [...new Set(shown.map((line) => line.periodStart ?? ""))].sort((a, b) => (a === "" ? 1 : b === "" ? -1 : a.localeCompare(b)));

  return (
    <Card title="Charges">
      <FilterPills<LineFilter>
        label="Show charges"
        name="Show"
        choices={[
          { value: "waiting", label: "Not put anywhere" },
          { value: "left_out", label: "Left out" },
        ]}
        value={filter}
        onChange={setFilter}
      />
      {tickedLines.length > 0 && (
        <div className="sticky top-0 z-10 mt-3 flex flex-wrap items-center gap-2 rounded-lg border border-line bg-surface p-2">
          <span className="font-medium">
            {tickedLines.length} ticked · <span data-amount>{formatCents(tickedLines.reduce((sum, line) => sum + line.amountCents, 0))}</span>
          </span>
          <button type="button" onClick={() => setChoosing(tickedLines)} className={BUTTON_PRIMARY}>
            Put {tickedLines.length} on a budget…
          </button>
          <button type="button" onClick={() => setFiling(tickedLines)} className={BUTTON_SECONDARY}>
            Category and tags…
          </button>
          <button type="button" onClick={() => setTicked([])} className={BUTTON_LINK}>
            Untick all
          </button>
        </div>
      )}
      {filed && (
        <p role="status" className="mt-2 font-medium">
          {filed}
        </p>
      )}
      {failed && (
        <p role="alert" className="mt-2 text-danger">
          {failed.message}
        </p>
      )}
      {shown.length === 0 && (
        <p className="mt-3 text-muted">
          {search.trim() !== "" ? `No charges match "${search.trim()}".` : filter === "waiting" ? "Every charge is put somewhere." : "Nothing here."}
        </p>
      )}
      {periods.map((start) => (
        <section key={start} aria-label={start === "" ? "In no pay period" : `Pay period from ${formatDate(start)}`}>
          <h3 className="pt-4 pb-1 text-sm font-semibold tracking-wide text-accent-ink uppercase">
            {start === "" ? "In no pay period" : `Pay period from ${formatDay(start)}`}
          </h3>
          <ul className="divide-y divide-line">
            {shown
              .filter((line) => (line.periodStart ?? "") === start)
              .map((line) => (
                <ChargeRow
                  key={line.id}
                  line={line}
                  accountId={upload.accountId}
                  budgets={budgetsFor(changes, line)}
                  candidates={candidates(line)}
                  categories={categories}
                  tags={tags}
                  ticked={ticked.includes(line.id)}
                  pending={pending}
                  onTick={() => toggle(line.id)}
                  onEdit={() => setEditing(line)}
                  onChooseBudget={() => setChoosing([line])}
                  onBudget={(itemId) => putOn.mutate({ lineIds: [line.id], itemId })}
                  onMatch={() => setMatching(line)}
                  onUnmatch={() => unmatch.mutate(line.id)}
                  onOneOff={(add) => oneOff.mutate({ lineId: line.id, add })}
                  onPutBack={() => change.mutate({ lineId: line.id, input: { categoryId: line.categoryId, tagIds: line.tagIds, leftOut: false } })}
                />
              ))}
          </ul>
        </section>
      ))}
      {editing && (
        <StatementLineForm
          uploadId={upload.id}
          line={editing}
          categories={categories}
          tags={tags}
          onFiled={(also) => setFiled(also === 0 ? undefined : `Also gave ${also} other ${also === 1 ? "charge" : "charges"} from the same payee that category and tags.`)}
          onClose={() => setEditing(undefined)}
        />
      )}
      {matching && <MatchDialog uploadId={upload.id} line={matching} candidates={candidates(matching)} onClose={() => setMatching(undefined)} />}
      {filing && (
        <FilingDialog
          uploadId={upload.id}
          lines={filing}
          categories={categories}
          tags={tags}
          onClose={(done) => {
            setFiling(undefined);
            if (done) {
              setTicked([]);
            }
          }}
        />
      )}
      {choosing && (
        <BudgetDialog
          uploadId={upload.id}
          lines={choosing}
          changes={changes}
          onClose={(done) => {
            setChoosing(undefined);
            if (done) {
              setTicked([]);
            }
          }}
        />
      )}
    </Card>
  );
}

function ChargeRow({
  line,
  accountId,
  budgets,
  candidates,
  categories,
  tags,
  ticked,
  pending,
  onTick,
  onEdit,
  onChooseBudget,
  onBudget,
  onMatch,
  onUnmatch,
  onOneOff,
  onPutBack,
}: {
  line: StatementLine;
  /** The statement's account, so a budget paid from another says so. */
  accountId: number;
  budgets: readonly BudgetChoice[];
  candidates: readonly Matchable[];
  categories: readonly Category[];
  tags: readonly Tag[];
  ticked: boolean;
  pending: boolean;
  onTick: () => void;
  onEdit: () => void;
  onChooseBudget: () => void;
  onBudget: (itemId: number | null) => void;
  onMatch: () => void;
  onUnmatch: () => void;
  onOneOff: (add: boolean) => void;
  onPutBack: () => void;
}) {
  const open = isOpen(line);
  const budget = budgets.find((each) => each.itemId === line.budgetItemId);
  const suggested = budgets.find((each) => each.itemId === line.suggestedItemId);
  const matched = line.matched === null ? undefined : candidates.find((candidate) => isMatch(line.matched, candidate));
  const oneOff = line.addOneOff || line.oneOffEntryId !== null;
  const category = categories.find((each) => each.id === line.categoryId);
  const canMatch = open && line.amountCents > 0 && candidates.some((candidate) => candidate.matchedLineId === null);
  const inPeriod = line.periodStart !== null;
  // Why a line isn't open, said in words: never by greying it out alone.
  const why = line.duplicateOf !== null ? "On an earlier statement too" : line.leftOut ? `${KIND_LABELS[line.kind]} · left out` : null;
  return (
    <li onClick={clickRowToEdit} className={`-mx-2 flex items-start gap-2 rounded-lg px-2 py-2 ${ROW_OPENS}`}>
      {open && inPeriod ? (
        <input type="checkbox" checked={ticked} onChange={onTick} aria-label={`Tick ${line.description}`} className="mt-1 size-5 shrink-0" />
      ) : (
        <span aria-hidden className="size-5 shrink-0" />
      )}
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2">
          <span className="font-medium wrap-anywhere">{line.description}</span>
          <span className="text-sm text-muted">{formatDay(line.txnDate)}</span>
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          {!line.sure && <span className="font-semibold text-warn-ink">Check this</span>}
          {why && <span className="text-muted">{why}</span>}
          {open && budget && (
            <>
              <span className="font-medium">On {budgetName(budget, accountId)}</span>
              <button type="button" disabled={pending} onClick={() => onBudget(null)} className={`${BUTTON_LINK} min-h-9`}>
                Take off
              </button>
            </>
          )}
          {open && !budget && suggested && (
            <>
              <span className="text-muted">{budgetName(suggested, accountId)} last time</span>
              <button type="button" disabled={pending} onClick={() => onBudget(suggested.itemId)} className={`${BUTTON_LINK} min-h-9`}>
                Use
              </button>
            </>
          )}
          {line.matched !== null && (
            <>
              <span className="font-medium">
                Is {matched ? `${matched.name} · ${formatDay(matched.date)}` : "something already in Budget"}
                {line.matched.itemId !== null && (line.matched.applied ? " · paid" : " · ticked paid on Apply")}
              </span>
              <button type="button" disabled={pending} onClick={onUnmatch} className={`${BUTTON_LINK} min-h-9`}>
                Unmatch
              </button>
            </>
          )}
          {line.oneOffEntryId !== null && <span className="font-medium">Added as a one-off</span>}
          {line.addOneOff && (
            <>
              <span className="font-medium">A one-off, added on Apply</span>
              <button type="button" disabled={pending} onClick={() => onOneOff(false)} className={`${BUTTON_LINK} min-h-9`}>
                Don't add
              </button>
            </>
          )}
          {open && inPeriod && budgets.length > 0 && (
            <button type="button" onClick={onChooseBudget} className={`${BUTTON_LINK} min-h-9`}>
              {budget ? "Another budget…" : "Put on a budget…"}
            </button>
          )}
          {canMatch && !budget && (
            <button type="button" onClick={onMatch} className={`${BUTTON_LINK} min-h-9`}>
              Already in Budget…
            </button>
          )}
          {open && !budget && (
            <button type="button" disabled={pending} onClick={() => onOneOff(true)} className={`${BUTTON_LINK} min-h-9`}>
              Add as a one-off
            </button>
          )}
          {line.leftOut && line.duplicateOf === null && (
            <button type="button" onClick={onPutBack} className={`${BUTTON_LINK} min-h-9`}>
              Put back
            </button>
          )}
        </div>
        {/* A one-off takes a category and tags, each labelled so they are never mistaken for each other. */}
        {oneOff && (category || line.tagIds.length > 0) && (
          <dl className="mt-1 space-y-1 text-sm">
            {category && (
              <div className="flex flex-wrap items-center gap-1.5">
                <dt className="text-muted">Category</dt>
                <dd
                  className="rounded-full px-2.5 py-0.5 font-semibold wrap-anywhere"
                  style={{ backgroundColor: category.color, color: readableTextOn(category.color) }}
                >
                  {categoryLabel(category, categories)}
                </dd>
              </div>
            )}
            {line.tagIds.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5">
                <dt className="text-muted">{line.tagIds.length === 1 ? "Tag" : "Tags"}</dt>
                <dd>
                  <TagChips tagIds={line.tagIds} tags={tags} />
                </dd>
              </div>
            )}
          </dl>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <span className={`text-right ${line.leftOut || line.duplicateOf !== null ? "text-muted line-through" : ""}`}>
          <Amount cents={line.amountCents} />
          <span className="sr-only">{line.amountCents < 0 ? " back" : " spent"}</span>
        </span>
        <EditButton name={line.description} onClick={onEdit} />
      </div>
    </li>
  );
}

/**
 * When the charges in a dialog were spent and the pay periods that puts them in, said at its top: the dialog covers the
 * rows, which is where the dates are (Rachel, 2026-10-30: "the modal blocks out the date on the charge so I can't tell
 * which pay period to put it in").
 */
function SpentWhen({ lines }: { lines: readonly StatementLine[] }) {
  const days = [...new Set(lines.map((line) => line.txnDate))].sort();
  const periods = [...new Set(lines.map((line) => line.periodStart))].sort((a, b) => (a ?? "").localeCompare(b ?? ""));
  const first = days[0] ?? "";
  const last = days[days.length - 1] ?? "";
  const spent = first === last ? formatDay(first) : `${formatDay(first)} – ${formatDay(last)}`;
  const inPeriods = periods.map((start) => (start === null ? "no pay period" : `the pay period from ${formatDay(start)}`)).join(" and ");
  return (
    <p className="font-medium">
      Spent {spent} · in {inPeriods}
    </p>
  );
}

/**
 * Which budget one charge, or several ticked, go on: the budgets of their pay periods paid from this account, a payee's
 * last one first. Each counts toward the date of the budget its day falls under; nothing changes until Apply.
 */
function BudgetDialog({
  uploadId,
  lines,
  changes,
  onClose,
}: {
  uploadId: number;
  lines: readonly StatementLine[];
  changes: StatementChanges | undefined;
  onClose: (done: boolean) => void;
}) {
  const putOn = usePutOnBudget(uploadId);
  // Every budget any of them can go on, by name; one some of them can't is refused by name when chosen.
  const choices = new Map<number, BudgetChoice>();
  lines.forEach((line) => budgetsFor(changes, line).forEach((budget) => choices.set(budget.itemId, budget)));
  const suggested = lines.length === 1 ? lines[0]?.suggestedItemId : null;
  const accountId = changes?.accountId;
  // Last time's first, then the statement's own account's budgets, then other accounts'.
  const sorted = [...choices.values()].sort(
    (a, b) =>
      Number(b.itemId === suggested) - Number(a.itemId === suggested) ||
      Number(b.accountId === accountId) - Number(a.accountId === accountId) ||
      a.accountName.localeCompare(b.accountName) ||
      a.name.localeCompare(b.name),
  );
  const total = lines.reduce((sum, line) => sum + line.amountCents, 0);
  const title = lines.length === 1 ? `${lines[0]?.description} · ${formatCents(total)}` : `${lines.length} charges · ${formatCents(total)}`;
  return (
    <Modal title={title} icon={ReceiptText} onClose={() => onClose(false)}>
      <div className="space-y-3">
        <SpentWhen lines={lines} />
        <p className="text-muted">
          Which budget? It becomes what was spent on it, once you apply the statement. A budget paid from another account keeps
          its total: these come off what is still to come there and are added here as one one-off.
        </p>
        <ul className="max-h-[50dvh] divide-y divide-line overflow-y-auto rounded-lg border border-line">
          {sorted.map((budget) => (
            <li key={budget.itemId}>
              <button
                type="button"
                disabled={putOn.isPending}
                onClick={() => putOn.mutate({ lineIds: lines.map((line) => line.id), itemId: budget.itemId }, { onSuccess: () => onClose(true) })}
                className="flex min-h-11 w-full items-center gap-3 px-3 py-2 text-left hover:bg-bg/60"
              >
                <span className="min-w-0 flex-1 wrap-anywhere">
                  <span className="font-medium">{budget.name}</span>
                  {budget.accountId !== accountId && <span className="block text-sm text-muted">Paid from {budget.accountName}</span>}
                </span>
                {budget.itemId === suggested && <span className="shrink-0 text-sm text-muted">Last time</span>}
              </button>
            </li>
          ))}
        </ul>
        {putOn.error && (
          <p role="alert" className="text-danger">
            {putOn.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={() => onClose(false)} className={BUTTON_SECONDARY}>
            Cancel
          </button>
        </div>
      </div>
    </Modal>
  );
}

/**
 * Which bill, transfer or one-off already in Budget a charge is: from its pay period or the one either side, same amount
 * first, leaving out any another charge already has. On Apply a bill takes the charge's amount and is ticked paid on its
 * day; a one-off is only linked.
 */
function MatchDialog({
  uploadId,
  line,
  candidates,
  onClose,
}: {
  uploadId: number;
  line: StatementLine;
  candidates: readonly Matchable[];
  onClose: () => void;
}) {
  const match = useMatchStatementLine(uploadId);
  const free = candidates.filter((candidate) => candidate.matchedLineId === null);
  const sorted = [...free].sort((a, b) => Number(b.amountCents === line.amountCents) - Number(a.amountCents === line.amountCents));
  return (
    <Modal title={`${line.description} · ${formatCents(line.amountCents)}`} icon={ReceiptText} onClose={onClose}>
      <div className="space-y-3">
        <SpentWhen lines={[line]} />
        <p className="text-muted">
          Which of these is it? On Apply a bill or transfer takes this amount and is ticked paid on {formatDay(line.txnDate)}, which puts it in
          this charge's pay period; a one-off you entered is just linked.
        </p>
        <ul className="max-h-[50dvh] divide-y divide-line overflow-y-auto rounded-lg border border-line">
          {sorted.map((candidate) => (
            <li key={candidate.entryId !== null ? `entry-${candidate.entryId}` : `item-${candidate.itemId}-${candidate.scheduledDate}`}>
              <button
                type="button"
                disabled={match.isPending}
                onClick={() => match.mutate({ lineId: line.id, match: candidate }, { onSuccess: onClose })}
                className="flex min-h-11 w-full items-center gap-3 px-3 py-2 text-left hover:bg-bg/60"
              >
                <span className="min-w-0 flex-1">
                  <span className="block font-medium wrap-anywhere">{candidate.name}</span>
                  <span className="block text-sm text-muted">
                    {candidate.entryId !== null ? "One-off" : "Recurring"} · {formatDay(candidate.date)}
                    {candidate.paid && candidate.entryId === null && " · paid"}
                    {line.periodStart !== null && candidate.periodStart < line.periodStart && " · last pay period"}
                    {line.periodStart !== null && candidate.periodStart > line.periodStart && " · next pay period"}
                    {candidate.amountCents === line.amountCents && " · same amount"}
                  </span>
                </span>
                <Amount cents={candidate.amountCents} className="shrink-0" />
              </button>
            </li>
          ))}
        </ul>
        {match.error && (
          <p role="alert" className="text-danger">
            {match.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
        </div>
      </div>
    </Modal>
  );
}

/** What Apply does, by pay period: each budget date's Now → Becomes, the bills matched, and the one-offs to add. */
function WhatChangesCard({ upload, changes, search }: { upload: StatementUpload; changes: StatementChanges; search: string }) {
  const own = changes.periods.filter((period) => upload.lines.some((line) => line.periodStart === period.start));
  const searching = search.trim() !== "";
  const shown = own.map((period) => ({ period, found: foundIn(period, changes, upload, search) })).filter(({ found }) => !searching || found.any);
  return (
    <Card title="What changes" tip="statementUpload">
      <p className={HINT}>
        A budget becomes what was spent on it, plus what is still to come for days no statement covers yet. Statements of this account add
        together.
      </p>
      {own.length === 0 && <p className="mt-3 text-muted">None of this statement's charges fall in a pay period.</p>}
      {searching && shown.length === 0 && <p className="mt-3 text-muted">{`Nothing here matches "${search.trim()}".`}</p>}
      {shown.map(({ period, found }) => (
        <PeriodSection key={period.start} uploadId={upload.id} period={period} changes={changes} found={found} />
      ))}
      {changes.outside.length > 0 && (
        <p className="mt-3 text-sm text-muted">
          {changes.outside.length === 1 ? "One charge falls" : `${changes.outside.length} charges fall`} outside every pay period, so it changes
          nothing.
        </p>
      )}
    </Card>
  );
}

/** What a pay period's What changes shows for the search: its budget dates, matches and one-offs that match it. */
interface FoundInPeriod {
  changes: readonly BudgetDateChange[];
  matches: readonly PeriodMatch[];
  oneOffs: readonly StatementLine[];
  any: boolean;
}

/**
 * A budget date is found by its name, its amounts or any charge on it; a match by the charge or what it is; a one-off by
 * its charge.
 */
function foundIn(period: PeriodChanges, changes: StatementChanges, upload: StatementUpload, search: string): FoundInPeriod {
  const own = new Map(upload.lines.map((line) => [line.id, line]));
  const dates = period.changes.filter((change) => {
    const its = changes.lines.filter((line) => change.lineIds.includes(line.id));
    return matchesSearch(
      search,
      [change.name, ...its.map((line) => line.description)],
      [change.currentCents, change.newCents, ...its.map((line) => line.amountCents)],
    );
  });
  const matches = period.matches.filter((match) => matchesSearch(search, [match.description, match.name], [match.amountCents]));
  const oneOffs = period.oneOffs
    .map((lineId) => own.get(lineId))
    .filter((line): line is StatementLine => line !== undefined && matchesSearch(search, [line.description], [line.amountCents]));
  return { changes: dates, matches, oneOffs, any: dates.length > 0 || matches.length > 0 || oneOffs.length > 0 };
}

function PeriodSection({
  uploadId,
  period,
  changes,
  found,
}: {
  uploadId: number;
  period: PeriodChanges;
  changes: StatementChanges;
  found: FoundInPeriod;
}) {
  const oneOffs = found.oneOffs;
  const nothing = period.changes.length === 0 && period.matches.length === 0 && oneOffs.length === 0 && period.restores === 0;
  return (
    <section aria-label={`Pay period ${formatDay(period.start)} – ${formatDay(period.end)}`} className="border-t border-line pt-3 mt-3 first-of-type:mt-3">
      <h3 className="flex flex-wrap items-baseline justify-between gap-x-3 font-semibold">
        <span>
          {formatDay(period.start)} – {formatDay(period.end)}
          {period.current && <span className="ml-2 text-sm font-normal text-accent-ink">This pay period</span>}
        </span>
        {period.coverage?.partly && (
          <span className="text-sm font-normal text-warn-ink">
            Statements cover {formatDay(period.coverage.from)} – {formatDay(period.coverage.to)}
          </span>
        )}
      </h3>
      {nothing && <p className="mt-1 text-sm text-muted">Nothing to change yet.</p>}
      {found.changes.length > 0 && (
        <ul className="mt-1 divide-y divide-line">
          {found.changes.map((change) => (
            <BudgetDateRow key={`${change.itemId}-${change.scheduledDate}`} uploadId={uploadId} change={change} changes={changes} />
          ))}
        </ul>
      )}
      {found.matches.length > 0 && (
        <div className="mt-2">
          <h4 className="text-sm font-semibold text-muted">Already in Budget</h4>
          <ul className="text-sm">
            {found.matches.map((match) => (
              <li key={match.lineId} className="flex flex-wrap justify-between gap-x-3 py-1">
                <span className="min-w-0 wrap-anywhere">
                  {match.description} → {match.name}
                  {match.bill && <span className="text-muted">{match.applied ? " · paid" : " · ticked paid on Apply"}</span>}
                </span>
                <Amount cents={match.amountCents} />
              </li>
            ))}
          </ul>
        </div>
      )}
      {oneOffs.length > 0 && (
        <div className="mt-2">
          <h4 className="text-sm font-semibold text-muted">One-offs to add</h4>
          <ul className="text-sm">
            {oneOffs.map((line) => (
              <li key={line.id} className="flex flex-wrap justify-between gap-x-3 py-1">
                <span className="min-w-0 wrap-anywhere">
                  {line.description} <span className="text-muted">{formatDay(line.txnDate)}</span>
                </span>
                <Amount cents={line.amountCents} />
              </li>
            ))}
          </ul>
        </div>
      )}
      {period.restores > 0 && (
        <p className="mt-2 text-sm text-muted">
          {period.restores === 1 ? "One bill goes" : `${period.restores} bills go`} back to how it was before it was matched.
        </p>
      )}
    </section>
  );
}

/** One budget date: Now → Becomes, what was spent plus still to come (Edit), and the charges behind it. */
function BudgetDateRow({ uploadId, change, changes }: { uploadId: number; change: BudgetDateChange; changes: StatementChanges }) {
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const id = useId();
  const its = changes.lines.filter((line) => change.lineIds.includes(line.id));
  const reverting = change.lineIds.length === 0;
  // Coverage is the budget's own account's: for a budget paid from another account, say whose statements count.
  const own = change.accountId === changes.accountId;
  const coverage =
    change.covered === "fully"
      ? `${own ? "the statements" : `${change.accountName}'s statements`} cover all of it`
      : change.covered === "partly" && change.coveredThrough
        ? `${own ? "the statements" : `${change.accountName}'s statements`} cover it to ${formatDay(change.coveredThrough)}`
        : `no ${own ? "" : `${change.accountName} `}statement covers it yet`;
  return (
    <li className="py-2">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-medium wrap-anywhere">{budgetName(change, changes.accountId)}</p>
          <p className="text-sm text-muted">
            {formatDay(change.from)}
            {change.to !== change.from && ` – ${formatDay(change.to)}`} · {coverage}
          </p>
        </div>
        <p className="shrink-0 text-right">
          <span className="text-sm text-muted">
            Now <span data-amount>{formatCents(change.currentCents)}</span>
          </span>
          <span className="block font-semibold">
            Becomes <span data-amount>{formatCents(change.newCents)}</span>
          </span>
        </p>
      </div>
      {reverting ? (
        <p className="mt-1 text-sm text-muted">No charges on it now, so it goes back to the {formatCents(change.budgetedCents)} budgeted.</p>
      ) : (
        <div className="mt-1 flex flex-wrap items-center gap-x-2 text-sm">
          <span>
            Spent <span data-amount>{formatCents(change.actualCents)}</span> + still to come{" "}
            <span data-amount>{formatCents(change.stillToComeCents)}</span>
            {change.stillTyped && " (typed)"}
          </span>
          {change.elsewhere.map((each) => (
            <span key={each.accountId}>
              · <span data-amount>{formatCents(each.cents)}</span> as a one-off on {each.accountName}
            </span>
          ))}
          {change.covered !== "fully" && <EditButton name={`still to come for ${change.name}`} onClick={() => setEditing(true)} />}
          <button
            type="button"
            aria-expanded={open}
            aria-controls={id}
            onClick={() => setOpen(!open)}
            className={`${BUTTON_LINK} inline-flex min-h-9 items-center gap-1`}
          >
            {its.length === 1 ? "1 charge" : `${its.length} charges`}
            {open ? <ChevronUp aria-hidden className="size-4" /> : <ChevronDown aria-hidden className="size-4" />}
          </button>
        </div>
      )}
      {open && (
        <ul id={id} className="mt-1 space-y-1 rounded-lg bg-bg/60 p-2 text-sm">
          {its.map((line) => (
            <li key={line.id} className="flex flex-wrap justify-between gap-x-3">
              <span className="min-w-0 wrap-anywhere">
                {formatDay(line.txnDate)} · {line.description}
                {line.uploadId !== changes.uploadId && <span className="text-muted"> · {line.uploadName}</span>}
              </span>
              <Amount cents={line.amountCents} />
            </li>
          ))}
        </ul>
      )}
      {editing && <StillDialog uploadId={uploadId} change={change} onClose={() => setEditing(false)} />}
    </li>
  );
}

/** Types still to come for a budget date the statements only partly cover, or works it out again (budget less spent). */
function StillDialog({ uploadId, change, onClose }: { uploadId: number; change: BudgetDateChange; onClose: () => void }) {
  const save = useStatementStillToCome(uploadId);
  const [value, setValue] = useState(centsToInput(change.stillToComeCents));
  const [error, setError] = useState<string>();
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const cents = parseDollars(value);
    if (cents === null || cents < 0) {
      setError(AMOUNT_HELP);
      return;
    }
    setError(undefined);
    save.mutate({ itemId: change.itemId, scheduledDate: change.scheduledDate, cents }, { onSuccess: onClose });
  };
  return (
    <Modal title={`${change.name} · still to come`} icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className={HINT}>
          What you expect to spend on the days no statement covers yet. Spent so far:{" "}
          {formatCents(change.actualCents + change.elsewhere.reduce((sum, each) => sum + each.cents, 0))} of{" "}
          {formatCents(change.budgetedCents)} budgeted. Once a statement covers the rest, this goes to nothing.
        </p>
        <MoneyField label="Still to come" value={value} onChange={setValue} error={error} required autoFocus />
        {save.error && (
          <p role="alert" className="text-danger">
            {save.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          {change.stillTyped && (
            <button
              type="button"
              disabled={save.isPending}
              onClick={() => save.mutate({ itemId: change.itemId, scheduledDate: change.scheduledDate, cents: null }, { onSuccess: onClose })}
              className={BUTTON_SECONDARY}
            >
              Budget less spent
            </button>
          )}
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Which pay periods to apply: every one with something to do, ticked to start with. */
function ApplyDialog({ uploadId, periods, onClose }: { uploadId: number; periods: readonly PeriodChanges[]; onClose: () => void }) {
  const apply = useApplyStatement(uploadId);
  const [chosen, setChosen] = useState<string[]>(periods.map((period) => period.start));
  const toggle = (start: string) => setChosen((before) => (before.includes(start) ? before.filter((each) => each !== start) : [...before, start]));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    apply.mutate({ periods: chosen }, { onSuccess: onClose });
  };

  return (
    <Modal title="Apply the statement" icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-muted">
          Budgets take what was spent plus still to come, matched bills are ticked paid, and one-offs are added. Hovering a budget's amount on
          the pay period shows what was budgeted. Undo takes it all back.
        </p>
        <fieldset>
          <legend className="mb-1 font-medium">Pay periods</legend>
          <ul className="divide-y divide-line">
            {periods.map((period) => {
              const amounts = period.changes.filter((change) => change.newCents !== change.currentCents).length;
              const bills = period.matches.filter((match) => match.bill && !match.applied).length;
              const parts = [
                amounts > 0 && (amounts === 1 ? "1 budget changes" : `${amounts} budgets change`),
                bills > 0 && (bills === 1 ? "1 bill ticked paid" : `${bills} bills ticked paid`),
                period.oneOffs.length > 0 && (period.oneOffs.length === 1 ? "1 one-off" : `${period.oneOffs.length} one-offs`),
                period.restores > 0 && (period.restores === 1 ? "1 bill put back" : `${period.restores} bills put back`),
              ].filter(Boolean);
              return (
                <li key={period.start}>
                  <label className="flex min-h-11 cursor-pointer items-center gap-3 py-1">
                    <input type="checkbox" checked={chosen.includes(period.start)} onChange={() => toggle(period.start)} className="size-5 shrink-0" />
                    <span className="min-w-0 flex-1">
                      <span className="block font-medium">
                        {formatDay(period.start)} – {formatDay(period.end)}
                      </span>
                      <span className="block text-sm text-muted">{parts.length > 0 ? parts.join(" · ") : "Links only"}</span>
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        </fieldset>
        {apply.error && (
          <p role="alert" className="text-danger">
            {apply.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={chosen.length === 0 || apply.isPending} className={BUTTON_PRIMARY}>
            {apply.isPending ? "Applying…" : "Apply"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
