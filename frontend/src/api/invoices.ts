import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { PAY_KEY } from "./income";

/**
 * Billed later (spec §4.6): visits that build up with no invoice yet, shown on the current pay
 * period until an invoice is entered for them; the invoice is then paid like any bill.
 */

/** Visits of one billed-later item that no invoice covers yet, up to today. */
export interface Unbilled {
  itemId: number;
  name: string;
  payFromAccountId: number;
  visits: number;
  amountCents: number;
  /** The first of them. */
  since: string;
  /** The last of them. */
  through: string;
}

export interface Invoice {
  id: number;
  itemId: number;
  invoiceDate: string;
  dueDate: string;
  /** The last visit it pays for. */
  coversThrough: string;
  amountCents: number;
  /** What its visits came to when it was entered. */
  projectedCents: number;
  paid: boolean;
  paidDate: string | null;
  confirmationCode: string | null;
  checkNumber: string | null;
  note: string | null;
}

export interface InvoiceView {
  invoice: Invoice;
  /** The pay period that pays it; `null` when none holds its due date. */
  periodStart: string | null;
}

export interface Visit {
  scheduledDate: string;
  date: string;
  amountCents: number;
}

export interface Billing {
  /** Up to today, oldest first. */
  notBilled: Visit[];
  notBilledCents: number;
  /** Newest first. */
  invoices: InvoiceView[];
}

export interface InvoiceInput {
  invoiceDate: string;
  dueDate: string;
  /** The last visit it is for; left out, every visit up to the invoice date. */
  coversThrough?: string;
  /** Left out, what those visits come to. */
  amountCents?: number;
  note?: string;
}

const billingKey = (itemId: number) => [...PAY_KEY, "invoices", itemId];

export function useBilling(itemId: number, enabled = true) {
  return useQuery({
    queryKey: billingKey(itemId),
    queryFn: () => api.get<Billing>(`/api/items/${itemId}/invoices`),
    enabled,
  });
}

/** An invoice for the visits up to a day. Everything that follows the money (periods, balances, reports) is asked again. */
export function useAddInvoice(itemId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: InvoiceInput) => api.post<InvoiceView>(`/api/items/${itemId}/invoices`, input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}

/** Removes the newest invoice: its visits are not billed again. Undo brings it back. */
export function useDeleteInvoice(itemId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (invoiceId: number) => api.delete<{ deleted: boolean }>(`/api/items/${itemId}/invoices/${invoiceId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PAY_KEY }),
  });
}
