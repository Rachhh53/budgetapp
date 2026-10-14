import { BookOpen, ChevronRight, type LucideIcon } from "lucide-react";
import { Link } from "react-router";
import type { Task, Topic } from "../../help/guide";
import { type TermId, TERMS } from "../../help/terms";
import { NAV_ITEMS } from "../../nav";
import { BUTTON_SECONDARY } from "../ui";

/** A chapter's icon is its screen's, from the sidebar, so the two are recognisably the same place. */
export function topicIcon(topic: Topic): LucideIcon {
  return NAV_ITEMS.find((item) => item.path === topic.path)?.icon ?? BookOpen;
}

/** A question answered in full, with the button that goes where it's done. */
export function TaskAnswer({ task }: { task: Task }) {
  return (
    <div>
      <p className="font-semibold">{task.question}</p>
      <p className="mt-1 text-muted">{task.answer}</p>
      <Link to={task.to} className={`${BUTTON_SECONDARY} mt-2 inline-flex cursor-pointer items-center justify-center gap-1`}>
        {task.button}
        <ChevronRight aria-hidden className="size-4" />
      </Link>
    </div>
  );
}

/** Questions that open in place: the question is the button, so a list of them stays short to scan. */
export function TaskList({ tasks }: { tasks: readonly Task[] }) {
  return (
    <ul className="divide-y divide-line">
      {tasks.map((task) => (
        <li key={task.question}>
          <details className="group py-1">
            <summary className="-mx-2 flex min-h-11 cursor-pointer list-none items-center gap-2 rounded-lg px-2 font-medium hover:bg-bg/60 [&::-webkit-details-marker]:hidden">
              <ChevronRight aria-hidden className="size-4 shrink-0 text-muted transition-transform group-open:rotate-90" />
              {task.question}
            </summary>
            <div className="pb-3 pl-6">
              <p className="text-muted">{task.answer}</p>
              <Link to={task.to} className={`${BUTTON_SECONDARY} mt-2 inline-flex cursor-pointer items-center justify-center gap-1`}>
                {task.button}
                <ChevronRight aria-hidden className="size-4" />
              </Link>
            </div>
          </details>
        </li>
      ))}
    </ul>
  );
}

/** Words and what they mean, as a definition list. */
export function TermList({ terms }: { terms: readonly TermId[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-8 gap-y-3 md:grid-cols-2">
      {terms.map((id) => (
        <div key={id}>
          <dt className="font-semibold">{TERMS[id].term}</dt>
          <dd className="text-muted">{TERMS[id].about}</dd>
        </div>
      ))}
    </dl>
  );
}
