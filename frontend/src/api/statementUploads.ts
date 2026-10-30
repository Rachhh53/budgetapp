import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { api } from "./client";
import { PAY_KEY } from "./income";
import { ITEMS_KEY } from "./items";

/**
 * Statement uploads (Rachel, 2026-10-25): a card or bank statement is read on this PC, its charges filed by category
 * and tag, and totalled by pay period. Each total changes only the budget or recurring item she chooses for it,
 * replacing its amount or adding to it. Called uploads because a card's own statements (its balance each month) are
 * something else.
 */

/** What a statement line is, as far as the statement says. Payments and transfers are left out until put back. */
export type LineKind = "charge" | "refund" | "payment" | "transfer";

export type GroupType = "category" | "tag";

/** A category or a tag, which charges are totalled by. */
export interface Group {
  type: GroupType;
  id: number;
}

export type ReplaceMode = "replace" | "add";

export interface StatementLine {
  id: number;
  /** The day it was spent, which decides its pay period. */
  txnDate: string;
  /** The day the bank recorded it, when the statement gives both. */
  postedDate: string | null;
  description: string;
  /** Spent as positive; money back (a refund, a payment) negative. */
  amountCents: number;
  kind: LineKind;
  leftOut: boolean;
  /** False for a line the reader had to guess at, until it has been looked at. */
  sure: boolean;
  /** The line of an earlier statement of this account it repeats, which is counted instead. */
  duplicateOf: number | null;
  categoryId: number | null;
  tagIds: number[];
  /** When its category and a tag are both pointed at items, the one it counts toward. */
  countsToward: Group | null;
  /** Added as a one-off of its own, so it counts as that and toward no total. */
  oneOffEntryId: number | null;
  /** The pay period the day it was spent falls in; `null` outside every period. */
  periodStart: string | null;
  /** A payee Budget has no category or tags for yet. */
  newPayee: boolean;
}

export type StatementFormat = "csv" | "ofx" | "pdf";

export interface StatementUpload {
  id: number;
  accountId: number;
  accountName: string;
  onCard: boolean;
  name: string;
  format: StatementFormat;
  from: string;
  to: string;
  appliedAt: string | null;
  /** A kept PDF's pages; `null` for a CSV or OFX file, which is not kept. */
  pages: number | null;
  lines: StatementLine[];
  toCheck: number;
  /** CSV rows that could not be read, said once, when the file is added. */
  skippedRows: number[];
}

export interface StatementUploadSummary {
  id: number;
  name: string;
  format: StatementFormat;
  from: string;
  to: string;
  appliedAt: string | null;
  lineCount: number;
  toCheck: number;
}

/** Which CSV column holds what, counted from 0. */
export interface CsvLayout {
  hasHeader: boolean;
  dateColumn: number;
  postedColumn: number | null;
  descriptionColumn: number;
  /** One column of signed amounts; `null` when spending and money back have a column each. */
  amountColumn: number | null;
  debitColumn: number | null;
  creditColumn: number | null;
  dateOrder: "mdy" | "dmy" | "ymd";
  /** In the one amount column, money spent is written negative. */
  chargesNegative: boolean;
}

export interface CsvColumns {
  headers: string[];
  sample: string[][];
  guess: CsvLayout;
  /** The layout was remembered from an earlier download from the same bank. */
  known: boolean;
}

export interface GroupTotal {
  type: GroupType;
  id: number;
  name: string;
  color: string | null;
  totalCents: number;
  lineIds: number[];
  /** The item Rachel pointed it at in this period, and how; `null` for none. */
  target: { itemId: number; mode: ReplaceMode } | null;
  /** The item it was last pointed at, offered as a suggestion; never chosen by itself. */
  hintItemId: number | null;
}

/** A budget or recurring item paid from the account with a date in the period. */
export interface ItemChoice {
  itemId: number;
  name: string;
  kind: "bill" | "budget";
  categoryId: number | null;
  tagIds: number[];
  dates: { scheduledDate: string; countsOn: string; currentCents: number; estimate: boolean }[];
}

export interface AmountChange {
  itemId: number;
  scheduledDate: string;
  currentCents: number;
  newCents: number;
  lineIds: number[];
}

export interface PeriodChanges {
  start: string;
  end: string;
  current: boolean;
  /** The days of it this account's statements cover; `partly` when some are on none of them. */
  coverage: { from: string; to: string; partly: boolean } | null;
  groups: GroupTotal[];
  items: ItemChoice[];
  changes: AmountChange[];
  /** Charges two chosen items both claim, waiting for a choice of which. */
  undecided: number[];
  /** Charges no chosen item takes. */
  unmatched: number[];
  /** Every change is already the amount it would set. */
  upToDate: boolean;
}

/** A line of any statement of the account that a period's totals count. */
export interface CountedLine {
  id: number;
  uploadId: number;
  uploadName: string;
  txnDate: string;
  description: string;
  amountCents: number;
  categoryId: number | null;
  tagIds: number[];
  countsToward: Group | null;
}

export interface StatementChanges {
  uploadId: number;
  accountId: number;
  periods: PeriodChanges[];
  /** Lines of this statement in no pay period. */
  outside: number[];
  lines: CountedLine[];
}

/** A line as it is changed on the review page. */
export interface LineInput {
  txnDate?: string;
  description?: string;
  amountCents?: number;
  kind?: LineKind;
  leftOut?: boolean;
  categoryId: number | null;
  tagIds: number[];
  countsToward?: Group | null;
}

/** Under the pay key: applying a statement changes item amounts and everything worked out from them. */
export const STATEMENT_UPLOADS_KEY = [...PAY_KEY, "statement-uploads"] as const;

/** What a statement can be: a CSV or OFX/QFX download, or a PDF statement. */
export const ACCEPTED_STATEMENTS = ".csv,.ofx,.qfx,.pdf,text/csv,application/pdf,application/x-ofx";

/** Says at once, in words, when a file chosen cannot be a statement; `null` when it can go. */
export function problemWithStatement(files: readonly File[]): string | null {
  if (files.length !== 1) {
    return "Please choose one statement at a time.";
  }
  const file = files[0];
  if (file && !/\.(csv|ofx|qfx|pdf|txt)$/i.test(file.name)) {
    return "Budget can read a statement saved as CSV, OFX or QFX (from your bank's \"download transactions\"), or a PDF statement.";
  }
  return null;
}

/** A CSV is read only once its columns are known; anything else says what it is by itself. */
export function isCsv(file: File): boolean {
  return /\.(csv|txt)$/i.test(file.name) || file.type === "text/csv";
}

export function statementPreviewUrl(uploadId: number, page = 1): string {
  return `/api/statement-uploads/${uploadId}/preview?page=${page}`;
}

export function useStatementUploads(accountId: number) {
  return useQuery({
    queryKey: [...STATEMENT_UPLOADS_KEY, "account", accountId],
    queryFn: () => api.get<StatementUploadSummary[]>(`/api/accounts/${accountId}/statement-uploads`),
  });
}

export function useStatementUpload(uploadId: number | undefined) {
  return useQuery({
    queryKey: [...STATEMENT_UPLOADS_KEY, uploadId],
    queryFn: () => api.get<StatementUpload>(`/api/statement-uploads/${uploadId}`),
    enabled: uploadId !== undefined,
  });
}

export function useStatementChanges(uploadId: number | undefined) {
  return useQuery({
    queryKey: [...STATEMENT_UPLOADS_KEY, uploadId, "changes"],
    queryFn: () => api.get<StatementChanges>(`/api/statement-uploads/${uploadId}/changes`),
    enabled: uploadId !== undefined,
  });
}

/** A CSV's columns and a first guess at which is which; nothing is kept. */
export function useCsvColumns() {
  return useMutation({
    mutationFn: ({ accountId, file }: { accountId: number; file: File }) =>
      api.upload<CsvColumns>(`/api/accounts/${accountId}/statement-uploads/columns`, [file]),
  });
}

/** Adds a statement to an account; a CSV goes with the layout it is read by. */
export function useUploadStatement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ accountId, file, layout }: { accountId: number; file: File; layout?: CsvLayout }) =>
      api.upload<StatementUpload>(
        `/api/accounts/${accountId}/statement-uploads`,
        [file],
        layout ? { layout: JSON.stringify(layout) } : undefined,
      ),
    onSuccess: (upload) => {
      queryClient.setQueryData([...STATEMENT_UPLOADS_KEY, upload.id], upload);
      return queryClient.invalidateQueries({ queryKey: [...STATEMENT_UPLOADS_KEY, "account"] });
    },
  });
}

/** Changes one line: its category, tags, what it is, whether it is left out, which total it counts toward. */
export function useChangeStatementLine(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lineId, input }: { lineId: number; input: LineInput }) =>
      api.put<{ upload: StatementUpload; alsoFiled: number }>(`/api/statement-uploads/${uploadId}/lines/${lineId}`, input),
    onSuccess: ({ upload }) => {
      queryClient.setQueryData([...STATEMENT_UPLOADS_KEY, uploadId], upload);
      // Every statement of the account shares its payees and totals.
      return queryClient.invalidateQueries({ queryKey: STATEMENT_UPLOADS_KEY });
    },
  });
}

/** Points a category's or tag's total in one pay period at an item, or with `itemId` null at nothing. */
export function useSetStatementTarget(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { periodStart: string; groupType: GroupType; groupId: number; itemId: number | null; mode: ReplaceMode }) =>
      api.put<StatementChanges>(`/api/statement-uploads/${uploadId}/targets`, input),
    onSuccess: (changes) => {
      queryClient.setQueryData([...STATEMENT_UPLOADS_KEY, uploadId, "changes"], changes);
      return queryClient.invalidateQueries({ queryKey: STATEMENT_UPLOADS_KEY });
    },
  });
}

/** Makes the chosen pay periods' changes, and adds the chosen charges as one-offs of their own. */
export function useApplyStatement(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { periods: string[]; oneOffLineIds: number[] }) =>
      api.post<{ amountsChanged: number; oneOffsAdded: number; changes: StatementChanges }>(`/api/statement-uploads/${uploadId}/apply`, input),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: PAY_KEY }),
        queryClient.invalidateQueries({ queryKey: ITEMS_KEY }),
        queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      ]),
  });
}

/** Removes a statement. What it changed stays; Undo straight afterwards brings it back. */
export function useDeleteStatementUpload() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (uploadId: number) => api.delete<{ done: boolean }>(`/api/statement-uploads/${uploadId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: STATEMENT_UPLOADS_KEY }),
  });
}
