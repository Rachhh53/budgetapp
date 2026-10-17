import { ShieldCheck, TriangleAlert } from "lucide-react";
import { type ReactNode, useState } from "react";
import { Link } from "react-router";
import { type Network, type PhoneAccessView, useChangePhoneAccess, usePhoneAccess } from "../../api/phoneAccess";
import { Card } from "../Card";
import { BUTTON_DANGER_OUTLINE, BUTTON_PRIMARY, LABEL } from "../ui";

const SELECT = "min-h-11 w-full rounded-lg border border-line bg-surface px-3";
const WARNING = "flex items-start gap-2 rounded-lg border border-warn-line bg-warn p-3 text-warn-ink";

/**
 * How a network reads in the list: its name, what kind it looks like, and the address. A tunnel is
 * only "VPN or tunnel": from this PC alone a VPN that links your devices (Tailscale) can't be told
 * from one for browsing privately (Hotspot Shield), which a phone can never reach this PC through.
 */
function networkLabel(network: Network): string {
  return `${network.name} · ${network.tunnel ? "VPN or tunnel" : "not encrypted"} · ${network.address}`;
}

/**
 * Settings → Phone access (spec §10, Phase 12): open Budget on a phone, through one of this PC's
 * networks. Off by default. A VPN (Tailscale, WireGuard…) is recommended; choosing home Wi-Fi or a
 * network cable says plainly that the connection over it isn't encrypted. It needs a PIN, and can
 * only be changed on this PC; opened from a phone, the card only says that it is on.
 */
export function PhoneAccessCard({ children }: { children?: ReactNode }) {
  const phoneAccess = usePhoneAccess();
  const view = phoneAccess.data;

  return (
    <Card title="Phone access">
      {phoneAccess.isPending && <p className="text-muted">Looking at this PC's networks…</p>}
      {phoneAccess.isError && (
        <p role="alert" className="text-danger">
          {phoneAccess.error.message}
        </p>
      )}
      {view && !view.fromThisPc && (
        <p className="text-muted">
          You're using Budget from another device. Phone access can only be turned on, off or moved on the PC Budget is
          installed on.
        </p>
      )}
      {view?.fromThisPc && (
        <>
          <OnThisPc view={view} />
          {children && <div className="mt-4 border-t border-line pt-4">{children}</div>}
        </>
      )}
    </Card>
  );
}

function OnThisPc({ view }: { view: PhoneAccessView }) {
  const change = useChangePhoneAccess();
  const { status, networks, pinSet } = view;
  const [chosen, setChosen] = useState(status.on ? status.address : "");
  // While it is on, the list shows where it is unless another network has just been picked.
  const selected = chosen || status.address;
  const network = networks.find((each) => each.address === selected);
  const current = networks.find((each) => each.address === status.address);
  const moving = status.on && selected !== status.address;

  return (
    <div className="space-y-4">
      <p className="text-muted">
        Open Budget on your phone while this PC is on and Budget is open. Your data stays on this PC; the phone only
        shows it, after your PIN.
      </p>

      {!pinSet && (
        <p className={WARNING}>
          <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>Phone access needs a PIN, so nobody else on the network can open your budget. Please set one under Security first.</span>
        </p>
      )}

      {status.on && <OnNow view={view} networkName={current?.name} />}

      {networks.length === 0 ? (
        <p className="text-muted">
          This PC isn't connected to a network right now. Once it is (or once your VPN is running), it will appear here.
        </p>
      ) : (
        <div>
          <label htmlFor="phone-network" className={LABEL}>
            {status.on ? "Network" : "Choose a network"}
          </label>
          <select
            id="phone-network"
            value={selected}
            disabled={!pinSet || change.isPending}
            aria-describedby="phone-network-hint"
            onChange={(event) => {
              change.reset();
              setChosen(event.target.value);
            }}
            className={SELECT}
          >
            {!status.on && <option value="">Choose a network…</option>}
            {status.on && !current && <option value={status.address}>Not connected · {status.address}</option>}
            {networks.map((each) => (
              <option key={each.address} value={each.address}>
                {networkLabel(each)}
              </option>
            ))}
          </select>
          <p id="phone-network-hint" className="mt-1 text-sm text-muted">
            A VPN such as Tailscale or WireGuard is the safe choice: it encrypts everything between your phone and this
            PC, at home or away.{" "}
            <Link to="/help/settings" className="font-semibold text-accent-ink underline">
              How to set one up
            </Link>
          </p>
        </div>
      )}

      {network && !network.tunnel && (
        <p role="note" className={WARNING}>
          <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>
            <strong>{network.name}</strong> looks like your home Wi-Fi or a network cable, and Budget's connection over it
            isn't encrypted. Anyone else on this network (a guest, or another device on it) could see your numbers and
            your PIN as they go by. A VPN keeps them private.
          </span>
        </p>
      )}
      {network?.tunnel && (
        <p className="flex items-start gap-2 text-sm">
          <ShieldCheck aria-hidden className="mt-0.5 size-5 shrink-0 text-accent-ink" />
          <span>
            <strong>{network.name}</strong> looks like a VPN or tunnel. It only works if it's one that links your own
            devices, like Tailscale or WireGuard, running on your phone too. A VPN for browsing privately (such as
            Hotspot Shield, NordVPN or ExpressVPN) can't connect your phone to this PC.
          </span>
        </p>
      )}

      {change.isError && (
        <p role="alert" className="text-danger">
          {change.error.message}
        </p>
      )}

      <div className="flex flex-col gap-2 sm:flex-row">
        {(!status.on || moving) && (
          <button
            type="button"
            disabled={!pinSet || !network || change.isPending}
            onClick={() => change.mutate(selected, { onSuccess: () => setChosen("") })}
            className={BUTTON_PRIMARY}
          >
            {change.isPending ? "Saving…" : moving ? "Move phone access here" : "Turn on phone access"}
          </button>
        )}
        {status.on && (
          <button
            type="button"
            disabled={change.isPending}
            onClick={() => change.mutate(null, { onSuccess: () => setChosen("") })}
            className={BUTTON_DANGER_OUTLINE}
          >
            Turn off phone access
          </button>
        )}
      </div>
      {status.on && <p className="text-sm text-muted">Turning it off signs out every phone straight away.</p>}
    </div>
  );
}

/** While phone access is on: the address to open, whether the phone can reach it now, and what to do next. */
function OnNow({ view, networkName }: { view: PhoneAccessView; networkName: string | undefined }) {
  const { status } = view;
  return (
    <div className="space-y-3 rounded-lg border border-line p-4">
      <div>
        <p className="font-medium">On{networkName ? `, through ${networkName}` : ""}. Open this on your phone:</p>
        <p className="mt-1 font-mono text-lg font-semibold break-all select-all">{status.url}</p>
      </div>
      {status.listening ? (
        <p role="status" className="text-sm">
          Listening for your phone now. If it still can't connect, check the steps below.
        </p>
      ) : (
        <p role="alert" className={WARNING}>
          <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>
            {status.problem === "port_in_use"
              ? "Your phone can't reach Budget right now: another program on this PC is using the port it needs. Closing that program should fix it; Budget tries again by itself."
              : "Your phone can't reach Budget right now, because this network isn't connected. Budget opens it again by itself once it is (for a VPN, once it's running)."}
          </span>
        </p>
      )}
      <ol className="list-decimal space-y-1 pl-5 text-sm">
        <li>On your iPhone, open the address above in Safari.</li>
        <li>
          Tap <strong>Share</strong>, then <strong>Add to Home Screen</strong>, and use Budget from there rather than a
          Safari tab.
        </li>
        <li>
          If Windows asks whether to let Budget through its firewall, allow <strong>Private networks</strong> only. If
          Windows calls this network Public, the firewall keeps your phone out: change the network to Private in
          Windows Settings, and only for a network you trust.
        </li>
        <li>Your phone can only reach Budget while this PC is awake. If it goes to sleep, the phone says it can't connect.</li>
      </ol>
      <p className="text-sm text-muted">
        While phone access is on, closing this window keeps Budget running in the tray, by the clock, so your phone can
        still reach it. Open it again from there, or choose <strong>Quit Budget</strong> to close it for good.
      </p>
    </div>
  );
}
