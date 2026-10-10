import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type Asset, ASSET_KINDS, type AssetKind, useDeleteAsset, useSaveAsset } from "../../api/assets";
import { todayIso } from "../../lib/dates";
import { parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { ASSET_KIND_ICONS, ASSET_KIND_LABELS } from "./labels";

const KIND_OPTIONS = ASSET_KINDS.map((kind) => ({ value: kind, label: ASSET_KIND_LABELS[kind], icon: ASSET_KIND_ICONS[kind] }));

const PLACEHOLDERS: Record<AssetKind, string> = {
  home: "e.g. Maple Street house",
  vehicle: "e.g. Outback",
  other: "e.g. Grandmother's ring",
};

/** The highest rate the backend takes: 1,000% a year, in basis points. */
const MAX_RATE_BP = 100_000;

interface AssetFormProps {
  /** The asset being changed; leave out to add a new one. */
  asset?: Asset;
  /** Every asset, for the recently used colors. */
  assets: readonly Asset[];
  /** Every account, for the loan picker. */
  accounts: readonly Account[];
  onClose: () => void;
  /** Called after the asset was deleted, so the caller can leave its screen. */
  onDeleted?: () => void;
}

/**
 * Add or change a home, a vehicle or anything else of value. Its first value is entered with
 * it; later values are entered on its own page, where their history is kept.
 */
export function AssetForm({ asset, assets, accounts, onClose, onDeleted }: AssetFormProps) {
  const id = useId();
  const save = useSaveAsset(asset?.id);
  const today = todayIso();

  // Newest first, so the colors offered first are the ones used most recently.
  const usedColors = [...assets].reverse().map((existing) => existing.color);
  const [kind, setKind] = useState<AssetKind>(asset?.kind ?? "home");
  const [name, setName] = useState(asset?.name ?? "");
  const [color, setColor] = useState(asset?.color ?? firstUnusedColor(usedColors));
  const [value, setValue] = useState("");
  const [valueDate, setValueDate] = useState(today);
  const [rate, setRate] = useState(asset?.appreciationBp != null ? rateToInput(asset.appreciationBp) : "");
  const [loanId, setLoanId] = useState<string | null>(asset?.linkedLoanAccountId != null ? String(asset.linkedLoanAccountId) : null);
  const [archived, setArchived] = useState(asset?.archived ?? false);
  const [problem, setProblem] = useState<string>();

  // Archived loans are left out, unless this asset already points at one.
  const loanOptions = accounts
    .filter((account) => account.type === "loan" && (!account.archived || String(account.id) === loanId))
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const valueCents = !asset && value.trim() !== "" ? parseDollars(value) : null;
    if (!asset && value.trim() !== "" && (valueCents === null || valueCents < 0)) {
      setProblem(AMOUNT_HELP);
      return;
    }
    const appreciationBp = rate.trim() === "" ? null : parseRate(rate);
    if (rate.trim() !== "" && (appreciationBp === null || appreciationBp > MAX_RATE_BP)) {
      setProblem("Please enter a yearly rate like 3.00, or leave it empty.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        name,
        kind,
        color,
        appreciationBp,
        linkedLoanAccountId: loanId === null ? null : Number(loanId),
        archived,
        ...(asset ? {} : { valueCents, valueDate: valueCents === null ? null : valueDate }),
      },
      { onSuccess: onClose },
    );
  };

  const failure = problem ?? save.error?.message;

  return (
    <Modal
      title={asset ? `Edit ${asset.name}` : "Add a home or asset"}
      icon={ASSET_KIND_ICONS[asset?.kind ?? kind]}
      tone={asset?.color}
      onClose={onClose}
    >
      <form onSubmit={submit} className="space-y-4">
        <Segmented legend="What is it?" options={KIND_OPTIONS} value={kind} onChange={setKind} />

        {!asset && (
          <FormSection title="What it is worth">
            <MoneyField
              label="Value"
              value={value}
              onChange={setValue}
              hint="Your best figure: an appraisal, a listing for one like it, or what it would sell for. You can leave it empty and add it later."
              hero
            />
            <div>
              <label htmlFor={`${id}-value-date`} className={LABEL}>
                As of
              </label>
              <input
                id={`${id}-value-date`}
                type="date"
                value={valueDate}
                max={today}
                onChange={(event) => setValueDate(event.target.value)}
                required
                className={INPUT}
              />
            </div>
          </FormSection>
        )}

        <FormSection title="What it is">
          <div>
            <label htmlFor={`${id}-name`} className={LABEL}>
              Name
            </label>
            <input
              id={`${id}-name`}
              value={name}
              onChange={(event) => setName(event.target.value)}
              maxLength={80}
              required
              autoComplete="off"
              placeholder={PLACEHOLDERS[kind]}
              className={INPUT}
            />
          </div>
          <ColorPicker label="Color" value={color} onChange={setColor} recent={usedColors} />
        </FormSection>

        <FormSection title="Loan and growth">
          <div>
            <Picker
              label="Loan against it (optional)"
              noun="loan"
              placeholder={loanOptions.length === 0 ? "No loan accounts yet" : "Owned outright"}
              options={loanOptions}
              value={loanId}
              onChange={setLoanId}
              clearable
            />
            <p className={HINT}>
              {loanOptions.length === 0
                ? "To see equity, add the mortgage or car loan as a Loan account on the Accounts screen, then choose it here."
                : "Equity is the value less what is still owed on this loan."}
            </p>
          </div>
          <div>
            <label htmlFor={`${id}-rate`} className={LABEL}>
              Assumed growth a year, % (optional)
            </label>
            <input
              id={`${id}-rate`}
              value={rate}
              onChange={(event) => setRate(event.target.value)}
              inputMode="decimal"
              autoComplete="off"
              placeholder="e.g. 3.00"
              className={`${INPUT} tabular-nums`}
            />
            <p className={HINT}>
              Your own guess, used between the values you enter. Leave it empty and the value simply stays as entered, which suits
              anything that loses value: for that, enter a new, lower value when you have one.
            </p>
          </div>
        </FormSection>

        {asset && (
          <FormSection title="About this">
            <div>
              <label className="flex min-h-11 items-center gap-3">
                <input
                  type="checkbox"
                  checked={archived}
                  onChange={(event) => setArchived(event.target.checked)}
                  className="size-5 accent-(--accent)"
                />
                <span className="font-medium">Archived</span>
              </label>
              <p className={HINT}>For something sold or gone: it keeps its history but no longer counts toward net worth.</p>
            </div>
          </FormSection>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            Cancel
          </button>
          <button type="submit" disabled={save.isPending} className={BUTTON_PRIMARY}>
            {save.isPending ? "Saving…" : asset ? "Save changes" : "Add"}
          </button>
        </div>
      </form>

      {asset && (
        <div className="mt-6 border-t border-line pt-4">
          <DeleteAsset
            asset={asset}
            onDeleted={() => {
              onClose();
              onDeleted?.();
            }}
          />
        </div>
      )}
    </Modal>
  );
}

function DeleteAsset({ asset, onDeleted }: { asset: Asset; onDeleted: () => void }) {
  const remove = useDeleteAsset(asset.id);
  return (
    <ConfirmDelete
      action="Delete…"
      question={`Delete ${asset.name}?`}
      detail="It goes, along with every value you entered for it. To keep the history of something you sold, archive it instead. You can undo this straight afterwards."
      confirmLabel="Yes, delete it"
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: onDeleted })}
    />
  );
}
