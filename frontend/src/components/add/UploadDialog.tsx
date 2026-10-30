import { FileText, Landmark, type LucideIcon, Receipt, Upload } from "lucide-react";
import { useId, useState } from "react";
import { Link } from "react-router";
import { useAccounts } from "../../api/accounts";
import { useIncomeSources } from "../../api/income";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { Segmented } from "../Segmented";
import { StatementUpload } from "../statements/StatementUpload";
import { StubUpload } from "../stubs/StubUpload";
import { BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * "Upload a pay stub, statement or receipt…" from the + Add item menu (spec §6). A stub belongs to an
 * income, so it asks which one and opens the stub's review page. A statement belongs to an account, so it
 * asks which and opens the statement's page (Rachel, 2026-10-25). A receipt belongs to the expense it is
 * for, so it leads to that expense's form, which has the place for it. One kind is shown at a time, chosen at the
 * top, so the dialog fits one screen.
 */
export function UploadDialog({ onReceipt, onClose }: { onReceipt: () => void; onClose: () => void }) {
  const id = useId();
  const sources = useIncomeSources();
  const [chosen, setChosen] = useState<number>();
  const list = sources.data ?? [];
  // The primary paycheck is listed first, and is the likeliest.
  const sourceId = list.find((source) => source.id === chosen)?.id ?? list[0]?.id;
  const [what, setWhat] = useState<UploadKind>("stub");
  // While a CSV statement asks which column is which, only that question is shown, so the dialog still fits.
  const [askingColumns, setAskingColumns] = useState(false);

  return (
    <Modal title="Upload a pay stub, statement or receipt" icon={Upload} onClose={onClose}>
      <div className="space-y-4">
        {!askingColumns && <Segmented legend="What you're uploading" options={UPLOAD_KINDS} value={what} onChange={setWhat} />}

        {what === "stub" && (
          <FormSection title="A pay stub">
            {sources.isPending && <p className="text-muted">Loading your income…</p>}
            {sources.isError && (
              <p role="alert" className="text-danger">
                {sources.error.message}
              </p>
            )}
            {sources.data && sourceId === undefined && (
              <p className="text-muted">
                A stub belongs to an income source.{" "}
                <Link to="/income" onClick={onClose} className="font-semibold text-accent-ink underline">
                  Add your paycheck on the Income screen
                </Link>{" "}
                first; a stub can fill in its amounts there.
              </p>
            )}
            {sourceId !== undefined && (
              <>
                {list.length > 1 && (
                  <div>
                    <label htmlFor={`${id}-source`} className={LABEL}>
                      Whose stub it is
                    </label>
                    <select
                      id={`${id}-source`}
                      value={sourceId}
                      // Choosing here saves nothing by itself.
                      data-saves-itself
                      onChange={(event) => setChosen(Number(event.target.value))}
                      className={INPUT}
                    >
                      {list.map((source) => (
                        <option key={source.id} value={source.id}>
                          {source.name}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
                <div>
                  <StubUpload sourceId={sourceId} onUploaded={onClose} />
                  <p className={HINT}>A PDF from your payroll site. It is read on this PC, and nothing changes until you save it.</p>
                </div>
              </>
            )}
          </FormSection>
        )}

        {what === "statement" && <StatementSection onClose={onClose} onAsking={setAskingColumns} />}

        {what === "receipt" && (
          <FormSection title="A receipt">
            <p className="text-muted">
              A receipt is kept with the expense it is for. Add the expense and attach the receipt as you go; for a bill already on a pay
              period, open its row there and add the file.
            </p>
            <button type="button" onClick={onReceipt} className={BUTTON_SECONDARY}>
              Add the expense it is for
            </button>
          </FormSection>
        )}
      </div>
    </Modal>
  );
}

type UploadKind = "stub" | "statement" | "receipt";

const UPLOAD_KINDS: readonly { value: UploadKind; label: string; icon: LucideIcon }[] = [
  { value: "stub", label: "Pay stub", icon: FileText },
  { value: "statement", label: "Statement", icon: Landmark },
  { value: "receipt", label: "Receipt", icon: Receipt },
];

/** A card or bank statement: which account it is for, then the file. Only accounts money is spent from can have one. */
function StatementSection({ onClose, onAsking }: { onClose: () => void; onAsking: (asking: boolean) => void }) {
  const id = useId();
  const accounts = useAccounts();
  const [chosen, setChosen] = useState<number>();
  const [asking, setAsking] = useState(false);
  const list = (accounts.data ?? []).filter(
    (account) => !account.archived && (account.type === "checking" || account.type === "savings" || account.type === "credit_card"),
  );
  const accountId = list.find((account) => account.id === chosen)?.id ?? list[0]?.id;

  // While asking which column is which, the question goes unframed: the frame's room is what lets it fit a phone.
  return (
    <FormSection title={asking ? "Which column is which" : "A card or bank statement"} bare={asking}>
      {accounts.isPending && <p className="text-muted">Loading your accounts…</p>}
      {accounts.isError && (
        <p role="alert" className="text-danger">
          {accounts.error.message}
        </p>
      )}
      {accounts.data && accountId === undefined && (
        <p className="text-muted">
          A statement belongs to a checking, savings or card account.{" "}
          <Link to="/accounts" onClick={onClose} className="font-semibold text-accent-ink underline">
            Add one on the Accounts screen
          </Link>{" "}
          first.
        </p>
      )}
      {accountId !== undefined && (
        <>
          {!asking && (
            <div>
              <label htmlFor={`${id}-account`} className={LABEL}>
                Which account it&apos;s for
              </label>
              <select
                id={`${id}-account`}
                value={accountId}
                // Choosing here saves nothing by itself.
                data-saves-itself
                onChange={(event) => setChosen(Number(event.target.value))}
                className={INPUT}
              >
                {list.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div>
            <StatementUpload
              accountId={accountId}
              onUploaded={onClose}
              onAsking={(now) => {
                setAsking(now);
                onAsking(now);
              }}
            />
            {!asking && (
              <p className={HINT}>A CSV, OFX or QFX download, or a PDF. Nothing changes until you choose what each total replaces.</p>
            )}
          </div>
        </>
      )}
    </FormSection>
  );
}
