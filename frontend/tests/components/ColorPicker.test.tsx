import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ColorPicker, PRESET_COLORS } from "../../src/components/ColorPicker";
import { contrastRatio, readableTextOn } from "../../src/lib/color";

function setup(recent: string[] = []) {
  const onChange = vi.fn();
  render(<ColorPicker label="Color" value="#0f766e" onChange={onChange} recent={recent} />);
  return { onChange, toggle: screen.getByRole("button", { name: /Color/ }) };
}

describe("ColorPicker", () => {
  it("names the current color and stays closed until asked", () => {
    const { toggle } = setup();

    expect(toggle).toHaveAccessibleName("Color Teal");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("group", { name: "Colors" })).not.toBeInTheDocument();
  });

  it("picks a preset swatch by name", async () => {
    const { onChange, toggle } = setup();

    await userEvent.click(toggle);
    const presets = screen.getByRole("group", { name: "Colors" });
    expect(within(presets).getByRole("button", { name: "Teal" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(within(presets).getByRole("button", { name: "Terracotta" }));

    expect(onChange).toHaveBeenCalledWith("#c2410c");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("shows recently used colors first, without repeating the presets", async () => {
    const { onChange, toggle } = setup(["#ABCDEF", "#0f766e", "#abcdef", "#123456"]);

    await userEvent.click(toggle);
    const groups = screen.getAllByRole("group").map((group) => group.getAttribute("aria-label"));
    const recent = screen.getByRole("group", { name: "Recently used" });

    expect(groups).toEqual(["Recently used", "Colors"]);
    expect(within(recent).getAllByRole("button").map((button) => button.getAttribute("aria-label")))
      .toEqual(["#abcdef", "#123456"]);
    await userEvent.click(within(recent).getByRole("button", { name: "#123456" }));
    expect(onChange).toHaveBeenCalledWith("#123456");
  });

  it("accepts any hex code as a custom color", async () => {
    const { onChange, toggle } = setup();

    await userEvent.click(toggle);
    await userEvent.type(screen.getByLabelText("Custom hex code"), "F4C95D");
    await userEvent.click(screen.getByRole("button", { name: "Use" }));

    expect(onChange).toHaveBeenCalledWith("#f4c95d");
  });

  it("explains a hex code that is not one, and will not use it", async () => {
    const { onChange, toggle } = setup();

    await userEvent.click(toggle);
    await userEvent.type(screen.getByLabelText("Custom hex code"), "teal{Enter}");

    expect(screen.getByRole("button", { name: "Use" })).toBeDisabled();
    expect(screen.getByText(/six letters or digits/)).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("every preset keeps its check mark readable", () => {
    for (const { hex } of PRESET_COLORS) {
      expect(contrastRatio(hex, readableTextOn(hex))).toBeGreaterThanOrEqual(4.5);
    }
  });
});
