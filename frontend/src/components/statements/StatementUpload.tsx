import { Columns3, Upload } from "lucide-react";
import { type DragEvent, type FormEvent, useId, useState } from "react";
import { useNavigate } from "react-router";
import {
  ACCEPTED_STATEMENTS,
  type CsvColumns,
  type CsvLayout,
  isCsv,
  problemWithStatement,
  useCsvColumns,
  useUploadStatement,
} from "../../api/statementUploads";
import { formatCents } from "../../lib/money";
import { Modal } from "../Modal";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT, LABEL } from "../ui";

/**
 * Where a statement is added to an account: drop the file or browse for it. A CSV first asks which column holds what,
 * unless the same bank's columns were seen before; anything else is read straight away. The statement's page opens
 * once it is read; nothing changes until something is applied there.
 *
 * @param onUploaded for a dialog this sits in, which is done once the statement's page opens
 * @param onAsking  given inside a dialog: the column question is then asked in place of the drop zone, never as a
 *                  dialog over a dialog (whose Escape would close both), and this says when it is showing
 */
export function StatementUpload({
  accountId,
  label = "Drop a statement here or browse",
  onUploaded,
  onAsking,
}: {
  accountId: number;
  label?: string;
  onUploaded?: () => void;
  onAsking?: (asking: boolean) => void;
}) {
  const inputId = useId();
  const navigate = useNavigate();
  const columns = useCsvColumns();
  const upload = useUploadStatement();
  const [problem, setProblem] = useState<string>();
  const [over, setOver] = useState(false);
  // A CSV waiting for its columns to be said.
  const [asking, setAskingState] = useState<{ file: File; columns: CsvColumns }>();
  const setAsking = (next: { file: File; columns: CsvColumns } | undefined) => {
    setAskingState(next);
    onAsking?.(next !== undefined);
  };

  const send = (file: File, layout?: CsvLayout) =>
    upload.mutate(
      { accountId, file, layout },
      {
        onSuccess: (statement) => {
          setAsking(undefined);
          onUploaded?.();
          navigate(`/accounts/${accountId}/statements/${statement.id}`);
        },
      },
    );

  const add = (picked: readonly File[]) => {
    const file = picked[0];
    if (!file) {
      return;
    }
    const found = problemWithStatement(picked);
    setProblem(found ?? undefined);
    if (found !== null) {
      return;
    }
    if (!isCsv(file)) {
      send(file);
      return;
    }
    columns.mutate(
      { accountId, file },
      {
        // Columns seen before from the same bank are used as they were; otherwise asked.
        onSuccess: (read) => (read.known ? send(file, read.guess) : setAsking({ file, columns: read })),
      },
    );
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    const dropped = [...event.dataTransfer.files];
    if (dropped.length === 0) {
      // Dragged out of a web page, what arrives is a link, and Budget never goes out to fetch anything.
      setProblem(
        "That came across as a link, not a file, so there was nothing to read. Download the statement first, then drop it here from its folder, or choose it with browse.",
      );
      return;
    }
    add(dropped);
  };

  if (asking && onAsking) {
    return (
      <CsvColumnsForm
        columns={asking.columns}
        saving={upload.isPending}
        error={upload.error?.message}
        onSave={(layout) => send(asking.file, layout)}
        onCancel={() => setAsking(undefined)}
      />
    );
  }

  const busy = columns.isPending || upload.isPending;
  const failure = problem ?? columns.error?.message ?? (asking ? undefined : upload.error?.message);
  return (
    <div>
      {/* A label, so the whole dashed box is the file chooser's button; dropping on it does the same. */}
      <label
        htmlFor={inputId}
        onDragOver={(event) => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={`flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg border border-dashed px-3 py-2 text-center font-medium has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent-ink ${
          over ? "border-accent bg-bg" : "border-line"
        }`}
      >
        <Upload aria-hidden className="size-4 shrink-0" />
        {busy ? "Reading the statement…" : label}
        <input
          id={inputId}
          type="file"
          // Nothing is kept until it is read: a dialog this sits in has nothing unsaved because of it.
          data-saves-itself
          accept={ACCEPTED_STATEMENTS}
          disabled={busy}
          onChange={(event) => {
            add([...(event.target.files ?? [])]);
            // So choosing the same file again is still a change.
            event.target.value = "";
          }}
          className="sr-only"
        />
      </label>
      {failure && (
        <p role="alert" className="mt-2 text-danger">
          {failure}
        </p>
      )}
      {asking && (
        <Modal title="Which column is which" icon={Columns3} onClose={() => setAsking(undefined)}>
          <CsvColumnsForm
            columns={asking.columns}
            saving={upload.isPending}
            error={upload.error?.message}
            onSave={(layout) => send(asking.file, layout)}
            onCancel={() => setAsking(undefined)}
          />
        </Modal>
      )}
    </div>
  );
}

/** A loose reading of a sample amount, only to show how a row would be read: the backend reads the real thing. */
function sampleAmount(text: string | undefined): number | null {
  if (text === undefined || text.trim() === "") {
    return null;
  }
  const negative = /^\s*[-−(]|-\s*$|\)\s*$|cr\s*$/i.test(text) || /\$\s*-/.test(text);
  const digits = text.replace(/[^0-9.]/g, "");
  const value = Number(digits);
  if (digits === "" || Number.isNaN(value)) {
    return null;
  }
  const cents = Math.round(value * 100);
  return negative ? -cents : cents;
}

/** How the first sample row reads with a layout, said the way the review page will say it. */
function sampleReading(columns: CsvColumns, layout: CsvLayout): string | null {
  const row = columns.sample[0];
  if (!row) {
    return null;
  }
  let spent: number | null;
  if (layout.amountColumn !== null) {
    const written = sampleAmount(row[layout.amountColumn]);
    spent = written === null ? null : layout.chargesNegative ? -written : written;
  } else {
    const out = sampleAmount(row[layout.debitColumn ?? -1]);
    const back = sampleAmount(row[layout.creditColumn ?? -1]);
    spent = out === null && back === null ? null : Math.abs(out ?? 0) - Math.abs(back ?? 0);
  }
  const date = row[layout.dateColumn] ?? "";
  const description = row[layout.descriptionColumn] ?? "";
  if (spent === null) {
    return `${date} · ${description} · no amount found`;
  }
  return `${date} · ${description} · ${formatCents(Math.abs(spent))} ${spent >= 0 ? "spent" : "back"}`;
}

/**
 * Which column of a CSV holds what, asked once per bank (decided with Rachel, 2026-10-25) and filled in with a guess.
 * The first row is read back as the review page will show it, so a wrong choice is seen before the file is added.
 * Fits one screen: two columns of choices from `sm` up, one on a phone. Shown in a dialog of its own from an account's
 * page, or in place of the drop zone inside the upload dialog.
 */
function CsvColumnsForm({
  columns,
  saving,
  error,
  onSave,
  onCancel,
}: {
  columns: CsvColumns;
  saving: boolean;
  error: string | undefined;
  onSave: (layout: CsvLayout) => void;
  onCancel: () => void;
}) {
  const id = useId();
  const [layout, setLayout] = useState<CsvLayout>(columns.guess);
  const twoColumns = layout.amountColumn === null;
  const set = (change: Partial<CsvLayout>) => setLayout((before) => ({ ...before, ...change }));
  const choose = (field: string, label: string, value: number | null, onChange: (column: number) => void) => (
    <div>
      <label htmlFor={`${id}-${field}`} className={LABEL}>
        {label}
      </label>
      <select
        id={`${id}-${field}`}
        value={value ?? 0}
        onChange={(event) => onChange(Number(event.target.value))}
        className={INPUT}
      >
        {columns.headers.map((header, index) => (
          <option key={index} value={index}>
            {header}
          </option>
        ))}
      </select>
    </div>
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSave(layout);
  };
  const reading = sampleReading(columns, layout);

  return (
    <form onSubmit={submit} className="space-y-3">
      <p className="text-muted">Asked once per bank, then remembered. Read on this PC; the file isn&apos;t kept.</p>
      {/* Column pickers sit two to a row even on a phone (a column's name is short), so the form fits one screen. */}
      <div className="grid grid-cols-2 gap-3">
        {choose("date", "Date spent", layout.dateColumn, (column) => set({ dateColumn: column }))}
        {choose("description", "Description", layout.descriptionColumn, (column) => set({ descriptionColumn: column }))}
        <div className="col-span-2">
          <Segmented
            legend="Amounts are in"
            options={[
              { value: "one", label: "One column" },
              { value: "two", label: "Out and in" },
            ]}
            value={twoColumns ? "two" : "one"}
            onChange={(choice) =>
              choice === "two"
                ? set({ amountColumn: null, debitColumn: layout.debitColumn ?? 0, creditColumn: layout.creditColumn ?? 0 })
                : set({ amountColumn: layout.amountColumn ?? 0, debitColumn: null, creditColumn: null })
            }
          />
        </div>
        {twoColumns ? (
          <>
            {choose("debit", "Money out", layout.debitColumn, (column) => set({ debitColumn: column }))}
            {choose("credit", "Money in", layout.creditColumn, (column) => set({ creditColumn: column }))}
          </>
        ) : (
          <>
            {choose("amount", "Amount", layout.amountColumn, (column) => set({ amountColumn: column }))}
            <div>
              <label htmlFor={`${id}-sign`} className={LABEL}>
                Spending shows
              </label>
              <select
                id={`${id}-sign`}
                value={layout.chargesNegative ? "negative" : "positive"}
                onChange={(event) => set({ chargesNegative: event.target.value === "negative" })}
                className={INPUT}
              >
                <option value="positive">As 12.00</option>
                <option value="negative">As -12.00</option>
              </select>
            </div>
          </>
        )}
        <div className="col-span-2 sm:col-span-1">
          <label htmlFor={`${id}-order`} className={LABEL}>
            Dates are written
          </label>
          <select
            id={`${id}-order`}
            value={layout.dateOrder}
            onChange={(event) => set({ dateOrder: event.target.value as CsvLayout["dateOrder"] })}
            className={INPUT}
          >
            <option value="mdy">Month first (10/05/2026)</option>
            <option value="dmy">Day first (05/10/2026)</option>
            <option value="ymd">Year first (2026-10-05)</option>
          </select>
        </div>
        <label className="col-span-2 flex min-h-11 items-center gap-2 self-end font-medium sm:col-span-1">
          <input
            type="checkbox"
            checked={layout.hasHeader}
            onChange={(event) => set({ hasHeader: event.target.checked })}
            className="size-5"
          />
          The first row names the columns
        </label>
      </div>
      {reading && (
        <div className="rounded-lg bg-bg p-3">
          <p className="text-sm font-semibold text-muted">The first line reads as</p>
          <p data-amount className="wrap-anywhere">
            {reading}
          </p>
        </div>
      )}
      {error && (
        <p role="alert" className="text-danger">
          {error}
        </p>
      )}
      {/* Side by side even on a phone: the last row this form can spare to fit one screen. */}
      <div className="grid grid-cols-2 gap-2 sm:flex sm:justify-end">
        <button type="button" onClick={onCancel} className={BUTTON_SECONDARY}>
          Cancel
        </button>
        <button type="submit" disabled={saving} className={BUTTON_PRIMARY}>
          {saving ? "Reading…" : "Read the statement"}
        </button>
      </div>
    </form>
  );
}
