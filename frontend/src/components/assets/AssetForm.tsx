import { type FormEvent, useId, useState } from "react";
import type { Account } from "../../api/accounts";
import { type Asset, ASSET_KINDS, type AssetKind, useDeleteAsset, useSaveAsset } from "../../api/assets";
import { todayIso } from "../../lib/dates";
import { useDefaultDate } from "../../api/settings";
import { parseDollars } from "../../lib/money";
import { parseRate, rateToInput } from "../../lib/rates";
import { ArchivedField } from "../ArchivedField";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { Picker } from "../Picker";
import { Segmented } from "../Segmented";
import { DefaultDateHint } from "../settings/DefaultDateHint";
import { HINT, INPUT, LABEL } from "../ui";
import { ASSET_KIND_ICONS, ASSET_KIND_LABELS } from "./labels";

const KIND_OPTIONS = ASSET_KINDS.map((kind) => ({ value: kind, label: ASSET_KIND_LABELS[kind], icon: ASSET_KIND_ICONS[kind] }));

const PLACEHOLDERS: Record<AssetKind, string> = {
  home: "e.g. Maple Street house",
  vehicle: "e.g. Outback",
  other: "e.g. Grandmother's ring",
};

/** The highest rate the backend takes: 1,000% a year, in basis points. */
const MAX_RATE_BP = 100_000;
/** The steepest yearly loss it takes: 99%. */
const MAX_LOSS_BP = 9_900;

/** What an asset's value does between the values entered for it (a loss was added 2026-10-11, for cars). */
type Trend = "hold" | "up" | "down";

const TRENDS: readonly { value: Trend; label: string }[] = [
  { value: "hold", label: "Stays the same" },
  { value: "up", label: "Goes up" },
  { value: "down", label: "Goes down" },
];

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

/** The parts of the form: which kind, its name and color, its first value, its loan and annual change, and putting it away. */
type AssetPart = "kind" | "what" | "worth" | "loan" | "about";

const ADD_GROUPS: readonly FormGroup<AssetPart>[] = [
  { label: "What it is", parts: ["kind", "what"] },
  { label: "What it is worth", parts: ["worth"] },
  { label: "Loan and annual change", parts: ["loan"] },
];
// The first value is entered while adding only; later ones have a history on the asset's page.
const EDIT_GROUPS: readonly FormGroup<AssetPart>[] = [
  { label: "What it is", parts: ["kind", "what"] },
  { label: "Loan and annual change", parts: ["loan"] },
  { label: "Archive or delete", parts: ["about"] },
];

/**
 * Add or change a home, a vehicle or anything else of value. Its first value is entered with
 * it; later values are entered on its own page, where their history is kept. The form is
 * shown a screen at a time (see `useFormParts`), so it never needs scrolling.
 */
export function AssetForm({ asset, assets, accounts, onClose, onDeleted }: AssetFormProps) {
  const id = useId();
  const parts = useFormParts<AssetPart>({ groups: asset ? EDIT_GROUPS : ADD_GROUPS, adding: !asset });
  const save = useSaveAsset(asset?.id);
  const today = todayIso();

  // Newest first, so the colors offered first are the ones used most recently.
  const usedColors = [...assets].reverse().map((existing) => existing.color);
  const [kind, setKind] = useState<AssetKind>(asset?.kind ?? "home");
  const [name, setName] = useState(asset?.name ?? "");
  const [color, setColor] = useState(asset?.color ?? firstUnusedColor(usedColors));
  const [value, setValue] = useState("");
  const [valueDate, setValueDate] = useState(useDefaultDate().date);
  // A loss is kept as a rate below zero; here it is a direction and a plain percent.
  const [trend, setTrend] = useState<Trend>(!asset?.appreciationBp ? "hold" : asset.appreciationBp > 0 ? "up" : "down");
  const [rate, setRate] = useState(asset?.appreciationBp ? rateToInput(Math.abs(asset.appreciationBp)) : "");
  const [loanId, setLoanId] = useState<string | null>(asset?.linkedLoanAccountId != null ? String(asset.linkedLoanAccountId) : null);
  const [archived, setArchived] = useState(asset?.archived ?? false);
  const [archivedOn, setArchivedOn] = useState(asset?.archivedOn ?? null);

  // Archived loans are left out, unless this asset already points at one.
  const loanOptions = accounts
    .filter((account) => account.type === "loan" && (!account.archived || String(account.id) === loanId))
    .map((account) => ({ id: String(account.id), label: account.name, color: account.color }));

  const valueCents = !asset && value.trim() !== "" ? parseDollars(value) : null;
  const typedRate = trend === "hold" ? null : parseRate(rate);
  const appreciationBp = typedRate === null || typedRate === 0 ? null : trend === "down" ? -typedRate : typedRate;

  const check = (): PartProblem<AssetPart> | null => {
    if (!asset && value.trim() !== "" && (valueCents === null || valueCents < 0)) {
      return { part: "worth", problem: AMOUNT_HELP };
    }
    if (trend !== "hold" && typedRate === null) {
      return { part: "loan", problem: `Please enter a percent a year, like ${trend === "down" ? "15" : "3"}.` };
    }
    if (trend === "up" && typedRate !== null && typedRate > MAX_RATE_BP) {
      return { part: "loan", problem: "Please enter 1,000% a year or less." };
    }
    if (trend === "down" && typedRate !== null && typedRate > MAX_LOSS_BP) {
      return { part: "loan", problem: "Something can lose up to 99% a year. Please enter 99 or less." };
    }
    return null;
  };

  const send = () => {
    save.mutate(
      {
        name,
        kind,
        color,
        appreciationBp,
        linkedLoanAccountId: loanId === null ? null : Number(loanId),
        archived,
        archivedOn,
        ...(asset ? {} : { valueCents, valueDate: valueCents === null ? null : valueDate }),
      },
      { onSuccess: onClose },
    );
  };

  const failure = parts.problem ?? save.error?.message;

  return (
    <Modal
      title={asset ? `Edit ${asset.name}` : "Add a home or asset"}
      icon={ASSET_KIND_ICONS[asset?.kind ?? kind]}
      tone={asset?.color}
      onClose={onClose}
    >
      <form ref={parts.ref} onSubmit={(event: FormEvent) => parts.submit(event, check, send)} className="space-y-4">
        {parts.header()}
        {parts.shows("kind") && <Segmented legend="What is it?" options={KIND_OPTIONS} value={kind} onChange={setKind} />}

        {parts.shows("what") && (
          <FormSection title="Name and color">
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
        )}

        {!asset && parts.shows("worth") && (
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
              {!asset && <DefaultDateHint value={valueDate} />}
            </div>
          </FormSection>
        )}

        {parts.shows("loan") && (
          <FormSection title="Loan and annual change">
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
              {/* Side by side on a phone too: a short list and a short number. */}
              <div className="grid grid-cols-[minmax(0,1fr)_7rem] gap-3">
                <div>
                  <label htmlFor={`${id}-trend`} className={LABEL}>
                    Between values, it
                  </label>
                  <select id={`${id}-trend`} value={trend} onChange={(event) => setTrend(event.target.value as Trend)} className={INPUT}>
                    {TRENDS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </div>
                {trend !== "hold" && (
                  <div>
                    <label htmlFor={`${id}-rate`} className={LABEL}>
                      By, % a year
                    </label>
                    <input
                      id={`${id}-rate`}
                      value={rate}
                      onChange={(event) => setRate(event.target.value)}
                      inputMode="decimal"
                      autoComplete="off"
                      placeholder={trend === "down" ? "e.g. 15" : "e.g. 3"}
                      className={`${INPUT} tabular-nums`}
                    />
                  </div>
                )}
              </div>
              <p className={HINT}>
                {trend === "hold"
                  ? "Its value stays what you last entered until you enter a new one."
                  : trend === "down"
                    ? "Your own guess, for something like a car. Its value drops a little each month until you enter a real one."
                    : "Your own guess, for something like a home. Its value rises a little each month until you enter a real one."}
              </p>
            </div>
          </FormSection>
        )}

        {asset && parts.shows("about") && (
          <FormSection title="About this">
            <ArchivedField
              archived={archived}
              archivedOn={archivedOn}
              onChange={(on, day) => {
                setArchived(on);
                setArchivedOn(day);
              }}
              hint="For something sold or gone: it counts toward net worth until that day, and not from then on."
            />
          </FormSection>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        {parts.buttons({ onClose, pending: save.isPending, saveLabel: asset ? "Save changes" : "Add" })}
      </form>

      {asset && parts.shows("about") && (
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
