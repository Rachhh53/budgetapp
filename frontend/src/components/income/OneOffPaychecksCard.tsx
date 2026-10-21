import { Banknote } from "lucide-react";
import { useState } from "react";
import { type Account, useAccounts } from "../../api/accounts";
import { type IncomeSource, type OneOffPaycheck, useIncomeSources, useOneOffPaychecks } from "../../api/income";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { Modal } from "../Modal";
import { BUTTON_LINK } from "../ui";
import { OneOffPaycheckForm } from "./OneOffPaycheckForm";

/** Which form is open: adding one (`null`), or changing the one given. */
type Open = { oneOff: OneOffPaycheck | null } | null;

/**
 * A source's one-off paychecks, like a bonus: newest first, each with its own pencil, and an Add
 * across from the title. Each is a whole paycheck of its own, so it is changed here and not on
 * the source's form.
 */
export function OneOffPaychecksCard({ source, accounts }: { source: IncomeSource; accounts: readonly Account[] }) {
  const oneOffs = useOneOffPaychecks(source.id);
  const [open, setOpen] = useState<Open>(null);
  // The server sends them newest first.
  const newestFirst = oneOffs.data ?? [];

  return (
    <Card
      title="One-off paychecks"
      action={
        <button type="button" onClick={() => setOpen({ oneOff: null })} className={BUTTON_LINK}>
          + Add
        </button>
      }
    >
      {oneOffs.isPending && <p className="text-muted">Loading your one-off paychecks…</p>}
      {oneOffs.isError && (
        <p role="alert" className="text-danger">
          {oneOffs.error.message}
        </p>
      )}
      {oneOffs.data && newestFirst.length === 0 && (
        <p className="text-muted">
          None yet. A bonus, or any pay that comes once on its own day, can be added here with its own deductions and match.
        </p>
      )}
      {newestFirst.length > 0 && (
        <ul className="divide-y divide-line">
          {newestFirst.map((oneOff) => (
            <li key={oneOff.id} className="flex items-center justify-between gap-3 py-2">
              <span className="min-w-0">
                <span className="block font-medium break-words">{oneOff.name}</span>
                <span className="block text-sm text-muted">
                  {formatDate(oneOff.payDate)} · <span data-amount>{formatCents(oneOff.netCents)}</span> take-home
                  {oneOff.matchCents !== null && (
                    <>
                      {" "}
                      · <span data-amount>{formatCents(oneOff.matchCents)}</span> match
                    </>
                  )}
                </span>
              </span>
              <EditButton name={oneOff.name} onClick={() => setOpen({ oneOff })} />
            </li>
          ))}
        </ul>
      )}

      {open && (
        <OneOffPaycheckForm
          source={source}
          oneOff={open.oneOff ?? undefined}
          accounts={accounts}
          onClose={() => setOpen(null)}
        />
      )}
    </Card>
  );
}

/**
 * Opens a one-off paycheck's form from somewhere that only has the paycheck itself, like a pay
 * period: its source and its saved figures are looked up first.
 */
export function OneOffPaycheckOpener({ sourceId, oneOffId, onClose }: { sourceId: number; oneOffId: number; onClose: () => void }) {
  const sources = useIncomeSources();
  const oneOffs = useOneOffPaychecks(sourceId);
  const accounts = useAccounts();
  const source = sources.data?.find((candidate) => candidate.id === sourceId);
  const oneOff = oneOffs.data?.find((candidate) => candidate.id === oneOffId);

  if (source && oneOff && accounts.data) {
    return <OneOffPaycheckForm source={source} oneOff={oneOff} accounts={accounts.data} onClose={onClose} />;
  }
  const failed = sources.isError || oneOffs.isError || accounts.isError || (sources.data && oneOffs.data && (!source || !oneOff));
  return (
    <Modal title="One-off paycheck" icon={Banknote} onClose={onClose}>
      {failed ? (
        <p role="alert" className="text-danger">
          We couldn't open this paycheck just now. It may have been deleted; please close this and try again.
        </p>
      ) : (
        <p className="text-muted">Loading this paycheck…</p>
      )}
    </Modal>
  );
}
