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
  /** Debt linkage: present when the transaction is a debt movement */
  debtId: number | null;
  debtName: string | null;
  debtRole: DebtEntryRole | null;
  txnPrincipal: number | null;
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

export interface ConvertToTransferRequest {
  date: string;
  accountId: number;
  amount: number;
  counterAccountId: number;
  counterAmount: number;
  description?: string | null;
  /** Already-imported opposite side to absorb — keeps its import key for dedup. */
  counterTransactionId?: number | null;
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
  forecastOnly: boolean;
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
  forecastOnly?: boolean;
}

export interface QueueItem {
  kind: 'RECURRING' | 'PLANNED' | 'LOAN';
  ruleId: number | null;
  plannedId: number | null;
  name: string;
  dueDate: string;
  type: TransactionType;
  accountId: number | null;
  accountName: string | null;
  accountCurrency: string | null;
  counterAccountId: number | null;
  counterAccountName: string | null;
  counterAccountCurrency: string | null;
  suggestedAmount: number | null;
  suggestedCounterAmount: number | null;
  approx: boolean;
  debtId: number | null;
  scheduleItemId: number | null;
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
  kind:
    | 'RECURRING'
    | 'PLANNED'
    | 'PROFILE_DAILY'
    | 'PROFILE_MONTHLY'
    | 'TRANSACTION'
    | 'LOAN_PAYMENT'
    | 'DEBT_REPAYMENT';
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

// --- debts & loans (FR-13)

export type DebtDirection = 'I_OWE' | 'OWED_TO_ME';
export type DebtKind = 'PERSONAL' | 'LOAN';
export type DebtEntryRole = 'ISSUE' | 'REPAYMENT';

export interface Counterparty {
  id: number;
  name: string;
  note: string | null;
  archived: boolean;
  debtCount: number;
}

export interface Debt {
  id: number;
  name: string;
  direction: DebtDirection;
  kind: DebtKind;
  counterpartyId: number;
  counterpartyName: string;
  currency: string;
  openingPrincipal: number;
  anchorDate: string;
  expectedReturnDate: string | null;
  accountId: number | null;
  accountName: string | null;
  accountCurrency: string | null;
  interestCategoryId: number | null;
  interestCategoryName: string | null;
  note: string | null;
  closed: boolean;
  outstanding: number;
  totalPrincipal: number;
  totalRepaid: number;
  nextPaymentDate: string | null;
  nextPaymentAmount: number | null;
}

export interface DebtRequest {
  name: string;
  direction: DebtDirection;
  kind: DebtKind;
  counterpartyId: number;
  currency: string;
  openingPrincipal: number;
  anchorDate: string;
  expectedReturnDate?: string | null;
  accountId?: number | null;
  interestCategoryId?: number | null;
  note?: string | null;
}

export interface DebtSummary {
  currency: string;
  owedToMe: number;
  iOwe: number;
  approx: boolean;
}

export interface DebtEntry {
  id: number;
  debtId: number;
  role: DebtEntryRole;
  txnPrincipal: number;
  debtPrincipal: number;
  scheduleItemId: number | null;
  transactionId: number;
  txnDate: string;
  txnAmount: number;
  txnCurrency: string;
  accountName: string;
  description: string | null;
}

export interface LoanScheduleItem {
  id: number;
  position: number;
  dueDate: string;
  principalAmount: number;
  interestAmount: number;
  totalAmount: number;
  remainingAfter: number | null;
  skipped: boolean;
  paid: boolean;
  transactionId: number | null;
}

export interface ScheduleImportResult {
  inserted: number;
  replaced: number;
  keptPaid: number;
}

// --- AI parser generator (FR-4.10)

export interface ParserInfo {
  id: string;
  script: string;
}

export type AgentProvider = 'CLAUDE' | 'CODEX';

export interface ProviderOption {
  id: AgentProvider;
  label: string;
  available: boolean;
  version: string | null;
  error: string | null;
  models: string[];
}

export interface GeneratorOptions {
  enabled: boolean;
  providers: ProviderOption[];
}

export interface GeneratorSettings {
  provider: AgentProvider;
  claudeModel: string | null;
  codexModel: string | null;
}

export type GeneratorStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface GeneratorTask {
  taskId: string;
  status: GeneratorStatus;
  parserId: string | null;
  scriptFile: string | null;
  error: string | null;
  warnings: string[];
  startedAt: string;
  finishedAt: string | null;
}

export interface GeneratorLogEvent {
  seq: number;
  kind: 'text' | 'activity' | 'info' | 'error';
  text: string;
}
