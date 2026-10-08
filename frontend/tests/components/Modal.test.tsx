import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Modal } from "../../src/components/Modal";

function setup() {
  const onClose = vi.fn();
  render(
    <Modal title="Add a bill" onClose={onClose}>
      <label>
        Name
        <input />
      </label>
      <ul role="listbox" aria-label="Accounts">
        <li role="option" aria-selected={false}>
          Everyday Checking
        </li>
      </ul>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
    </Modal>,
  );
  return { onClose, dialog: screen.getByRole("dialog", { name: "Add a bill" }), outside: screen.getByTestId("modal-backdrop") };
}

describe("dialog", () => {
  it("closes on a click outside it when nothing was changed", async () => {
    const { onClose, outside } = setup();

    await userEvent.click(outside);

    expect(onClose).toHaveBeenCalledOnce();
  });

  it("stays open on a click inside it", async () => {
    const { onClose, dialog } = setup();

    await userEvent.click(within(dialog).getByRole("heading", { name: "Add a bill" }));

    expect(onClose).not.toHaveBeenCalled();
  });

  it("asks before a click outside throws away what was typed", async () => {
    const { onClose, dialog, outside } = setup();
    await userEvent.type(within(dialog).getByLabelText("Name"), "Electric");

    await userEvent.click(outside);

    expect(onClose).not.toHaveBeenCalled();
    const warning = within(dialog).getByRole("alert");
    expect(warning).toHaveTextContent("You have changes that aren't saved.");
    expect(within(warning).getByRole("button", { name: "Keep editing" })).toHaveFocus();

    await userEvent.click(within(warning).getByRole("button", { name: "Keep editing" }));
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText("Name")).toHaveValue("Electric");
    expect(onClose).not.toHaveBeenCalled();

    await userEvent.click(outside);
    await userEvent.click(within(dialog).getByRole("button", { name: "Close without saving" }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("asks on the X and on Escape too, and counts a choice from a list as a change", async () => {
    const { onClose, dialog } = setup();
    await userEvent.click(within(dialog).getByRole("option", { name: "Everyday Checking" }));

    await userEvent.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(within(dialog).getByRole("alert")).toBeInTheDocument();
    // Escape answers with the safe choice.
    await userEvent.keyboard("{Escape}");
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(within(dialog).getByRole("alert")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("lets the form's own Cancel discard without asking", async () => {
    const { onClose, dialog } = setup();
    await userEvent.type(within(dialog).getByLabelText("Name"), "Electric");

    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalledOnce();
  });
});
