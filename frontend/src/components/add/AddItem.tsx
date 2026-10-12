import { ArrowLeftRight, CalendarClock, ChevronDown, type LucideIcon, Minus, Plus, Upload } from "lucide-react";
import {
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useAccounts } from "../../api/accounts";
import { useCategories, useTags } from "../../api/categories";
import type { Entry, EntryKind } from "../../api/entries";
import type { PeriodDetail } from "../../api/periods";
import { formatDay } from "../../lib/dates";
import { ItemFormBody } from "../items/ItemForm";
import { Modal } from "../Modal";
import { Segmented } from "../Segmented";
import { BUTTON_PRIMARY } from "../ui";
import { EntryFields, type EntryPart } from "./EntryFields";
import { UploadDialog } from "./UploadDialog";

/** What the + Add item form can add: a one-off of some kind, or a recurring item. */
export type AddKind = EntryKind | "recurring";

interface AddItemContext {
  /**
   * Opens the form with `kind` chosen and, when given, the account it is for (a charge on one card, say)
   * or the expense that money coming in pays back.
   */
  add: (kind: AddKind, accountId?: number, paysBack?: Entry) => void;
  /** Opens the form on a one-off that already exists, to change or delete it. */
  /** Opens a one-off for changing; `startAt` names the fields to open on, for a card's own Edit button. */
  editEntry: (entry: Entry, startAt?: EntryPart) => void;
  /** Opens "Upload a pay stub or receipt…". */
  upload: () => void;
  menuOpen: boolean;
  setMenuOpen: (open: boolean) => void;
  /** Tells the form which pay period is on screen; `undefined` when none is. */
  setPeriod: (period: PeriodDetail | undefined) => void;
  /** Lets the + Add item button say it is on screen, so Ctrl+N can open its menu. */
  setHasMenu: (present: boolean) => void;
}

const Context = createContext<AddItemContext | null>(null);

export function useAddItem(): AddItemContext {
  const context = useContext(Context);
  if (context === null) {
    throw new Error("useAddItem needs an AddItemProvider above it");
  }
  return context;
}

const KIND_CHOICES: readonly { value: AddKind; label: string; icon: LucideIcon }[] = [
  { value: "money_out", label: "Money out", icon: Minus },
  { value: "money_in", label: "Money in", icon: Plus },
  { value: "transfer", label: "Transfer", icon: ArrowLeftRight },
  { value: "recurring", label: "Recurring", icon: CalendarClock },
];

const EDITABLE = "input, textarea, select, [contenteditable='true']";

/**
 * Owns the + Add item form (spec §6) for the whole app, so its shortcuts work on every
 * screen: Ctrl+N opens the menu, Ctrl+E goes straight to money out and Ctrl+I to money in.
 */
export function AddItemProvider({ children }: { children: ReactNode }) {
  const [form, setForm] = useState<{ kind: AddKind; entry?: Entry; accountId?: number; paysBack?: Entry; startAt?: EntryPart } | null>(null);
  const [uploading, setUploading] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [hasMenu, setHasMenu] = useState(false);
  const [period, setPeriod] = useState<PeriodDetail>();

  const add = useCallback((kind: AddKind, accountId?: number, paysBack?: Entry) => {
    setMenuOpen(false);
    setForm({ kind, accountId, paysBack });
  }, []);
  const editEntry = useCallback((entry: Entry, startAt?: EntryPart) => setForm({ kind: entry.kind, entry, startAt }), []);
  const upload = useCallback(() => {
    setMenuOpen(false);
    setUploading(true);
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.shiftKey || event.altKey || form !== null || uploading) {
        return;
      }
      const key = event.key.toLowerCase();
      if (key === "n") {
        event.preventDefault();
        // On a screen without the button there is no menu to open, so go straight to the form.
        if (hasMenu) {
          setMenuOpen(true);
        } else {
          add("money_out");
        }
      } else if ((key === "e" || key === "i") && !(event.target instanceof Element && event.target.closest(EDITABLE))) {
        // Left alone inside a field, where the browser may have its own use for the keys.
        event.preventDefault();
        add(key === "e" ? "money_out" : "money_in");
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [add, form, hasMenu, uploading]);

  const context = useMemo(
    () => ({ add, editEntry, upload, menuOpen, setMenuOpen, setPeriod, setHasMenu }),
    [add, editEntry, upload, menuOpen],
  );

  return (
    <Context.Provider value={context}>
      {children}
      {form && (
        <AddItemForm
          initialKind={form.kind}
          entry={form.entry}
          accountId={form.accountId}
          paysBack={form.paysBack}
          startAt={form.startAt}
          period={period}
          onClose={() => setForm(null)}
        />
      )}
      {uploading && (
        <UploadDialog
          onReceipt={() => {
            setUploading(false);
            add("money_out");
          }}
          onClose={() => setUploading(false)}
        />
      )}
    </Context.Provider>
  );
}

/**
 * The one form behind every choice in the menu (design/add-form.png): a switch along the top
 * picks what is being added, and the fields below follow it.
 */
function AddItemForm({
  initialKind,
  entry,
  accountId,
  paysBack,
  startAt,
  period,
  onClose,
}: {
  initialKind: AddKind;
  entry?: Entry;
  /** The expense a new money-in entry pays back, when the form was opened from that expense. */
  paysBack?: Entry;
  /** When changing: the fields to open on. */
  startAt?: EntryPart;
  /** The account a new one-off starts on, when the form was opened for one. */
  accountId?: number;
  period?: PeriodDetail;
  onClose: () => void;
}) {
  const accounts = useAccounts();
  const categories = useCategories();
  const tags = useTags();
  const [kind, setKind] = useState<AddKind>(initialKind);
  // Past its first screen, what is being added has been chosen: the switch steps aside so each screen fits the window.
  // A form opened on a later group starts past the first screen, so the switch never flashes.
  const [step, setStep] = useState(startAt === undefined || startAt === "amount" ? 0 : 1);
  const choosing = step === 0;

  const title = entry
    ? `Edit ${entry.description}`
    : period
      ? `Add to ${formatDay(period.start)} – ${formatDay(period.end)} pay period`
      : "Add an item";
  // A one-off that exists stays a one-off; it cannot be turned into something that repeats.
  const choices = entry ? KIND_CHOICES.filter((choice) => choice.value !== "recurring") : KIND_CHOICES;
  const failure = accounts.error ?? categories.error ?? tags.error;

  return (
    <Modal title={title} icon={KIND_CHOICES.find((choice) => choice.value === kind)?.icon} onClose={onClose}>
      {choosing && (
        <div className="mb-4">
          <Segmented legend="What are you adding?" options={choices} value={kind} onChange={setKind} />
        </div>
      )}
      {failure && (
        <p role="alert" className="text-danger">
          {failure.message}
        </p>
      )}
      {!failure && (accounts.isPending || categories.isPending || tags.isPending) && <p className="text-muted">Loading…</p>}
      {accounts.data && categories.data && tags.data && accounts.data.length === 0 && (
        <p className="text-muted">Add an account first, on the Accounts screen, so there is somewhere for this money to come from or go to.</p>
      )}
      {accounts.data && categories.data && tags.data && accounts.data.length > 0 &&
        (kind === "recurring" ? (
          <ItemFormBody accounts={accounts.data} categories={categories.data} tags={tags.data} onClose={onClose} onStep={setStep} />
        ) : (
          <EntryFields
            kind={kind}
            entry={entry}
            accountId={accountId}
            paysBack={paysBack}
            startAt={startAt}
            period={period}
            accounts={accounts.data}
            categories={categories.data}
            tags={tags.data}
            onClose={onClose}
            onStep={setStep}
          />
        ))}
    </Modal>
  );
}

const MENU: readonly { kind: AddKind; label: string; about: string; shortcut?: string; icon: typeof Plus }[] = [
  { kind: "money_out", label: "Money out", about: "One-time purchase, gift, donation, emergency", shortcut: "Ctrl+E", icon: Minus },
  { kind: "money_in", label: "Money in", about: "Bonus, refund, gift, reimbursement, side cash", shortcut: "Ctrl+I", icon: Plus },
  { kind: "transfer", label: "Transfer", about: "Between two of your accounts", icon: ArrowLeftRight },
  { kind: "recurring", label: "Recurring item", about: "Bill, expense, budget, transfer or card payment that repeats", icon: CalendarClock },
];

/**
 * The "+ Add item" button and its menu (design/add-menu.png). A real menu for the keyboard:
 * ↓/↑ move, Home/End jump, Enter chooses, Esc closes and hands the focus back to the button.
 */
export function AddItemButton() {
  const { add, upload, menuOpen, setMenuOpen, setHasMenu } = useAddItem();
  const button = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setHasMenu(true);
    return () => {
      setHasMenu(false);
      setMenuOpen(false);
    };
  }, [setHasMenu, setMenuOpen]);

  // Opening the menu — by click or by Ctrl+N — puts the focus on its first choice.
  useEffect(() => {
    if (menuOpen) {
      menu.current?.querySelector<HTMLElement>("[role='menuitem']")?.focus();
    }
  }, [menuOpen]);

  useEffect(() => {
    if (!menuOpen) {
      return;
    }
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && !menu.current?.contains(event.target) && !button.current?.contains(event.target)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [menuOpen, setMenuOpen]);

  const onMenuKeyDown = (event: ReactKeyboardEvent) => {
    const items = [...(menu.current?.querySelectorAll<HTMLElement>("[role='menuitem']:not([aria-disabled='true'])") ?? [])];
    const at = items.findIndex((item) => item === document.activeElement);
    const go = (index: number) => {
      event.preventDefault();
      items[(index + items.length) % items.length]?.focus();
    };
    if (event.key === "ArrowDown") {
      go(at + 1);
    } else if (event.key === "ArrowUp") {
      go(at - 1);
    } else if (event.key === "Home") {
      go(0);
    } else if (event.key === "End") {
      go(items.length - 1);
    } else if (event.key === "Escape" || event.key === "Tab") {
      setMenuOpen(false);
      button.current?.focus();
    }
  };

  return (
    <div className="relative">
      <button
        ref={button}
        type="button"
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen(!menuOpen)}
        className={`${BUTTON_PRIMARY} flex items-center justify-center gap-2`}
      >
        <Plus aria-hidden className="size-5" />
        Add item
        <ChevronDown aria-hidden className="size-4" />
      </button>

      {menuOpen && (
        <div
          ref={menu}
          role="menu"
          aria-label="Add item"
          onKeyDown={onMenuKeyDown}
          className="absolute right-0 z-20 mt-2 w-full min-w-72 rounded-card border border-line bg-surface p-2 shadow-lg sm:w-96"
        >
          {MENU.map(({ kind, label, about, shortcut, icon: Icon }) => (
            <button
              key={kind}
              type="button"
              role="menuitem"
              onClick={() => add(kind)}
              className="flex min-h-11 w-full items-center gap-3 rounded-lg p-2 text-left hover:bg-bg focus-visible:bg-bg"
            >
              <span aria-hidden className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-bg text-accent-ink">
                <Icon className="size-5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold">{label}</span>
                <span className="block text-sm text-muted">{about}</span>
              </span>
              {shortcut && <kbd className="hidden shrink-0 text-sm text-muted md:block">{shortcut}</kbd>}
            </button>
          ))}
          <div className="mt-2 border-t border-line pt-2">
            <button
              type="button"
              role="menuitem"
              onClick={upload}
              className="flex min-h-11 w-full items-center gap-3 rounded-lg p-2 text-left hover:bg-bg focus-visible:bg-bg"
            >
              <span aria-hidden className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-bg text-accent-ink">
                <Upload className="size-5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold">Upload a pay stub or receipt…</span>
                <span className="block text-sm text-muted">Read and kept on this PC</span>
              </span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
