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
  type StubLineInput,
  stubPreviewUrl,
  useChangeStub,
  useDeleteStub,
  usePayStub,
  useReopenStub,
  useSaveStubCopy,
} from "../api/payStubs";
import { Card } from "../components/Card";
import { EditPageButton } from "../components/EditButton";
import { FilePreview } from "../components/files/Receipts";
import { APPLIED_LABELS, SECTION_LABELS, tracksAsText } from "../components/stubs/labels";
import { SaveStubDialog } from "../components/stubs/SaveStubDialog";
import { LINE_COLUMNS, NewStubLine, StubLineRow } from "../components/stubs/StubLineEditor";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, HINT, INPUT, LABEL, PAGE_TITLE } from "../components/ui";
import { formatDate, todayIso } from "../lib/dates";
import { formatCents } from "../lib/money";

/**
 * One pay stub (design/pay-stub.png): the PDF beside every line read from it, this stub and
 * the year to date, grouped as a stub groups them, with what each is tracked as and whether
 * gross less everything taken out comes to the net pay.
 *
 * A stub in review changes nothing until it is saved. Each line is put right where it stands
 * (its name, its amounts, what it tracks as) or taken off, and how it is filed is remembered
 * for the income source, so the next stub fills in by itself.
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
  const reopen = useReopenStub(stubId);
  // What the stub changed the last time it was saved, kept while it is open again so the save
  // dialog starts on the same choice.
  const [lastApplied, setLastApplied] = useState<StubApply>();
  const [adding, setAdding] = useState(false);
  const [saving, setSaving] = useState(false);
  // The lines as they were just sent, shown until the answer comes back, so a row never flickers
  // back to what it said before while its change is on the way.
  const [sent, setSent] = useState<StubLineInput[]>();

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

  const data: PayStub = sent ? { ...stub.data, lines: sent.map((line, index) => ({ ...line, id: stub.data.lines[index]?.id ?? null })) } : stub.data;
  const inReview = data.status === "review";
  const back = `/income/${data.incomeSourceId}`;
  const depositAccountId = sources.find((source) => source.id === data.incomeSourceId)?.depositAccountId;
  const depositAccount = accounts.find((account) => account.id === depositAccountId)?.name;
  // The paycheck's row asks for "just this paycheck"; anything else in the address is ignored.
  const asked = STUB_APPLIES.find((apply) => apply === search.get("apply")) as StubApply | undefined;

  /** Sends the stub back with its lines changed. A line is known by its place: lines have no id until they are saved. */
  const send = (lines: StubLineInput[], payDate = data.payDate, done?: () => void) => {
    setSent(lines);
    change.mutate({ payDate, lines }, { onSuccess: done, onSettled: () => setSent(undefined) });
  };
  const asInputs = (): StubLineInput[] => data.lines.map(({ id: _id, ...line }) => line);
  const changeLine = (index: number, line: StubLineInput) => send(asInputs().map((each, at) => (at === index ? line : each)));
  const removeLine = (index: number) => send(asInputs().filter((_line, at) => at !== index));

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
              : `Saved · ${data.applied ? APPLIED_LABELS[data.applied].toLowerCase() : ""}. Choose Edit stub to change it; what it changed stays until you save it again.`}
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
          {inReview ? (
            <button type="button" onClick={() => setSaving(true)} className={BUTTON_PRIMARY}>
              Save stub…
            </button>
          ) : (
            <EditPageButton
              label={reopen.isPending ? "Opening…" : "Edit stub"}
              onClick={() => {
                setLastApplied(data.applied ?? undefined);
                reopen.mutate();
              }}
            />
          )}
        </div>
      </div>

      <div className="space-y-4">
        <CheckBanner stub={data} />
        {(remove.isError || change.isError || reopen.isError) && (
          <p role="alert" className="text-danger">
            {remove.error?.message ?? change.error?.message ?? reopen.error?.message}
          </p>
        )}

        <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          {/* Stays beside the lines as they are gone through, so each can be checked against the stub. */}
          <div className="lg:sticky lg:top-24">
            <StubFile stub={data} />
          </div>
          <Card title="Lines on the stub">
            <PaidOn stub={data} editable={inReview} pending={change.isPending} onChange={(payDate) => send(asInputs(), payDate)} />
            <Lines
              stub={data}
              accounts={accounts}
              depositAccount={depositAccount}
              onChange={inReview ? changeLine : undefined}
              onRemove={removeLine}
            />
            {inReview &&
              (adding ? (
                <NewStubLine
                  accounts={accounts}
                  pending={change.isPending}
                  onAdd={(line) => send([...asInputs(), line], data.payDate, () => setAdding(false))}
                  onCancel={() => setAdding(false)}
                />
              ) : (
                <button type="button" onClick={() => setAdding(true)} className={`${BUTTON_LINK} mt-2 inline-flex items-center gap-1`}>
                  <Plus aria-hidden className="size-4" />
                  Add a line
                </button>
              ))}
            <p className={HINT}>
              Budget remembers how each line is filed for {data.sourceName}, so the next stub from them fills in by itself. Taxes are kept
              with the stub; what comes out before and after tax becomes the deductions on {data.sourceName}.
            </p>
          </Card>
        </div>
      </div>

      {saving && <SaveStubDialog stub={data} startOn={asked ?? lastApplied} onSaved={() => navigate(back)} onClose={() => setSaving(false)} />}
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
    <Unbalanced stub={stub} check={check} />
  );
}

/**
 * The lines do not come to the net pay. Said as a sum anyone can follow, with what to look for:
 * too much taken out usually means a line that is not a deduction was read as one, too little
 * that a line is missing. (The first wording, "leaves $X unexplained", did not say which.)
 */
function Unbalanced({ stub, check }: { stub: PayStub; check: string }) {
  const { totals } = stub;
  const left = totals.grossCents - totals.deductionsCents;
  const over = totals.netCents - left;
  const money = (cents: number) => (
    <span data-amount className="font-semibold tabular-nums">
      {formatCents(cents)}
    </span>
  );
  return (
    <div role="status" className="flex items-start gap-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink">
      <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
      <div className="space-y-1">
        <p className="font-semibold">These lines don't add up to your net pay yet.</p>
        <p>
          Gross pay {money(totals.grossCents)} minus {money(totals.deductionsCents)} taken out (before tax, taxes and after tax) leaves{" "}
          {money(left)}, but the stub says you were paid {money(totals.netCents)}.
        </p>
        <p>
          {over > 0 ? (
            <>
              So {money(over)} too much is counted as taken out. Look for a line below that isn't really a deduction, or an amount
              that is too large, and fix or remove it.
            </>
          ) : (
            <>
              So {money(-over)} is missing from what is taken out. Look for a line on the stub that isn't in the list below, or an
              amount that is too small, and add or fix it.
            </>
          )}
          {check}
        </p>
      </div>
    </div>
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
 * line is a small card, since four columns do not fit a phone. In review each line is a row of
 * boxes to type in; a saved stub's lines are plain text.
 */
function Lines({
  stub,
  accounts,
  depositAccount,
  onChange,
  onRemove,
}: {
  stub: PayStub;
  accounts: readonly Account[];
  depositAccount: string | undefined;
  /** Given for a stub in review: a line, by its place, as it should now be. */
  onChange?: (index: number, line: StubLineInput) => void;
  onRemove: (index: number) => void;
}) {
  // Which line is open for changing, by its place; one at a time, so the list stays a list.
  const [editing, setEditing] = useState<number>();
  if (stub.lines.length === 0) {
    return <p className="text-muted">No lines yet.</p>;
  }
  const indexed = stub.lines.map((line, index) => ({ line, index }));
  return (
    <div>
      <div aria-hidden className={`hidden gap-x-3 border-b border-line pb-2 text-sm font-semibold text-muted md:grid ${LINE_COLUMNS}`}>
        <span>Line on the stub</span>
        <span className="text-right">This stub</span>
        <span className="text-right">Year to date</span>
        <span>Tracks as</span>
        <span />
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
                <StubLineRow
                  key={index}
                  line={line}
                  tracksAs={tracksAsText(line, accounts, depositAccount)}
                  accounts={accounts}
                  editing={editing === index}
                  onEdit={onChange ? () => setEditing(index) : undefined}
                  onDone={() => setEditing(undefined)}
                  onChange={(changed) => onChange?.(index, changed)}
                  onRemove={() => {
                    setEditing(undefined);
                    onRemove(index);
                  }}
                />
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}
