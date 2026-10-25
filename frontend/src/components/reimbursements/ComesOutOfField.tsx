import { useId } from "react";
import { useAccounts } from "../../api/accounts";
import { INPUT, LABEL } from "../ui";

/** The choice for money paid back from outside: an insurer, work, anyone but her own accounts. */
const OUTSIDE = "";

/**
 * Where money paid back comes out of (Rachel, 2026-10-25: "some will come out of there [the FSA], some might just be
 * reimbursed from no account"): one of her own accounts, so it is a transfer into the account it comes back into, or
 * none of them. The account it comes back into is not offered.
 *
 * @param value `null` for none of her accounts
 */
export function ComesOutOfField({
  value,
  onChange,
  intoAccountId,
}: {
  value: number | null;
  onChange: (accountId: number | null) => void;
  intoAccountId: number | null;
}) {
  const id = useId();
  const accounts = useAccounts();
  const choices = (accounts.data ?? []).filter(
    (account) => account.id !== intoAccountId && (!account.archived || account.id === value) && account.type !== "credit_card" && account.type !== "loan",
  );
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        Comes out of
      </label>
      <select
        id={id}
        value={value === null ? OUTSIDE : String(value)}
        onChange={(event) => onChange(event.target.value === OUTSIDE ? null : Number(event.target.value))}
        className={INPUT}
      >
        <option value={OUTSIDE}>None of my accounts</option>
        {choices.map((account) => (
          <option key={account.id} value={account.id}>
            {account.name}
          </option>
        ))}
      </select>
    </div>
  );
}
