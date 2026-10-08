import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Category, Tag } from "../../src/api/categories";
import { bodiesSentTo, calls, fakeBackend, renderApp } from "../helpers";

const HOUSING: Category = { id: 1, name: "Housing", color: "#0f766e", parentId: null, itemCount: 2, perYearCents: 1980000 };
const RENT: Category = { id: 2, name: "Rent", color: "#0f766e", parentId: 1, itemCount: 1, perYearCents: 1860000 };
const FOOD: Category = { id: 3, name: "Food", color: "#15803d", parentId: null, itemCount: 0, perYearCents: 0 };

const MEDICAL: Tag = { id: 1, name: "Medical – out of pocket", color: "#be185d", itemCount: 12, perYearCents: 218460 };
const PALE: Tag = { id: 2, name: "Vacation 2027", color: "#fde68a", itemCount: 0, perYearCents: 0 };

function withData(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/categories": () => ({ status: 200, data: [HOUSING, RENT, FOOD] }),
    "GET /api/tags": () => ({ status: 200, data: [MEDICAL, PALE] }),
    ...overrides,
  });
}

describe("categories & tags screen", () => {
  it("invites the first category and the first tag", async () => {
    fakeBackend();
    renderApp("/categories");

    expect(await screen.findByText(/No categories yet\./)).toBeInTheDocument();
    expect(await screen.findByText(/No tags yet\./)).toBeInTheDocument();
  });

  it("says so kindly when either list can't be loaded", async () => {
    const error = "Something went wrong on our side. Your data is safe — please try again.";
    fakeBackend({
      "GET /api/categories": () => ({ status: 500, error }),
      "GET /api/tags": () => ({ status: 500, error }),
    });
    renderApp("/categories");

    expect(await screen.findAllByRole("alert")).toHaveLength(2);
  });

  it("lists categories with their sub-categories, item counts and yearly totals", async () => {
    withData();
    renderApp("/categories");

    const categories = await screen.findByRole("region", { name: "Categories" });
    const rows = await within(categories).findAllByRole("listitem");
    expect(rows.map((row) => row.textContent)).toEqual([
      "Housing2 items$19,800.00 a year",
      "Rent1 item$18,600.00 a year",
      "Food0 items$0.00 a year",
    ]);
    // Everything about a row is in its name, for anyone not seeing the layout.
    expect(within(categories).getByRole("button", { name: "Edit Rent, 1 item, $18,600.00 a year" })).toBeInTheDocument();
  });

  it("draws each tag in the color chosen for it, with text that stays readable", async () => {
    withData();
    renderApp("/categories");

    const tags = await screen.findByRole("region", { name: "Tags" });
    const dark = await within(tags).findByText("Medical – out of pocket");
    const pale = within(tags).getByText("Vacation 2027");
    expect(dark).toHaveStyle({ backgroundColor: "#be185d", color: "#ffffff" });
    // The pale yellow is kept exactly as chosen; the text on it turns dark instead.
    expect(pale).toHaveStyle({ backgroundColor: "#fde68a", color: "#000000" });
    expect(tags).toHaveTextContent("12 items");
    expect(tags).toHaveTextContent("$2,184.60");
  });

  it("adds a sub-category with a color from the picker", async () => {
    const fetchMock = withData({ "POST /api/categories": () => ({ status: 201, data: { ...RENT, id: 9, name: "Insurance" } }) });
    renderApp("/categories");

    await userEvent.click(await screen.findByRole("button", { name: "+ Category" }));
    const dialog = screen.getByRole("dialog", { name: "Add a category" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Insurance");
    // Only main categories can hold a sub-category.
    const inside = within(dialog).getByRole("combobox", { name: "Inside" });
    expect(within(inside).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Nothing — it's a main category",
      "Housing",
      "Food",
    ]);
    await userEvent.selectOptions(inside, "Housing");
    await userEvent.click(within(dialog).getByRole("button", { name: /^Color/ }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Plum" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Add category" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/categories")).toEqual([{ name: "Insurance", color: "#7e22ce", parentId: 1 }]);
  });

  it("starts a new category on a color that is not in use yet", async () => {
    const fetchMock = withData({ "POST /api/categories": () => ({ status: 201, data: FOOD }) });
    renderApp("/categories");

    await userEvent.click(await screen.findByRole("button", { name: "+ Category" }));
    const dialog = screen.getByRole("dialog", { name: "Add a category" });
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Pets");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add category" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // Teal and Forest are taken, so the first free preset is Raspberry.
    expect(bodiesSentTo(fetchMock, "POST /api/categories")).toEqual([{ name: "Pets", color: "#be185d", parentId: null }]);
  });

  it("renames a category and shows the backend's message when the name is taken", async () => {
    let attempts = 0;
    const fetchMock = withData({
      "PUT /api/categories/3": () =>
        ++attempts === 1
          ? { status: 409, error: "You already have a category called Housing." }
          : { status: 200, data: { ...FOOD, name: "Groceries" } },
    });
    renderApp("/categories");

    await userEvent.click(await screen.findByRole("button", { name: /^Edit Food/ }));
    const dialog = screen.getByRole("dialog", { name: "Edit Food" });
    const name = within(dialog).getByRole("textbox", { name: "Name" });
    await userEvent.clear(name);
    await userEvent.type(name, "Housing");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("You already have a category called Housing.");

    await userEvent.clear(name);
    await userEvent.type(name, "Groceries");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/categories/3")[1]).toEqual({ name: "Groceries", color: "#15803d", parentId: null });
  });

  it("deletes a category without asking twice and explains when it is still in use", async () => {
    const fetchMock = withData({
      "DELETE /api/categories/2": () => ({
        status: 409,
        error: "This category is still used by an item. Choose another category for those first.",
      }),
    });
    renderApp("/categories");

    await userEvent.click(await screen.findByRole("button", { name: /^Edit Rent/ }));
    const dialog = screen.getByRole("dialog", { name: "Edit Rent" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete category" }));
    expect(calls(fetchMock)).toContain("DELETE /api/categories/2");

    expect(await within(dialog).findByText(/still used by an item/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Edit Rent" })).toBeInTheDocument();
  });

  it("adds, changes and deletes a tag", async () => {
    const fetchMock = withData({
      "POST /api/tags": () => ({ status: 201, data: { ...PALE, id: 5, name: "Reimbursable" } }),
      "PUT /api/tags/2": () => ({ status: 200, data: { ...PALE, name: "Vacation 2028" } }),
      "DELETE /api/tags/2": () => ({ status: 200, data: { deleted: true } }),
    });
    renderApp("/categories");

    await userEvent.click(await screen.findByRole("button", { name: "+ Tag" }));
    let dialog = screen.getByRole("dialog", { name: "Add a tag" });
    // A tag does not sit inside anything.
    expect(within(dialog).queryByRole("combobox", { name: "Inside" })).not.toBeInTheDocument();
    await userEvent.type(within(dialog).getByRole("textbox", { name: "Name" }), "Reimbursable");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add tag" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "POST /api/tags")).toMatchObject([{ name: "Reimbursable" }]);

    await userEvent.click(screen.getByRole("button", { name: /^Edit Vacation 2027/ }));
    dialog = screen.getByRole("dialog", { name: "Edit Vacation 2027" });
    const name = within(dialog).getByRole("textbox", { name: "Name" });
    await userEvent.clear(name);
    await userEvent.type(name, "Vacation 2028");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/tags/2")).toEqual([{ name: "Vacation 2028", color: "#fde68a" }]);

    await userEvent.click(screen.getByRole("button", { name: /^Edit Vacation 2027/ }));
    dialog = screen.getByRole("dialog", { name: "Edit Vacation 2027" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete tag" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls(fetchMock)).toContain("DELETE /api/tags/2");
  });
});
