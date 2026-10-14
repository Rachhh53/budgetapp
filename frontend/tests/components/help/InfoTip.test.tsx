import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { InfoTip } from "../../../src/components/help/InfoTip";
import { TERMS } from "../../../src/help/terms";

describe("InfoTip", () => {
  it("says what a word means on a click, and closes on Escape or a click elsewhere", async () => {
    render(
      <p>
        Projected ending <InfoTip term="projectedEnding" />
        <button type="button">Elsewhere</button>
      </p>,
    );
    const tip = screen.getByRole("button", { name: 'What "Projected ending" means' });
    expect(tip).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(tip);
    expect(tip).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("note", { name: "Projected ending" })).toHaveTextContent(TERMS.projectedEnding.about);

    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(tip).toHaveFocus();

    await userEvent.click(tip);
    await userEvent.click(screen.getByRole("button", { name: "Elsewhere" }));
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("opens from the keyboard", async () => {
    render(<InfoTip term="bucket" />);
    await userEvent.tab();
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("note", { name: "Bucket" })).toBeInTheDocument();
  });
});
