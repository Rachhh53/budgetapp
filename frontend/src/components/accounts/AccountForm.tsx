import { Landmark } from "lucide-react";
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
import { useDefaultDate } from "../../api/settings";
import { centsToInput, parseDollars } from "../../lib/money";
import { parseRate } from "../../lib/rates";
import { ColorPicker, firstUnusedColor } from "../ColorPicker";
import { ConfirmDelete } from "../ConfirmDelete";
import { type FormGroup, type PartProblem, useFormParts } from "../FormParts";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { TextPicker } from "../Picker";
import { BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
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

/** The parts of the form: what the account is, how it looks, its warnings, and putting it away. */
type AccountPart = "what" | "look" | "warnings" | "about";


/**
 * Add or change an account. Everything chosen from a list is a picker, never free text. The
 * form is shown a screen at a time (see `useFormParts`), so it never needs scrolling.
 */
export function AccountForm({ account, accounts, onClose, onDeleted }: AccountFormProps) {
  const id = useId();
  const save = useSaveAccount(account?.id);
  // A rate typed with a new account applies from the day new things start on (today, unless Settings says another).
  const rateFrom = useDefaultDate().date;

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
  // Adding takes two screens; changing has a third, for archiving and deleting. Only checking and savings have warnings.
  const groups: FormGroup<AccountPart>[] = [
    { label: "Details", parts: ["what"] },
    { label: holdsCash(type) ? "Color and warnings" : "Color", parts: ["look", "warnings"] },
    ...(account ? [{ label: "Archive or delete", parts: ["about"] } as const] : []),
  ];
  const parts = useFormParts<AccountPart>({ groups, adding: !account });
  const saveRate = useSaveRateFor();
  // Set once a new account is in but its rate was refused: adding again would make a second account.
  const [addedWithoutRate, setAddedWithoutRate] = useState(false);

  const institutions = unique(accounts.map((existing) => existing.institution));
  const labels = unique([
    ...LABEL_SUGGESTIONS[type],
    ...accounts.filter((existing) => existing.type === type).map((existing) => existing.subtypeLabel),
  ]);

  // Only checking and savings can have the warning; an empty field means no warning.
  const threshold = holdsCash(type) && lowBalance.trim() !== "" ? parseDollars(lowBalance) : null;
  // The rate can be given while adding; afterwards it has a history, kept on the account's page.
  const rateBp = !account && holdsCash(type) && apy.trim() !== "" ? parseRate(apy) : null;

  const check = (): PartProblem<AccountPart> | null => {
    if (holdsCash(type) && lowBalance.trim() !== "" && (threshold === null || threshold < 0)) {
      return { part: "warnings", problem: AMOUNT_HELP };
    }
    if (!account && holdsCash(type) && apy.trim() !== "" && (rateBp === null || rateBp > MAX_RATE_BP)) {
      return { part: "warnings", problem: "Please enter a rate like 4.10." };
    }
    return null;
  };

  const send = () => {
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
            { accountId: saved.account.id, rateBp, effectiveDate: rateFrom },
            { onSuccess: onClose, onError: () => setAddedWithoutRate(true) },
          );
        },
      },
    );
  };

  const failure =
    parts.problem ??
    save.error?.message ??
    (addedWithoutRate ? "The account was added, but its interest rate could not be saved. You can add the rate on the account's page." : undefined);

  return (
    <Modal title={account ? "Edit account" : "Add an account"} icon={Landmark} tone={account?.color} onClose={onClose}>
      <form ref={parts.ref} onSubmit={(event: FormEvent) => parts.submit(event, check, send)} className="space-y-4">
        {parts.header()}
        {parts.shows("what") && (
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
          </FormSection>
        )}

        {parts.shows("look") && (
          <FormSection title="How it looks">
            <ColorPicker label="Color" value={color} onChange={setColor} recent={usedColors} />
          </FormSection>
        )}

        {parts.shows("warnings") && holdsCash(type) && (
          <FormSection title="Warnings and interest">
            <MoneyField
              label="Warn me when the balance dips below (optional)"
              value={lowBalance}
              onChange={setLowBalance}
              hint="Pay periods where it is projected to go lower are flagged. Leave empty for no warning."
            />

            {/* Afterwards the rate has a history, and is changed in the Interest card on the account's page. */}
            {!account && (
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
                <p className={HINT}>Each month's interest is then projected, as an estimate.</p>
              </div>
            )}
          </FormSection>
        )}

        {account && parts.shows("about") && (
          <FormSection title="About this account">
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
          </FormSection>
        )}

        {failure && (
          <p role="alert" className="text-danger">
            {failure}
          </p>
        )}

        {addedWithoutRate ? (
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
              Close
            </button>
          </div>
        ) : (
          parts.buttons({
            onClose,
            pending: save.isPending || saveRate.isPending,
            saveLabel: account ? "Save changes" : "Add account",
          })
        )}
      </form>

      {account && parts.shows("about") && (
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
