import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ACCOUNTS_KEY } from "./accounts";
import { api } from "./client";
import { PAY_KEY } from "./income";
import { ITEMS_KEY } from "./items";

/** A loan's terms. Amounts are whole cents. */
export interface LoanSettings {
  accountId: number;
  originalPrincipalCents: number | null;
  termMonths: number | null;
  startDate: string | null;
  /** The whole monthly payment, escrow included. */
  paymentCents: number;
  /** Day of the month it is due and interest is added, 1–31; 31 means the last day. */
  paymentDay: number;
  escrowInsuranceCents: number;
  escrowTaxesCents: number;
  /** The recurring item linked as its payment from the loan's page; `null` when none is. */
  paymentItemId: number | null;
}

export interface LoanInput {
  originalPrincipalCents: number | null;
  termMonths: number | null;
  startDate: string | null;
  paymentCents: number;
  paymentDay: number;
  escrowInsuranceCents: number;
  escrowTaxesCents: number;
  /** The recurring item linked as its payment from the loan's page; `null` when none is. */
  paymentItemId: number | null;
}

/** A recurring item that pays the loan: the one linked here, or a transfer into the loan. */
export interface PaidBy {
  itemId: number;
  name: string;
  linked: boolean;
}

/** How one monthly payment is shared out. `paymentCents` is the whole payment, escrow included. */
export interface PaymentSplit {
  date: string;
  paymentCents: number;
  interestCents: number;
  principalCents: number;
  escrowCents: number;
}

/** A calendar year of the payments still to come. */
export interface LoanYear {
  year: number;
  interestCents: number;
  principalCents: number;
  /** What is owed at the end of it. */
  endingCents: number;
}

/** A loan with what its terms work out to from today's balance. */
export interface Loan {
  /** `null` until the terms are entered; nothing below is worked out then. */
  settings: LoanSettings | null;
  /** The rate in force today, in basis points; `null` until one is entered. */
  rateBp: number | null;
  balanceCents: number | null;
  /** The level payment, before escrow, that pays off what was borrowed over its term. */
  standardPaymentCents: number | null;
  nextPayment: PaymentSplit | null;
  /** `null` when the payment never pays the loan off. */
  payoffDate: string | null;
  interestRemainingCents: number | null;
  /** False when the payment does not cover the interest. */
  paysOff: boolean;
  /** The recurring items that pay it. Empty until one does: the payment is then taken as made each month. */
  paidBy: PaidBy[];
  years: LoanYear[];
}

/** Under PAY_KEY: what is owed, and so the payoff date, follows everything that moves money. */
/** `enabled` is false on a page that may not be showing a loan: only a loan has terms to ask for. */
export function useLoan(accountId: number, enabled = true) {
  return useQuery({
    queryKey: [...PAY_KEY, "loan", accountId],
    queryFn: () => api.get<Loan>(`/api/accounts/${accountId}/loan`),
    enabled,
  });
}

export function useSaveLoan(accountId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: LoanInput) => api.put<Loan>(`/api/accounts/${accountId}/loan`, input),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: PAY_KEY }),
        queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      ]),
  });
}

/** Adds the loan's monthly payment as a recurring transfer into it, from the account chosen. */
export function useAddLoanPayment(accountId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { payFromAccountId: number; autopay: boolean }) => api.post<Loan>(`/api/accounts/${accountId}/loan/payment`, input),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: PAY_KEY }),
        queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
        queryClient.invalidateQueries({ queryKey: ITEMS_KEY }),
      ]),
  });
}
