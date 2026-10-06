import type { Account, AccountType } from "../../api/accounts";

export const TYPE_LABELS: Record<AccountType, string> = {
  checking: "Checking",
  savings: "Savings",
  credit_card: "Credit card",
  investment: "Investment",
  loan: "Loan",
};

/** How the Accounts screen groups accounts (design/accounts.png). */
export const ACCOUNT_GROUPS: readonly { title: string; types: readonly AccountType[] }[] = [
  { title: "Checking & savings", types: ["checking", "savings"] },
  { title: "Credit cards", types: ["credit_card"] },
  { title: "Investments", types: ["investment"] },
  { title: "Loans", types: ["loan"] },
];

/** Common labels offered for each kind of account; the user can always type their own. */
export const LABEL_SUGGESTIONS: Record<AccountType, readonly string[]> = {
  checking: ["Joint", "Bills"],
  savings: ["High-yield", "Emergency fund"],
  credit_card: ["Rewards", "Store card"],
  investment: ["401(k)", "403(b)", "IRA", "Roth IRA", "Brokerage", "HSA", "529"],
  loan: ["Mortgage", "Car loan", "Student loan", "Personal loan"],
};

/** The line under an account's name: its label (or kind) and where it is held. */
export function accountSubtitle(account: Account): string {
  return [account.subtypeLabel ?? TYPE_LABELS[account.type], account.institution].filter(Boolean).join(" · ");
}
