import { Upload } from "lucide-react";
import { useId, useState } from "react";
import { Link } from "react-router";
import { useIncomeSources } from "../../api/income";
import { FormSection } from "../FormSection";
import { Modal } from "../Modal";
import { StubUpload } from "../stubs/StubUpload";
import { BUTTON_SECONDARY, HINT, INPUT, LABEL } from "../ui";

/**
 * "Upload a pay stub or receipt…" from the + Add item menu (spec §6). A stub belongs to an
 * income, so it asks which one and opens the stub's review page. A receipt belongs to the
 * expense it is for, so it leads to that expense's form, which has the place for it.
 */
export function UploadDialog({ onReceipt, onClose }: { onReceipt: () => void; onClose: () => void }) {
  const id = useId();
  const sources = useIncomeSources();
  const [chosen, setChosen] = useState<number>();
  const list = sources.data ?? [];
  // The primary paycheck is listed first, and is the likeliest.
  const sourceId = list.find((source) => source.id === chosen)?.id ?? list[0]?.id;

  return (
    <Modal title="Upload a pay stub or receipt" icon={Upload} onClose={onClose}>
      <div className="space-y-4">
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

        <FormSection title="A receipt">
          <p className="text-muted">
            A receipt is kept with the expense it is for. Add the expense and attach the receipt as you go; for a bill already on a pay
            period, open its row there and add the file.
          </p>
          <button type="button" onClick={onReceipt} className={BUTTON_SECONDARY}>
            Add the expense it is for
          </button>
        </FormSection>
      </div>
    </Modal>
  );
}
