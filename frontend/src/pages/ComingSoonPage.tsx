import { Card } from "../components/Card";

/** Stands in for a screen that a later phase builds, so navigation works end to end today. */
export function ComingSoonPage({ title }: { title: string }) {
  return (
    <>
      <h1 className="mb-4 text-2xl font-bold md:mb-6 md:text-3xl">{title}</h1>
      <Card>
        <p className="font-medium">This screen is on its way.</p>
        <p className="mt-1 text-muted">Nothing for you to do here yet — it will appear in a later update.</p>
      </Card>
    </>
  );
}
