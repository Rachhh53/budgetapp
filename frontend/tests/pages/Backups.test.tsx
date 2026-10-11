import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { BackupStatus, FolderListing } from "../../src/api/backups";
import { addDays, todayIso } from "../../src/lib/dates";
import { bodiesSentTo, calls, DEFAULT_SETTINGS, fakeBackend, NO_BACKUPS, renderApp } from "../helpers";

const DOCUMENTS = "C:\\Users\\Amara\\Documents";
const D_DRIVE = "D:\\";
const D_BACKUPS = "D:\\Budget backups";

const TODAY = { name: "budget-backup-today.zip", createdAt: `${todayIso()}T07:02:00`, sizeBytes: 1_300_000 };
const EARLIER = { name: "budget-backup-earlier.zip", createdAt: "2026-03-04T18:30:00", sizeBytes: 20_480 };

const WITH_BACKUPS: BackupStatus = {
  ...NO_BACKUPS,
  folder: D_BACKUPS,
  usingDefaultFolder: false,
  backups: [TODAY, EARLIER],
  lastBackupAt: TODAY.createdAt,
};

const TOP: FolderListing = {
  path: null,
  parent: null,
  folders: [{ name: "Documents", path: DOCUMENTS }, { name: "D:\\", path: D_DRIVE }],
  writable: false,
};
const ON_D: FolderListing = { path: D_DRIVE, parent: null, folders: [{ name: "Photos", path: "D:\\Photos" }], writable: true };
const IN_D_BACKUPS: FolderListing = { path: D_BACKUPS, parent: D_DRIVE, folders: [], writable: true };

const folderRoute = (path: string) => `GET /api/folders?path=${encodeURIComponent(path)}`;

async function card(): Promise<HTMLElement> {
  return screen.findByRole("region", { name: "Backups" });
}

describe("backups", () => {
  it("says where backups go before a folder is chosen, and that there are none yet", async () => {
    fakeBackend();
    renderApp("/settings");

    const backups = await card();
    expect(await within(backups).findByText(NO_BACKUPS.folder)).toBeInTheDocument();
    expect(within(backups).getByText(/inside Budget's own data on this PC/)).toBeInTheDocument();
    expect(within(backups).getByText(/No backups yet\. One is made each day Budget is open\./)).toBeInTheDocument();
    expect(within(backups).getByText(/your PIN does not lock/)).toBeInTheDocument();
    // Nothing to restore yet.
    expect(within(backups).getByRole("button", { name: "Restore…" })).toBeDisabled();
    expect(within(backups).queryByRole("button", { name: "Use Budget's own folder" })).not.toBeInTheDocument();
  });

  it("says when the last backup was made", async () => {
    fakeBackend({ "GET /api/backups": () => ({ status: 200, data: WITH_BACKUPS }) });
    renderApp("/settings");

    const backups = await card();
    expect(await within(backups).findByText(/Last backup today at 7:02 AM\. Receipts and pay stubs are included\./)).toBeInTheDocument();
    expect(within(backups).getByText(D_BACKUPS)).toBeInTheDocument();
  });

  it("warns, in words, when today's backup could not be saved", async () => {
    fakeBackend({ "GET /api/backups": () => ({ status: 200, data: { ...WITH_BACKUPS, lastDailyBackupFailed: true } }) });
    renderApp("/settings");

    expect(await within(await card()).findByRole("alert")).toHaveTextContent("Today's backup couldn't be saved.");
  });

  it("backs up now and says so", async () => {
    const fetchMock = fakeBackend({ "POST /api/backups": () => ({ status: 201, data: WITH_BACKUPS }) });
    renderApp("/settings");

    const backups = await card();
    await userEvent.click(await within(backups).findByRole("button", { name: "Back up now" }));

    expect(await within(backups).findByRole("status")).toHaveTextContent("Backed up just now.");
    expect(within(backups).getByText(/Last backup today at 7:02 AM/)).toBeInTheDocument();
    expect(calls(fetchMock)).toContain("POST /api/backups");
  });

  it("explains kindly when the folder has gone away", async () => {
    fakeBackend({
      "GET /api/backups": () => ({ status: 200, data: WITH_BACKUPS }),
      "POST /api/backups": () => ({
        status: 409,
        error: "We couldn't save a backup to that folder. Please check it's still there, or choose another one.",
      }),
    });
    renderApp("/settings");

    const backups = await card();
    await userEvent.click(await within(backups).findByRole("button", { name: "Back up now" }));

    expect(await within(backups).findByRole("alert")).toHaveTextContent("Please check it's still there");
  });

  it("chooses how many backups are kept", async () => {
    let saved: Record<string, string> = { ...DEFAULT_SETTINGS };
    const fetchMock = fakeBackend({
      "GET /api/settings": () => ({ status: 200, data: saved }),
      "PUT /api/settings/backup_keep_count": (body) => {
        saved = { ...saved, backup_keep_count: (body as { value: string }).value };
        return { status: 200, data: saved };
      },
    });
    renderApp("/settings");

    const keep = await screen.findByLabelText("Keep the last");
    expect(within(keep).getAllByRole("option").map((option) => option.textContent))
      .toEqual(["7 backups", "14 backups", "30 backups", "60 backups", "90 backups"]);
    await userEvent.selectOptions(keep, "14 backups");

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/settings/backup_keep_count")).toEqual([{ value: "14" }]));
  });
});

describe("choosing the backup folder", () => {
  function browsing(overrides: Parameters<typeof fakeBackend>[0] = {}) {
    let status: BackupStatus = NO_BACKUPS;
    return fakeBackend({
      "GET /api/backups": () => ({ status: 200, data: status }),
      "GET /api/folders": () => ({ status: 200, data: TOP }),
      [folderRoute(D_DRIVE)]: () => ({ status: 200, data: ON_D }),
      [folderRoute(D_BACKUPS)]: () => ({ status: 200, data: IN_D_BACKUPS }),
      "PUT /api/backups/folder": (body) => {
        const path = (body as { path: string | null }).path;
        status = path === null ? NO_BACKUPS : { ...NO_BACKUPS, folder: path, usingDefaultFolder: false };
        return { status: 200, data: status, changed: true };
      },
      ...overrides,
    });
  }

  it("starts on the drives, where nothing can be chosen yet", async () => {
    browsing();
    renderApp("/settings");

    await userEvent.click(await within(await card()).findByRole("button", { name: "Choose folder…" }));

    const dialog = screen.getByRole("dialog", { name: "Choose a backup folder" });
    expect(within(dialog).getByText("This PC")).toBeInTheDocument();
    const folders = await within(dialog).findByRole("list", { name: "Folders" });
    expect(within(folders).getAllByRole("button").map((button) => button.textContent)).toEqual(["Documents", "D:\\"]);
    expect(within(dialog).getByRole("button", { name: "Save backups here" })).toBeDisabled();
    expect(within(dialog).queryByRole("button", { name: "New folder in here" })).not.toBeInTheDocument();
  });

  it("opens a folder, goes back up, and saves the one chosen", async () => {
    const fetchMock = browsing();
    renderApp("/settings");
    await userEvent.click(await within(await card()).findByRole("button", { name: "Choose folder…" }));
    const dialog = screen.getByRole("dialog", { name: "Choose a backup folder" });

    await userEvent.click(await within(dialog).findByRole("button", { name: "D:\\" }));
    expect(await within(dialog).findByRole("button", { name: "Photos" })).toBeInTheDocument();
    expect(within(dialog).getByText(D_DRIVE)).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole("button", { name: "Up a level" }));
    expect(await within(dialog).findByText("This PC")).toBeInTheDocument();

    await userEvent.click(await within(dialog).findByRole("button", { name: "D:\\" }));
    await within(dialog).findByRole("button", { name: "Photos" });
    await userEvent.click(within(dialog).getByRole("button", { name: "Save backups here" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bodiesSentTo(fetchMock, "PUT /api/backups/folder")).toEqual([{ path: D_DRIVE }]);
    const backups = await card();
    expect(within(backups).getByText(D_DRIVE)).toBeInTheDocument();
    expect(within(backups).queryByText(/inside Budget's own data/)).not.toBeInTheDocument();
  });

  it("makes a new folder, opens it, and it can be chosen", async () => {
    const fetchMock = browsing({ "POST /api/folders": () => ({ status: 201, data: IN_D_BACKUPS }) });
    renderApp("/settings");
    await userEvent.click(await within(await card()).findByRole("button", { name: "Choose folder…" }));
    const dialog = screen.getByRole("dialog", { name: "Choose a backup folder" });
    await userEvent.click(await within(dialog).findByRole("button", { name: "D:\\" }));

    await userEvent.click(await within(dialog).findByRole("button", { name: "New folder in here" }));
    const name = within(dialog).getByLabelText("Name of the new folder");
    expect(name).toHaveValue("Budget backups");
    await userEvent.click(within(dialog).getByRole("button", { name: "Make folder" }));

    expect(await within(dialog).findByText(D_BACKUPS)).toBeInTheDocument();
    expect(await within(dialog).findByText("No folders in here. This one can still be chosen.")).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/folders")).toEqual([{ parent: D_DRIVE, name: "Budget backups" }]);
    await userEvent.click(within(dialog).getByRole("button", { name: "Save backups here" }));

    await waitFor(() => expect(bodiesSentTo(fetchMock, "PUT /api/backups/folder")).toEqual([{ path: D_BACKUPS }]));
  });

  it("says so when a folder cannot take files, and when a name is refused", async () => {
    browsing({
      [folderRoute(DOCUMENTS)]: () => ({ status: 200, data: { path: DOCUMENTS, parent: "C:\\Users\\Amara", folders: [], writable: false } }),
      "POST /api/folders": () => ({ status: 400, error: "We couldn't make that folder. Please try a different name." }),
    });
    renderApp("/settings");
    await userEvent.click(await within(await card()).findByRole("button", { name: "Choose folder…" }));
    const dialog = screen.getByRole("dialog", { name: "Choose a backup folder" });

    await userEvent.click(await within(dialog).findByRole("button", { name: "Documents" }));
    expect(await within(dialog).findByText(/can't save files in this folder/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save backups here" })).toBeDisabled();

    await userEvent.click(within(dialog).getByRole("button", { name: "Up a level" }));
    await userEvent.click(await within(dialog).findByRole("button", { name: "D:\\" }));
    await userEvent.click(await within(dialog).findByRole("button", { name: "New folder in here" }));
    await userEvent.click(within(dialog).getByRole("button", { name: "Make folder" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Please try a different name.");
  });

  it("goes back to Budget's own folder in one press", async () => {
    let status: BackupStatus = WITH_BACKUPS;
    const fetchMock = fakeBackend({
      "GET /api/backups": () => ({ status: 200, data: status }),
      "PUT /api/backups/folder": () => {
        status = NO_BACKUPS;
        return { status: 200, data: status };
      },
    });
    renderApp("/settings");

    const backups = await card();
    await userEvent.click(await within(backups).findByRole("button", { name: "Use Budget's own folder" }));

    expect(await within(backups).findByText(NO_BACKUPS.folder)).toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "PUT /api/backups/folder")).toEqual([{ path: null }]);
  });
});

describe("restoring a backup", () => {
  it("asks first, with keeping the data as the easy answer", async () => {
    const fetchMock = fakeBackend({ "GET /api/backups": () => ({ status: 200, data: WITH_BACKUPS }) });
    renderApp("/settings");

    await userEvent.click(await within(await card()).findByRole("button", { name: "Restore…" }));

    const dialog = screen.getByRole("dialog", { name: "Restore a backup" });
    expect(within(dialog).getByRole("button", { name: "Keep my current data" })).toHaveFocus();
    expect(dialog).toHaveTextContent("Everything in Budget now is replaced by this backup.");
    expect(dialog).toHaveTextContent("A copy of what you have now is kept first");
    // Chosen from the list, newest first, said in words.
    const which = within(dialog).getByLabelText("Backup to restore");
    expect(within(which).getAllByRole("option").map((option) => option.textContent))
      .toEqual(["Today at 7:02 AM · 1.2 MB", "Mar 4, 2026 at 6:30 PM · 20 KB"]);

    await userEvent.click(within(dialog).getByRole("button", { name: "Keep my current data" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls(fetchMock)).not.toContain("POST /api/backups/restore");
  });

  it("restores the one chosen, loads everything afresh and says what it holds now", async () => {
    let accounts = 0;
    const fetchMock = fakeBackend({
      "GET /api/backups": () => ({ status: 200, data: WITH_BACKUPS }),
      "GET /api/holidays": () => {
        accounts += 1;
        return { status: 200, data: [] };
      },
      "POST /api/backups/restore": () => ({ status: 200, data: { restored: true } }),
    });
    renderApp("/settings");
    await userEvent.click(await within(await card()).findByRole("button", { name: "Restore…" }));
    const dialog = screen.getByRole("dialog", { name: "Restore a backup" });
    const loadedBefore = accounts;

    await userEvent.selectOptions(within(dialog).getByLabelText("Backup to restore"), EARLIER.name);
    await userEvent.click(within(dialog).getByRole("button", { name: "Restore this backup" }));

    expect(await within(await card()).findByRole("status")).toHaveTextContent(
      "Restored. Budget now holds the backup from Mar 4, 2026 at 6:30 PM.",
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(bodiesSentTo(fetchMock, "POST /api/backups/restore")).toEqual([{ name: EARLIER.name, confirm: "restore-backup" }]);
    // What is on screen came from the data that was replaced, so it is asked for again.
    await waitFor(() => expect(accounts).toBeGreaterThan(loadedBefore));
  });

  it("shows the backend's message when a backup cannot be read, and changes nothing", async () => {
    fakeBackend({
      "GET /api/backups": () => ({ status: 200, data: WITH_BACKUPS }),
      "POST /api/backups/restore": () => ({
        status: 400,
        error: "We couldn't read that backup, so nothing was changed. It may be damaged; please try another one.",
      }),
    });
    renderApp("/settings");
    await userEvent.click(await within(await card()).findByRole("button", { name: "Restore…" }));
    const dialog = screen.getByRole("dialog", { name: "Restore a backup" });

    await userEvent.click(within(dialog).getByRole("button", { name: "Restore this backup" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("nothing was changed");
    expect(within(dialog).getByRole("button", { name: "Keep my current data" })).toBeEnabled();
  });

  it("words yesterday's backup as yesterday", async () => {
    const yesterday = { ...EARLIER, createdAt: `${addDays(todayIso(), -1)}T21:15:00` };
    fakeBackend({
      "GET /api/backups": () => ({ status: 200, data: { ...WITH_BACKUPS, backups: [yesterday], lastBackupAt: yesterday.createdAt } }),
    });
    renderApp("/settings");

    expect(await within(await card()).findByText(/Last backup yesterday at 9:15 PM/)).toBeInTheDocument();
  });
});
