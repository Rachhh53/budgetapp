import { Upload } from "lucide-react";
import { type DragEvent, useId, useState } from "react";
import { useNavigate } from "react-router";
import { ACCEPTED_STUBS, problemWithStub, type StubApply, useUploadStub } from "../../api/payStubs";

/**
 * Where a pay stub is added: drop the PDF or browse for it. The stub is read on this PC and
 * its review page opens straight away; nothing is changed until it is saved there.
 *
 * @param apply what saving it is expected to change, when it is added for one paycheck
 * @param label what the box says; "Drop a pay stub here or browse" unless told otherwise
 * @param onUploaded for a dialog this sits in, which is done once the stub's page opens
 */
export function StubUpload({
  sourceId,
  apply,
  label = "Drop a pay stub here or browse",
  onUploaded,
}: {
  sourceId: number;
  apply?: StubApply;
  label?: string;
  onUploaded?: () => void;
}) {
  const inputId = useId();
  const navigate = useNavigate();
  const upload = useUploadStub();
  const [problem, setProblem] = useState<string>();
  const [over, setOver] = useState(false);

  const add = (picked: readonly File[]) => {
    const file = picked[0];
    if (!file) {
      return;
    }
    const found = problemWithStub(picked);
    setProblem(found ?? undefined);
    if (found === null) {
      upload.mutate(
        { sourceId, file },
        {
          onSuccess: (stub) => {
            onUploaded?.();
            navigate(`/income/${sourceId}/stubs/${stub.id}${apply ? `?apply=${apply}` : ""}`);
          },
        },
      );
    }
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    const dropped = [...event.dataTransfer.files];
    if (dropped.length === 0) {
      // Dragged out of a web page, what arrives is a link, and Budget never goes out to fetch anything.
      setProblem(
        "That came across as a link, not a file, so there was nothing to read. Download the stub first, then drop it here from its folder, or choose it with browse.",
      );
      return;
    }
    add(dropped);
  };

  const failure = problem ?? upload.error?.message;
  return (
    <div>
      {/* A label, so the whole dashed box is the file chooser's button; dropping on it does the same. */}
      <label
        htmlFor={inputId}
        onDragOver={(event) => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={`flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg border border-dashed px-3 py-2 text-center font-medium has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent-ink ${
          over ? "border-accent bg-bg" : "border-line"
        }`}
      >
        <Upload aria-hidden className="size-4 shrink-0" />
        {upload.isPending ? "Reading the stub…" : label}
        <input
          id={inputId}
          type="file"
          // The stub is kept as it is added: a dialog this sits in has nothing unsaved because of it.
          data-saves-itself
          accept={ACCEPTED_STUBS}
          disabled={upload.isPending}
          onChange={(event) => {
            add([...(event.target.files ?? [])]);
            // So choosing the same file again is still a change.
            event.target.value = "";
          }}
          className="sr-only"
        />
      </label>
      {failure && (
        <p role="alert" className="mt-2 text-danger">
          {failure}
        </p>
      )}
    </div>
  );
}
