import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { MoneyField } from "../../src/components/MoneyField";

function Field({ start = "" }: { start?: string }) {
  const [value, setValue] = useState(start);
  return (
    <form>
      <MoneyField label="In this bucket" value={value} onChange={setValue} />
      <button type="submit">Save</button>
    </form>
  );
}

const field = () => screen.getByRole("textbox", { name: "In this bucket" });
const openCalculator = async () => {
  await userEvent.click(screen.getByRole("button", { name: "Calculator for In this bucket" }));
  return within(screen.getByRole("group", { name: "Calculator for In this bucket" }));
};

describe("money field", () => {
  it("works out a sum typed straight in when the field is left", async () => {
    render(<Field />);

    await userEvent.type(field(), "4005+3751");
    await userEvent.tab();

    expect(field()).toHaveValue("7756.00");
  });

  it("leaves a plain amount, and anything it cannot read, exactly as typed", async () => {
    render(<Field />);

    await userEvent.type(field(), "1,250");
    await userEvent.tab();
    expect(field()).toHaveValue("1,250");

    await userEvent.clear(field());
    await userEvent.type(field(), "40+");
    await userEvent.tab();
    expect(field()).toHaveValue("40+");
  });

  it("chains sums in the calculator, lists each one, and puts the answer in the field", async () => {
    render(<Field start="300.00" />);

    const calculator = await openCalculator();
    // It starts from what the field holds.
    expect(calculator.getByRole("button", { name: "Use $300.00" })).toBeInTheDocument();
    await userEvent.click(calculator.getByRole("button", { name: "divided by" }));
    await userEvent.click(calculator.getByRole("button", { name: "1" }));
    await userEvent.click(calculator.getByRole("button", { name: "2" }));
    // Pressing the next sign works out the one before it.
    await userEvent.click(calculator.getByRole("button", { name: "times" }));
    await userEvent.click(calculator.getByRole("button", { name: "2" }));
    await userEvent.click(calculator.getByRole("button", { name: "equals" }));

    const steps = within(calculator.getByRole("list", { name: "Worked out so far" })).getAllByRole("listitem");
    expect(steps.map((step) => step.textContent)).toEqual(["$300.00 ÷ 12 = $25.00", "$25.00 × 2 = $50.00"]);
    // Nothing has touched the field yet.
    expect(field()).toHaveValue("300.00");

    await userEvent.click(calculator.getByRole("button", { name: "Use $50.00" }));
    expect(field()).toHaveValue("50.00");
    expect(screen.queryByRole("group", { name: "Calculator for In this bucket" })).not.toBeInTheDocument();
    // The keyboard is back where it came from.
    expect(screen.getByRole("button", { name: "Calculator for In this bucket" })).toHaveFocus();
  });

  it("works from the keyboard, and Use finishes a sum that is still waiting", async () => {
    render(<Field start="4005" />);

    const calculator = await openCalculator();
    await userEvent.keyboard("+3751");

    await userEvent.click(calculator.getByRole("button", { name: "Use $7,756.00" }));
    expect(field()).toHaveValue("7756.00");
  });

  it("leaves the field alone on Cancel and on Escape", async () => {
    render(<Field start="300.00" />);

    let calculator = await openCalculator();
    await userEvent.keyboard("*4=");
    expect(calculator.getByRole("button", { name: "Use $1,200.00" })).toBeInTheDocument();
    await userEvent.click(calculator.getByRole("button", { name: "Cancel" }));
    expect(field()).toHaveValue("300.00");
    expect(screen.queryByRole("group", { name: "Calculator for In this bucket" })).not.toBeInTheDocument();

    calculator = await openCalculator();
    await userEvent.keyboard("-100{Escape}");
    expect(field()).toHaveValue("300.00");
    expect(screen.queryByRole("group", { name: "Calculator for In this bucket" })).not.toBeInTheDocument();
  });

  it("says so kindly instead of dividing by zero", async () => {
    render(<Field start="300.00" />);

    const calculator = await openCalculator();
    await userEvent.keyboard("/0=");

    expect(calculator.getByRole("alert")).toHaveTextContent("Nothing can be divided by zero. Please enter another number.");
    // The amount it had is still there to carry on from.
    await userEvent.keyboard("{Backspace}3=");
    expect(calculator.getByRole("button", { name: "Use $100.00" })).toBeInTheDocument();
  });

  it("starts again from a number typed after an answer, and Clear keeps what was worked out", async () => {
    render(<Field />);

    const calculator = await openCalculator();
    await userEvent.keyboard("19.99*3=");
    expect(calculator.getByRole("button", { name: "Use $59.97" })).toBeInTheDocument();
    await userEvent.click(calculator.getByRole("button", { name: "Clear" }));
    expect(calculator.getByRole("button", { name: "Use $0.00" })).toBeInTheDocument();
    expect(calculator.getByRole("list", { name: "Worked out so far" })).toHaveTextContent("$19.99 × 3 = $59.97");
  });
});
