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

// --- stage 3: recurring, planned, averaging, forecast ---

export type ScheduleFrequency = 'MONTHLY' | 'WEEKLY' | 'EVERY_N_DAYS';
export type AmountStrategy = 'FIXED' | 'AVERAGE';
export type PlannedStatus = 'PENDING' | 'CONFIRMED' | 'DISMISSED';
export type AveragingGranularity = 'DAILY' | 'MONTHLY';
export type CategoryFilterMode = 'INCLUDE' | 'EXCLUDE';

export interface RecurringRule {
  id: number;
  name: string;
  type: TransactionType;
  accountId: number;
  accountName: string;
  accountCurrency: string;
  counterAccountId: number | null;
  counterAccountName: string | null;
  counterAccountCurrency: string | null;
  categoryId: number | null;
  categoryName: string | null;
  categoryColor: string | null;
  scheduleFreq: ScheduleFrequency;
  dayOfMonth: number | null;
  dayOfWeek: number | null;
  intervalDays: number | null;
  startDate: string;
  endDate: string | null;
  amountStrategy: AmountStrategy;
  amount: number | null;
  counterAmount: number | null;
  avgLookback: number | null;
  archived: boolean;
  nextDue: string | null;
  currentAmount: number | null;
  approxAmount: boolean;
}

export interface RecurringRuleRequest {
  name: string;
  type: TransactionType;
  accountId: number;
  counterAccountId?: number | null;
  categoryId?: number | null;
  scheduleFreq: ScheduleFrequency;
  dayOfMonth?: number | null;
  dayOfWeek?: number | null;
  intervalDays?: number | null;
  startDate: string;
  endDate?: string | null;
  amountStrategy: AmountStrategy;
  amount?: number | null;
  counterAmount?: number | null;
  avgLookback?: number | null;
  archived?: boolean;
}

export interface QueueItem {
  kind: 'RECURRING' | 'PLANNED';
  ruleId: number | null;
  plannedId: number | null;
  name: string;
  dueDate: string;
  type: TransactionType;
  accountId: number;
  accountName: string;
  accountCurrency: string;
  counterAccountId: number | null;
  counterAccountName: string | null;
  counterAccountCurrency: string | null;
  suggestedAmount: number | null;
  suggestedCounterAmount: number | null;
  approx: boolean;
}

export interface Planned {
  id: number;
  type: TransactionType;
  dueDate: string;
  accountId: number;
  accountName: string;
  accountCurrency: string;
  counterAccountId: number | null;
  counterAccountName: string | null;
  counterAccountCurrency: string | null;
  amount: number;
  counterAmount: number | null;
  categoryId: number | null;
  categoryName: string | null;
  categoryColor: string | null;
  description: string | null;
  status: PlannedStatus;
  transactionId: number | null;
}

export interface ComputedValue {
  value: number;
  dayCount: number;
  txnCount: number;
  total: number;
  approx: boolean;
}

export interface AveragingProfile {
  id: number;
  name: string;
  accountId: number;
  accountName: string;
  accountCurrency: string;
  granularity: AveragingGranularity;
  periodFrom: string;
  periodTo: string;
  categoryFilterMode: CategoryFilterMode;
  categoryIds: number[];
  manualOverride: number | null;
  archived: boolean;
  computed: ComputedValue;
  effectiveValue: number | null;
}

export interface ForecastSeries {
  dates: string[];
  series: Record<string, number[]>;
  total: number[];
}

export interface ForecastEventItem {
  kind: 'RECURRING' | 'PLANNED' | 'PROFILE_DAILY' | 'PROFILE_MONTHLY' | 'TRANSACTION';
  refId: number;
  label: string;
  accountId: number;
  delta: number;
  counterAccountId: number | null;
  counterDelta: number | null;
  approx: boolean;
}

export interface ForecastEventDay {
  date: string;
  items: ForecastEventItem[];
  balances: Record<string, number>;
  total: number;
}

export interface ForecastResponse {
  currency: string;
  approxTotal: boolean;
  accounts: { id: number; name: string; currency: string }[];
  history: ForecastSeries;
  forecast: ForecastSeries;
  eventDays: ForecastEventDay[];
  negativeDates: { accountId: number; date: string }[];
  firstNegative: { accountId: number; date: string } | null;
}

// --- stage 4: statistics, conversion routes ---

export type StatsGranularity = 'MONTH' | 'WEEK';

export interface StatsBucket {
  periodStart: string;
  periodEnd: string;
  label: string;
  total: number;
  byCategory: { categoryId: number | null; name: string; color: string | null; amount: number }[];
}

export interface StatsCategoriesResponse {
  currency: string;
  incomplete: boolean;
  buckets: StatsBucket[];
}

export interface FactVsForecast {
  currency: string;
  forecast: number;
  actual: number;
  diff: number;
  diffPct: number | null;
  approx: boolean;
}

export interface ConversionStepDef {
  id?: number;
  position?: number;
  fromCurrency: string;
  toCurrency: string;
  rate: number;
  rateInverted: boolean;
  feeFixed: number;
  feePct: number;
}

export interface ConversionRoute {
  id: number;
  name: string;
  archived: boolean;
  steps: ConversionStepDef[];
  fromCurrency: string | null;
  toCurrency: string | null;
}

export interface CalcStep {
  position: number;
  fromCurrency: string;
  toCurrency: string;
  amountIn: number;
  amountOut: number;
  rate: number;
  rateInverted: boolean;
  feeFixed: number;
  feePct: number;
}

export interface CalcResult {
  steps: CalcStep[];
  amountIn: number;
  amountOut: number;
  effectiveRate: number | null;
  marketRate: number | null;
  lossPct: number | null;
}

export interface CompareRow {
  routeId: number;
  name: string;
  effectiveRate: number;
  amountOut: number;
  lossPct: number | null;
  best: boolean;
}
