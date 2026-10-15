import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { STEPS } from "../../src/help/guide";
import { DEFAULT_SETTINGS, fakeBackend, renderApp } from "../helpers";

function withSettings() {
  return fakeBackend({ "GET /api/settings": () => ({ status: 200, data: DEFAULT_SETTINGS }) });
}

describe("Help", () => {
  it("lists where to start, each step with a button to its screen", async () => {
    withSettings();
    renderApp("/help");

    const start = await screen.findByRole("region", { name: "Where to start" });
    const steps = within(start).getAllByRole("listitem");
    expect(steps).toHaveLength(STEPS.length);
    expect(within(steps[1]!).getByRole("link", { name: /Go to Accounts/ })).toHaveAttribute("href", "/accounts");
    expect(within(steps[2]!).getByRole("link", { name: /Go to Income/ })).toHaveAttribute("href", "/income");
  });

  it("answers what is typed in Where is…?, with a button to where it is done", async () => {
    withSettings();
    renderApp("/help");

    await userEvent.type(await screen.findByRole("searchbox", { name: "Where is…?" }), "receipt");

    expect(screen.getByText("How do I keep a receipt?")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /Go to Expenses & receipts/ })[0]).toHaveAttribute("href", "/expenses");
  });

  it("says kindly when nothing matches", async () => {
    withSettings();
    renderApp("/help?q=zebra");

    expect(await screen.findByText(/Nothing in the guide matches “zebra” yet/)).toBeInTheDocument();
  });

  it("fills the finder from an idea", async () => {
    withSettings();
    renderApp("/help");

    await userEvent.click(await screen.findByRole("button", { name: "backup" }));

    expect(screen.getByRole("searchbox", { name: "Where is…?" })).toHaveValue("backup");
    expect(screen.getByText("How do I back up my data?")).toBeInTheDocument();
  });

  it("opens a chapter, with its questions and a button to the screen", async () => {
    withSettings();
    renderApp("/help");

    await userEvent.click(await screen.findByRole("link", { name: /Settings, security and backups/ }));

    expect(await screen.findByRole("heading", { level: 1, name: "Settings, security and backups" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Backups" })).toBeInTheDocument();
    // The header's button comes first; each question has its own further down.
    expect(screen.getAllByRole("link", { name: /^Go to Settings$/ })[0]).toHaveAttribute("href", "/settings");
    expect(screen.getByText("How do I back up my data?")).toBeInTheDocument();
  });

  it("says so kindly when a chapter does not exist", async () => {
    withSettings();
    renderApp("/help/no-such-chapter");

    expect(await screen.findByText("We couldn't find that part of the guide.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go back to Help" })).toHaveAttribute("href", "/help");
  });

  it("has a ? beside a screen's title that opens that screen's chapter", async () => {
    withSettings();
    renderApp("/settings");

    expect(await screen.findByRole("link", { name: "Help with Settings, security and backups" })).toHaveAttribute("href", "/help/settings");
  });
});
