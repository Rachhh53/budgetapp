import type { ReactNode } from "react";

/** The white (or dark-surface) panel every screen is built from. */
export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="rounded-card border border-line bg-surface p-5 md:p-6">
      {title && <h2 className="mb-3 text-lg font-semibold">{title}</h2>}
      {children}
    </section>
  );
}
