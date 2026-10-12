import { Check, Download, Plus, TriangleAlert } from "lucide-react";
import { useId, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { type Account, useAccounts } from "../api/accounts";
import { savedMessage } from "../api/client";
import { useIncomeSources } from "../api/income";
import {
  type PayStub,
  STUB_APPLIES,
  STUB_SECTIONS,
  type StubApply,
  type StubLine,
  type StubLineInput,
  stubPreviewUrl,
  useChangeStub,
  useDeleteStub,
  usePayStub,
  useSaveStubCopy,
} from "../api/payStubs";
import { Card } from "../components/Card";
import { EditName } from "../components/EditButton";
import { FilePreview } from "../components/files/Receipts";
import { clickRowToEdit, ROW_OPENS } from "../components/rowEdit";
import { APPLIED_LABELS, SECTION_LABELS, tracksAsText } from "../components/stubs/labels";
import { SaveStubDialog } from "../components/stubs/SaveStubDialog";
import { StubLineForm } from "../components/stubs/StubLineForm";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { formatDate, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";

/** Which line the form is open on: one of the stub's (by its place in the list), or a new one. */
type Editing = { index: number } | "new" | null;

/**
 * One pay stub (design/pay-stub.png): the PDF beside every line read from it, this stub and
 * the year to date, grouped as a stub groups them, with what each is tracked as and whether
 * gross less everything taken out comes to the net pay.
 *
 * A stub in review changes nothing until it is saved. Each line opens to be put right, and
 * how it is filed is remembered for the income source, so the next stub fills in by itself.
 */
export function PayStubPage() {
  const params = useParams();
  const sourceId = Number(params.id);
  const stubId = Number(params.stubId);
  const navigate = useNavigate();
  const [search] = useSearchParams();
  const stub = usePayStub(Number.isInteger(stubId) ? stubId : undefined);
  const accounts = useAccounts().data ?? [];
  const sources = useIncomeSources().data ?? [];
  const change = useChangeStub(stubId);
  const remove = useDeleteStub();
  const [editing, setEditing] = useState<Editing>(null);
  const [saving, setSaving] = useState(false);

  if (stub.isPending) {
    return <p className="text-muted">Loading the stub…</p>;
  }
  if (stub.isError || stub.data.incomeSourceId !== sourceId) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Pay stub</h1>
        <Card>
          <p role="alert" className="font-medium">
            {stub.isError && stub.error.message !== "We couldn't find that."
              ? stub.error.message
              : "We couldn't find that pay stub. It may have been removed."}
          </p>
          <Link to="/income" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all income
          </Link>
        </Card>
      </>
    );
  }

  const data = stub.data;
  const inReview = data.status === "review";
  const back = `/income/${data.incomeSourceId}`;
  const depositAccountId = sources.find((source) => source.id === data.incomeSourceId)?.depositAccountId;
  const depositAccount = accounts.find((account) => account.id === depositAccountId)?.name;
  // The paycheck's row asks for "just this paycheck"; anything else in the address is ignored.
  const asked = STUB_APPLIES.find((apply) => apply === search.get("apply")) as StubApply | undefined;

  /** Sends the stub back with its lines changed. A line is known by its place: lines have no id until they are saved. */
  const send = (lines: StubLineInput[], payDate = data.payDate) => change.mutate({ payDate, lines }, { onSuccess: () => setEditing(null) });
  const asInputs = (): StubLineInput[] => data.lines.map(({ id: _id, ...line }) => line);
  const saveLine = (line: StubLineInput) => {
    const lines = asInputs();
    if (editing === "new") {
      lines.push(line);
    } else if (editing) {
      lines[editing.index] = line;
    }
    send(lines);
  };

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/income" className="text-accent-ink underline">
              Income
            </Link>{" "}
            /{" "}
            <Link to={back} className="text-accent-ink underline">
              {data.sourceName}
            </Link>{" "}
            / Pay stub
          </p>
          <h1 className={`${PAGE_TITLE} truncate`}>
            {inReview ? "Review pay stub" : "Pay stub"} · {data.payDate ? formatDate(data.payDate) : "no date yet"}
          </h1>
          <p className="text-muted">
            {inReview
              ? "Nothing changes until you save it."
              : `Saved · ${data.applied ? APPLIED_LABELS[data.applied].toLowerCase() : ""}. To change it, remove it and add the PDF again.`}
          </p>
        </div>
        <div className="flex shrink-0 flex-col gap-2 whitespace-nowrap sm:flex-row">
          <button
            type="button"
            disabled={remove.isPending}
            onClick={() => remove.mutate(data.id, { onSuccess: () => navigate(back) })}
            className={BUTTON_SECONDARY}
          >
            {remove.isPending ? "Removing…" : inReview ? "Discard" : "Remove stub"}
          </button>
          {inReview && (
            <button type="button" onClick={() => setSaving(true)} className={BUTTON_PRIMARY}>
              Save stub…
            </button>
          )}
        </div>
      </div>

      <div className="space-y-4">
        <CheckBanner stub={data} />
        {(remove.isError || (change.isError && editing === null)) && (
          <p role="alert" className="text-danger">
            {remove.error?.message ?? change.error?.message}
          </p>
        )}

        <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <StubFile stub={data} />
          <Card title="Lines on the stub">
            <PaidOn stub={data} editable={inReview} pending={change.isPending} onChange={(payDate) => send(asInputs(), payDate)} />
            <Lines
              stub={data}
              accounts={accounts}
              depositAccount={depositAccount}
              onEdit={inReview ? (index) => setEditing({ index }) : undefined}
            />
            {inReview && (
              <button type="button" onClick={() => setEditing("new")} className={`${BUTTON_LINK} mt-2 inline-flex items-center gap-1`}>
                <Plus aria-hidden className="size-4" />
                Add a line
              </button>
            )}
            <p className={HINT}>
              Budget remembers how each line is filed for {data.sourceName}, so the next stub from them fills in by itself. Taxes are kept
              with the stub; what comes out before and after tax becomes the deductions on {data.sourceName}.
            </p>
          </Card>
        </div>
      </div>

      {editing !== null && (
        <StubLineForm
          line={editing === "new" ? undefined : data.lines[editing.index]}
          accounts={accounts}
          pending={change.isPending}
          error={change.error?.message}
          onSave={saveLine}
          onRemove={editing === "new" ? undefined : () => send(asInputs().filter((_line, index) => index !== editing.index))}
          onClose={() => {
            change.reset();
            setEditing(null);
          }}
        />
      )}
      {saving && <SaveStubDialog stub={data} startOn={asked} onSaved={() => navigate(back)} onClose={() => setSaving(false)} />}
    </>
  );
}

/**
 * Whether the stub adds up, said in words and figures with an icon, never by color alone, and
 * how many lines want a second look.
 */
function CheckBanner({ stub }: { stub: PayStub }) {
  const { totals, toCheck } = stub;
  const sum = (
    <span data-amount className="tabular-nums">
      {formatCents(totals.grossCents)} − {formatCents(totals.deductionsCents)} = {formatCents(totals.grossCents - totals.deductionsCents)}
    </span>
  );
  const check =
    toCheck === 0 ? "" : toCheck === 1 ? " One line was hard to read, so please check it." : ` ${toCheck} lines were hard to read, so please check them.`;

  if (stub.lines.length === 0) {
    return (
      <p role="status" className="flex items-start gap-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
        <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
        <span>
          <span className="font-semibold">We couldn't find any pay lines in this PDF.</span> If it is a pay stub, its lines can be added
          by hand from the stub shown beside them. Read on this PC; nothing was sent anywhere.
        </span>
      </p>
    );
  }
  return totals.balanced ? (
    <p role="status" className="flex items-start gap-2 rounded-lg border border-line bg-surface p-3">
      <Check aria-hidden className="mt-0.5 size-5 shrink-0 text-accent-ink" />
      <span>
        <span className="font-semibold">Checks out:</span> gross − everything taken out = net pay ({sum}).{check} Read on this PC; nothing
        was sent anywhere.
      </span>
    </p>
  ) : (
    <p role="status" className="flex items-start gap-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
      <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
      <span>
        <span className="font-semibold">Doesn't add up yet:</span> gross − everything taken out is {sum}, and the net pay line says{" "}
        <span data-amount className="tabular-nums">
          {formatCents(totals.netCents)}
        </span>
        . That leaves{" "}
        <span data-amount className="font-semibold tabular-nums">
          {formatCents(Math.abs(totals.differenceCents))}
        </span>{" "}
        unexplained: a line may be missing, or an amount misread.{check}
      </span>
    </p>
  );
}

/** The stub itself, a page at a time, to check the lines against. */
function StubFile({ stub }: { stub: PayStub }) {
  const saveCopy = useSaveStubCopy();
  return (
    <Card title="The stub">
      <FilePreview name={stub.name} pages={stub.pages} src={(page) => stubPreviewUrl(stub.id, page)} size="column" />
      <div className="mt-2 flex items-center justify-between gap-2">
        <p className="min-w-0 truncate text-sm text-muted">{stub.name} · kept on this PC and in your backups</p>
        <button
          type="button"
          onClick={() => saveCopy.mutate(stub)}
          disabled={saveCopy.isPending}
          className={`${BUTTON_LINK} inline-flex shrink-0 items-center gap-1`}
        >
          <Download aria-hidden className="size-4" />
          Save a copy
        </button>
      </div>
      {saveCopy.data && (
        <p role="status" className="font-medium wrap-anywhere">
          {savedMessage(saveCopy.data)}
        </p>
      )}
      {saveCopy.isError && (
        <p role="alert" className="text-danger">
          {saveCopy.error.message}
        </p>
      )}
    </Card>
  );
}

/** The day the stub was paid: read from it, and put right here when it was not found or is wrong. */
function PaidOn({ stub, editable, pending, onChange }: { stub: PayStub; editable: boolean; pending: boolean; onChange: (payDate: string | null) => void }) {
  const id = useId();
  if (!editable) {
    return null;
  }
  return (
    <div className="mb-3 sm:max-w-xs">
      <label htmlFor={id} className={LABEL}>
        Paid on
      </label>
      <input
        id={id}
        type="date"
        value={stub.payDate ?? ""}
        max={todayIso()}
        disabled={pending}
        onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
        className={INPUT}
      />
      {stub.payDate === null ? (
        <p className="mt-1 text-sm font-medium text-warn-ink">We couldn't find the pay date on the stub. Please fill it in.</p>
      ) : (
        stub.paycheck === null && <p className={HINT}>No paycheck from {stub.sourceName} is within a few days of this date.</p>
      )}
    </div>
  );
}

/**
 * Every line, under the heading of its part of the stub. A table from `md` up; below that each
 * line is a small card, since four columns do not fit a phone.
 */
function Lines({
  stub,
  accounts,
  depositAccount,
  onEdit,
}: {
  stub: PayStub;
  accounts: readonly Account[];
  depositAccount: string | undefined;
  onEdit?: (index: number) => void;
}) {
  if (stub.lines.length === 0) {
    return <p className="text-muted">No lines yet.</p>;
  }
  const indexed = stub.lines.map((line, index) => ({ line, index }));
  return (
    <div>
      <div aria-hidden className="hidden grid-cols-[minmax(0,3fr)_7rem_7rem_minmax(0,3fr)] gap-3 border-b border-line pb-2 text-sm font-semibold text-muted md:grid">
        <span>Line on the stub</span>
        <span className="text-right">This stub</span>
        <span className="text-right">Year to date</span>
        <span>Tracks as</span>
      </div>
      {STUB_SECTIONS.map((section) => {
        const inSection = indexed.filter(({ line }) => line.section === section);
        if (inSection.length === 0) {
          return null;
        }
        return (
          <section key={section} aria-label={SECTION_LABELS[section]}>
            <h3 className="pt-3 pb-1 text-sm font-semibold tracking-wide text-accent-ink uppercase">{SECTION_LABELS[section]}</h3>
            <ul className="divide-y divide-line">
              {inSection.map(({ line, index }) => (
                <LineRow
                  key={index}
                  line={line}
                  tracksAs={tracksAsText(line, accounts, depositAccount)}
                  onEdit={onEdit ? () => onEdit(index) : undefined}
                />
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function LineRow({ line, tracksAs, onEdit }: { line: StubLine; tracksAs: string; onEdit?: () => void }) {
  return (
    <li
      onClick={clickRowToEdit}
      className={`-mx-2 grid grid-cols-2 gap-x-3 gap-y-0.5 rounded-lg px-2 py-2 md:grid-cols-[minmax(0,3fr)_7rem_7rem_minmax(0,3fr)] md:items-center ${ROW_OPENS} ${
        line.sure ? "" : "bg-warn/60"
      }`}
    >
      <span className="col-span-2 flex min-w-0 flex-wrap items-center gap-2 md:col-span-1">
        {onEdit ? <EditName name={line.label} onClick={onEdit} /> : <span className="font-medium wrap-break-word">{line.label}</span>}
        {/* In words with an icon: the highlight alone would not say what to do. */}
        {!line.sure && (
          <span className="inline-flex items-center gap-1 rounded-md border border-warn-line bg-warn px-1.5 text-xs font-semibold text-warn-ink uppercase">
            <TriangleAlert aria-hidden className="size-3" />
            Check
          </span>
        )}
      </span>
      <span data-amount className="font-semibold tabular-nums md:text-right">
        <span className="text-sm font-normal text-muted md:hidden">This stub </span>
        {formatCents(line.amountCents)}
      </span>
      <span data-amount className="text-right text-muted tabular-nums">
        {line.ytdCents !== null && (
          <>
            <span className="text-sm md:hidden">Year to date </span>
            {formatCents(line.ytdCents)}
          </>
        )}
      </span>
      <span className="col-span-2 min-w-0 text-sm text-muted md:col-span-1 md:text-base">{tracksAs}</span>
    </li>
  );
}
