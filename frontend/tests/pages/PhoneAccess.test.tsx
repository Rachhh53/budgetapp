import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { PhoneAccessView } from "../../src/api/phoneAccess";
import { bodiesSentTo, DEFAULT_SETTINGS, fakeBackend, PHONE_ACCESS_OFF, renderApp, UNLOCKED } from "../helpers";

const TAILSCALE = { address: "100.101.102.103", name: "Tailscale", tunnel: true };
const WIFI = { address: "192.168.1.20", name: "Wi-Fi", tunnel: false };

/** Off, with a PIN set, on a PC with a VPN and home Wi-Fi. */
const READY: PhoneAccessView = { ...PHONE_ACCESS_OFF, pinSet: true, networks: [TAILSCALE, WIFI] };

const ON_TAILSCALE: PhoneAccessView = {
  ...READY,
  status: { on: true, address: TAILSCALE.address, url: "http://100.101.102.103:4873/", listening: true, problem: "none" },
};

async function card(): Promise<HTMLElement> {
  return screen.findByRole("region", { name: "Phone access" });
}

describe("phone access", () => {
  it("needs a PIN before it can be turned on", async () => {
    fakeBackend({ "GET /api/phone-access": () => ({ status: 200, data: { ...READY, pinSet: false } }) });
    renderApp("/settings");

    const phone = await card();
    expect(await within(phone).findByText(/Phone access needs a PIN/)).toBeInTheDocument();
    expect(within(phone).getByLabelText("Choose a network")).toBeDisabled();
    expect(within(phone).getByRole("button", { name: "Turn on phone access" })).toBeDisabled();
  });

  it("lists the VPN first and turns phone access on there, without a warning", async () => {
    const fetchMock = fakeBackend({
      "GET /api/session": () => ({ status: 200, data: { ...UNLOCKED, pinSet: true } }),
      "GET /api/phone-access": () => ({ status: 200, data: READY }),
      "PUT /api/phone-access": () => ({ status: 200, data: ON_TAILSCALE }),
    });
    renderApp("/settings");

    const phone = await card();
    const list = await within(phone).findByLabelText("Choose a network");
    expect(within(list).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Choose a network…",
      "Tailscale · VPN · 100.101.102.103",
      "Wi-Fi · not encrypted · 192.168.1.20",
    ]);

    await userEvent.selectOptions(list, TAILSCALE.address);
    expect(within(phone).getByText(/looks like a VPN, which encrypts the connection/)).toBeInTheDocument();
    expect(within(phone).queryByText(/isn't encrypted/)).not.toBeInTheDocument();
    await userEvent.click(within(phone).getByRole("button", { name: "Turn on phone access" }));

    expect(bodiesSentTo(fetchMock, "PUT /api/phone-access")).toEqual([{ address: TAILSCALE.address }]);
    expect(await within(phone).findByText("http://100.101.102.103:4873/")).toBeInTheDocument();
    expect(within(phone).getByText(/On, through Tailscale/)).toBeInTheDocument();
    expect(within(phone).getByRole("status")).toHaveTextContent("Ready for your phone now.");
    expect(within(phone).getByText(/Add to Home Screen/)).toBeInTheDocument();
    expect(within(phone).getByText(/Private networks/)).toBeInTheDocument();
  });

  it("warns plainly when home Wi-Fi is chosen", async () => {
    fakeBackend({ "GET /api/phone-access": () => ({ status: 200, data: READY }) });
    renderApp("/settings");

    const phone = await card();
    await userEvent.selectOptions(await within(phone).findByLabelText("Choose a network"), WIFI.address);

    expect(within(phone).getByRole("note")).toHaveTextContent(/isn't encrypted.*your numbers and your PIN/);
  });

  it("says why the phone can't reach it, while it is on", async () => {
    fakeBackend({
      "GET /api/phone-access": () => ({
        status: 200,
        data: {
          ...ON_TAILSCALE,
          networks: [WIFI],
          status: { ...ON_TAILSCALE.status, listening: false, problem: "network_not_connected" },
        },
      }),
    });
    renderApp("/settings");

    const phone = await card();
    expect(await within(phone).findByRole("alert")).toHaveTextContent(/this network isn't connected/);
    expect(within(phone).getByLabelText("Network")).toHaveValue(TAILSCALE.address);
    expect(within(phone).getByRole("option", { name: "Not connected · 100.101.102.103" })).toBeInTheDocument();
  });

  it("turns off, and says the phone is signed out", async () => {
    const fetchMock = fakeBackend({
      "GET /api/phone-access": () => ({ status: 200, data: ON_TAILSCALE }),
      "PUT /api/phone-access": () => ({ status: 200, data: READY }),
    });
    renderApp("/settings");

    const phone = await card();
    expect(await within(phone).findByText(/signs out every phone/)).toBeInTheDocument();
    await userEvent.click(within(phone).getByRole("button", { name: "Turn off phone access" }));

    expect(bodiesSentTo(fetchMock, "PUT /api/phone-access")).toEqual([{ address: null }]);
    expect(await within(phone).findByRole("button", { name: "Turn on phone access" })).toBeInTheDocument();
  });

  it("moves to another network only when asked", async () => {
    const fetchMock = fakeBackend({
      "GET /api/phone-access": () => ({ status: 200, data: ON_TAILSCALE }),
      "PUT /api/phone-access": () => ({ status: 200, data: ON_TAILSCALE }),
    });
    renderApp("/settings");

    const phone = await card();
    expect(within(phone).queryByRole("button", { name: "Move phone access here" })).not.toBeInTheDocument();
    await userEvent.selectOptions(await within(phone).findByLabelText("Network"), WIFI.address);
    await userEvent.click(within(phone).getByRole("button", { name: "Move phone access here" }));

    expect(bodiesSentTo(fetchMock, "PUT /api/phone-access")).toEqual([{ address: WIFI.address }]);
  });

  it("shows what went wrong when it can't be turned on", async () => {
    fakeBackend({
      "GET /api/phone-access": () => ({ status: 200, data: READY }),
      "PUT /api/phone-access": () => ({ status: 409, error: "Another program on this PC is using the port phone access needs." }),
    });
    renderApp("/settings");

    const phone = await card();
    await userEvent.selectOptions(await within(phone).findByLabelText("Choose a network"), TAILSCALE.address);
    await userEvent.click(within(phone).getByRole("button", { name: "Turn on phone access" }));

    expect(await within(phone).findByRole("alert")).toHaveTextContent("Another program on this PC is using the port");
  });

  it("can't be changed from the phone itself", async () => {
    fakeBackend({ "GET /api/phone-access": () => ({ status: 200, data: { ...ON_TAILSCALE, fromThisPc: false, networks: [] } }) });
    renderApp("/settings");

    const phone = await card();
    expect(await within(phone).findByText(/can only be turned on, off or moved on the PC/)).toBeInTheDocument();
    expect(within(phone).queryByRole("button")).not.toBeInTheDocument();
  });

  it("says closing the window keeps Budget in the tray while it is on", async () => {
    fakeBackend({ "GET /api/phone-access": () => ({ status: 200, data: ON_TAILSCALE }) });
    renderApp("/settings");

    expect(await within(await card()).findByText(/closing this window keeps Budget running in the tray/)).toBeInTheDocument();
  });

  it("turns Start with Windows on, from this PC", async () => {
    const fetchMock = fakeBackend({
      "PUT /api/settings/start_with_windows": () => ({ status: 200, data: { ...DEFAULT_SETTINGS, start_with_windows: "true" } }),
    });
    renderApp("/settings");

    await userEvent.click(await within(await card()).findByLabelText("Start with Windows"));

    expect(bodiesSentTo(fetchMock, "PUT /api/settings/start_with_windows")).toEqual([{ value: "true" }]);
  });

  it("leaves out what only the PC itself can do, on a phone", async () => {
    fakeBackend({
      "GET /api/session": () => ({ status: 200, data: { ...UNLOCKED, pinSet: true, fromThisPc: false } }),
      "GET /api/phone-access": () => ({ status: 200, data: { ...ON_TAILSCALE, fromThisPc: false, networks: [] } }),
    });
    renderApp("/settings");

    expect(await screen.findByText("Backups are made and restored on the PC Budget is installed on.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Back up now" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Erase all data/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Start with Windows")).not.toBeInTheDocument();
  });

  it("says when this PC isn't on any network", async () => {
    fakeBackend({ "GET /api/phone-access": () => ({ status: 200, data: { ...READY, networks: [] } }) });
    renderApp("/settings");

    expect(await within(await card()).findByText(/isn't connected to a network right now/)).toBeInTheDocument();
  });
});
