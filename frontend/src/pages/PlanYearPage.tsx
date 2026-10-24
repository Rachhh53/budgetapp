import { type ReactNode, useId, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useAccounts } from "../api/accounts";
import { useCategories, useTags } from "../api/categories";
import { useIncomeSources } from "../api/income";
import {
  PLAN_BASES,
  type PlanDetail,
  type PlanRow,
  type PlanStepView,
  type PlansOverview,
  useAcceptSuggestions,
  useDeleteDraft,
  useFinishSetup,
  usePlan,
  usePlans,
  useSetPlanAmount,
  useSetPlanBasis,
  useStartPlan,
} from "../api/plans";
import { AccountForm } from "../components/accounts/AccountForm";
import { Card } from "../components/Card";
import { ConfirmDelete } from "../components/ConfirmDelete";
import { IncomeSourceForm } from "../components/income/IncomeSourceForm";
import { ItemForm } from "../components/items/ItemForm";
import {
  basisLabel,
  describePlan,
  monthOf,
  STATUS_LABELS,
  STEP_HELP,
  STEP_LABELS,
  WIZARD_STEPS,
  type WizardStep,
} from "../components/plans/labels";
import { PlanStepTable } from "../components/plans/PlanStepTable";
import { Segmented } from "../components/Segmented";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, DETAIL_HEADER, PAGE_TITLE, ROW_LINK } from "../components/ui";
import { formatDate } from "../lib/dates";
import { formatCents } from "../lib/money";
import { HelpLink } from "../components/help/HelpLink";
import { InfoTip } from "../components/help/InfoTip";

/** A link drawn as a button: the way on to the next step is an address, so Back and a refresh keep the step. */
const LINK_BUTTON = "flex items-center justify-center text-center";

/**
 * Plan next year (design/plan-year.png). `/plan` lists the plans and starts one; `/plan/{id}/{step}`
 * is the plan itself, one step to a screen. A draft is saved as it is typed and changes nothing
 * until the day its year starts. First-time setup walks the same steps for the year under way.
 */
export function PlanYearPage() {
  const { id } = useParams();
  return id === undefined ? <PlansList /> : <PlanSteps id={Number(id)} />;
}

function PlansList() {
  const plans = usePlans();
  const start = useStartPlan();
  const navigate = useNavigate();
  const headingId = useId();

  const begin = (kind: "next_year" | "setup") =>
    start.mutate(kind, { onSuccess: (started) => navigate(`/plan/${started.id}/income`) });
  const overview = plans.data;
  const draft = overview?.plans.find((plan) => plan.status === "draft");

  return (
    <>
      <div className="mb-4 md:mb-6">
        <p className="text-sm font-semibold tracking-wide text-muted uppercase">Year by year</p>
        <div className="flex min-w-0 items-center gap-1">
          <h1 className={PAGE_TITLE}>Plan next year</h1>
          <HelpLink />
        </div>
      </div>
      <div className="space-y-4">
        {plans.isPending && <p className="text-muted">Looking up your plans…</p>}
        {plans.error && (
          <Card>
            <p role="alert" className="text-danger">
              {plans.error.message}
            </p>
          </Card>
        )}
        {overview && (
          <>
            <Card>
              <StartAPlan overview={overview} pending={start.isPending} onStart={begin} />
              {start.error && (
                <p role="alert" className="mt-3 text-danger">
                  {start.error.message}
                </p>
              )}
            </Card>
            {overview.plans.length > 0 && (
              <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
                <h2 id={headingId} className="mb-1 text-lg font-semibold">
                  Your plans
                </h2>
                <ul className="divide-y divide-line">
                  {overview.plans.map((plan) => (
                    <li key={plan.id} className="flex">
                      <Link to={`/plan/${plan.id}/${plan === draft ? "income" : "review"}`} className={ROW_LINK}>
                        <span className="min-w-0 flex-1">
                          <span className="block font-semibold">{describePlan(plan)}</span>
                          <span className="block text-sm text-muted">
                            {formatDate(plan.startsOn)} – {formatDate(plan.endsOn)}
                          </span>
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
      </div>
    </>
  );
}

/** What can be started from here, in words, with the one button that does it. */
function StartAPlan({
  overview,
  pending,
  onStart,
}: {
  overview: PlansOverview;
  pending: boolean;
  onStart: (kind: "next_year" | "setup") => void;
}) {
  const draft = overview.plans.find((plan) => plan.status === "draft");
  if (draft) {
    return (
      <>
        <p className="font-medium">{draft.setup ? "You are part-way through setting up Budget." : `Your ${draft.year} plan is a draft.`}</p>
        <p className="mt-1 text-muted">
          {draft.setup
            ? "Carry on where you left off. Everything you have added is saved."
            : `It is saved as you go and changes nothing until ${formatDate(draft.startsOn)}.`}
        </p>
        <Link to={`/plan/${draft.id}/income`} className={`${BUTTON_PRIMARY} ${LINK_BUTTON} mt-4`}>
          {draft.setup ? "Carry on setting up" : `Continue the ${draft.year} plan`}
        </Link>
      </>
    );
  }
  if (overview.canSetUp) {
    return (
      <>
        <p className="font-medium">Welcome. Let's set up your budget.</p>
        <p className="mt-1 text-muted">
          Six short steps: your accounts and income, your bills, what comes once a year, your spending budgets, and what you save. You can
          stop at any point and carry on later.
        </p>
        <button type="button" disabled={pending} onClick={() => onStart("setup")} className={`${BUTTON_PRIMARY} mt-4`}>
          {pending ? "Starting…" : "Set up Budget"}
        </button>
      </>
    );
  }
  if (overview.canPlan) {
    return (
      <>
        <p className="font-medium">Plan {overview.nextYear} before it starts.</p>
        <p className="mt-1 text-muted">
          Every bill and budget starts at what it will be on {formatDate(overview.nextStartsOn)}, beside a suggestion from what the
          last 12 months came to. Use the suggestions, keep the amounts or type your own. Nothing
          changes until {formatDate(overview.nextStartsOn)}, and a draft can be deleted and redone any time before then.
        </p>
        <button type="button" disabled={pending} onClick={() => onStart("next_year")} className={`${BUTTON_PRIMARY} mt-4`}>
          {pending ? "Starting…" : `Start the ${overview.nextYear} plan`}
        </button>
      </>
    );
  }
  if (overview.plans.some((plan) => plan.startsOn === overview.nextStartsOn)) {
    return (
      <>
        <p className="font-medium">{overview.nextYear} is planned.</p>
        <p className="mt-1 text-muted">The next plan can be started once this one's year is under way.</p>
      </>
    );
  }
  return (
    <>
      <p className="font-medium">A plan follows your paydays.</p>
      <p className="mt-1 text-muted">
        <Link to="/income" className="font-semibold text-accent-ink underline">
          Add your income
        </Link>{" "}
        and next year can be planned from here.
      </p>
    </>
  );
}

function isWizardStep(value: string | undefined): value is WizardStep {
  return WIZARD_STEPS.some((step) => step === value);
}

function PlanSteps({ id }: { id: number }) {
  const params = useParams();
  const plan = usePlan(id);
  const current: WizardStep = isWizardStep(params.step) ? params.step : "income";

  if (plan.isPending) {
    return <p className="text-muted">Opening the plan…</p>;
  }
  if (plan.error || plan.data === undefined) {
    return (
      <Card>
        <p role="alert" className="font-medium">
          We couldn't open that plan.
        </p>
        <p className="mt-1 text-muted">
          It may have been deleted.{" "}
          <Link to="/plan" className="font-semibold text-accent-ink underline">
            Back to Plan next year
          </Link>
        </p>
      </Card>
    );
  }
  return <Wizard detail={plan.data} current={current} />;
}

function Wizard({ detail, current }: { detail: PlanDetail; current: WizardStep }) {
  const { plan } = detail;
  const index = WIZARD_STEPS.indexOf(current);
  const before = WIZARD_STEPS[index - 1];
  const after = WIZARD_STEPS[index + 1];
  const stepView = detail.steps.find((step) => step.step === current);
  // Amounts are typed only into a draft of a coming year; setup changes the items themselves.
  const planning = plan.status === "draft" && !plan.setup;

  const setAmount = useSetPlanAmount(plan.id);
  const setBasis = useSetPlanBasis(plan.id);
  const sources = useIncomeSources();
  const accounts = useAccounts();
  // The income line whose whole paycheck for the plan's year is open; it is kept with the plan only.
  const [editingPay, setEditingPay] = useState<PlanRow>();
  const payingSource = editingPay && sources.data?.find((source) => source.id === editingPay.id);
  const accept = useAcceptSuggestions(plan.id);
  const failed = setAmount.error ?? setBasis.error ?? accept.error;
  const saving = setAmount.isPending || setBasis.isPending || accept.isPending;
  const address = (step: WizardStep) => `/plan/${plan.id}/${step}`;
  const title = plan.setup ? `Set up Budget: ${STEP_LABELS[current]}` : `${STEP_LABELS[current]} for ${plan.year}`;
  const costs = current === "bills" || current === "annual" || current === "budgets";

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/plan" className="text-accent-ink underline">
              Plan next year
            </Link>{" "}
            / {plan.year}
          </p>
          <p className="text-sm font-semibold tracking-wide text-muted uppercase">
            {plan.setup ? "Setup" : `Plan ${plan.year}`} · {STATUS_LABELS[plan.status]} · Step {index + 1} of {WIZARD_STEPS.length}
          </p>
          <div className="flex min-w-0 items-center gap-1">
            <h1 className={`${PAGE_TITLE} wrap-break-word`}>{title}</h1>
            <HelpLink />
          </div>
        </div>
      </div>

      <nav aria-label="Steps" className="mb-4">
        <ol className="grid grid-cols-2 gap-2 md:grid-cols-3 xl:grid-cols-6">
          {WIZARD_STEPS.map((step, position) => (
            <li key={step}>
              <Link
                to={address(step)}
                aria-current={step === current ? "step" : undefined}
                className={`flex min-h-14 cursor-pointer flex-col justify-center rounded-lg border px-3 py-2 ${
                  step === current ? "border-accent bg-accent text-on-accent" : "border-line bg-surface hover:bg-bg/60"
                }`}
              >
                <span className={`text-xs font-semibold tracking-wide uppercase ${step === current ? "" : "text-muted"}`}>
                  Step {position + 1}
                  {position < index && " · done"}
                </span>
                <span className="font-semibold wrap-break-word">{STEP_LABELS[step]}</span>
              </Link>
            </li>
          ))}
        </ol>
      </nav>

      <div className="space-y-4">
        {failed && (
          <p role="alert" className="text-danger">
            {failed.message}
          </p>
        )}

        {stepView ? (
          <>
            {planning && costs && (
              <Card>
                <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
                  <p className="min-w-0">
                    Suggestions use{" "}
                    <strong>
                      {monthOf(detail.historyFrom)} – {monthOf(detail.historyTo)}
                    </strong>
                    {plan.basis === "last_12_trend" && " and each one's price trend"}. Each amount starts at what it will be on{" "}
                    {formatDate(plan.startsOn)}: use the suggestion, keep it or type your own.{" "}
                    <InfoTip term="suggestionBasis" />
                  </p>
                  <div className="lg:w-96 lg:shrink-0">
                    <Segmented
                      legend="Suggest from"
                      dense
                      options={PLAN_BASES.map((basis) => ({ value: basis, label: basisLabel(basis, plan) }))}
                      value={plan.basis}
                      onChange={(basis) => setBasis.mutate({ basis })}
                    />
                  </div>
                </div>
                {detail.stale && (
                  <p role="status" className="mt-3 flex flex-col gap-2 rounded-lg border border-warn-line bg-warn px-3 py-2 text-warn-ink sm:flex-row sm:items-center sm:justify-between">
                    <span>A month has ended since these suggestions were worked out.</span>
                    <button
                      type="button"
                      disabled={saving}
                      onClick={() => setBasis.mutate({ basis: plan.basis, refresh: true })}
                      className={BUTTON_SECONDARY}
                    >
                      Use the latest months
                    </button>
                  </p>
                )}
              </Card>
            )}
            <Card>
              <p className="mb-3 text-muted">{STEP_HELP[stepView.step]}</p>
              <PlanStepTable
                plan={detail}
                step={stepView}
                saving={saving}
                onAmount={planning ? (row: PlanRow, amountCents) => setAmount.mutate({ kind: row.kind, id: row.id, amountCents }) : undefined}
                onEditPay={planning ? setEditingPay : undefined}
              />
              {plan.setup && plan.status === "draft" && <SetupAdd step={stepView} />}
            </Card>
            {editingPay?.pay && payingSource && (
              <IncomeSourceForm
                source={payingSource}
                plan={{ id: plan.id, year: plan.year, startsOn: plan.startsOn, pay: editingPay.pay }}
                isFirst={false}
                accounts={accounts.data ?? []}
                onClose={() => setEditingPay(undefined)}
              />
            )}
          </>
        ) : (
          <Review detail={detail} />
        )}

        <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
          {before && (
            <Link to={address(before)} className={`${BUTTON_SECONDARY} ${LINK_BUTTON}`}>
              ← {STEP_LABELS[before]}
            </Link>
          )}
          <p className="min-w-0 flex-1 text-sm text-muted">
            {plan.status !== "draft"
              ? plan.activatedOn && `In use since ${formatDate(plan.activatedOn)}. What it set is shown as it was then.`
              : plan.setup
                ? "Saved as you go. You can stop here and carry on later."
                : `Saved as you go. Nothing changes until ${formatDate(plan.startsOn)}. A draft can be deleted and redone any time before it starts.`}
          </p>
          {planning && stepView && stepView.rows.some((row) => row.reason !== "fixed" && row.chosenCents !== row.suggestedCents) && (
            <button type="button" disabled={saving} onClick={() => accept.mutate(stepView.step)} className={BUTTON_SECONDARY}>
              Accept all suggestions
            </button>
          )}
          {after && (
            <Link to={address(after)} className={`${BUTTON_PRIMARY} ${LINK_BUTTON}`}>
              Next: {STEP_LABELS[after]} →
            </Link>
          )}
        </div>

        {plan.status === "draft" && <DeleteDraft detail={detail} />}
      </div>
    </>
  );
}

/** First-time setup: what a step holds is added with the same forms as everywhere else. */
function SetupAdd({ step }: { step: PlanStepView }) {
  const accounts = useAccounts();
  const sources = useIncomeSources();
  const categories = useCategories();
  const tags = useTags();
  const [adding, setAdding] = useState<"account" | "income" | "item" | null>(null);

  const allAccounts = accounts.data ?? [];
  const open = allAccounts.filter((account) => !account.archived);
  // Until the accounts have loaded there is none to pay from either, so what needs one waits.
  const needsAccount = open.length === 0;
  const close = () => setAdding(null);

  return (
    <>
      {step.step === "income" && (
        <div className="mt-4 border-t border-line pt-4">
          <p className="font-medium">
            {accounts.data === undefined
              ? "Looking up your accounts…"
              : open.length === 0
                ? "Start with the account your paycheck lands in."
                : `${open.length === 1 ? "1 account" : `${open.length} accounts`} so far: ${open.map((account) => account.name).join(", ")}.`}
          </p>
          <p className="mt-1 text-sm text-muted">Add every account you want to follow: checking, savings, cards, loans, investments.</p>
        </div>
      )}
      <div className="mt-4 flex flex-col gap-2 sm:flex-row">
        {step.step === "income" ? (
          <>
            <button type="button" onClick={() => setAdding("account")} className={needsAccount ? BUTTON_PRIMARY : BUTTON_SECONDARY}>
              + Add an account
            </button>
            <button type="button" disabled={needsAccount} onClick={() => setAdding("income")} className={needsAccount ? BUTTON_SECONDARY : BUTTON_PRIMARY}>
              + Add income
            </button>
          </>
        ) : (
          <button type="button" disabled={needsAccount} onClick={() => setAdding("item")} className={BUTTON_PRIMARY}>
            + Add a recurring item
          </button>
        )}
      </div>
      {accounts.data !== undefined && needsAccount && step.step !== "income" && (
        <p className="mt-2 text-sm text-muted">An item is paid from an account. Add one in step 1 first.</p>
      )}
      {adding === "account" && <AccountForm accounts={allAccounts} onClose={close} />}
      {adding === "income" && <IncomeSourceForm isFirst={sources.data?.length === 0} accounts={allAccounts} onClose={close} />}
      {adding === "item" && <ItemForm accounts={allAccounts} categories={categories.data ?? []} tags={tags.data ?? []} onClose={close} />}
    </>
  );
}

function Review({ detail }: { detail: PlanDetail }) {
  const { plan } = detail;
  const navigate = useNavigate();
  const finish = useFinishSetup(plan.id);
  const left = detail.incomeCents - detail.outCents;
  const changed = detail.steps.flatMap((step) => step.rows).filter((row) => row.chosenCents !== row.currentCents);
  const tableId = useId();

  return (
    <>
      <Card>
        <div className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-3">
          <Figure label={`Take-home in ${plan.year}`} value={formatCents(detail.incomeCents)}>
            {detail.paydays} {detail.paydays === 1 ? "payday" : "paydays"}
          </Figure>
          <Figure label="Planned to go out" value={formatCents(detail.outCents)}>
            bills, yearly costs, budgets and saving
          </Figure>
          <Figure label={left < 0 ? "Short by" : "Left over"} value={formatCents(Math.abs(left))}>
            {left < 0 ? "more is planned to go out than comes in" : "not yet given a job"}
          </Figure>
        </div>
      </Card>

      <section aria-labelledby={tableId} className="rounded-card border border-line bg-surface p-5 md:p-6">
        <h2 id={tableId} className="mb-1 text-lg font-semibold">
          Step by step
        </h2>
        <ul className="divide-y divide-line">
          {detail.steps.map((step) => (
            <li key={step.step} className="flex">
              <Link to={`/plan/${plan.id}/${step.step}`} className={ROW_LINK}>
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{STEP_LABELS[step.step]}</span>
                  <span className="block text-sm text-muted">
                    {step.rows.length === 0
                      ? "nothing in this step"
                      : plan.setup
                        ? `${step.rows.length} added`
                        : `${step.changes === 0 ? "no changes" : step.changes === 1 ? "1 change" : `${step.changes} changes`}${
                            step.yours > 0 ? ` · ${step.yours} typed by you` : ""
                          }`}
                  </span>
                </span>
                <span data-amount className="text-right font-semibold tabular-nums">
                  {formatCents(step.chosenCents)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </section>

      {/* Only while it is a draft: once in force, every item already holds the plan's amount, so nothing would differ. */}
      {!plan.setup && plan.status === "draft" && (
        <Card title={`What changes on ${formatDate(plan.startsOn)}`}>
          {changed.length === 0 ? (
            <p className="text-muted">Nothing: every amount stays as it is now.</p>
          ) : (
            <ul className="divide-y divide-line">
              {changed.map((row) => (
                <li key={`${row.kind}-${row.id}`} className="flex flex-col gap-x-4 py-2 sm:flex-row sm:items-baseline sm:justify-between">
                  <span className="min-w-0 font-medium wrap-break-word">
                    {row.name}
                    {row.yours && <span className="ml-2 text-xs font-bold tracking-wide text-accent-ink uppercase">Yours</span>}
                  </span>
                  <span data-amount className="tabular-nums sm:text-right">
                    {formatCents(row.currentCents)} → <strong>{formatCents(row.chosenCents)}</strong>{" "}
                    <span className="text-muted">{row.kind === "income" ? "a paycheck" : "a year"}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-3 text-sm text-muted">
            There is nothing to press: on {formatDate(plan.startsOn)} this plan becomes active by itself, and these become the amounts from
            that day on. Everything before then stays exactly as it was.
          </p>
        </Card>
      )}

      {plan.setup && plan.status === "draft" && (
        <Card title="All set?">
          <p className="text-muted">
            Finishing changes nothing you have added. It only puts setup away, and from then on you can plan each coming year from here.
          </p>
          <button
            type="button"
            disabled={finish.isPending}
            onClick={() => finish.mutate(undefined, { onSuccess: () => navigate("/") })}
            className={`${BUTTON_PRIMARY} mt-4`}
          >
            {finish.isPending ? "Finishing…" : "Finish setup"}
          </button>
          {finish.error && (
            <p role="alert" className="mt-3 text-danger">
              {finish.error.message}
            </p>
          )}
        </Card>
      )}
    </>
  );
}

function Figure({ label, value, children }: { label: string; value: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <p className="text-muted">{label}</p>
      <p data-amount className="text-2xl font-bold tabular-nums wrap-break-word">
        {value}
      </p>
      <p className="text-sm text-muted wrap-break-word">{children}</p>
    </div>
  );
}

/** Deleting a draft is one of the few things that asks first (spec §6). */
function DeleteDraft({ detail }: { detail: PlanDetail }) {
  const { plan } = detail;
  const navigate = useNavigate();
  const remove = useDeleteDraft(plan.id);
  const typed = detail.steps.reduce((count, step) => count + step.yours, 0);
  return (
    <ConfirmDelete
      action={plan.setup ? "Leave setup…" : "Delete draft & start over…"}
      question={plan.setup ? "Leave setup?" : `Delete the ${plan.year} draft?`}
      detail={
        plan.setup
          ? "Everything you have added stays, and Budget works as it is. Only these setup steps are put away."
          : `${typed === 0 ? "No amounts were typed into it" : typed === 1 ? "The 1 amount you typed goes with it" : `The ${typed} amounts you typed go with it`}. Nothing else changes, and you can start a new draft straight away.`
      }
      confirmLabel={plan.setup ? "Yes, leave setup" : "Yes, delete the draft"}
      pending={remove.isPending}
      error={remove.error?.message}
      onConfirm={() => remove.mutate(undefined, { onSuccess: () => navigate("/plan") })}
    />
  );
}
