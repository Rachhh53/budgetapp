import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Picker, type PickerOption, TextPicker } from "../../src/components/Picker";

const CATEGORIES: PickerOption[] = [
  { id: "1", label: "Groceries", color: "#15803d" },
  { id: "2", label: "Transportation", color: "#4338ca" },
  { id: "3", label: "Transportation › Tolls & parking", color: "#4338ca" },
];

function setup(props: { value?: string | null; clearable?: boolean; withCreate?: boolean } = {}) {
  const onChange = vi.fn();
  const onCreate = vi.fn();
  render(
    <Picker
      label="Category"
      noun="category"
      options={CATEGORIES}
      value={props.value ?? null}
      onChange={onChange}
      onCreate={props.withCreate === false ? undefined : onCreate}
      clearable={props.clearable}
    />,
  );
  return { onChange, onCreate, input: screen.getByRole("combobox", { name: "Category" }) };
}

function optionNames(): (string | null)[] {
  return screen.getAllByRole("option").map((option) => option.textContent);
}

describe("Picker", () => {
  it("lists everything when opened, and filters as you type", async () => {
    const { input } = setup();

    await userEvent.click(input);
    expect(input).toHaveAttribute("aria-expanded", "true");
    expect(screen.getAllByRole("option")).toHaveLength(3);

    await userEvent.type(input, "tra");

    expect(optionNames().slice(0, 2)).toEqual(["TransportationEnter", "Transportation › Tolls & parking"]);
    // The part that matches is set in bold.
    expect(within(screen.getAllByRole("option")[0]!).getByText("Tra").tagName).toBe("STRONG");
  });

  it("chooses the highlighted match with Enter", async () => {
    const { input, onChange } = setup();

    await userEvent.type(input, "tra{Enter}");

    expect(onChange).toHaveBeenCalledWith("2");
    expect(input).toHaveAttribute("aria-expanded", "false");
  });

  it("moves through the list with the arrow keys", async () => {
    const { input, onChange } = setup();

    await userEvent.type(input, "tra{ArrowDown}");
    expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{Enter}");

    expect(onChange).toHaveBeenCalledWith("3");
  });

  it("offers to add what was typed as new, as the last option", async () => {
    const { input, onCreate, onChange } = setup();

    await userEvent.type(input, "tra");
    const add = screen.getByRole("option", { name: 'Add "tra" as a new category' });
    expect(screen.getAllByRole("option").at(-1)).toBe(add);
    await userEvent.click(add);

    expect(onCreate).toHaveBeenCalledWith("tra");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("adds a new entry with Enter when nothing matches", async () => {
    const { input, onCreate } = setup();

    await userEvent.type(input, "Pet care{Enter}");

    expect(onCreate).toHaveBeenCalledWith("Pet care");
  });

  it("does not offer to add something that already exists, however it is capitalized", async () => {
    const { input } = setup();

    await userEvent.type(input, "groceries");

    expect(optionNames()).toEqual(["GroceriesEnter"]);
  });

  it("only picks from the list when adding is not allowed", async () => {
    const { input, onChange } = setup({ withCreate: false });

    await userEvent.type(input, "zzz{Enter}");

    expect(screen.queryByRole("option")).not.toBeInTheDocument();
    expect(screen.getByText("Nothing matches that.")).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("shows the current choice, and Escape leaves it alone", async () => {
    const { input, onChange } = setup({ value: "1" });
    expect(input).toHaveValue("Groceries");

    await userEvent.type(input, "tra{Escape}");

    expect(input).toHaveValue("Groceries");
    expect(input).toHaveAttribute("aria-expanded", "false");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("tabbing through without typing changes nothing", async () => {
    const { onChange } = setup({ value: "1", clearable: true });

    await userEvent.tab();
    await userEvent.tab();

    expect(onChange).not.toHaveBeenCalled();
  });

  it("empties a clearable choice when the text is deleted", async () => {
    const { input, onChange } = setup({ value: "1", clearable: true });

    await userEvent.type(input, "x{Backspace}");
    await userEvent.tab();

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("empties a clearable choice with its clear button", async () => {
    const { onChange } = setup({ value: "1", clearable: true });

    await userEvent.click(screen.getByRole("button", { name: "Clear the category" }));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("offers no clear button when a choice is required", () => {
    setup({ value: "1" });
    expect(screen.queryByRole("button", { name: "Clear the category" })).not.toBeInTheDocument();
  });
});

describe("TextPicker", () => {
  function Bank() {
    const [bank, setBank] = useState("");
    return (
      <>
        <TextPicker label="Bank" noun="bank" suggestions={["Coastal Credit Union"]} value={bank} onChange={setBank} />
        <output>{bank}</output>
      </>
    );
  }

  it("picks an existing entry", async () => {
    render(<Bank />);

    await userEvent.type(screen.getByRole("combobox", { name: "Bank" }), "coast{Enter}");

    expect(screen.getByRole("status")).toHaveTextContent("Coastal Credit Union");
  });

  it("keeps a newly added entry as the value", async () => {
    render(<Bank />);
    const input = screen.getByRole("combobox", { name: "Bank" });

    await userEvent.type(input, "Sunrise Bank");
    await userEvent.click(screen.getByRole("option", { name: 'Add "Sunrise Bank" as a new bank' }));

    expect(screen.getByRole("status")).toHaveTextContent("Sunrise Bank");
    expect(input).toHaveValue("Sunrise Bank");
  });
});
