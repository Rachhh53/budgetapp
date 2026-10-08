import { Link } from "react-router";
import { type PayPeriod, usePayPeriods } from "../api/income";
import { Amount, EstimateKey } from "../components/Amount";
import { Card } from "../components/Card";
import { movedReason } from "../components/income/schedule";
import { PAGE_TITLE } from "../components/ui";
import { formatDate, formatDay, formatWeekdayDay } from "../lib/dates";

/**
 * Pay periods, generated from the primary income source: the current one first, then every
 * one out to the projection horizon, each with the paychecks landing in it and, once a
 * balance has been entered, where the paycheck's account is projected to end. The full
 * one-screen period view (design/pay-period.png) replaces this list in Phase 2b.
 */
export function PayPeriodsPage() {
  const payPeriods = usePayPeriods();
  const periods = payPeriods.data?.periods ?? [];
  const anyEstimate = periods.some((period) => period.paychecks.some((paycheck) => paycheck.estimate));

  return (
    <>
      <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between md:mb-6">
        <h1 className={PAGE_TITLE}>Pay periods</h1>
        {anyEstimate && <EstimateKey />}
      </div>

      {payPeriods.isPending && <p className="text-muted">Loading your pay periods…</p>}
      {payPeriods.isError && (
        <p role="alert" className="text-danger">
          {payPeriods.error.message}
        </p>
      )}

      {payPeriods.data && periods.length === 0 && (
        <Card>
          {payPeriods.data.primarySourceId === null ? (
            <>
              <p className="font-medium">No pay periods yet.</p>
              <p className="mt-1 text-muted">
                Pay periods come from your main paycheck.{" "}
                <Link to="/income" className="font-semibold text-accent underline">
                  Add your income
                </Link>{" "}
                and they'll appear here, one per payday.
              </p>
            </>
          ) : (
            <>
              <p className="font-medium">Your pay periods haven't started yet.</p>
              <p className="mt-1 text-muted">
                The first one begins on your first payday, and each needs the payday after it to know where it ends.{" "}
                <Link to="/income" className="font-semibold text-accent underline">
                  Check your pay schedule
                </Link>{" "}
                if that doesn't sound right.
              </p>
            </>
          )}
        </Card>
      )}

      {periods.length > 0 && (
        <ol className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-3">
          {periods.map((period) => (
            <li key={period.start}>
              <PeriodCard period={period} />
            </li>
          ))}
        </ol>
      )}
    </>
  );
}

function PeriodCard({ period }: { period: PayPeriod }) {
  return (
    <section
      aria-label={`${formatDate(period.start)} to ${formatDate(period.end)}`}
      className={`h-full rounded-card bg-surface p-5 ${period.current ? "border-2 border-accent" : "border border-line"}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">
          {formatDay(period.start)} – {formatDate(period.end)}
        </h2>
        {period.current && (
          <span className="rounded-md bg-accent px-2 py-0.5 text-xs font-bold tracking-wide text-on-accent uppercase">
            Current
          </span>
        )}
      </div>

      <ul className="mt-2 divide-y divide-line">
        {period.paychecks.map((paycheck) => {
          const reason = movedReason(paycheck);
          return (
            <li key={`${paycheck.incomeSourceId}-${paycheck.scheduledDate}`} className="flex items-center gap-3 py-2">
              <span className="min-w-0 flex-1">
                <span className="block truncate">{paycheck.sourceName}</span>
                <span className="block text-sm text-muted">
                  {formatWeekdayDay(paycheck.payDate)}
                  {reason && ` · ${reason}`}
                </span>
              </span>
              <Amount cents={paycheck.netCents} estimate={paycheck.estimate} className="shrink-0" />
            </li>
          );
        })}
      </ul>

      <p className="mt-2 flex items-center justify-between gap-3 border-t border-line pt-2 font-semibold">
        <span>Coming in</span>
        <Amount cents={period.incomeCents} />
      </p>
      {period.projectedEndingCents !== null && (
        <p className="flex items-center justify-between gap-3 font-semibold">
          <span>Projected ending</span>
          <Amount cents={period.projectedEndingCents} />
        </p>
      )}
    </section>
  );
}
