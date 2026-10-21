import { describe, expect, it } from "vitest";
import { STEPS, TASKS, TOPICS, topicForPath } from "../../src/help/guide";
import { searchHelp } from "../../src/help/search";

/** Every screen a guide button may lead to: the sidebar's and the Dashboard's tabs. */
const SCREENS = ["/", "/dashboard", "/dashboard/reports", "/dashboard/annual", "/recurring", "/accounts", "/income", "/expenses", "/plan", "/planner", "/retirement", "/categories", "/settings"];

describe("the guide", () => {
  it("only sends people to screens that exist, and files every question under a chapter", () => {
    for (const place of [...STEPS, ...TASKS, ...TOPICS.map((topic) => ({ to: topic.path }))]) {
      expect(SCREENS).toContain(place.to);
    }
    const chapters = TOPICS.map((topic) => topic.id);
    for (const task of TASKS) {
      expect(chapters).toContain(task.topic);
    }
    expect(new Set(TASKS.map((task) => task.question)).size).toBe(TASKS.length);
  });

  it("opens the chapter for the screen you are on, detail pages included", () => {
    expect(topicForPath("/")?.id).toBe("pay-periods");
    expect(topicForPath("/periods/2026-10-01")?.id).toBe("pay-periods");
    expect(topicForPath("/dashboard/reports")?.id).toBe("dashboard");
    expect(topicForPath("/plan/3/bills")?.id).toBe("plan");
    // "/planner" starts with "/plan": the longer path wins.
    expect(topicForPath("/planner")?.id).toBe("planner");
    expect(topicForPath("/assets/2")?.id).toBe("accounts");
    expect(topicForPath("/income/1/stubs/4")?.id).toBe("income");
    expect(topicForPath("/help")).toBeUndefined();
  });
});

describe("Where is…?", () => {
  it("answers a question in everyday words, ignoring how it is asked", () => {
    expect(searchHelp("how do I mark a bill paid").tasks[0]?.question).toBe("How do I mark a bill as paid?");
    expect(searchHelp("backup").tasks[0]?.question).toBe("How do I back up my data?");
    expect(searchHelp("Forgot PIN").tasks[0]?.question).toBe("I forgot my PIN.");
  });

  it("matches the start of a word, so a few letters are enough", () => {
    expect(searchHelp("recei").tasks.map((task) => task.question)).toContain("How do I keep a receipt?");
    // "car" starts "card", but nothing in the receipt question.
    expect(searchHelp("car").tasks.map((task) => task.question)).not.toContain("How do I keep a receipt?");
  });

  it("finds sections of the guide and words as well as questions", () => {
    expect(searchHelp("available").terms.map((term) => term.term)).toContain("Available after buckets");
    expect(searchHelp("payoff order").sections.map((section) => section.heading)).toContain("Payoff order");
  });

  it("finds nothing for words it does not know, or for filler alone", () => {
    expect(searchHelp("zebra")).toEqual({ tasks: [], sections: [], terms: [] });
    expect(searchHelp("how do I")).toEqual({ tasks: [], sections: [], terms: [] });
  });
});
