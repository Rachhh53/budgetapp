import { type ReactNode, useId } from "react";

/**
 * The white (or dark-surface) panel every screen is built from. A titled card is named by
 * its heading, so screen readers list it as a region to jump to.
 */
export function Card({ title, children }: { title?: string; children: ReactNode }) {
  const headingId = useId();
  return (
    <section aria-labelledby={title ? headingId : undefined} className="rounded-card border border-line bg-surface p-5 md:p-6">
      {title && (
        <h2 id={headingId} className="mb-3 text-lg font-semibold">
          {title}
        </h2>
      )}
      {children}
    </section>
  );
}
