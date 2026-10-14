import { CircleHelp } from "lucide-react";
import { Link, useLocation } from "react-router";
import { topicForPath } from "../../help/guide";

/**
 * The ? beside a screen's title: opens the guide's chapter for the screen you're on (worked out
 * from the address, so every screen uses the same tag). A full-size target, kept small to look at.
 */
export function HelpLink() {
  const { pathname } = useLocation();
  const topic = topicForPath(pathname);
  return (
    <Link
      to={topic ? `/help/${topic.id}` : "/help"}
      aria-label={topic ? `Help with ${topic.title}` : "Help"}
      title={topic ? `Help with ${topic.title}` : "Help"}
      // The negative margin keeps the full-size target from making the title's row any taller.
      className="-my-2 inline-flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-full text-muted hover:bg-surface hover:text-accent-ink focus-visible:outline-2 focus-visible:outline-accent-ink"
    >
      <CircleHelp aria-hidden className="size-5" />
    </Link>
  );
}
