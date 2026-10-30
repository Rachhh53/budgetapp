import { ChevronRight } from "lucide-react";
import { Link } from "react-router";
import { useStatementUploads } from "../../api/statementUploads";
import { formatDate } from "../../lib/dates";
import { Card } from "../Card";
import { ROW_LINK } from "../ui";
import { StatementUpload } from "./StatementUpload";

const FORMAT_LABELS = { csv: "CSV", ofx: "OFX", pdf: "PDF" } as const;

/**
 * An account's statement uploads (Rachel, 2026-10-25): add a statement downloaded from the bank, and open one already
 * added. Each opens its own page, where its charges are filed and totals by pay period change the items chosen.
 *
 * @param title "Statement uploads" on a card's page, where "Statements" already means its monthly balances
 */
export function StatementUploadsCard({ accountId, title = "Statement uploads" }: { accountId: number; title?: string }) {
  const uploads = useStatementUploads(accountId);
  return (
    <Card title={title} tip="statementUpload">
      <StatementUpload accountId={accountId} />
      <p className="mt-1 text-sm text-muted">
        A CSV, OFX or QFX download, or a PDF statement. Read on this PC; nothing changes until you choose what each total replaces.
      </p>
      {uploads.isPending && <p className="mt-3 text-muted">Loading statements…</p>}
      {uploads.isError && (
        <p role="alert" className="mt-3 text-danger">
          {uploads.error.message}
        </p>
      )}
      {uploads.data && uploads.data.length > 0 && (
        <ul className="mt-3 divide-y divide-line">
          {uploads.data.map((upload) => (
            <li key={upload.id} className="flex">
              <Link to={`/accounts/${accountId}/statements/${upload.id}`} className={ROW_LINK}>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">
                    {formatDate(upload.from)} – {formatDate(upload.to)}
                  </span>
                  <span className="block truncate text-sm text-muted">
                    {upload.name} · {FORMAT_LABELS[upload.format]} · {upload.lineCount} {upload.lineCount === 1 ? "line" : "lines"}
                    {upload.toCheck > 0 && ` · ${upload.toCheck} to check`}
                    {upload.appliedAt ? " · applied" : " · not applied yet"}
                  </span>
                </span>
                <ChevronRight aria-hidden className="size-5 shrink-0 text-muted" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
