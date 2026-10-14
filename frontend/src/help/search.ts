import { type Task, TASKS, type Topic, TOPICS } from "./guide";
import { type TermId, TERMS } from "./terms";

/** A section of the guide that matched, to open its chapter. */
export interface SectionMatch {
  topic: Topic;
  heading: string;
}

export interface TermMatch {
  id: TermId;
  term: string;
  about: string;
}

export interface HelpResults {
  tasks: Task[];
  sections: SectionMatch[];
  terms: TermMatch[];
}

const MOST_TASKS = 8;
const MOST_SECTIONS = 5;
const MOST_TERMS = 4;

/** Lower case, accents and punctuation gone, so "Où" and "ou" and "back-up" and "back up" meet. */
function words(text: string): string[] {
  return text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .split(/[^\p{Letter}\p{Number}%]+/u)
    .filter((word) => word !== "");
}

/** Short words that say nothing about what someone is after ("how do I add a bill" is "add bill"). */
const FILLER = new Set(["how", "do", "i", "a", "an", "the", "to", "my", "is", "can", "where", "what", "of", "for", "in", "on", "it", "me"]);

/**
 * How well `query` fits each piece of text, best first: every word typed has to start a word in
 * the text (so "rec" finds "receipt" but not "car"); a fit in the question or title counts double.
 */
function score(query: readonly string[], strong: string, weak: string): number {
  const strongWords = words(strong);
  const weakWords = words(weak);
  let total = 0;
  for (const word of query) {
    if (strongWords.some((candidate) => candidate.startsWith(word))) {
      total += 2;
    } else if (weakWords.some((candidate) => candidate.startsWith(word))) {
      total += 1;
    } else {
      return 0;
    }
  }
  return total;
}

function best<T>(items: readonly T[], rate: (item: T) => number, most: number): T[] {
  return items
    .map((item, index) => ({ item, index, points: rate(item) }))
    .filter((rated) => rated.points > 0)
    // Equal fits keep the guide's own order, which puts the commonest questions first.
    .sort((a, b) => b.points - a.points || a.index - b.index)
    .slice(0, most)
    .map((rated) => rated.item);
}

/** The "Where is…?" finder: questions, sections of the guide and words that fit what was typed. */
export function searchHelp(text: string): HelpResults {
  const all = words(text);
  const query = all.filter((word) => !FILLER.has(word));
  // "How do I" alone, or nothing at all, is not yet a question.
  if (query.length === 0) {
    return { tasks: [], sections: [], terms: [] };
  }
  const sections = TOPICS.flatMap((topic) => topic.sections.map((section) => ({ topic, section })));
  return {
    tasks: best(TASKS, (task) => score(query, `${task.question} ${task.keywords}`, task.answer), MOST_TASKS),
    sections: best(sections, ({ topic, section }) => score(query, `${topic.title} ${section.heading}`, section.body.join(" ")), MOST_SECTIONS).map(
      ({ topic, section }) => ({ topic, heading: section.heading }),
    ),
    terms: best(
      (Object.keys(TERMS) as TermId[]).map((id) => ({ id, ...TERMS[id] })),
      (term) => score(query, term.term, term.about),
      MOST_TERMS,
    ),
  };
}

/** The address of a section in its chapter, so a match can open the chapter scrolled to it. */
export function sectionAnchor(heading: string): string {
  return words(heading).join("-");
}
