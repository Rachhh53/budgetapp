import { type ReactNode, useId } from "react";

/**
 * The white (or dark-surface) panel every screen is built from. A titled card is named by
 * its heading, so screen readers list it as a region to jump to. `action` sits across from the
 * title: the card's own Edit, for a card whose contents are changed from the card itself.
 */
export function Card({ title, action, children }: { title?: string; action?: ReactNode; children: ReactNode }) {
  const headingId = useId();
  return (
    <section aria-labelledby={title ? headingId : undefined} className="rounded-card border border-line bg-surface p-5 md:p-6">
      {title && action === undefined && (
        <h2 id={headingId} className="mb-3 text-lg font-semibold">
          {title}
        </h2>
      )}
      {title && action !== undefined && (
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 id={headingId} className="min-w-0 text-lg font-semibold">
            {title}
          </h2>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
