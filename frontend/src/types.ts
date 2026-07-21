export type AccountType = 'BANK' | 'CASH' | 'CRYPTO';
export type CategoryType = 'EXPENSE' | 'INCOME';
export type TransactionType = 'EXPENSE' | 'INCOME' | 'TRANSFER';
export type TransactionSource = 'MANUAL' | 'IMPORT';

export interface Bank {
  id: number;
  name: string;
  parserId: string | null;
  accountCount: number;
}

export interface Account {
  id: number;
  name: string;
  currency: string;
  type: AccountType;
  bankId: number | null;
  bankName: string | null;
  accountNumber: string | null;
  startBalance: number;
  startDate: string;
  archived: boolean;
  balance: number;
}

export interface Category {
  id: number;
  name: string;
  type: CategoryType;
  color: string;
  system: boolean;
  archived: boolean;
}

export interface Transaction {
  id: number;
  type: TransactionType;
  date: string;
  time: string | null;
  reference: string | null;
  accountId: number;
  accountName: string;
  accountCurrency: string;
  amount: number;
  categoryId: number | null;
  categoryName: string | null;
  categoryColor: string | null;
  description: string | null;
  originalCurrency: string | null;
  originalAmount: number | null;
  counterAccountId: number | null;
  counterAccountName: string | null;
  counterAccountCurrency: string | null;
  counterAmount: number | null;
  source: TransactionSource;
}

export interface TransactionRequest {
  type: TransactionType;
  date: string;
  accountId: number;
  amount: number;
  categoryId?: number | null;
  description?: string | null;
  originalCurrency?: string | null;
  originalAmount?: number | null;
  counterAccountId?: number | null;
  counterAmount?: number | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface FxRates {
  base: string;
  rates: Record<string, number>;
  updatedAt: string;
}

export interface CurrencyTotal {
  currency: string;
  expense: number;
  income: number;
}

export interface DayTotal {
  date: string;
  expense: number | null;
  income: number | null;
  byCurrency: CurrencyTotal[] | null;
}

export interface DayTotalsResponse {
  currency: string;
  incomplete: boolean;
  days: DayTotal[];
}

export interface PeriodTotals {
  expense: number;
  income: number;
}

export interface Summary {
  currency: string;
  incomplete: boolean;
  day: PeriodTotals;
  week: PeriodTotals;
  month: PeriodTotals;
}

export interface TransactionFilters {
  from?: string;
  to?: string;
  accountId?: number;
  categoryId?: number;
  type?: TransactionType;
  q?: string;
  needsReview?: boolean;
}

// --- stage 2: import, rules, reconciliation ---

export interface CategoryRule {
  id: number;
  pattern: string;
  categoryId: number;
  categoryName: string;
  categoryType: CategoryType;
  categoryColor: string;
  archived: boolean;
}

export type MappingAction = 'MAP' | 'CREATE' | 'SKIP';
export type SuggestionKind = 'AUTO' | 'SKIP' | 'UNMAPPED';

export interface PreviewAccount {
  accountNumber: string;
  currency: string;
  txnCount: number;
  balanceStart: number | null;
  balanceEnd: number | null;
  suggestion: { kind: SuggestionKind; accountId: number | null; accountName: string | null };
}

export interface CashCurrency {
  currency: string;
  withdrawalCount: number;
  suggestedAccountId: number | null;
  suggestedAccountName: string | null;
}

export interface ImportPreview {
  token: string;
  fileName: string;
  periodFrom: string;
  periodTo: string;
  totalRows: number;
  accounts: PreviewAccount[];
  cashCurrencies: CashCurrency[];
}

export interface AccountDecision {
  accountNumber: string;
  action: MappingAction;
  accountId?: number | null;
  newAccount?: { name: string; startBalance?: number | null; startDate?: string | null } | null;
}

export interface CashDecision {
  currency: string;
  accountId?: number | null;
  createNew: boolean;
}

export interface ImportReport {
  importRunId: number;
  created: number;
  duplicates: number;
  needsReview: number;
  transfers: number;
  warnings: string[];
  accounts: {
    accountId: number;
    name: string;
    currency: string;
    balanceBefore: number;
    balanceAfter: number;
  }[];
}

export interface ImportRun {
  id: number;
  bankName: string;
  fileName: string;
  periodFrom: string;
  periodTo: string;
  created: number;
  duplicates: number;
  needsReview: number;
  transfers: number;
  createdAt: string;
}

export interface ReconciliationRow {
  accountId: number;
  name: string;
  currency: string;
  computedBalance: number;
}

export interface Correction {
  transactionId: number;
  accountId: number;
  accountName: string;
  currency: string;
  type: TransactionType;
  amount: number;
}

export interface ReconciliationHistoryEntry {
  date: string;
  corrections: Correction[];
}
