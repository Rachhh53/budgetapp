import { useState } from "react";
import { useAccounts } from "../../api/accounts";
import type { Item } from "../../api/items";
import { type Occurrence, useUpcoming } from "../../api/periods";
import { formatDate } from "../../lib/dates";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { OccurrenceForm } from "../period/OccurrenceForm";
import { clickRowToEdit, ROW_OPENS } from "../rowEdit";

/**
 * An item's next dates and what each will be, on its own page (Rachel, 2026-10-20: "allow me to change upcoming
 * amounts in the recurring screen"). Each opens the same "just this one" form as its pay period row: its amount,
 * its day, Skip this one. The item's amount from now on is changed with Change the amount instead.
 */
export function UpcomingCard({ item }: { item: Item }) {
  const upcoming = useUpcoming(item.id);
  const accounts = useAccounts();
  const [editing, setEditing] = useState<Occurrence>();
  const isChecking = (id: number) => accounts.data?.some((account) => account.id === id && account.type === "checking") ?? false;

  return (
    <Card title="Coming up">
      {upcoming.isPending ? (
        <p className="text-muted">Loading the coming dates…</p>
      ) : upcoming.isError ? (
        <p role="alert" className="text-danger">
          We couldn't load the coming dates just now. Please try again in a moment.
        </p>
      ) : upcoming.data.length === 0 ? (
        <p className="text-muted">Nothing is planned in the coming year.</p>
      ) : (
        <>
          <p className="mb-2 text-sm text-muted">Open one to change it just that once, or to skip it.</p>
          <ul className="divide-y divide-line">
            {upcoming.data.map((occurrence) => (
              <li
                key={occurrence.scheduledDate}
                onClick={clickRowToEdit}
                className={`-mx-2 flex min-h-11 items-center justify-between gap-3 rounded-lg px-2 py-1 ${ROW_OPENS}`}
              >
                <button
                  type="button"
                  data-edit
                  onClick={() => setEditing(occurrence)}
                  aria-label={`Change ${item.name} on ${formatDate(occurrence.dueDate)}`}
                  className="min-w-0 flex-1 cursor-pointer truncate rounded-sm text-left font-medium"
                >
                  {formatDate(occurrence.dueDate)}
                  {occurrence.skipped && <span className="ml-2 text-sm font-normal text-muted">Skipped</span>}
                </button>
                <Amount
                  cents={occurrence.amountCents}
                  estimate={occurrence.estimate}
                  className={`shrink-0 font-semibold ${occurrence.skipped ? "text-muted line-through" : ""}`}
                />
              </li>
            ))}
          </ul>
        </>
      )}
      {editing && (
        <OccurrenceForm
          occurrence={editing}
          byHand={editing.kind !== "budget" && !editing.autopay}
          fromChecking={isChecking(editing.payFromAccountId)}
          onClose={() => setEditing(undefined)}
        />
      )}
    </Card>
  );
}
