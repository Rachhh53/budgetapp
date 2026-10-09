import type { ReactNode } from "react";

/**
 * A titled group of fields on a faint panel, so a long form reads as a few short questions
 * ("Amount", "Schedule") rather than one unbroken column of boxes. A real fieldset, so a
 * screen reader announces the group's name on the way in.
 */
export function FormSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <fieldset className="min-w-0 rounded-lg border border-line bg-bg/60 p-4">
      {/* Floated so the heading sits inside the panel instead of cutting through its border. */}
      <legend className="float-left mb-3 w-full text-xs font-bold tracking-wider text-muted uppercase">{title}</legend>
      <div className="clear-both space-y-4">{children}</div>
    </fieldset>
  );
}
