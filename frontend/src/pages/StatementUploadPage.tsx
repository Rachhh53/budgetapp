import { ArrowRight, Check, ChevronDown, ChevronUp, ReceiptText, TriangleAlert } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { type Category, type Tag, useCategories, useTags } from "../api/categories";
import {
  type CountedLine,
  type GroupTotal,
  type ItemChoice,
  type PeriodChanges,
  type ReplaceMode,
  type StatementChanges,
  type StatementLine,
  type StatementUpload,
  statementPreviewUrl,
  useApplyStatement,
  useChangeStatementLine,
  useDeleteStatementUpload,
  useSetStatementTarget,
  useStatementChanges,
  useStatementUpload,
} from "../api/statementUploads";
import { Amount } from "../components/Amount";
import { Card } from "../components/Card";
import { EditButton } from "../components/EditButton";
import { FilePreview } from "../components/files/Receipts";
import { FilterPills } from "../components/FilterPills";
import { HelpLink } from "../components/help/HelpLink";
import { TagChips } from "../components/expenses/TagChips";
import { Modal } from "../components/Modal";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { Segmented } from "../components/Segmented";
import { KIND_LABELS, StatementLineForm } from "../components/statements/StatementLineForm";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, INPUT, PAGE_TITLE } from "../components/ui";
import { formatDate, formatDay } from "../lib/dates";
import { formatCents } from "../lib/money";
import { readableTextOn } from "../lib/color";

/**
 * One statement upload (Rachel, 2026-10-25): every line read from it, filed by category and tag, and what its totals
 * would change in each pay period. Nothing is chosen for her: each category's or tag's total changes only the budget or
 * recurring item she picks under Replaces, replacing its amount or adding to it, and only once she applies it.
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
  const toApply = (changes.data?.periods ?? []).filter((period) => !period.upToDate && period.changes.length > 0);

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
          <p className="text-muted">
            {data.name} · read on this PC. Nothing changes until you choose what each total replaces and apply it.
          </p>
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
        <Notices upload={data} changes={changes.data} />
        {remove.isError && (
          <p role="alert" className="text-danger">
            {remove.error.message}
          </p>
        )}
        <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-2">
          <ChargesCard upload={data} categories={categories} tags={tags} />
          <div className="space-y-4">
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
            {changes.data && <WhatChangesCard upload={data} changes={changes.data} categories={categories} tags={tags} />}
            {changes.data && <NotMatchedCard upload={data} changes={changes.data} />}
          </div>
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

/** What wants a look: lines read with a guess, CSV rows that couldn't be read, payees new to Budget, choices to make. */
function Notices({ upload, changes }: { upload: StatementUpload; changes: StatementChanges | undefined }) {
  const newPayees = upload.lines.filter((line) => line.newPayee && line.categoryId === null && line.tagIds.length === 0 && counted(line)).length;
  const undecided = (changes?.periods ?? []).reduce((sum, period) => sum + period.undecided.length, 0);
  const notes: string[] = [];
  if (upload.toCheck > 0) {
    notes.push(`${upload.toCheck === 1 ? "One line was" : `${upload.toCheck} lines were`} hard to read; each is marked Check this.`);
  }
  if (upload.skippedRows.length > 0) {
    notes.push(`Rows ${upload.skippedRows.join(", ")} of the file had no date or amount Budget could read, so they were left out.`);
  }
  if (newPayees > 0) {
    notes.push(
      newPayees === 1
        ? "One charge is from a payee Budget hasn't seen yet: give it a category or tags to total it."
        : `${newPayees} charges are from payees Budget hasn't seen yet: give them a category or tags to total them.`,
    );
  }
  if (undecided > 0) {
    notes.push(`${undecided === 1 ? "One charge could" : `${undecided} charges could`} count toward two items; choose which under What changes.`);
  }
  if (notes.length === 0) {
    return (
      <p role="status" className="flex items-start gap-2 rounded-lg border border-line bg-surface p-3">
        <Check aria-hidden className="mt-0.5 size-5 shrink-0 text-accent-ink" />
        <span>Every charge is filed. Choose under What changes which item each total replaces.</span>
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

function counted(line: StatementLine): boolean {
  return !line.leftOut && line.duplicateOf === null && line.oneOffEntryId === null;
}

type LineFilter = "to_file" | "left_out";

/**
 * Every line, by pay period, each with its category and tags and an Edit to file it. Left-out lines (payments,
 * transfers, repeats of an earlier statement) stay listed, saying why, with Put back where that applies.
 */
function ChargesCard({ upload, categories, tags }: { upload: StatementUpload; categories: readonly Category[]; tags: readonly Tag[] }) {
  const change = useChangeStatementLine(upload.id);
  const [editing, setEditing] = useState<StatementLine>();
  const [filter, setFilter] = useState<LineFilter | null>(null);
  const [filed, setFiled] = useState<string>();
  const shown = upload.lines.filter((line) =>
    filter === null ? true : filter === "left_out" ? !counted(line) : counted(line) && line.categoryId === null && line.tagIds.length === 0,
  );
  // By pay period, in date order; lines in none at the end.
  const periods = [...new Set(shown.map((line) => line.periodStart ?? ""))].sort((a, b) => (a === "" ? 1 : b === "" ? -1 : a.localeCompare(b)));

  return (
    <Card title="Charges">
      <FilterPills<LineFilter>
        label="Show charges"
        name="Show"
        choices={[
          { value: "to_file", label: "To file" },
          { value: "left_out", label: "Left out" },
        ]}
        value={filter}
        onChange={setFilter}
      />
      {filed && (
        <p role="status" className="mt-2 font-medium">
          {filed}
        </p>
      )}
      {change.isError && (
        <p role="alert" className="mt-2 text-danger">
          {change.error.message}
        </p>
      )}
      {shown.length === 0 && <p className="mt-3 text-muted">{filter === "to_file" ? "Every charge is filed." : "Nothing here."}</p>}
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
                  categories={categories}
                  tags={tags}
                  onEdit={() => setEditing(line)}
                  onPutBack={() =>
                    change.mutate({ lineId: line.id, input: { categoryId: line.categoryId, tagIds: line.tagIds, leftOut: false, countsToward: line.countsToward } })
                  }
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
          onFiled={(also) => setFiled(also === 0 ? undefined : `Also filed ${also} other ${also === 1 ? "charge" : "charges"} from the same payee.`)}
          onClose={() => setEditing(undefined)}
        />
      )}
    </Card>
  );
}

function ChargeRow({
  line,
  categories,
  tags,
  onEdit,
  onPutBack,
}: {
  line: StatementLine;
  categories: readonly Category[];
  tags: readonly Tag[];
  onEdit: () => void;
  onPutBack: () => void;
}) {
  const category = categories.find((each) => each.id === line.categoryId);
  // Why a line isn't counted, said in words: never by greying it out alone.
  const why =
    line.oneOffEntryId !== null
      ? "Added as a one-off"
      : line.duplicateOf !== null
        ? "On an earlier statement too"
        : line.leftOut
          ? `${KIND_LABELS[line.kind]} · left out`
          : null;
  return (
    <li onClick={clickRowToEdit} className={`-mx-2 flex items-start gap-2 rounded-lg px-2 py-2 ${ROW_OPENS}`}>
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2">
          <span className="font-medium wrap-anywhere">{line.description}</span>
          <span className="text-sm text-muted">{formatDay(line.txnDate)}</span>
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-1 text-sm">
          {category ? (
            <span
              className="rounded-full px-2.5 py-0.5 font-semibold"
              style={{ backgroundColor: category.color, color: readableTextOn(category.color) }}
            >
              {category.name}
            </span>
          ) : (
            line.tagIds.length === 0 && counted(line) && <span className="font-medium text-warn-ink">Not filed yet</span>
          )}
          <TagChips tagIds={line.tagIds} tags={tags} />
          {!line.sure && <span className="font-semibold text-warn-ink">Check this</span>}
          {why && <span className="text-muted">{why}</span>}
          {line.leftOut && line.duplicateOf === null && line.oneOffEntryId === null && (
            <button type="button" onClick={onPutBack} className={`${BUTTON_LINK} min-h-9`}>
              Put back
            </button>
          )}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <span className={`text-right ${counted(line) ? "" : "text-muted line-through"}`}>
          <Amount cents={line.amountCents} />
          <span className="sr-only">{line.amountCents < 0 ? " back" : " spent"}</span>
        </span>
        <EditButton name={line.description} onClick={onEdit} />
      </div>
    </li>
  );
}

/**
 * Each pay period the statement reaches into: its category and tag totals, and for each the item it replaces, chosen
 * here and never by itself. An item chosen last time is offered with Use.
 */
function WhatChangesCard({
  upload,
  changes,
  categories,
  tags,
}: {
  upload: StatementUpload;
  changes: StatementChanges;
  categories: readonly Category[];
  tags: readonly Tag[];
}) {
  if (changes.periods.length === 0) {
    return (
      <Card title="What changes">
        <p className="text-muted">
          These dates are in no pay period yet. Pay periods come from your primary paycheck; add it on the Income screen.
        </p>
      </Card>
    );
  }
  return (
    <Card title="What changes" tip="statementUpload">
      <p className={HINT}>
        For each total, choose under Replaces which budget or recurring item of that pay period it changes. Replace makes the amount the total;
        Add puts the total on top of what it is now.
      </p>
      <div className="mt-2 divide-y divide-line">
        {changes.periods.map((period) => (
          <PeriodSection key={period.start} upload={upload} changes={changes} period={period} categories={categories} tags={tags} />
        ))}
      </div>
    </Card>
  );
}

function PeriodSection({
  upload,
  changes,
  period,
  categories,
  tags,
}: {
  upload: StatementUpload;
  changes: StatementChanges;
  period: PeriodChanges;
  categories: readonly Category[];
  tags: readonly Tag[];
}) {
  const target = useSetStatementTarget(upload.id);
  return (
    <section aria-label={`Pay period ${formatDate(period.start)} to ${formatDate(period.end)}`} className="py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-semibold">
          {formatDay(period.start)} – {formatDay(period.end)}
          {period.current && <span className="ml-2 text-sm font-medium text-accent-ink">This pay period</span>}
        </h3>
        {period.coverage && (
          <p className={`text-sm ${period.coverage.partly ? "font-medium text-warn-ink" : "text-muted"}`}>
            {period.coverage.partly
              ? `Partly covered · ${formatDay(period.coverage.from)} – ${formatDay(period.coverage.to)}`
              : "Every day covered"}
          </p>
        )}
      </div>
      {period.groups.length === 0 && <p className="mt-1 text-muted">No filed charges in this pay period yet.</p>}
      {period.items.length === 0 && period.groups.length > 0 && (
        <p className="mt-1 text-sm text-muted">No budget or recurring item is paid from {upload.accountName} in this pay period.</p>
      )}
      <ul className="mt-1 divide-y divide-line">
        {period.groups.map((group) => (
          <GroupRow
            key={`${group.type}-${group.id}`}
            group={group}
            period={period}
            lines={changes.lines}
            uploadId={upload.id}
            pending={target.isPending}
            onTarget={(itemId, mode) =>
              target.mutate({ periodStart: period.start, groupType: group.type, groupId: group.id, itemId, mode })
            }
          />
        ))}
      </ul>
      {target.isError && (
        <p role="alert" className="mt-1 text-danger">
          {target.error.message}
        </p>
      )}
      {period.undecided.length > 0 && (
        <Undecided upload={upload} changes={changes} period={period} categories={categories} tags={tags} />
      )}
      {period.unmatched.length > 0 && (
        <p className="mt-2 text-sm text-muted">
          {period.unmatched.length === 1 ? "One charge" : `${period.unmatched.length} charges`} no chosen item takes; see Not matched.
        </p>
      )}
    </section>
  );
}

/** What a chosen item comes to now and would come to, over its dates in the period. */
function itemFigures(period: PeriodChanges, item: ItemChoice) {
  const now = item.dates.reduce((sum, date) => sum + date.currentCents, 0);
  const changes = period.changes.filter((change) => change.itemId === item.itemId);
  const becomes = changes.length === 0 ? now : changes.reduce((sum, change) => sum + change.newCents, 0);
  return { now, becomes };
}

function GroupRow({
  group,
  period,
  lines,
  uploadId,
  pending,
  onTarget,
}: {
  group: GroupTotal;
  period: PeriodChanges;
  lines: readonly CountedLine[];
  /** This statement: a charge from another statement of the account says which. */
  uploadId: number;
  pending: boolean;
  onTarget: (itemId: number | null, mode: ReplaceMode) => void;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const chosen = period.items.find((item) => item.itemId === group.target?.itemId);
  const hint = group.target === null ? period.items.find((item) => item.itemId === group.hintItemId) : undefined;
  const mode = group.target?.mode ?? "replace";
  const figures = chosen ? itemFigures(period, chosen) : null;
  const its = lines.filter((line) => group.lineIds.includes(line.id));

  return (
    <li className="py-2">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="flex items-center gap-2 font-medium">
            {group.color && <span aria-hidden className="size-3 shrink-0 rounded-full" style={{ backgroundColor: group.color }} />}
            <span className="wrap-anywhere">{group.name}</span>
            <span className="text-sm font-normal text-muted">{group.type === "category" ? "Category" : "Tag"}</span>
          </p>
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen(!open)}
            className={`${BUTTON_LINK} -ml-2 inline-flex min-h-9 items-center gap-1 text-sm`}
          >
            {its.length === 1 ? "1 charge" : `${its.length} charges`}
            {open ? <ChevronUp aria-hidden className="size-4" /> : <ChevronDown aria-hidden className="size-4" />}
          </button>
        </div>
        <Amount cents={group.totalCents} className="shrink-0 font-semibold" />
      </div>
      {open && (
        <ul className="mb-2 rounded-lg bg-bg p-2 text-sm">
          {its.map((line) => (
            <li key={line.id} className="flex items-baseline justify-between gap-2 py-0.5">
              <span className="min-w-0 wrap-anywhere">
                {formatDay(line.txnDate)} · {line.description}
                {line.uploadId !== uploadId && (
                  <span className="text-muted"> · {line.uploadName}</span>
                )}
              </span>
              <span data-amount className="shrink-0 tabular-nums">
                {formatCents(line.amountCents)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {period.items.length > 0 && (
        <div className="mt-1 grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
          <div>
            <label htmlFor={`${id}-item`} className="mb-1 block text-sm font-medium">
              Replaces
            </label>
            <select
              id={`${id}-item`}
              value={group.target?.itemId ?? ""}
              disabled={pending}
              onChange={(event) => onTarget(event.target.value === "" ? null : Number(event.target.value), mode)}
              className={INPUT}
            >
              <option value="">Nothing</option>
              {period.items.map((item) => (
                <option key={item.itemId} value={item.itemId}>
                  {item.name} · {formatCents(item.dates.reduce((sum, date) => sum + date.currentCents, 0))}
                  {item.dates.length > 1 ? ` over ${item.dates.length} dates` : ""}
                </option>
              ))}
            </select>
          </div>
          {chosen && (
            <Segmented
              legend="How"
              options={[
                { value: "replace", label: "Replace" },
                { value: "add", label: "Add" },
              ]}
              value={mode}
              onChange={(next) => onTarget(chosen.itemId, next)}
            />
          )}
        </div>
      )}
      {hint && (
        <p className="mt-1 flex flex-wrap items-center gap-x-2 text-sm text-muted">
          Last time: {hint.name}
          <button type="button" disabled={pending} onClick={() => onTarget(hint.itemId, "replace")} className={`${BUTTON_LINK} min-h-9`}>
            Use
          </button>
        </p>
      )}
      {chosen && figures && (
        <p className="mt-1 flex flex-wrap items-center gap-2 text-sm">
          <span className="text-muted">{chosen.name}: now</span>
          <Amount cents={figures.now} estimate={chosen.dates.some((date) => date.estimate)} />
          <ArrowRight aria-hidden className="size-4 text-muted" />
          <span className="text-muted">becomes</span>
          <Amount cents={figures.becomes} className="font-semibold" />
          {period.upToDate && <span className="text-muted">· applied</span>}
        </p>
      )}
    </li>
  );
}

/**
 * Charges whose category and a tag are both pointed at items, each with a button per item it could count toward.
 * Nothing in the pay period can be applied until each is settled.
 */
function Undecided({
  upload,
  changes,
  period,
  categories,
  tags,
}: {
  upload: StatementUpload;
  changes: StatementChanges;
  period: PeriodChanges;
  categories: readonly Category[];
  tags: readonly Tag[];
}) {
  const change = useChangeStatementLine(upload.id);
  return (
    <div className="mt-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
      <p className="font-semibold">Which item should these count toward?</p>
      <ul className="mt-1 space-y-2">
        {period.undecided.map((lineId) => {
          const line = changes.lines.find((each) => each.id === lineId);
          if (!line) {
            return null;
          }
          const options = period.groups.filter(
            (group) =>
              group.target !== null &&
              (group.type === "category" ? group.id === line.categoryId : line.tagIds.includes(group.id)),
          );
          return (
            <li key={lineId}>
              <p>
                {formatDay(line.txnDate)} · {line.description} · <span data-amount>{formatCents(line.amountCents)}</span>
              </p>
              <div className="flex flex-wrap gap-2">
                {options.map((group) => {
                  const item = period.items.find((each) => each.itemId === group.target?.itemId);
                  const name = group.type === "category" ? categories.find((each) => each.id === group.id)?.name : tags.find((each) => each.id === group.id)?.name;
                  return (
                    <button
                      key={`${group.type}-${group.id}`}
                      type="button"
                      disabled={change.isPending}
                      onClick={() =>
                        change.mutate({
                          lineId,
                          input: { categoryId: line.categoryId, tagIds: line.tagIds, countsToward: { type: group.type, id: group.id } },
                        })
                      }
                      className={`${BUTTON_SECONDARY} text-ink`}
                    >
                      {name ?? group.name} → {item?.name ?? ""}
                    </button>
                  );
                })}
              </div>
            </li>
          );
        })}
      </ul>
      {change.isError && (
        <p role="alert" className="mt-1 text-danger">
          {change.error.message}
        </p>
      )}
    </div>
  );
}

/** This statement's charges no chosen item takes, which can be added as one-offs of their own instead. */
function NotMatchedCard({ upload, changes }: { upload: StatementUpload; changes: StatementChanges }) {
  const apply = useApplyStatement(upload.id);
  const [chosen, setChosen] = useState<number[]>([]);
  const own = new Map(upload.lines.map((line) => [line.id, line]));
  const unmatched = changes.periods.flatMap((period) => period.unmatched).filter((lineId) => own.has(lineId));
  if (unmatched.length === 0) {
    return null;
  }
  const toggle = (lineId: number) => setChosen((before) => (before.includes(lineId) ? before.filter((each) => each !== lineId) : [...before, lineId]));
  return (
    <Card title="Not matched">
      <p className={HINT}>
        No chosen item takes these. Leave them, choose an item for their category or tag above, or add any of them as one-offs of their own.
      </p>
      <ul className="mt-2 divide-y divide-line">
        {unmatched.map((lineId) => {
          const line = own.get(lineId);
          if (!line) {
            return null;
          }
          return (
            <li key={lineId}>
              <label className="flex min-h-11 cursor-pointer items-center gap-3 py-1">
                <input type="checkbox" checked={chosen.includes(lineId)} onChange={() => toggle(lineId)} className="size-5 shrink-0" />
                <span className="min-w-0 flex-1 wrap-anywhere">
                  {line.description} <span className="text-sm text-muted">{formatDay(line.txnDate)}</span>
                </span>
                <Amount cents={line.amountCents} className="shrink-0" />
              </label>
            </li>
          );
        })}
      </ul>
      {apply.isError && (
        <p role="alert" className="mt-1 text-danger">
          {apply.error.message}
        </p>
      )}
      {apply.isSuccess && (
        <p role="status" className="mt-1 font-medium">
          Added {apply.data.oneOffsAdded} {apply.data.oneOffsAdded === 1 ? "one-off" : "one-offs"}.
        </p>
      )}
      <button
        type="button"
        disabled={chosen.length === 0 || apply.isPending}
        onClick={() => apply.mutate({ periods: [], oneOffLineIds: chosen }, { onSuccess: () => setChosen([]) })}
        className={`${BUTTON_SECONDARY} mt-2`}
      >
        {apply.isPending ? "Adding…" : chosen.length === 0 ? "Add as one-offs" : `Add ${chosen.length} as ${chosen.length === 1 ? "a one-off" : "one-offs"}`}
      </button>
    </Card>
  );
}

/**
 * Which pay periods to apply. A period the statements only partly cover starts unticked: the rest of its charges may
 * still be on the next statement. Only amounts change, just for those dates.
 */
function ApplyDialog({ uploadId, periods, onClose }: { uploadId: number; periods: readonly PeriodChanges[]; onClose: () => void }) {
  const apply = useApplyStatement(uploadId);
  const [chosen, setChosen] = useState<string[]>(periods.filter((period) => !period.coverage?.partly).map((period) => period.start));
  const toggle = (start: string) => setChosen((before) => (before.includes(start) ? before.filter((each) => each !== start) : [...before, start]));
  const blocked = periods.filter((period) => chosen.includes(period.start) && period.undecided.length > 0);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    apply.mutate({ periods: chosen, oneOffLineIds: [] }, { onSuccess: onClose });
  };

  return (
    <Modal title="Apply the statement" icon={ReceiptText} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-muted">
          Only the amounts change, just for these dates: each date, paid tick and note stays as it is, and hovering an amount shows what it
          was. Undo takes it back.
        </p>
        <fieldset>
          <legend className="mb-1 font-medium">Pay periods</legend>
          <ul className="divide-y divide-line">
            {periods.map((period) => {
              const count = period.changes.filter((change) => change.newCents !== change.currentCents).length;
              return (
                <li key={period.start}>
                  <label className="flex min-h-11 cursor-pointer items-center gap-3 py-1">
                    <input type="checkbox" checked={chosen.includes(period.start)} onChange={() => toggle(period.start)} className="size-5 shrink-0" />
                    <span className="min-w-0 flex-1">
                      <span className="block font-medium">
                        {formatDay(period.start)} – {formatDay(period.end)}
                      </span>
                      <span className="block text-sm text-muted">
                        {count === 1 ? "1 amount changes" : `${count} amounts change`}
                        {period.coverage?.partly && ` · partly covered, to ${formatDay(period.coverage.to)}`}
                      </span>
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        </fieldset>
        {blocked.length > 0 && (
          <p role="alert" className="font-medium text-warn-ink">
            Some charges in {blocked.map((period) => formatDay(period.start)).join(", ")} could count toward two items. Choose which first.
          </p>
        )}
        {apply.error && (
          <p role="alert" className="text-danger">
            {apply.error.message}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={chosen.length === 0 || blocked.length > 0 || apply.isPending} className={BUTTON_PRIMARY}>
            {apply.isPending ? "Applying…" : "Apply"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
