import { type FormEvent, useId, useState } from "react";
import {
  type Account,
  ACCOUNT_TYPES,
  type AccountType,
  holdsCash,
  useDeleteAccount,
  useSaveAccount,
  useSaveRateFor,
} from "../../api/accounts";
import { todayIso } from "../../lib/dates";
import { centsToInput, parseDollars } from "../../lib/money";
import { parseRate } from "../../lib/rates";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { TextPicker } from "../Picker";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { LABEL_SUGGESTIONS, TYPE_LABELS } from "./labels";

/** The highest rate the backend takes: 1,000%, in basis points. */
const MAX_RATE_BP = 100_000;

interface AccountFormProps {
  /** The account being changed; leave out to add a new one. */
  account?: Account;
  /** Every account, for the bank and label suggestions and recently used colors. */
  accounts: readonly Account[];
  onClose: () => void;
  /** Called after the account was deleted, so the caller can leave its screen. */
  onDeleted?: () => void;
}

/** Add or change an account. Everything chosen from a list is a picker, never free text. */
export function AccountForm({ account, accounts, onClose, onDeleted }: AccountFormProps) {
  const id = useId();
  const save = useSaveAccount(account?.id);

  // Newest accounts first, so the colors offered first are the ones used most recently.
  const usedColors = [...accounts].reverse().map((existing) => existing.color);
  const [name, setName] = useState(account?.name ?? "");
  const [type, setType] = useState<AccountType>(account?.type ?? "checking");
  const [subtypeLabel, setSubtypeLabel] = useState(account?.subtypeLabel ?? "");
  const [institution, setInstitution] = useState(account?.institution ?? "");
  const [color, setColor] = useState(account?.color ?? firstUnusedColor(usedColors));
  const [archived, setArchived] = useState(account?.archived ?? false);
  const [lowBalance, setLowBalance] = useState(
    account?.lowBalanceThresholdCents != null ? centsToInput(account.lowBalanceThresholdCents) : "",
  );
  const [apy, setApy] = useState("");
  const [problem, setProblem] = useState<string>();
  const saveRate = useSaveRateFor();
  // Set once a new account is in but its rate was refused: adding again would make a second account.
  const [addedWithoutRate, setAddedWithoutRate] = useState(false);

  const institutions = unique(accounts.map((existing) => existing.institution));
  const labels = unique([
    ...LABEL_SUGGESTIONS[type],
    ...accounts.filter((existing) => existing.type === type).map((existing) => existing.subtypeLabel),
  ]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    // Only checking and savings can have the warning; an empty field means no warning.
    const threshold = holdsCash(type) && lowBalance.trim() !== "" ? parseDollars(lowBalance) : null;
    if (holdsCash(type) && lowBalance.trim() !== "" && (threshold === null || threshold < 0)) {
      setProblem(AMOUNT_HELP);
      return;
    }
    // The rate can be given while adding; afterwards it has a history, kept on the account's page.
    const rateBp = !account && holdsCash(type) && apy.trim() !== "" ? parseRate(apy) : null;
    if (!account && holdsCash(type) && apy.trim() !== "" && (rateBp === null || rateBp > MAX_RATE_BP)) {
      setProblem("Please enter a rate like 4.10.");
      return;
    }
    setProblem(undefined);
    save.mutate(
      {
        name,
        type,
        subtypeLabel: subtypeLabel || null,
        institution: institution || null,
        color,
        archived,
        lowBalanceThresholdCents: threshold,
      },
      {
        onSuccess: (saved) => {
          if (rateBp === null) {
            onClose();
            return;
          }
          saveRate.mutate(
            { accountId: saved.account.id, rateBp, effectiveDate: todayIso() },
            { onSuccess: onClose, onError: () => setAddedWithoutRate(true) },
          );
        },
      },
    );
  };

  const failure =
    problem ??
    save.error?.message ??
    (addedWithoutRate ? "The account was added, but its interest rate could not be saved. You can add the rate on the account's page." : undefined);

  return (
    <Modal title={account ? "Edit account" : "Add an account"} onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
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
            className={INPUT}
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-type`} className={LABEL}>
              Kind of account
            </label>
            <select
              id={`${id}-type`}
              value={type}
              onChange={(event) => setType(event.target.value as AccountType)}
              className={INPUT}
            >
              {ACCOUNT_TYPES.map((option) => (
                <option key={option} value={option}>
                  {TYPE_LABELS[option]}
                </option>
              ))}
            </select>
          </div>
          <TextPicker
            label="Label (optional)"
            noun="label"
            placeholder="e.g. 401(k), Mortgage"
            suggestions={labels}
            value={subtypeLabel}
            onChange={setSubtypeLabel}
          />
        </div>

        <TextPicker
          label="Bank or institution (optional)"
          noun="bank"
          placeholder="Choose or type a bank"
          suggestions={institutions}
          value={institution}
          onChange={setInstitution}
        />

        <ColorPicker label="Color" value={color} onChange={setColor} recent={usedColors} />

        {holdsCash(type) && (
          <MoneyField
            label="Warn me when the balance dips below (optional)"
            value={lowBalance}
            onChange={setLowBalance}
            hint="Pay periods where it is projected to go lower are flagged. Leave empty for no warning."
          />
        )}

        {holdsCash(type) &&
          (account ? (
            <p className={HINT}>
              Interest rate: add or change it under <span className="font-semibold">Interest</span> on this account's page, where its
              history is kept.
            </p>
          ) : (
            <div>
              <label htmlFor={`${id}-apy`} className={LABEL}>
                Interest rate, APY % (optional)
              </label>
              <input
                id={`${id}-apy`}
                value={apy}
                onChange={(event) => setApy(event.target.value)}
                inputMode="decimal"
                autoComplete="off"
                placeholder="e.g. 4.10"
                className={INPUT}
              />
              <p className={HINT}>
                With a rate, each month's interest is projected as an estimate. You can change it later on the account's page.
              </p>
            </div>
          ))}

        {account && (
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
            <p className={HINT}>An archived account keeps its history but is tucked away at the bottom of the list.</p>
          </div>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
            {addedWithoutRate ? "Close" : "Cancel"}
          </button>
          {!addedWithoutRate && (
            <button type="submit" disabled={save.isPending || saveRate.isPending} className={BUTTON_PRIMARY}>
              {save.isPending || saveRate.isPending ? "Saving…" : account ? "Save changes" : "Add account"}
            </button>
          )}
        </div>
      </form>

      {account && (
        <div className="mt-6 border-t border-line pt-4">
          <DeleteAccount
            account={account}
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

function DeleteAccount({ account, onDeleted }: { account: Account; onDeleted: () => void }) {
  const remove = useDeleteAccount(account.id);
  return (
    <ConfirmDelete
      action="Delete account…"
      question={`Delete ${account.name}?`}
      detail="The account and every balance you entered for it go with it. You can undo this straight afterwards."
      confirmLabel="Yes, delete it"
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: onDeleted })}
    />
  );
}

function unique(values: readonly (string | null)[]): string[] {
  return [...new Set(values.filter((value): value is string => value !== null && value !== ""))];
}
