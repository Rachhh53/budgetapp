import { Save } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import { type PayStub, type StubApply, useSaveStub } from "../../api/payStubs";
import { formatDate } from "../../lib/dates";
import { formatCents } from "../../lib/money";
import { Modal } from "../Modal";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, INPUT } from "../ui";
import { goesToAnAccount } from "./labels";

interface Choice {
  value: StubApply;
  label: string;
  /** What choosing it does, in this stub's own figures. */
  detail: string;
  /** Why it cannot be chosen; undefined when it can. */
  unavailable?: string;
}

/**
 * Saving a stub asks what it should change (decided 2026-10-12: asked every time, since a
 * salaried stub usually corrects the source and an hourly one is only ever that paycheck).
 * Nothing is chosen for the person, except when the stub was added for one paycheck.
 */
export function SaveStubDialog({
  stub,
  startOn,
  onSaved,
  onClose,
}: {
  stub: PayStub;
  /** The choice to open on, for a stub added from one paycheck's row. */
  startOn?: StubApply;
  onSaved: () => void;
  onClose: () => void;
}) {
  const id = useId();
  const save = useSaveStub(stub.id);
  const pay = stub.wouldSet;
  const paycheck = stub.paycheck;
  const noNet = "This stub has no net pay line yet, so there is no take-home amount to use.";

  // Said once, above the choices: each choice then only says how far it reaches, so the form fits a phone.
  const figures = pay
    ? `take-home ${formatCents(pay.netCents)}${pay.grossCents !== null ? `, gross ${formatCents(pay.grossCents)}` : ""}, with ${
        pay.deductions.length === 1 ? "1 deduction" : `${pay.deductions.length} deductions`
      }.`
    : undefined;
  const choices: Choice[] = [
    {
      value: "correct",
      label: `Correct ${stub.sourceName}'s amounts`,
      detail: "Every paycheck using today's amounts.",
      unavailable: pay ? undefined : noNet,
    },
    {
      value: "pay_change",
      label: "My pay changed",
      detail: pay ? `From ${formatDate(paycheck?.scheduledDate ?? stub.payDate ?? "")} on. Earlier paychecks keep what they had.` : "",
      unavailable: pay ? undefined : noNet,
    },
    {
      value: "paycheck",
      label: "Just this paycheck",
      detail:
        pay && paycheck
          ? `Only the paycheck of ${formatDate(paycheck.payDate)}.`
          : "",
      unavailable: !pay
        ? noNet
        : paycheck
          ? undefined
          : `${stub.sourceName} has no paycheck within a few days of ${stub.payDate ? formatDate(stub.payDate) : "the stub's date"}.`,
    },
    {
      value: "one_off",
      label: "A one-off paycheck (bonus)",
      // Short enough for one line on a phone: the dialog has five choices and must not scroll.
      detail: `Its own paycheck on ${stub.payDate ? formatDate(stub.payDate) : "the stub's date"}.`,
      unavailable: pay ? undefined : noNet,
    },
    { value: "none", label: "Don't change anything", detail: "Keep the stub and change nothing." },
  ];

  const offered = choices.find((choice) => choice.value === startOn && !choice.unavailable)?.value;
  const [apply, setApply] = useState<StubApply | undefined>(offered);
  // A one-off's name. Left blank, a stub saved this way before keeps the name it was given, and a new one is called Bonus.
  const [name, setName] = useState("");
  const [problem, setProblem] = useState<string>();

  const unfiled = stub.lines.filter((line) => goesToAnAccount(line.tracksAs) && line.accountId === null && line.amountCents !== 0).length;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (apply === undefined) {
      setProblem("Please choose what this stub should change.");
      return;
    }
    setProblem(undefined);
    const oneOffName = apply === "one_off" && name.trim() !== "" ? name.trim() : undefined;
    save.mutate({ apply, name: oneOffName }, { onSuccess: onSaved });
  };

  const failure = problem ?? save.error?.message;
  return (
    <Modal title="Save this stub" icon={Save} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {figures && (
          <p data-amount className="text-muted">
            From this stub: {figures}
          </p>
        )}
        <fieldset className="space-y-1.5">
          <legend className="mb-1.5 font-medium">What should it change?</legend>
          {choices.map((choice) => (
            <div
              key={choice.value}
              className={`rounded-lg border px-3 py-1.5 has-checked:border-accent has-checked:bg-bg/60 ${
                choice.unavailable ? "border-line text-muted" : "border-line hover:bg-bg/60"
              }`}
            >
              <label className={`flex gap-3 ${choice.unavailable ? "" : "cursor-pointer"}`}>
                <input
                  type="radio"
                  name={`${id}-apply`}
                  value={choice.value}
                  checked={apply === choice.value}
                  disabled={choice.unavailable !== undefined}
                  onChange={() => setApply(choice.value)}
                  className="mt-1 size-5 shrink-0 accent-(--accent)"
                />
                <span className="min-w-0">
                  <span className="block font-semibold">{choice.label}</span>
                  <span data-amount className="block text-sm text-muted">
                    {choice.unavailable ?? choice.detail}
                  </span>
                </span>
              </label>
              {/* Asked inside its own choice, on one row, so the dialog still fits a phone without scrolling. */}
              {choice.value === "one_off" && apply === "one_off" && (
                <div className="mt-1.5 mb-1 flex items-center gap-3 pl-8">
                  <label htmlFor={`${id}-name`} className="shrink-0 text-sm font-medium">
                    Name (optional)
                  </label>
                  <input
                    id={`${id}-name`}
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    maxLength={80}
                    autoComplete="off"
                    placeholder={stub.applied === "one_off" ? "Keeps its name" : "Bonus"}
                    className={`${INPUT} min-w-0 flex-1`}
                  />
                </div>
              )}
            </div>
          ))}
        </fieldset>

        {!stub.totals.balanced && (
          <p role="status" className="rounded-lg border border-warn-line bg-warn px-3 py-2 text-sm text-warn-ink">
            The lines leave <span data-amount>{formatCents(Math.abs(stub.totals.differenceCents))}</span> unexplained. It can still be
            saved as it is.
          </p>
        )}
        {unfiled > 0 && apply !== "none" && apply !== "paycheck" && (
          <p role="status" className="text-sm text-muted">
            {unfiled === 1 ? "1 line has" : `${unfiled} lines have`} no account chosen, so {unfiled === 1 ? "it is" : "they are"} listed
            without moving money.
          </p>
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
            {save.isPending ? "Saving…" : "Save stub"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
