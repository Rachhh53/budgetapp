import { useId, useState } from "react";
import { type Item, useItems, usePauseItem, useResumeItem } from "../../api/items";
import { formatDate, todayIso } from "../../lib/dates";
import { FormSection } from "../FormSection";
import { BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * Stops an item for a while without ending it: until a date, or until it is turned back on.
 * It acts straight away, on its own. These are the controls alone, for whatever frames them:
 * a card on the item's page, or a section of the bucket form.
 */
export function PauseControls({ item: opened }: { item: Item }) {
  const id = useId();
  // Whoever shows this may hold the item as it was when opened; pausing changes it, so read it as it is now.
  const item = useItems().data?.find((candidate) => candidate.id === opened.id) ?? opened;
  const pause = usePauseItem(item.id);
  const resume = useResumeItem(item.id);
  const [until, setUntil] = useState("");
  const failure = pause.error?.message ?? resume.error?.message;

  return (
    <div className="space-y-4">
      {item.paused ? (
        <>
          <p role="status" className="font-semibold">
            {item.pausedUntil ? `Paused. It starts again on ${formatDate(item.pausedUntil)}.` : "Paused until you turn it back on."}
          </p>
          <p className={HINT}>Nothing it is scheduled for happens while it is paused. What it did before the pause stays as it was.</p>
          <div>
            <button type="button" onClick={() => resume.mutate()} disabled={resume.isPending} className={BUTTON_SECONDARY}>
              {resume.isPending ? "Resuming…" : "Resume now"}
            </button>
          </div>
        </>
      ) : (
        <>
          <div>
            <label htmlFor={`${id}-pause-until`} className={LABEL}>
              Start again on (optional)
            </label>
            <input
              id={`${id}-pause-until`}
              type="date"
              value={until}
              min={todayIso()}
              onChange={(event) => setUntil(event.target.value)}
              className={INPUT}
            />
            <p className={HINT}>
              Pausing skips everything it is scheduled for from today. Leave the date empty to pause until you turn it back on.
            </p>
          </div>
          <div>
            <button
              type="button"
              onClick={() => pause.mutate(until === "" ? null : until)}
              disabled={pause.isPending}
              className={BUTTON_SECONDARY}
            >
              {pause.isPending ? "Pausing…" : until === "" ? "Pause until I turn it back on" : "Pause until then"}
            </button>
          </div>
        </>
      )}
      {failure && (
        <p role="alert" className="text-danger">
          {failure}
        </p>
      )}
    </div>
  );
}

/** The pause controls as a section of a form. Nothing else typed in that form is saved by them. */
export function PauseItem({ item }: { item: Item }) {
  return (
    <FormSection title="Pause">
      <PauseControls item={item} />
    </FormSection>
  );
}
