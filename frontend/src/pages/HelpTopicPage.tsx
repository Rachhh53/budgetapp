import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect } from "react";
import { Link, useLocation, useParams } from "react-router";
import { Card } from "../components/Card";
import { TaskList, TermList, topicIcon } from "../components/help/HelpParts";
import { BUTTON_PRIMARY, DETAIL_HEADER, PAGE_TITLE } from "../components/ui";
import { TASKS, TOPICS, topicById } from "../help/guide";
import { sectionAnchor } from "../help/search";
import { NAV_ITEMS } from "../nav";

/**
 * One chapter of the guide, `/help/{topic}`: what the screen is for, a card per section, the
 * questions about it and the words it uses, with a button to the screen itself. Opened from Help
 * and from the ? beside each screen's title; `#section` scrolls to a section the finder matched.
 */
export function HelpTopicPage() {
  const { topic: id } = useParams();
  const { hash } = useLocation();
  const topic = topicById(id);

  useEffect(() => {
    if (hash !== "") {
      document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView({ block: "start" });
    }
  }, [hash, topic]);

  if (!topic) {
    return (
      <>
        <h1 className={`${PAGE_TITLE} mb-4`}>Help</h1>
        <Card>
          <p className="font-medium">We couldn't find that part of the guide.</p>
          <p className="mt-1 text-muted">
            It may have moved.{" "}
            <Link to="/help" className="font-semibold text-accent-ink underline">
              Go back to Help
            </Link>{" "}
            to look through every chapter.
          </p>
        </Card>
      </>
    );
  }

  const Icon = topicIcon(topic);
  const tasks = TASKS.filter((task) => task.topic === topic.id);
  const index = TOPICS.indexOf(topic);
  const previous = TOPICS[index - 1];
  const next = TOPICS[index + 1];

  return (
    <>
      <div className={DETAIL_HEADER}>
        <div className="min-w-0">
          <p className="text-sm text-muted">
            <Link to="/help" className="text-accent-ink underline">
              Help
            </Link>{" "}
            / Guide
          </p>
          <h1 className={`${PAGE_TITLE} flex items-center gap-3`}>
            <Icon aria-hidden className="size-7 shrink-0 text-muted" />
            <span className="min-w-0">{topic.title}</span>
          </h1>
          <p className="text-muted">{topic.summary}</p>
        </div>
        <Link to={topic.path} className={`${BUTTON_PRIMARY} inline-flex shrink-0 cursor-pointer items-center justify-center gap-1 whitespace-nowrap`}>
          Go to {NAV_ITEMS.find((item) => item.path === topic.path)?.label ?? topic.title}
          <ChevronRight aria-hidden className="size-4" />
        </Link>
      </div>

      <div className="grid grid-cols-1 gap-4 md:gap-6 lg:grid-cols-2">
        {topic.sections.map((section) => (
          // A margin above where a section is scrolled to, so the frozen header does not cover its title.
          <div key={section.heading} id={sectionAnchor(section.heading)} className="scroll-mt-40">
            <Card title={section.heading}>
              <div className="space-y-2">
                {section.body.map((paragraph) => (
                  <p key={paragraph}>{paragraph}</p>
                ))}
              </div>
            </Card>
          </div>
        ))}
        {tasks.length > 0 && (
          <Card title="Questions about this">
            <TaskList tasks={tasks} />
          </Card>
        )}
        {topic.terms.length > 0 && (
          <div className="lg:col-span-2">
            <Card title="Words on this screen">
              <TermList terms={topic.terms} />
            </Card>
          </div>
        )}
      </div>

      <nav aria-label="Other chapters" className="mt-6 flex flex-col gap-2 sm:flex-row sm:justify-between">
        {previous ? (
          <Link to={`/help/${previous.id}`} className="inline-flex min-h-11 cursor-pointer items-center gap-1 font-semibold text-accent-ink hover:underline">
            <ChevronLeft aria-hidden className="size-4" />
            {previous.title}
          </Link>
        ) : (
          <Link to="/help" className="inline-flex min-h-11 cursor-pointer items-center gap-1 font-semibold text-accent-ink hover:underline">
            <ChevronLeft aria-hidden className="size-4" />
            Help
          </Link>
        )}
        {next && (
          <Link to={`/help/${next.id}`} className="inline-flex min-h-11 cursor-pointer items-center gap-1 font-semibold text-accent-ink hover:underline sm:justify-end">
            {next.title}
            <ChevronRight aria-hidden className="size-4" />
          </Link>
        )}
      </nav>
    </>
  );
}
