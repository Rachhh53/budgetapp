import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type Saved } from "./client";
import { type Deduction, type EmployerMatch, PAY_KEY } from "./income";

/** Where a line sits on a stub. */
export const STUB_SECTIONS = ["earnings", "pre_tax", "tax", "after_tax", "net", "employer"] as const;
export type StubSection = (typeof STUB_SECTIONS)[number];

/** What a line is tracked as in the budget. */
export type StubTracking = "gross" | "contribution" | "benefit" | "tax" | "net" | "match" | "ignore";

/** What saving a stub is asked to change (decided 2026-10-12: asked each time). */
export const STUB_APPLIES = ["correct", "pay_change", "paycheck", "none"] as const;
export type StubApply = (typeof STUB_APPLIES)[number];

/** One line read from a stub. Amounts are whole cents; what is taken out is a positive amount. */
export interface StubLine {
  /** `null` for a line of a stub that was only read, not kept. */
  id: number | null;
  section: StubSection;
  label: string;
  amountCents: number;
  /** The year to date, when the stub gives one. */
  ytdCents: number | null;
  tracksAs: StubTracking;
  /** Where a contribution or the employer's match goes; `null` until chosen, and for anything else. */
  accountId: number | null;
  /** False for a line the reader had to guess at, until someone has looked at it. */
  sure: boolean;
}

/** A line as it is sent back: the same, without its id. */
export type StubLineInput = Omit<StubLine, "id">;

/** Whether the stub adds up: gross less everything taken out should be the net pay. */
export interface StubTotals {
  grossCents: number;
  preTaxCents: number;
  taxesCents: number;
  afterTaxCents: number;
  deductionsCents: number;
  netCents: number;
  employerCents: number;
  /** What is left over; zero when it adds up. */
  differenceCents: number;
  balanced: boolean;
}

export interface PayStub {
  id: number;
  incomeSourceId: number;
  sourceName: string;
  /** What its file was called when it was added. */
  name: string;
  /** `null` until the stub's date is read or typed. */
  payDate: string | null;
  status: "review" | "saved";
  /** What saving it changed; `null` while in review. */
  applied: StubApply | null;
  pages: number;
  sizeBytes: number;
  lines: StubLine[];
  totals: StubTotals;
  /** How many lines still want a second look. */
  toCheck: number;
  /** The paycheck it is for; `null` when the source has none near its date. */
  paycheck: { scheduledDate: string; payDate: string; netCents: number } | null;
  /** What the source's pay becomes if the stub is saved onto it; `null` until it has a net pay line. */
  wouldSet: { grossCents: number | null; netCents: number; deductions: Deduction[]; match: EmployerMatch | null } | null;
}

/** A stub in a source's list. */
export interface PayStubSummary {
  id: number;
  name: string;
  payDate: string | null;
  status: "review" | "saved";
  applied: StubApply | null;
  grossCents: number;
  netCents: number;
  toCheck: number;
}

/** What a stub says, read without keeping it. */
export interface StubRead {
  payDate: string | null;
  lines: StubLine[];
  totals: StubTotals;
}

/** Under the pay key: saving a stub changes paychecks, and everything worked out from them. */
export const STUBS_KEY = [...PAY_KEY, "pay-stubs"] as const;

/** A stub is a PDF: the kind a payroll site lets you download. */
export const ACCEPTED_STUBS = "application/pdf,.pdf";

/** Says at once, in words, when a file chosen as a stub cannot be one; `null` when it can go. */
export function problemWithStub(files: readonly File[]): string | null {
  if (files.length !== 1) {
    return "Please choose one pay stub at a time.";
  }
  const file = files[0];
  if (file && !/\.pdf$/i.test(file.name) && file.type !== "application/pdf") {
    return "A pay stub needs to be a PDF, the kind your payroll site lets you download. Photos and scans can't be read yet.";
  }
  return null;
}

export function stubPreviewUrl(stubId: number, page = 1): string {
  return `/api/pay-stubs/${stubId}/preview?page=${page}`;
}

export function usePayStubs(sourceId: number) {
  return useQuery({
    queryKey: [...STUBS_KEY, "source", sourceId],
    queryFn: () => api.get<PayStubSummary[]>(`/api/income-sources/${sourceId}/pay-stubs`),
  });
}

/** `stubId` undefined asks for nothing: for a card that shows the latest stub, when there is none. */
export function usePayStub(stubId: number | undefined) {
  return useQuery({
    queryKey: [...STUBS_KEY, stubId],
    queryFn: () => api.get<PayStub>(`/api/pay-stubs/${stubId}`),
    enabled: stubId !== undefined,
  });
}

/** Adds a stub's PDF to an income source. It is read on this PC and waits in review. */
export function useUploadStub() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ sourceId, file }: { sourceId: number; file: File }) =>
      api.upload<PayStub>(`/api/income-sources/${sourceId}/pay-stubs`, [file]),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: STUBS_KEY }),
  });
}

/** Reads a stub without keeping it, to fill in an income source that does not exist yet. */
export function useReadStub() {
  return useMutation({ mutationFn: (file: File) => api.upload<StubRead>("/api/pay-stubs/read", [file]) });
}

/** Sends a stub in review back with its date and every line as they should now be. */
export function useChangeStub(stubId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { payDate: string | null; lines: StubLineInput[] }) => api.put<PayStub>(`/api/pay-stubs/${stubId}`, input),
    onSuccess: (stub) => {
      queryClient.setQueryData([...STUBS_KEY, stubId], stub);
      return queryClient.invalidateQueries({ queryKey: [...STUBS_KEY, "source"] });
    },
  });
}

export function useSaveStub(stubId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (apply: StubApply) => api.post<PayStub>(`/api/pay-stubs/${stubId}/save`, { apply }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}

/** Removes a stub. What it changed when it was saved stays; Undo straight afterwards brings the stub back. */
export function useDeleteStub() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (stubId: number) => api.delete<{ done: boolean }>(`/api/pay-stubs/${stubId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: STUBS_KEY }),
  });
}

/** "Save a copy" of the stub's PDF, as for a receipt: into Downloads from this PC, a download from anywhere else. */
export function useSaveStubCopy() {
  return useMutation({
    mutationFn: async (stub: { id: number; name: string }): Promise<Saved | null> => {
      const { saved } = await api.post<{ saved: Saved | null }>(`/api/pay-stubs/${stub.id}/copy`, undefined);
      if (saved === null) {
        const link = document.createElement("a");
        link.href = `/api/pay-stubs/${stub.id}/file`;
        link.download = stub.name;
        document.body.append(link);
        link.click();
        link.remove();
      }
      return saved;
    },
  });
}
