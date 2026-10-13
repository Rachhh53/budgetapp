import { CircleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router";
import type { PlannerFlag } from "../../api/planner";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { formatRate } from "../../lib/rates";

function money(cents: number | null): ReactNode {
  return <span data-amount>{formatCents(cents ?? 0)}</span>;
}

function sentence(flag: PlannerFlag): ReactNode {
  if (flag.kind === "unclaimed_match") {
    return (
      <>
        {flag.putInBp !== null && flag.capBp !== null ? (
          <>
            You put in {formatRate(flag.putInBp)} of your pay and {flag.name} matches up to {formatRate(flag.capBp)}, so
          </>
        ) : (
          <>{flag.name} would match more than you put in now, so</>
        )}{" "}
        the next {money(flag.amountCents)} a month into {flag.accountName} also brings {money(flag.matchCents)} from your employer.
      </>
    );
  }
  return (
    <>
      {flag.accountName}: {flag.name} ends {flag.date === null ? "soon" : formatDate(flag.date)} with {money(flag.amountCents)} still on it.{" "}
      {flag.planned ? (
        <>Its payoff is already planned{flag.perPaycheckCents !== null && <>, at {money(flag.perPaycheckCents)} a paycheck</>}.</>
      ) : flag.urgent ? (
        "If any is left that day, interest for the whole promotion is charged at once."
      ) : (
        "What is left then starts being charged interest."
      )}
    </>
  );
}

/**
 * What to know before choosing (design/planner.png, "Worth knowing first"): a match that is not
 * being earned in full, and promotional rates that are running out. Each line leads to where it
 * is dealt with. Said in words with an icon, never by color alone.
 */
export function PlannerFlags({ flags }: { flags: readonly PlannerFlag[] }) {
  if (flags.length === 0) {
    return null;
  }
  return (
    <section aria-label="Worth knowing first" className="rounded-card border border-warn-line bg-warn p-4 text-warn-ink md:p-5">
      <h2 className="flex items-center gap-2 font-semibold">
        <CircleAlert aria-hidden className="size-5 shrink-0" />
        Worth knowing first
      </h2>
      <ul className="mt-2 space-y-2">
        {flags.map((flag, index) => (
          // Two promotions on one card can share a name; nothing but its place tells them apart.
          <li key={index} className="wrap-break-word">
            {sentence(flag)}{" "}
            <Link
              to={flag.kind === "unclaimed_match" ? `/income/${flag.sourceId}` : `/accounts/${flag.accountId}`}
              className="cursor-pointer font-semibold underline"
            >
              {flag.kind === "unclaimed_match" ? "Open the income source" : "Open the card"}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
