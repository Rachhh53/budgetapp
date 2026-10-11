import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, fakeBackend, renderApp, UNLOCKED } from "./helpers";

const LOCKED = { unlocked: false, pinSet: true, retryAfterSeconds: 0, offerPin: false };
const UNLOCKED_WITH_PIN = { ...UNLOCKED, pinSet: true };
const CODE = "K7QM-2XHP-9FTB-WRNC";

/** A backend with a PIN in front of it. `open` says whether this window has a session. */
function lockedBackend(overrides: Parameters<typeof fakeBackend>[0] = {}) {
  const state = { open: false };
  const fetchMock = fakeBackend({
    "GET /api/session": () => ({ status: 200, data: state.open ? UNLOCKED_WITH_PIN : LOCKED }),
    "GET /api/settings": () =>
      state.open ? { status: 200, data: DEFAULT_SETTINGS } : { status: 401, error: "Budget is locked." },
    "POST /api/session": (body) => {
      if ((body as { pin?: string } | undefined)?.pin !== "4829") {
        return { status: 401, error: "That PIN didn't match. Please try again." };
      }
      state.open = true;
      return { status: 201, data: UNLOCKED_WITH_PIN };
    },
    "DELETE /api/session": () => {
      state.open = false;
      return { status: 200, data: LOCKED };
    },
    ...overrides,
  });
  return { fetchMock, state };
}

describe("the lock screen", () => {
  it("stands in place of the whole app and asks for nothing of the budget", async () => {
    const { fetchMock } = lockedBackend();
    renderApp();

    expect(await screen.findByRole("heading", { level: 1, name: "Budget is locked" })).toBeInTheDocument();
    expect(screen.getByLabelText("PIN")).toHaveFocus();
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    expect(screen.getByText("Data stored only on this PC")).toBeInTheDocument();
    expect(calls(fetchMock)).toEqual(["GET /api/session"]);
  });

  it("opens the app with the right PIN", async () => {
    const { fetchMock } = lockedBackend();
    renderApp("/settings");

    await userEvent.type(await screen.findByLabelText("PIN"), "4829{Enter}");

    expect(await screen.findByRole("heading", { level: 1, name: "Settings" })).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/session")).toEqual([{ pin: "4829" }]);
  });

  it("keeps the PIN hidden and takes digits only", async () => {
    lockedBackend();
    renderApp();

    const pin = await screen.findByLabelText("PIN");
    await userEvent.type(pin, "4a8-2 9");

    expect(pin).toHaveAttribute("type", "password");
    expect(pin).toHaveValue("4829");
  });

  it("says kindly when the PIN didn't match, and clears the box for another try", async () => {
    lockedBackend();
    renderApp();

    await userEvent.type(await screen.findByLabelText("PIN"), "0000{Enter}");

    expect(await screen.findByRole("alert")).toHaveTextContent("That PIN didn't match. Please try again.");
    expect(screen.getByLabelText("PIN")).toHaveValue("");
    expect(screen.getByRole("heading", { level: 1, name: "Budget is locked" })).toBeInTheDocument();
  });

  it("says how long to wait after too many wrong tries, and holds the button until then", async () => {
    fakeBackend({ "GET /api/session": () => ({ status: 200, data: { ...LOCKED, retryAfterSeconds: 300 } }) });
    renderApp();

    expect(await screen.findByText(/Please wait 5 minutes, then try again./)).toHaveRole("status");
    await userEvent.type(screen.getByLabelText("PIN"), "4829");
    expect(screen.getByRole("button", { name: "Unlock" })).toBeDisabled();
  });

  it("locks when a request finds the session has run out", async () => {
    const { state } = lockedBackend({
      "PUT /api/settings/theme": () => ({ status: 401, error: "Budget is locked. Please unlock it to continue." }),
    });
    state.open = true;
    renderApp("/settings");
    await screen.findByLabelText("Theme");

    // The backend's own auto-lock: the next thing asked for is refused.
    state.open = false;
    await userEvent.selectOptions(screen.getByLabelText("Theme"), "Dark");

    expect(await screen.findByRole("heading", { level: 1, name: "Budget is locked" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Theme")).not.toBeInTheDocument();
  });
});

describe("a forgotten PIN", () => {
  it("is reset with the recovery code, which is replaced by a new one shown once", async () => {
    const { fetchMock, state } = lockedBackend({
      "POST /api/pin/recovery": () => {
        state.open = true;
        return { status: 201, data: { recoveryCode: "NEW2-CODE-9FTB-WRNC", session: UNLOCKED_WITH_PIN } };
      },
    });
    renderApp("/settings");

    await userEvent.click(await screen.findByRole("button", { name: "I forgot my PIN" }));
    expect(screen.getByRole("heading", { level: 1, name: "Reset your PIN" })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Recovery code"), "k7qm2xhp9ftbwrnc");
    await userEvent.type(screen.getByLabelText("New PIN"), "1357");
    await userEvent.type(screen.getByLabelText("New PIN again"), "1357");
    await userEvent.click(screen.getByRole("button", { name: "Set new PIN and unlock" }));

    expect(await screen.findByTestId("recovery-code")).toHaveTextContent("NEW2-CODE-9FTB-WRNC");
    expect(bodiesSentTo(fetchMock, "POST /api/pin/recovery")).toEqual([{ recoveryCode: "k7qm2xhp9ftbwrnc", newPin: "1357" }]);
    // The app stays shut until the new code has been saved: it is never shown again.
    const open = screen.getByRole("button", { name: "Open Budget" });
    expect(open).toBeDisabled();
    await userEvent.click(screen.getByRole("checkbox", { name: "I've saved my recovery code" }));
    await userEvent.click(open);

    expect(await screen.findByRole("heading", { level: 1, name: "Settings" })).toBeInTheDocument();
  });

  it("checks the new PIN before anything is sent", async () => {
    const { fetchMock } = lockedBackend();
    renderApp();
    await userEvent.click(await screen.findByRole("button", { name: "I forgot my PIN" }));
    const send = screen.getByRole("button", { name: "Set new PIN and unlock" });

    await userEvent.click(send);
    expect(screen.getByRole("alert")).toHaveTextContent("Please type your recovery code.");

    await userEvent.type(screen.getByLabelText("Recovery code"), CODE);
    await userEvent.type(screen.getByLabelText("New PIN"), "12");
    await userEvent.click(send);
    expect(screen.getByRole("alert")).toHaveTextContent("A PIN is 4 to 8 digits.");

    await userEvent.type(screen.getByLabelText("New PIN"), "34");
    await userEvent.type(screen.getByLabelText("New PIN again"), "1243");
    await userEvent.click(send);
    expect(screen.getByRole("alert")).toHaveTextContent("Those two PINs are different.");

    expect(calls(fetchMock)).not.toContain("POST /api/pin/recovery");
  });

  it("shows the backend's message for a code that didn't match, and the way back to the PIN", async () => {
    lockedBackend({
      "POST /api/pin/recovery": () => ({ status: 401, error: "That recovery code didn't match. Please check it and try again." }),
    });
    renderApp();
    await userEvent.click(await screen.findByRole("button", { name: "I forgot my PIN" }));

    await userEvent.type(screen.getByLabelText("Recovery code"), CODE);
    await userEvent.type(screen.getByLabelText("New PIN"), "1357");
    await userEvent.type(screen.getByLabelText("New PIN again"), "1357");
    await userEvent.click(screen.getByRole("button", { name: "Set new PIN and unlock" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("That recovery code didn't match.");
    await userEvent.click(screen.getByRole("button", { name: "Back to the PIN" }));
    expect(screen.getByRole("heading", { level: 1, name: "Budget is locked" })).toBeInTheDocument();
  });
});

describe("Lock now", () => {
  it("is offered only when there is a PIN to lock with", async () => {
    fakeBackend();
    renderApp();

    await screen.findAllByRole("navigation", { name: "Main" });
    expect(screen.queryByRole("button", { name: "Lock now" })).not.toBeInTheDocument();
  });

  it("puts the lock screen up straight away", async () => {
    const { fetchMock, state } = lockedBackend();
    state.open = true;
    renderApp();

    await userEvent.click((await screen.findAllByRole("button", { name: "Lock now" }))[0]!);

    expect(await screen.findByRole("heading", { level: 1, name: "Budget is locked" })).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("DELETE /api/session");
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });
});

describe("the offer to set a PIN", () => {
  const offering = { ...UNLOCKED, offerPin: true };

  it("is made once, and Not now is an answer", async () => {
    const fetchMock = fakeBackend({
      "GET /api/session": () => ({ status: 200, data: offering }),
      "DELETE /api/pin/offer": () => ({ status: 200, data: UNLOCKED }),
    });
    renderApp();

    const dialog = await screen.findByRole("dialog", { name: "Lock Budget with a PIN?" });
    expect(dialog).toHaveTextContent("any time in Settings");
    await userEvent.click(within(dialog).getByRole("button", { name: "Not now" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls(fetchMock)).toContain("DELETE /api/pin/offer");
  });

  it("sets the PIN and shows the recovery code once", async () => {
    const fetchMock = fakeBackend({
      "GET /api/session": () => ({ status: 200, data: offering }),
      "PUT /api/pin": () => ({ status: 200, data: { recoveryCode: CODE, session: UNLOCKED_WITH_PIN } }),
    });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Set a PIN" }));
    const form = screen.getByRole("dialog", { name: "Set a PIN" });
    await userEvent.type(within(form).getByLabelText("New PIN"), "4829");
    await userEvent.type(within(form).getByLabelText("New PIN again"), "4829");
    await userEvent.click(within(form).getByRole("button", { name: "Set PIN" }));

    const saved = await screen.findByRole("dialog", { name: "Your PIN is set" });
    expect(within(saved).getByTestId("recovery-code")).toHaveTextContent(CODE);
    expect(bodiesSentTo(fetchMock, "PUT /api/pin")).toEqual([{ newPin: "4829" }]);
    // No way out but saying it is saved: Escape and the X leave the code on screen.
    await userEvent.keyboard("{Escape}");
    await userEvent.click(within(saved).getByRole("button", { name: "Close" }));
    expect(within(saved).getByTestId("recovery-code")).toBeInTheDocument();
    expect(within(saved).getByRole("button", { name: "Done" })).toBeDisabled();

    await userEvent.click(within(saved).getByRole("checkbox", { name: "I've saved my recovery code" }));
    await userEvent.click(within(saved).getByRole("button", { name: "Done" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // With a PIN set, the way to lock is there.
    expect(screen.getAllByRole("button", { name: "Lock now" }).length).toBeGreaterThan(0);
  });

  it("does not let two different PINs through", async () => {
    const fetchMock = fakeBackend({ "GET /api/session": () => ({ status: 200, data: offering }) });
    renderApp();

    await userEvent.click(await screen.findByRole("button", { name: "Set a PIN" }));
    await userEvent.type(screen.getByLabelText("New PIN"), "4829");
    await userEvent.type(screen.getByLabelText("New PIN again"), "4820");
    await userEvent.click(screen.getByRole("button", { name: "Set PIN" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Those two PINs are different.");
    expect(calls(fetchMock)).not.toContain("PUT /api/pin");
  });
});
