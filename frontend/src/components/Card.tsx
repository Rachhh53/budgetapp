import { type ReactNode, useId } from "react";
import type { TermId } from "../help/terms";
import { InfoTip } from "./help/InfoTip";

/**
 * The white (or dark-surface) panel every screen is built from. A titled card is named by
 * its heading, so screen readers list it as a region to jump to. `action` sits across from the
 * title: the card's own Edit, for a card whose contents are changed from the card itself. `tip` puts
 * an ⓘ beside the title that says what the word means.
 */
export function Card({ title, action, tip, children }: { title?: string; action?: ReactNode; tip?: TermId; children: ReactNode }) {
  const headingId = useId();
  const heading = (className: string) => (
    <h2 id={headingId} className={className}>
      {title}
    </h2>
  );
  const named = (className: string) =>
    tip ? (
      <div className="flex min-w-0 items-center gap-1">
        {heading(className)}
        <InfoTip term={tip} />
      </div>
    ) : (
      heading(className)
    );
  return (
    <section aria-labelledby={title ? headingId : undefined} className="rounded-card border border-line bg-surface p-5 md:p-6">
      {title && action === undefined && <div className="mb-3">{named("text-lg font-semibold")}</div>}
      {title && action !== undefined && (
        <div className="mb-3 flex items-center justify-between gap-3">
          {named("min-w-0 text-lg font-semibold")}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
