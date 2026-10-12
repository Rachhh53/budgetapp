import type { Account } from "../../api/accounts";
import type { StubApply, StubLine, StubSection, StubTracking } from "../../api/payStubs";

/** The parts of a stub, as their headings read on the review screen (design/pay-stub.png). */
export const SECTION_LABELS: Record<StubSection, string> = {
  earnings: "Earnings",
  pre_tax: "Before tax",
  tax: "Taxes",
  after_tax: "After tax",
  net: "Paid to you",
  employer: "From employer",
};

/**
 * What a line can be: where it sits on the stub and what the budget follows it as, chosen
 * together, because only some pairs make sense (a tax is never a contribution).
 */
export const LINE_KINDS = [
  { value: "earnings:gross", label: "Earnings (part of gross pay)" },
  { value: "pre_tax:contribution", label: "Before tax · into one of your accounts" },
  { value: "pre_tax:benefit", label: "Before tax · a benefit or fee" },
  { value: "tax:tax", label: "Tax" },
  { value: "after_tax:contribution", label: "After tax · into one of your accounts" },
  { value: "after_tax:benefit", label: "After tax · a benefit or fee" },
  { value: "net:net", label: "Net pay (paid to you)" },
  { value: "employer:match", label: "From employer · match into your account" },
  { value: "employer:ignore", label: "From employer · not followed" },
] as const;
export type LineKind = (typeof LINE_KINDS)[number]["value"];

export function kindOf(line: Pick<StubLine, "section" | "tracksAs">): LineKind {
  return `${line.section}:${line.tracksAs}` as LineKind;
}

export function partsOf(kind: LineKind): { section: StubSection; tracksAs: StubTracking } {
  const [section, tracksAs] = kind.split(":") as [StubSection, StubTracking];
  return { section, tracksAs };
}

/** Whether a line of this kind goes to an account of the person's own. */
export function goesToAnAccount(tracksAs: StubTracking): boolean {
  return tracksAs === "contribution" || tracksAs === "match";
}

/** The "Tracks as" column: what the budget does with a line, in a few words. */
export function tracksAsText(line: StubLine, accounts: readonly Account[], depositAccount: string | undefined): string {
  const account = accounts.find((candidate) => candidate.id === line.accountId)?.name;
  switch (line.tracksAs) {
    case "gross":
      return "Gross pay";
    case "contribution":
      return account ? `Contribution → ${account}` : "Contribution · no account chosen";
    case "benefit":
      return "Benefit or fee";
    case "tax":
      return "Taxes";
    case "net":
      return depositAccount ? `Paycheck → ${depositAccount}` : "Paycheck";
    case "match":
      return account ? `Match → ${account}` : "Match · no account chosen";
    case "ignore":
      return "Not followed";
  }
}

/** What saving a stub changed, for the list of stubs and the top of a saved one. */
export const APPLIED_LABELS: Record<StubApply, string> = {
  correct: "Corrected the amounts",
  pay_change: "Recorded a pay change",
  paycheck: "Set that one paycheck",
  none: "Kept, nothing changed",
};
