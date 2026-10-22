import { FileText } from "lucide-react";
import { Link } from "react-router";
import type { IncomeSource } from "../../api/income";
import { type PayStubSummary, usePayStub, usePayStubs } from "../../api/payStubs";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Card } from "../Card";
import { ROW_LINK } from "../ui";
import { APPLIED_LABELS } from "./labels";
import { StubUpload } from "./StubUpload";

/**
 * An income source's pay stubs (design/income.png): the ones added so far, newest first, each
 * opening its own page, and the place to add the next.
 */
export function PayStubsCard({ source }: { source: IncomeSource }) {
  const stubs = usePayStubs(source.id);
  return (
    <Card title="Pay stubs">
      {stubs.isPending && <p className="text-muted">Loading the pay stubs…</p>}
      {stubs.isError && (
        <p role="alert" className="text-danger">
          {stubs.error.message}
        </p>
      )}
      {stubs.data?.length === 0 && (
        <p className="mb-3 text-muted">
          No pay stubs yet. Add the PDF from your payroll site and Budget reads the gross pay, what is taken out, the taxes and the
          take-home, here on this PC. Nothing changes until you have looked it over and saved it.
        </p>
      )}
      {stubs.data && stubs.data.length > 0 && (
        <ul className="mb-3 divide-y divide-line">
          {stubs.data.map((stub) => (
            <StubRow key={stub.id} sourceId={source.id} stub={stub} />
          ))}
        </ul>
      )}
      <StubUpload sourceId={source.id} />
      <p className="mt-2 text-sm text-muted">A PDF with text in it. Photos and scans can't be read yet.</p>
    </Card>
  );
}

function StubRow({ sourceId, stub }: { sourceId: number; stub: PayStubSummary }) {
  return (
    <li className="flex items-center gap-2">
      <Link to={`/income/${sourceId}/stubs/${stub.id}`} className={ROW_LINK}>
        <FileText aria-hidden className="size-4 shrink-0 text-muted" />
        <span className="min-w-0 flex-1">
          <span className="block font-semibold">{stub.payDate ? formatDate(stub.payDate) : "No date yet"}</span>
          {/* Where it stands, in words: a stub waiting to be looked over changes nothing yet. */}
          <span className={`block text-sm ${stub.status === "review" ? "font-semibold text-warn-ink" : "text-muted"}`}>
            {stub.status === "review"
              ? `To review${stub.toCheck > 0 ? ` · ${stub.toCheck === 1 ? "1 line" : `${stub.toCheck} lines`} to check` : ""}`
              : stub.applied
                ? APPLIED_LABELS[stub.applied]
                : "Saved"}
          </span>
        </span>
        <span className="shrink-0 text-right">
          <span data-amount className="block font-semibold tabular-nums">
            {formatCents(stub.netCents)}
          </span>
          <span className="block text-sm text-muted">
            take-home of <span data-amount>{formatCents(stub.grossCents)}</span>
          </span>
        </span>
      </Link>
    </li>
  );
}

/**
 * Taxes and benefits from the newest stub saved (design/income.png): what the budget does not
 * follow line by line, but is good to see — this paycheck's and the year's so far.
 */
export function TaxesAndBenefitsCard({ source }: { source: IncomeSource }) {
  const stubs = usePayStubs(source.id);
  // A bonus stub's taxes are not what a regular paycheck pays, so the newest regular one is shown.
  const newest = stubs.data?.find((stub) => stub.status === "saved" && stub.applied !== "one_off");
  const stub = usePayStub(newest?.id);
  const lines = (stub.data?.lines ?? []).filter((line) => line.tracksAs === "tax" || line.tracksAs === "benefit");
  const hasYtd = lines.some((line) => line.ytdCents !== null);

  return (
    <Card title="Taxes & benefits">
      {newest === undefined ? (
        <p className="text-muted">
          {stubs.isError
            ? stubs.error.message
            : "These come from your pay stubs. Once a stub is saved, its taxes and benefits show here, with the year so far."}
        </p>
      ) : stub.isError ? (
        <p role="alert" className="text-danger">
          {stub.error.message}
        </p>
      ) : stub.isPending ? (
        <p className="text-muted">Loading…</p>
      ) : lines.length === 0 ? (
        <p className="text-muted">The stub of {formatDate(stub.data.payDate ?? "")} lists no taxes or benefits.</p>
      ) : (
        <>
          <ul className="divide-y divide-line">
            {lines.map((line, index) => (
              <li key={index} className="flex items-center justify-between gap-3 py-2">
                <span className="min-w-0">
                  {line.label}
                  <span className="block text-sm text-muted">{line.tracksAs === "tax" ? "Tax" : "Insurance, benefit or fee"}</span>
                </span>
                <span className="shrink-0 text-right">
                  <span data-amount className="block font-semibold tabular-nums">
                    {formatCents(line.amountCents)}
                  </span>
                  {line.ytdCents !== null && (
                    <span className="block text-sm text-muted">
                      <span data-amount>{formatCents(line.ytdCents)}</span> this year
                    </span>
                  )}
                </span>
              </li>
            ))}
            <li className="flex items-center justify-between gap-3 py-2 font-semibold">
              <span>Each paycheck</span>
              <span className="shrink-0 text-right">
                <span data-amount className="block tabular-nums">
                  {formatCents(lines.reduce((sum, line) => sum + line.amountCents, 0))}
                </span>
                {hasYtd && (
                  <span className="block text-sm font-normal text-muted">
                    <span data-amount>{formatCents(lines.reduce((sum, line) => sum + (line.ytdCents ?? 0), 0))}</span> this year
                  </span>
                )}
              </span>
            </li>
          </ul>
          <p className="mt-2 text-sm text-muted">
            From the stub of{" "}
            <Link to={`/income/${source.id}/stubs/${stub.data.id}`} className="font-semibold text-accent-ink underline">
              {formatDate(stub.data.payDate ?? "")}
            </Link>
            , the newest one saved.
          </p>
        </>
      )}
    </Card>
  );
}
