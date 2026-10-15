import { ChevronRight, Search } from "lucide-react";
import { useId } from "react";
import { Link, useSearchParams } from "react-router";
import { Card } from "../components/Card";
import { TaskAnswer, TaskList, TermList, topicIcon } from "../components/help/HelpParts";
import { PRIVACY_NOTE } from "../components/Sidebar";
import { INPUT, PAGE_TITLE, ROW_LINK } from "../components/ui";
import { STEPS, TASKS, TOPICS } from "../help/guide";
import { searchHelp, sectionAnchor } from "../help/search";
import { type TermId, TERMS } from "../help/terms";

/** Questions shown before anything is typed: the first few, which the guide lists commonest first. */
const COMMON = 8;

/** Ideas for the finder, as buttons that fill it in. */
const IDEAS = ["mark paid", "receipt", "backup", "undo", "bucket", "PIN"];

/** Ctrl on Windows, ⌘ on a Mac. */
const MOD = typeof navigator !== "undefined" && /Mac/.test(navigator.platform) ? "⌘" : "Ctrl";

const SHORTCUTS: readonly { keys: string; does: string }[] = [
  { keys: `${MOD}+K`, does: "Find a bill or one-off (on Pay periods)" },
  { keys: `${MOD}+N`, does: "Add something new" },
  { keys: `${MOD}+Z`, does: "Undo the last change" },
  { keys: "← →", does: "Previous or next pay period" },
  { keys: "Enter", does: "Save an amount you're typing" },
  { keys: "Esc", does: "Cancel, or close a window" },
];

/**
 * Help (Rachel, 2026-10-14): a user guide with "Where is…?" at the top, then where to start, a
 * chapter per screen, common questions, the words Budget uses and keyboard shortcuts. What is
 * typed in the finder is kept in the address (`?q=`), so Back returns to the same answers.
 */
export function HelpPage() {
  const [params, setParams] = useSearchParams();
  const text = params.get("q") ?? "";
  const finderId = useId();
  const resultsId = useId();
  const search = (value: string) => setParams(value === "" ? {} : { q: value }, { replace: true });

  return (
    <>
      <div className="mb-4 md:mb-6">
        <p className="text-sm font-semibold tracking-wide text-muted uppercase">User guide</p>
        <h1 className={PAGE_TITLE}>Help</h1>
      </div>

      <div className="space-y-4 md:space-y-6">
        <Card>
          <label htmlFor={finderId} className="text-lg font-semibold">
            Where is…?
          </label>
          <p className="mt-1 text-muted">Type what you want to do, in your own words.</p>
          <div className="relative mt-3">
            <Search aria-hidden className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted" />
            <input
              id={finderId}
              type="search"
              value={text}
              onChange={(event) => search(event.target.value)}
              placeholder="Try “mark a bill paid” or “backup”"
              autoComplete="off"
              aria-describedby={resultsId}
              maxLength={100}
              className={`${INPUT} pl-10 text-lg`}
            />
          </div>
          <div id={resultsId} aria-live="polite">
            {text.trim() === "" ? (
              <p className="mt-3 flex flex-wrap items-center gap-2 text-sm text-muted">
                Ideas:
                {IDEAS.map((idea) => (
                  <button
                    key={idea}
                    type="button"
                    onClick={() => search(idea)}
                    className="min-h-11 cursor-pointer rounded-full border border-line px-3 font-medium text-ink hover:bg-bg/60"
                  >
                    {idea}
                  </button>
                ))}
              </p>
            ) : (
              <FinderResults text={text} />
            )}
          </div>
        </Card>

        <Card title="Where to start">
          <p className="mb-4 text-muted">New to Budget? These steps, in this order, get you from nothing to a budget you can trust.</p>
          <ol className="space-y-5">
            {STEPS.map((step, index) => (
              <li key={step.title} className="flex gap-3 sm:gap-4">
                <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-full bg-accent font-bold text-on-accent">
                  {index + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="font-semibold">{step.title}</p>
                  <p className="mt-1 text-muted">{step.body}</p>
                  <Link
                    to={step.to}
                    className="mt-2 inline-flex min-h-11 cursor-pointer items-center gap-1 rounded-lg font-semibold text-accent-ink hover:underline"
                  >
                    {step.button}
                    <ChevronRight aria-hidden className="size-4" />
                  </Link>
                </div>
              </li>
            ))}
          </ol>
        </Card>

        <div className="grid grid-cols-1 gap-4 md:gap-6 lg:grid-cols-2">
          <Card title="Find your way around">
            <ul className="divide-y divide-line">
              {TOPICS.map((topic) => {
                const Icon = topicIcon(topic);
                return (
                  <li key={topic.id} className="flex">
                    <Link to={`/help/${topic.id}`} className={ROW_LINK}>
                      <Icon aria-hidden className="size-5 shrink-0 text-muted" />
                      <span className="min-w-0 flex-1">
                        <span className="block font-semibold">{topic.title}</span>
                        <span className="block text-sm text-muted">{topic.summary}</span>
                      </span>
                      <ChevronRight aria-hidden className="size-4 shrink-0 text-muted" />
                    </Link>
                  </li>
                );
              })}
            </ul>
          </Card>

          <div className="space-y-4 md:space-y-6">
            <Card title="Common questions">
              <TaskList tasks={TASKS.slice(0, COMMON)} />
              <p className="mt-2 text-sm text-muted">More in each chapter, or type your question in Where is…? above.</p>
            </Card>

            <Card title="Keyboard shortcuts">
              <dl className="space-y-2">
                {SHORTCUTS.map((shortcut) => (
                  <div key={shortcut.keys} className="flex items-baseline gap-3">
                    <dt className="w-20 shrink-0">
                      <kbd className="rounded border border-line bg-bg px-1.5 py-0.5 font-mono text-sm">{shortcut.keys}</kbd>
                    </dt>
                    <dd>{shortcut.does}</dd>
                  </div>
                ))}
              </dl>
            </Card>
          </div>
        </div>

        <Card title="Words Budget uses">
          <p className="mb-4 text-muted">The same explanations are behind each ⓘ in the app.</p>
          <TermList terms={Object.keys(TERMS) as TermId[]} />
        </Card>

        <p className="text-sm text-muted">{PRIVACY_NOTE}. Budget never sends anything anywhere, this guide included.</p>
      </div>
    </>
  );
}

/** What the finder found: questions first (each with its answer and a button), then the guide, then words. */
function FinderResults({ text }: { text: string }) {
  const { tasks, sections, terms } = searchHelp(text);
  if (tasks.length === 0 && sections.length === 0 && terms.length === 0) {
    return (
      <p className="mt-3 text-muted">
        Nothing in the guide matches “{text.trim()}” yet. Try a shorter or different word, like “bill”, “balance” or “card”, or look through the chapters below.
      </p>
    );
  }
  return (
    <div className="mt-4 space-y-5">
      {tasks.length > 0 && (
        <ul aria-label="Answers" className="space-y-4">
          {tasks.map((task) => (
            <li key={task.question}>
              <TaskAnswer task={task} />
            </li>
          ))}
        </ul>
      )}
      {sections.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold tracking-wide text-muted uppercase">In the guide</h2>
          <ul className="mt-1 divide-y divide-line">
            {sections.map(({ topic, heading }) => (
              <li key={`${topic.id}-${heading}`} className="flex">
                <Link to={`/help/${topic.id}#${sectionAnchor(heading)}`} className={ROW_LINK}>
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium">{heading}</span>
                    <span className="block text-sm text-muted">{topic.title}</span>
                  </span>
                  <ChevronRight aria-hidden className="size-4 shrink-0 text-muted" />
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
      {terms.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold tracking-wide text-muted uppercase">Words</h2>
          <dl className="mt-2 space-y-2">
            {terms.map((term) => (
              <div key={term.id}>
                <dt className="font-semibold">{term.term}</dt>
                <dd className="text-muted">{term.about}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </div>
  );
}
