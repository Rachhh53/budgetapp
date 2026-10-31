import { type QueryClient, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { api } from "./client";
import { PAY_KEY } from "./income";
import { ITEMS_KEY } from "./items";

/**
 * Statement uploads (Rachel, 2026-10-25, made simpler 2026-10-30): a card or bank statement is read on this PC. Each
 * charge is put on a budget of its pay period, matched to a bill or one-off already in Budget, or chosen to be added as
 * a one-off, and nothing changes in the pay periods until Apply. A budget becomes what was spent on it plus what is still
 * to come for days no statement covers yet. Called uploads because a card's own statements (its balance each month) are
 * something else.
 */

/** What a statement line is, as far as the statement says. Payments and transfers are left out until put back. */
export type LineKind = "charge" | "refund" | "payment" | "transfer";

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
  /** Only a one-off uses these. */
  categoryId: number | null;
  tagIds: number[];
  /** The one-off Apply added it as; `null` until then. */
  oneOffEntryId: number | null;
  /** Chosen to be added as a one-off on the next Apply. */
  addOneOff: boolean;
  /** The pay period the day it was spent falls in; `null` outside every period. */
  periodStart: string | null;
  /** What Budget already had that it is, as its pay period's `matchable` lists it; `null` when it isn't matched. */
  matched: LineMatchChosen | null;
  /** The budget it is put on; `null` for none. */
  budgetItemId: number | null;
  /** The budget its payee's charges went on last time, when that budget has a date in its pay period; a suggestion only. */
  suggestedItemId: number | null;
}

/** A recurring item's date (`itemId` and `scheduledDate`) or a one-off entry (`entryId`). */
export interface LineMatch {
  itemId: number | null;
  scheduledDate: string | null;
  entryId: number | null;
}

/** A line's match, and whether Apply has written it to the bill yet. */
export interface LineMatchChosen extends LineMatch {
  applied: boolean;
}

/**
 * Something already in Budget, paid from the account in a charge's pay period or the one either side, that the charge can
 * be matched to: a recurring item's date other than a budget, or a one-off spent from the account (Rachel, 2026-10-30).
 */
export interface Matchable extends LineMatch {
  name: string;
  /** The day it is paid by, or a one-off's day. */
  date: string;
  amountCents: number;
  paid: boolean;
  /** The pay period it is in now. */
  periodStart: string;
  /** The charge of this account's statements already matched to it; `null` for none. */
  matchedLineId: number | null;
}

/** True when `match` names `candidate`. */
export function isMatch(match: LineMatch | null, candidate: LineMatch): boolean {
  return (
    match !== null &&
    (match.entryId !== null
      ? match.entryId === candidate.entryId
      : match.itemId === candidate.itemId && match.scheduledDate === candidate.scheduledDate)
  );
}

/** Open: not left out, not a repeat, not a one-off (added or chosen) and not matched. */
export function isOpen(line: StatementLine): boolean {
  return !line.leftOut && line.duplicateOf === null && line.oneOffEntryId === null && !line.addOneOff && line.matched === null;
}

export type StatementFormat = "csv" | "ofx" | "pdf";

/**
 * Whether every row read adds up to the statement's own balances (Rachel, 2026-10-30: "make sure the rows you show me
 * for the statement add up to the statement balance"). On a card the balances are what is owed.
 */
export interface StatementCheck {
  previousCents: number | null;
  newCents: number | null;
  /** The statement's own total of charges (withdrawals on a bank account), as typed; `null` when not. */
  statementChargesCents: number | null;
  /** The statement's own total of payments and credits (deposits on a bank account), as typed; `null` when not. */
  statementCreditsCents: number | null;
  /** Every row read that is money spent, added up by Budget. */
  spentCents: number;
  /** Every row read that is money back (refunds, payments, deposits), added up by Budget, as a positive amount. */
  backCents: number;
  /** The previous balance moved by every row; `null` without a previous balance. */
  expectedNewCents: number | null;
  /** The new balance less that: zero when it adds up, `null` without both balances. */
  differenceCents: number | null;
}

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
  check: StatementCheck;
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

/**
 * A budget with a date in the period: what a charge in it can be put on. A budget paid from another account takes the
 * charge too; it comes off that budget's still to come and lands as a one-off on this account on Apply.
 */
export interface BudgetChoice {
  itemId: number;
  name: string;
  accountId: number;
  accountName: string;
}

/** What a budget date took from another account's statements: one one-off there, made or updated on Apply. */
export interface BudgetElsewhere {
  accountId: number;
  accountName: string;
  cents: number;
}

/** How much of a budget date's days the account's statements cover. */
export type Covered = "fully" | "partly" | "none";

/** One budget date and what Apply makes it: what was spent on it plus what is still to come. */
export interface BudgetDateChange {
  itemId: number;
  name: string;
  /** The account the budget is paid from, which may not be the statement's. */
  accountId: number;
  accountName: string;
  scheduledDate: string;
  /** The days whose charges it takes. */
  from: string;
  to: string;
  covered: Covered;
  /** The last day of it the statements cover, when they cover only some. */
  coveredThrough: string | null;
  currentCents: number;
  budgetedCents: number;
  actualCents: number;
  stillToComeCents: number;
  /** Still to come is what was typed, not worked out. */
  stillTyped: boolean;
  /** The budget's own amount after Apply: what its own account's statements spent plus still to come. */
  newCents: number;
  /** All the charges on it, from every account. */
  lineIds: number[];
  /** What other accounts' statements spent on it, each a one-off on that account. */
  elsewhere: BudgetElsewhere[];
}

/** A line of the period matched to something already in Budget. */
export interface PeriodMatch {
  lineId: number;
  description: string;
  amountCents: number;
  txnDate: string;
  name: string;
  /** A bill's date, which takes the charge's amount; otherwise a one-off, only linked. */
  bill: boolean;
  applied: boolean;
}

export interface PeriodChanges {
  start: string;
  end: string;
  current: boolean;
  /** The days of it this account's statements cover; `partly` when some are on none of them. */
  coverage: { from: string; to: string; partly: boolean } | null;
  budgets: BudgetChoice[];
  /** Each budget date with charges on it, and any a statement set before with none on it now. */
  changes: BudgetDateChange[];
  /** What its charges can be matched to, by date. */
  matchable: Matchable[];
  matches: PeriodMatch[];
  /** Lines chosen to be added as one-offs, not added yet. */
  oneOffs: number[];
  /** Bill dates whose match was let go of, to be put back. */
  restores: number;
  /** This statement's lines in it not yet put anywhere. */
  open: number[];
  /** Nothing to apply. */
  upToDate: boolean;
}

/** A charge of any statement of the account put on a budget. */
export interface ChargeLine {
  id: number;
  uploadId: number;
  uploadName: string;
  /** The statement's account, which for a budget paid from another account is not the budget's. */
  accountId: number;
  txnDate: string;
  description: string;
  amountCents: number;
}

export interface StatementChanges {
  uploadId: number;
  accountId: number;
  periods: PeriodChanges[];
  /** Lines of this statement in no pay period. */
  outside: number[];
  lines: ChargeLine[];
}

/** A budget date a statement set, as the pay period shows it. */
export interface StatementBudget {
  itemId: number;
  scheduledDate: string;
  budgetedCents: number;
  actualCents: number;
  stillToComeCents: number;
  /** Spent on it from other accounts' statements, as one-offs on those accounts. */
  elsewhereCents: number;
  /** What the statement set it to; the figures apply only while the amount is still that. */
  appliedCents: number;
  charges: ChargeLine[];
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
}

/** Under the pay key: applying a statement changes item amounts and everything worked out from them. */
export const STATEMENT_UPLOADS_KEY = [...PAY_KEY, "statement-uploads"] as const;
const STATEMENT_BUDGETS_KEY = [...PAY_KEY, "statement-budgets"] as const;

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

/** The budget dates statements set from `from` through `to`, for the pay period to show what made them. */
export function useStatementBudgets(from: string | undefined, to: string | undefined) {
  return useQuery({
    queryKey: [...STATEMENT_BUDGETS_KEY, from, to],
    queryFn: () => api.get<StatementBudget[]>(`/api/statement-budgets?from=${from}&to=${to}`),
    enabled: from !== undefined && to !== undefined,
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

/** Changes one line: what was read, whether it is left out, and the category and tags a one-off takes. */
export function useChangeStatementLine(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lineId, input }: { lineId: number; input: LineInput }) =>
      api.put<{ upload: StatementUpload; alsoFiled: number }>(`/api/statement-uploads/${uploadId}/lines/${lineId}`, input),
    onSuccess: ({ upload }) => keepChoice(queryClient, uploadId, upload),
  });
}

/** Puts charges on a budget, or with `itemId` null takes them off. Nothing changes until Apply. */
export function usePutOnBudget(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { lineIds: number[]; itemId: number | null }) => api.put<StatementUpload>(`/api/statement-uploads/${uploadId}/budget`, input),
    onSuccess: (upload) => keepChoice(queryClient, uploadId, upload),
  });
}

/**
 * Gives several charges one category and the same tags at once; each payee remembers them for the next statement. With
 * `oneOffs` they are all added as one-offs on Apply too.
 */
export function useFileStatementLines(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { lineIds: number[]; categoryId: number | null; tagIds: number[]; oneOffs: boolean }) =>
      api.put<StatementUpload>(`/api/statement-uploads/${uploadId}/filing`, input),
    onSuccess: (upload) => keepChoice(queryClient, uploadId, upload),
  });
}

/**
 * Chooses what a charge already is in Budget. On Apply a recurring item's date takes the charge as its amount and is
 * ticked paid on the day it was spent; a one-off is only linked.
 */
export function useMatchStatementLine(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lineId, match }: { lineId: number; match: LineMatch }) =>
      api.put<StatementUpload>(
        `/api/statement-uploads/${uploadId}/lines/${lineId}/match`,
        match.entryId !== null ? { entryId: match.entryId } : { itemId: match.itemId, scheduledDate: match.scheduledDate },
      ),
    onSuccess: (upload) => keepChoice(queryClient, uploadId, upload),
  });
}

/** Lets go of a charge's match; a bill's date Apply already wrote is put back on the next Apply. */
export function useUnmatchStatementLine(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (lineId: number) => api.delete<StatementUpload>(`/api/statement-uploads/${uploadId}/lines/${lineId}/match`),
    onSuccess: (upload) => keepChoice(queryClient, uploadId, upload),
  });
}

/** Chooses a charge to be added as a one-off on Apply, or (`add` false) takes that back. */
export function useStatementOneOff(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lineId, add }: { lineId: number; add: boolean }) =>
      add
        ? api.post<StatementUpload>(`/api/statement-uploads/${uploadId}/lines/${lineId}/one-off`, {})
        : api.delete<StatementUpload>(`/api/statement-uploads/${uploadId}/lines/${lineId}/one-off`),
    onSuccess: (upload) => keepChoice(queryClient, uploadId, upload),
  });
}

/** Records the statement's balances, typed off the paper statement; `null` leaves one unsaid. */
export function useStatementBalances(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { previousCents: number | null; newCents: number | null; chargesCents: number | null; creditsCents: number | null }) =>
      api.put<StatementUpload>(`/api/statement-uploads/${uploadId}/balances`, input),
    onSuccess: (upload) => queryClient.setQueryData([...STATEMENT_UPLOADS_KEY, uploadId], upload),
  });
}

/** Types still to come for one of the statement's budget dates, or (`cents` null) works it out again. Waits for Apply. */
export function useStatementStillToCome(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { itemId: number; scheduledDate: string; cents: number | null }) =>
      api.put<StatementChanges>(`/api/statement-uploads/${uploadId}/still-to-come`, input),
    onSuccess: (changes) => queryClient.setQueryData([...STATEMENT_UPLOADS_KEY, uploadId, "changes"], changes),
  });
}

/** Makes every choice in the chosen pay periods real. */
export function useApplyStatement(uploadId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { periods: string[] }) =>
      api.post<{ amountsChanged: number; changes: StatementChanges }>(`/api/statement-uploads/${uploadId}/apply`, input),
    onSuccess: () => refreshAfterChange(queryClient),
  });
}

/** Types still to come for a budget date from the pay period: its amount changes straight away. */
export function useStillToComeOnPeriod() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ itemId, scheduledDate, cents }: { itemId: number; scheduledDate: string; cents: number | null }) =>
      api.put<StatementBudget>(`/api/statement-budgets/${itemId}/${scheduledDate}/still-to-come`, { cents }),
    onSuccess: () => refreshAfterChange(queryClient),
  });
}

/** A choice on a statement changes only the statements' own figures, which every statement of the account shares. */
function keepChoice(queryClient: QueryClient, uploadId: number, upload: StatementUpload) {
  queryClient.setQueryData([...STATEMENT_UPLOADS_KEY, uploadId], upload);
  return queryClient.invalidateQueries({ queryKey: STATEMENT_UPLOADS_KEY });
}

/** What Apply can touch: pay periods (the statements' own figures among them), items and accounts. */
function refreshAfterChange(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: PAY_KEY }),
    queryClient.invalidateQueries({ queryKey: ITEMS_KEY }),
    queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
  ]);
}

/** Removes a statement. What it changed stays; Undo straight afterwards brings it back. */
export function useDeleteStatementUpload() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (uploadId: number) => api.delete<{ done: boolean }>(`/api/statement-uploads/${uploadId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: STATEMENT_UPLOADS_KEY }),
  });
}
