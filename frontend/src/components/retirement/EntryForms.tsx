import { CalendarHeart, Coins, HandCoins } from "lucide-react";
import { type FormEvent, useId, useState } from "react";
import {
  OLDEST,
  type RetirementIncome,
  type RetirementOneOff,
  type RetirementScenario,
  YOUNGEST,
  scenarioAsInput,
  useSaveScenario,
  useSetBirthDate,
} from "../../api/retirement";
import { centsToInput, parseDollars } from "../../lib/money";
import { Modal } from "../Modal";
import { AMOUNT_HELP, MoneyField } from "../MoneyField";
import { BUTTON_PRIMARY, BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";
import { ageWithin } from "./ScenarioForm";

const NAME_MAX = 80;

/** Your birth date: every age on the page counts from it. Kept only on this PC, like everything else. */
export function BirthDateForm({ birthDate, onClose }: { birthDate: string | null; onClose: () => void }) {
  const id = useId();
  const save = useSetBirthDate();
  const [date, setDate] = useState(birthDate ?? "");
  const [problem, setProblem] = useState<string>();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (date === "") {
      setProblem("Please choose your birth date.");
      return;
    }
    setProblem(undefined);
    save.mutate(date, { onSuccess: onClose });
  };

  return (
    <Modal title="Your birth date" icon={CalendarHeart} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <div>
          <label htmlFor={`${id}-date`} className={LABEL}>
            Birth date
          </label>
          <input id={`${id}-date`} type="date" value={date} onChange={(event) => setDate(event.target.value)} className={INPUT} />
          <p className={HINT}>Every age on this page counts from it. It stays on this PC.</p>
        </div>
        <Footer problem={problem ?? save.error?.message} saving={save.isPending} onClose={onClose} />
      </form>
    </Modal>
  );
}

/**
 * Adds or changes one kind of other income on a scenario (Social Security, a pension), or removes
 * it. The scenario is saved whole, with only this entry changed.
 *
 * @param index which entry is changed; left out to add one
 */
export function IncomeForm({ scenario, index, onClose }: { scenario: RetirementScenario; index?: number; onClose: () => void }) {
  const id = useId();
  const save = useSaveScenario(scenario.id);
  const current: RetirementIncome | undefined = index === undefined ? undefined : scenario.incomes[index];
  const [name, setName] = useState(current?.name ?? "");
  const [yearly, setYearly] = useState(current ? centsToInput(current.yearlyCents) : "");
  const [age, setAge] = useState(current ? String(current.startAge) : "");
  const [problem, setProblem] = useState<string>();

  const saveWith = (incomes: RetirementIncome[]) => save.mutate({ ...scenarioAsInput(scenario), incomes }, { onSuccess: onClose });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const yearlyCents = parseDollars(yearly);
    const startAge = ageWithin(age);
    if (name.trim() === "") {
      setProblem("Please add a name, like Social Security.");
      return;
    }
    if (yearlyCents === null || yearlyCents <= 0) {
      setProblem(yearlyCents === null ? AMOUNT_HELP : `Please enter what ${name.trim()} brings a year, more than $0.00.`);
      return;
    }
    if (startAge === null) {
      setProblem(`Please enter the age it starts, between ${YOUNGEST} and ${OLDEST}.`);
      return;
    }
    setProblem(undefined);
    const entry = { name: name.trim(), yearlyCents, startAge };
    saveWith(index === undefined ? [...scenario.incomes, entry] : scenario.incomes.map((income, at) => (at === index ? entry : income)));
  };

  return (
    <Modal title={current ? current.name : "Other income"} icon={HandCoins} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <div>
          <label htmlFor={`${id}-name`} className={LABEL}>
            Name
          </label>
          <input
            id={`${id}-name`}
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={NAME_MAX}
            placeholder="Social Security"
            className={INPUT}
          />
        </div>
        <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
          <MoneyField label="A year" value={yearly} onChange={setYearly} hint="In today's dollars." />
          <AgeField id={`${id}-age`} label="Starts at age" value={age} onChange={setAge} placeholder="67" />
        </div>
        <Footer
          problem={problem ?? save.error?.message}
          saving={save.isPending}
          onClose={onClose}
          onRemove={index === undefined ? undefined : () => saveWith(scenario.incomes.filter((_, at) => at !== index))}
        />
      </form>
    </Modal>
  );
}

/**
 * Adds or changes one amount that comes in or goes out once at an age (a house sale, an
 * inheritance, a big expense), or removes it. Money out is typed with a minus sign.
 *
 * @param index which entry is changed; left out to add one
 */
export function OneOffForm({ scenario, index, onClose }: { scenario: RetirementScenario; index?: number; onClose: () => void }) {
  const id = useId();
  const save = useSaveScenario(scenario.id);
  const current: RetirementOneOff | undefined = index === undefined ? undefined : scenario.oneOffs[index];
  const [name, setName] = useState(current?.name ?? "");
  const [amount, setAmount] = useState(current ? centsToInput(current.amountCents) : "");
  const [age, setAge] = useState(current ? String(current.age) : "");
  const [problem, setProblem] = useState<string>();

  const saveWith = (oneOffs: RetirementOneOff[]) => save.mutate({ ...scenarioAsInput(scenario), oneOffs }, { onSuccess: onClose });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const amountCents = parseDollars(amount);
    const at = ageWithin(age);
    if (name.trim() === "") {
      setProblem("Please add a name, like Sell the house.");
      return;
    }
    if (amountCents === null || amountCents === 0) {
      setProblem("Please enter an amount: money in, or money out with a minus sign, like -15000.");
      return;
    }
    if (at === null) {
      setProblem(`Please enter the age it happens, between ${YOUNGEST} and ${OLDEST}.`);
      return;
    }
    setProblem(undefined);
    const entry = { name: name.trim(), amountCents, age: at };
    saveWith(index === undefined ? [...scenario.oneOffs, entry] : scenario.oneOffs.map((oneOff, place) => (place === index ? entry : oneOff)));
  };

  return (
    <Modal title={current ? current.name : "One-off amount"} icon={Coins} onClose={onClose}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <div>
          <label htmlFor={`${id}-name`} className={LABEL}>
            Name
          </label>
          <input
            id={`${id}-name`}
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={NAME_MAX}
            placeholder="Sell the house"
            className={INPUT}
          />
        </div>
        <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
          <MoneyField label="Amount" value={amount} onChange={setAmount} hint="Money out with a minus sign. Today's dollars." />
          <AgeField id={`${id}-age`} label="At age" value={age} onChange={setAge} placeholder="70" />
        </div>
        <Footer
          problem={problem ?? save.error?.message}
          saving={save.isPending}
          onClose={onClose}
          onRemove={index === undefined ? undefined : () => saveWith(scenario.oneOffs.filter((_, place) => place !== index))}
        />
      </form>
    </Modal>
  );
}

function AgeField({
  id,
  label,
  value,
  onChange,
  placeholder,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (text: string) => void;
  placeholder: string;
}) {
  return (
    <div>
      <label htmlFor={id} className={LABEL}>
        {label}
      </label>
      <input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        inputMode="numeric"
        autoComplete="off"
        placeholder={placeholder}
        className={`${INPUT} tabular-nums`}
      />
    </div>
  );
}

/** The problem to put right, then Remove (for an entry already there), Cancel and Save. */
function Footer({ problem, saving, onClose, onRemove }: { problem?: string; saving: boolean; onClose: () => void; onRemove?: () => void }) {
  return (
    <>
      {problem && (
        <p role="alert" className="text-danger">
          {problem}
        </p>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        {onRemove && (
          <button type="button" onClick={onRemove} disabled={saving} className={`${BUTTON_SECONDARY} sm:mr-auto`}>
            Remove
          </button>
        )}
        <button type="button" onClick={onClose} className={BUTTON_SECONDARY}>
          Cancel
        </button>
        <button type="submit" disabled={saving} className={BUTTON_PRIMARY}>
          {saving ? "Saving…" : "Save"}
        </button>
      </div>
    </>
  );
}
