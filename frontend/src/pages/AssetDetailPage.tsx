import { type FormEvent, useId, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useAccounts } from "../api/accounts";
import { type Asset, useAsset, useAssets, useDeleteAssetValue, useSaveAssetValue } from "../api/assets";
import { Amount } from "../components/Amount";
import { AssetForm } from "../components/assets/AssetForm";
import { ASSET_KIND_ICONS, ASSET_KIND_LABELS } from "../components/assets/labels";
import { Card } from "../components/Card";
import { EditPageButton } from "../components/EditButton";
import { Modal } from "../components/Modal";
import { AMOUNT_HELP, MoneyField } from "../components/MoneyField";
import { DefaultDateHint } from "../components/settings/DefaultDateHint";
import { BUTTON_LINK, BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL, DETAIL_HEADER, PAGE_TITLE } from "../components/ui";
import { formatDate, todayIso } from "../lib/dates";
import { useDefaultDate } from "../api/settings";
import { formatCents, parseDollars } from "../lib/money";
import { formatRate } from "../lib/rates";
import { HelpLink } from "../components/help/HelpLink";

/* One row of "Values you entered": stacked on a phone, columns from `md` up. */
const ROW = "grid grid-cols-2 gap-x-4 gap-y-1 py-3 md:grid-cols-[10rem_10rem_10rem_1fr] md:items-center";

/**
 * A home, vehicle or other asset (design/accounts.png, "Home & assets"): what it is worth
 * today, what is owed against it, the equity left, and every value entered for it.
 */
export function AssetDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const detail = useAsset(id);
  const assets = useAssets();
  const accounts = useAccounts();
  const [editing, setEditing] = useState(false);
  const [updating, setUpdating] = useState(false);

  if (detail.isPending) {
    return <p className="text-muted">Loading…</p>;
  }
  if (detail.isError) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Accounts</h1>
        <Card>
          <p role="alert" className="font-medium">
            {detail.error.message}
          </p>
          <Link to="/accounts" className="mt-2 inline-block font-semibold text-accent-ink underline">
            Back to all accounts
          </Link>
        </Card>
      </>
    );
  }

  const { asset, values } = detail.data;
  const loan = (accounts.data ?? []).find((account) => account.id === asset.linkedLoanAccountId);

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/accounts" className="text-accent-ink underline">
              Accounts
            </Link>{" "}
            / Home & assets
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} flex items-center gap-3`}>
              <span aria-hidden className="size-4 shrink-0 rounded-full" style={{ backgroundColor: asset.color }} />
              <span className="truncate">{asset.name}</span>
            </h1>
            <HelpLink />
          </div>
          <p className="text-muted">
            {ASSET_KIND_LABELS[asset.kind]}
            {asset.archived && " · Archived"}
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <EditPageButton label="Edit asset" onClick={() => setEditing(true)} />
          <button type="button" onClick={() => setUpdating(true)} className={BUTTON_PRIMARY}>
            Update value
          </button>
        </div>
      </div>

      <div className="space-y-4">
        <Card>
          {asset.valueCents === null ? (
            <p>
              <span className="font-medium">No value entered yet.</span>{" "}
              <span className="text-muted">Choose "Update value" and it will count toward your net worth.</span>
            </p>
          ) : (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <div>
                <p className="text-muted">Value</p>
                <Amount cents={asset.valueCents} estimate={asset.estimate} className="-ml-2 text-3xl font-bold" />
                <p className="text-muted">
                  {asset.estimate && asset.latestValue
                    ? `today, ${(asset.appreciationBp ?? 0) < 0 ? "losing" : "at"} your assumed ${formatRate(Math.abs(asset.appreciationBp ?? 0))} a year · you entered ${formatCents(asset.latestValue.valueCents)} on ${formatDate(asset.latestValue.date)}`
                    : asset.latestValue && `entered by you ${formatDate(asset.latestValue.date)}`}
                </p>
              </div>
              {asset.linkedLoanAccountId !== null && (
                <div>
                  <p className="text-muted">Still owed</p>
                  <p data-amount className="text-2xl font-bold tabular-nums">
                    {asset.owedCents === null ? "—" : formatCents(asset.owedCents)}
                  </p>
                  <p className="text-muted">
                    {loan ? (
                      <>
                        on{" "}
                        <Link to={`/accounts/${loan.id}`} className="text-accent-ink underline">
                          {loan.name}
                        </Link>
                        {asset.owedCents === null && " · no balance entered for it yet"}
                      </>
                    ) : (
                      "on its loan"
                    )}
                  </p>
                </div>
              )}
              {asset.equityCents !== null && (
                <div>
                  <p className="text-muted">Equity</p>
                  <p data-amount className="text-2xl font-bold tabular-nums">{formatCents(asset.equityCents)}</p>
                  <p className="text-muted">
                    {asset.linkedLoanAccountId === null
                      ? "owned outright"
                      : asset.equityCents < 0
                        ? "more is owed than it is worth"
                        : "the value less what is owed"}
                  </p>
                </div>
              )}
            </div>
          )}
          {asset.estimate && (
            <p className={HINT}>
              The shaded value is an estimate from your own assumed rate, not an appraisal. Entering a new value replaces it.
            </p>
          )}
        </Card>

        <Card title="Values you entered">
          {values.length === 0 ? (
            <p className="text-muted">Nothing here yet. Each value you enter is kept with its date, so you can see how it has changed.</p>
          ) : (
            <ValueList asset={asset} />
          )}
        </Card>
      </div>

      {editing && (
        <AssetForm
          asset={asset}
          assets={assets.data ?? []}
          accounts={accounts.data ?? []}
          onClose={() => setEditing(false)}
          onDeleted={() => navigate("/accounts")}
        />
      )}
      {updating && <ValueForm asset={asset} onClose={() => setUpdating(false)} />}
    </>
  );
}

function ValueList({ asset }: { asset: Asset }) {
  const values = useAsset(asset.id).data?.values ?? [];
  const remove = useDeleteAssetValue(asset.id);
  return (
    <>
      <div aria-hidden className={`${ROW} hidden border-b border-line pt-0 text-sm font-semibold text-muted md:grid`}>
        <span>Date</span>
        <span className="text-right">Value</span>
        <span className="text-right">Change</span>
        <span />
      </div>
      <ul className="divide-y divide-line">
        {values.map((entry, index) => {
          // Newest first, so the entry before this one in time is the next in the list.
          const previous = values[index + 1];
          const change = previous ? entry.valueCents - previous.valueCents : null;
          return (
            <li key={entry.id} className={ROW}>
              <span className="font-medium">{formatDate(entry.date)}</span>
              <span className="text-right">
                <Amount cents={entry.valueCents} className="font-semibold" />
              </span>
              <span className="text-muted md:sr-only">Change since the entry before</span>
              <span data-amount className="pr-2 text-right tabular-nums">
                {change === null ? "—" : `${change > 0 ? "+" : ""}${formatCents(change)}`}
              </span>
              <span className="col-span-2 text-right md:col-span-1">
                <button
                  type="button"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(entry.id)}
                  aria-label={`Remove the value from ${formatDate(entry.date)}`}
                  className={BUTTON_LINK}
                >
                  Remove
                </button>
              </span>
            </li>
          );
        })}
      </ul>
      {remove.isError && (
        <p role="alert" className="mt-3 text-danger">
          {remove.error.message}
        </p>
      )}
    </>
  );
}

/** "It is worth X now": records the value on a day. The same day again replaces what was there. */
function ValueForm({ asset, onClose }: { asset: Asset; onClose: () => void }) {
  const id = useId();
  const save = useSaveAssetValue(asset.id);
  const today = todayIso();
  const [value, setValue] = useState("");
  const [date, setDate] = useState(useDefaultDate().date);
  const [valueError, setValueError] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const valueCents = parseDollars(value);
    if (valueCents === null || valueCents < 0) {
      setValueError(AMOUNT_HELP);
      return;
    }
    setValueError(undefined);
    save.mutate({ date, valueCents }, { onSuccess: onClose });
  };

  return (
    <Modal title={`Update value — ${asset.name}`} icon={ASSET_KIND_ICONS[asset.kind]} tone={asset.color} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField
          label="Value"
          value={value}
          onChange={setValue}
          error={valueError}
          hint="Your best figure for what it is worth: an appraisal, or what one like it sells for."
          required
          hero
        />
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            As of
          </label>
          <input
            id={`${id}-date`}
            type="date"
            value={date}
            max={today}
            onChange={(event) => setDate(event.target.value)}
            required
            className={INPUT}
          />
          <p className={HINT}>Today or earlier. A day you already entered is replaced.</p>
          <DefaultDateHint value={date} />
        </div>

        {save.isError && (
          <p role="alert" className="text-danger">
            {save.error.message}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : "Save value"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
