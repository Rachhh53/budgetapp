import { HandCoins } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { Link } from "react-router";
import { useAccounts } from "../../api/accounts";
import type { Item } from "../../api/items";
import { paymentPath, type ReimbursementSettings, useItemReimbursement, useSaveReimbursementSettings } from "../../api/reimbursements";
import { formatDate } from "../../lib/dates";
import { Amount } from "../Amount";
import { Card } from "../Card";
import { EditButton } from "../EditButton";
import { ReimbursementBadge } from "../expenses/ReimbursementBadge";
import { Modal } from "../Modal";
import { ComesOutOfField } from "../reimbursements/ComesOutOfField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL, ROW_LINK } from "../ui";

/**
 * How a bill or budget is paid back, and the payments of it flagged to be (Rachel, 2026-10-24: "only payments I flag and a
 * set amount defaulted to the payment being flagged"). A payment is flagged from its own form, on its pay period or in
 * Coming up; each flagged one opens its own page.
 */
export function PaidBackCard({ item }: { item: Item }) {
  const reimbursement = useItemReimbursement(item.id);
  const accounts = useAccounts();
  const [editing, setEditing] = useState(false);
  const settings = reimbursement.data?.settings ?? null;
  const payments = reimbursement.data?.payments ?? [];
  // Packs still to buy, flagged on their pay period's row (Rachel, 2026-10-25: "Flag each pack").
  const packs = reimbursement.data?.packs ?? [];
  const accountName = (id: number) => accounts.data?.find((account) => account.id === id)?.name ?? "An account that was removed";

  return (
    <Card title="Paid back" action={<EditButton name="how it's paid back" onClick={() => setEditing(true)} />}>
      {reimbursement.isPending && <p className="text-muted">Loading…</p>}
      {reimbursement.isError && (
        <p role="alert" className="text-danger">
          {reimbursement.error.message}
        </p>
      )}
      {reimbursement.data && settings === null && payments.length === 0 && packs.length === 0 && (
        <p className="text-muted">
          Nobody pays this back. If someone pays some of these back, like an HSA, work or insurance, open one of those payments on
          its pay period or in Coming up and choose Paid back?.
        </p>
      )}
      {settings !== null && (
        <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <div className="min-w-0">
            <dt className="text-sm text-muted">Comes back into</dt>
            <dd className="mt-0.5 font-medium">{accountName(settings.intoAccountId)}</dd>
          </div>
          <div className="min-w-0">
            <dt className="text-sm text-muted">Comes out of</dt>
            <dd className="mt-0.5 font-medium">
              {settings.fromAccountId === null ? <span className="text-muted">None of my accounts</span> : accountName(settings.fromAccountId)}
            </dd>
          </div>
          <div className="min-w-0">
            <dt className="text-sm text-muted">When</dt>
            <dd className="mt-0.5 font-medium">
              About {settings.daysAfter} {settings.daysAfter === 1 ? "day" : "days"} after it's paid
            </dd>
          </div>
          <div className="min-w-0">
            <dt className="text-sm text-muted">Who pays it back</dt>
            <dd className="mt-0.5 font-medium wrap-break-word">{settings.reimbursedBy ?? <span className="text-muted">Not said</span>}</dd>
          </div>
        </dl>
      )}
      {payments.length > 0 && (
        <>
          <h3 className="mt-4 font-semibold">Payments flagged</h3>
          <ul className="divide-y divide-line">
            {payments.map((payment) => (
              <li key={payment.id} className="flex">
                <Link to={paymentPath(payment.itemId, payment.scheduledDate)} className={ROW_LINK}>
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium">{formatDate(payment.date)}</span>
                    <span className="block text-sm">
                      <ReimbursementBadge status={payment.reimbursement.status} />
                    </span>
                  </span>
                  <Amount cents={payment.amountCents} className="shrink-0 font-semibold" />
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
      {packs.length > 0 && (
        <>
          <h3 className="mt-4 font-semibold">Packs flagged</h3>
          <ul className="divide-y divide-line">
            {packs.map((pack) => (
              <li key={pack.plannedDate} className="flex items-center gap-3 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block font-medium">Pack due {formatDate(pack.dueDate)}</span>
                  <span className="block text-sm text-muted">Back about {formatDate(pack.expectedOn)} · changed on its pay period</span>
                </span>
                <Amount cents={pack.amountCents} className="shrink-0 font-semibold" />
              </li>
            ))}
          </ul>
        </>
      )}
      {settings !== null && payments.length === 0 && packs.length === 0 && (
        <p className="mt-3 text-sm text-muted">No payments flagged yet. Open one on its pay period or in Coming up and choose Paid back?.</p>
      )}
      {editing && <SettingsForm item={item} settings={settings} onClose={() => setEditing(false)} />}
    </Card>
  );
}

/** Where the money comes back into, about how many days after, and who pays it back. */
function SettingsForm({ item, settings, onClose }: { item: Item; settings: ReimbursementSettings | null; onClose: () => void }) {
  const id = useId();
  const accounts = useAccounts();
  const save = useSaveReimbursementSettings(item.id);
  const [into, setInto] = useState(String(settings?.intoAccountId ?? item.payFromAccountId));
  const [days, setDays] = useState(String(settings?.daysAfter ?? 30));
  const [by, setBy] = useState(settings?.reimbursedBy ?? "");
  const [from, setFrom] = useState<number | null>(settings?.fromAccountId ?? null);
  const [problem, setProblem] = useState<string>();
  const choices = (accounts.data ?? []).filter(
    (account) => account.id === settings?.intoAccountId || (!account.archived && ["checking", "savings", "credit_card"].includes(account.type)),
  );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const daysAfter = Number(days);
    if (days.trim() === "" || !Number.isInteger(daysAfter) || daysAfter < 0 || daysAfter > 365) {
      setProblem("Please enter about how many days after it comes back, from 0 to 365.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      { intoAccountId: Number(into), daysAfter, reimbursedBy: by.trim() === "" ? null : by.trim(), fromAccountId: from === Number(into) ? null : from },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal title={`Paid back — ${item.name}`} icon={HandCoins} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-muted">How every payment of it you flag comes back. Money still to come is planned this way.</p>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor={`${id}-into`} className={LABEL}>
              Comes back into
            </label>
            <select id={`${id}-into`} value={into} onChange={(event) => setInto(event.target.value)} className={INPUT}>
              {choices.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor={`${id}-days`} className={LABEL}>
              Days after it's paid
            </label>
            <input
              id={`${id}-days`}
              type="number"
              inputMode="numeric"
              min={0}
              max={365}
              value={days}
              onChange={(event) => setDays(event.target.value)}
              className={INPUT}
            />
          </div>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <ComesOutOfField value={from} onChange={setFrom} intoAccountId={Number(into)} />
          <div>
            <label htmlFor={`${id}-by`} className={LABEL}>
              Who pays it back (optional)
            </label>
            <input
              id={`${id}-by`}
              value={by}
              onChange={(event) => setBy(event.target.value)}
              maxLength={120}
              placeholder="e.g. HSA, work, insurance"
              autoComplete="off"
              className={INPUT}
            />
          </div>
        </div>
        <p className={HINT}>
          Out of one of your accounts, like an FSA, it's a transfer into the one it comes back into. Each payment can still be
          changed when you flag it.
        </p>
        {(problem ?? save.error?.message) && (
          <p role="alert" className="text-danger">
            {problem ?? save.error?.message}
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
