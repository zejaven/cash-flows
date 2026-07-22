import type {
  Account,
  AccountDecision,
  AveragingProfile,
  Bank,
  CalcResult,
  CashDecision,
  Category,
  CategoryRule,
  CompareRow,
  ComputedValue,
  ConversionRoute,
  ConversionStepDef,
  ConvertToTransferRequest,
  Correction,
  DayTotalsResponse,
  FactVsForecast,
  ForecastResponse,
  FxRates,
  GeneratorLogEvent,
  GeneratorOptions,
  GeneratorSettings,
  GeneratorTask,
  ImportPreview,
  ImportReport,
  ImportRun,
  ParserInfo,
  Page,
  Planned,
  QueueItem,
  ReconciliationHistoryEntry,
  ReconciliationRow,
  RecurringRule,
  RecurringRuleRequest,
  StatsCategoriesResponse,
  StatsGranularity,
  Summary,
  Transaction,
  TransactionFilters,
  TransactionRequest,
} from './types';

export class ApiError extends Error {
  constructor(
    public status: number,
    public body: unknown,
  ) {
    super(`API error ${status}`);
  }
}

/** Human-readable message: ProblemDetail detail when present, else a generic one. */
export function apiErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const detail = (e.body as { detail?: string } | null)?.detail;
    if (detail) return detail;
    if (e.status === 401) return 'Session expired — sign in again';
    if (e.status === 409) return 'Conflict — the operation is not allowed';
    return `Request failed (${e.status})`;
  }
  return 'Network error — is the server reachable?';
}

function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

let unauthorizedHandler: (() => void) | null = null;

/** Registers the global 401 hook (App resets the user and shows the login page). */
export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler;
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  let payload: BodyInit | undefined;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = csrfToken();
  }
  const response = await fetch(url, { method, headers, body: payload });
  if (response.status === 401 && unauthorizedHandler) unauthorizedHandler();
  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      /* not json */
    }
    throw new ApiError(response.status, errorBody);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

// --- auth
export async function login(username: string, password: string): Promise<void> {
  const response = await fetch('/api/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'X-XSRF-TOKEN': csrfToken(),
    },
    body: new URLSearchParams({ username, password }),
  });
  if (!response.ok) throw new ApiError(response.status, null);
}

export const logout = () => request<void>('POST', '/api/logout');
export const getSession = () => request<{ username: string }>('GET', '/api/session');

// --- fx: 204 (source down) -> null, UI falls back to original currencies
export async function getFxRates(): Promise<FxRates | null> {
  const response = await fetch('/api/fx/rates');
  if (response.status === 401 && unauthorizedHandler) unauthorizedHandler();
  if (response.status === 204) return null;
  if (!response.ok) throw new ApiError(response.status, null);
  return response.json();
}

// --- banks
export const getBanks = () => request<Bank[]>('GET', '/api/banks');
export const createBank = (body: { name: string; parserId?: string | null }) =>
  request<Bank>('POST', '/api/banks', body);
export const updateBank = (id: number, body: { name: string; parserId?: string | null }) =>
  request<Bank>('PUT', `/api/banks/${id}`, body);
export const deleteBank = (id: number) => request<void>('DELETE', `/api/banks/${id}`);

// --- accounts
export const getAccounts = (includeArchived = false) =>
  request<Account[]>('GET', `/api/accounts${query({ includeArchived })}`);
export const createAccount = (body: Partial<Account>) => request<Account>('POST', '/api/accounts', body);
export const updateAccount = (id: number, body: Partial<Account>) =>
  request<Account>('PUT', `/api/accounts/${id}`, body);
export const deleteAccount = (id: number) => request<void>('DELETE', `/api/accounts/${id}`);

// --- categories
export const getCategories = (includeArchived = false) =>
  request<Category[]>('GET', `/api/categories${query({ includeArchived })}`);
export const createCategory = (body: Partial<Category>) =>
  request<Category>('POST', '/api/categories', body);
export const updateCategory = (id: number, body: Partial<Category>) =>
  request<Category>('PUT', `/api/categories/${id}`, body);
export const deleteCategory = (id: number) => request<void>('DELETE', `/api/categories/${id}`);

// --- transactions
export const getTransactions = (filters: TransactionFilters, page = 0, size = 50) =>
  request<Page<Transaction>>('GET', `/api/transactions${query({ ...filters, page, size })}`);
export const createTransaction = (body: TransactionRequest) =>
  request<Transaction>('POST', '/api/transactions', body);
export const updateTransaction = (id: number, body: TransactionRequest) =>
  request<Transaction>('PUT', `/api/transactions/${id}`, body);
export const deleteTransaction = (id: number) => request<void>('DELETE', `/api/transactions/${id}`);
export const convertToTransfer = (id: number, body: ConvertToTransferRequest) =>
  request<Transaction>('POST', `/api/transactions/${id}/convert-to-transfer`, body);
export const bulkCategory = (transactionIds: number[], categoryId: number) =>
  request<void>('POST', '/api/transactions/bulk-category', { transactionIds, categoryId });
export const getDayTotals = (filters: TransactionFilters, currency: string) =>
  request<DayTotalsResponse>('GET', `/api/transactions/day-totals${query({ ...filters, currency })}`);
export const getSummary = (filters: TransactionFilters, date: string, currency: string) =>
  request<Summary>('GET', `/api/transactions/summary${query({ ...filters, date, currency })}`);

// --- statement import (stage 2)
export async function previewImport(bankId: number, file: File): Promise<ImportPreview> {
  const form = new FormData();
  form.set('bankId', String(bankId));
  form.set('file', file);
  // no Content-Type header: the browser sets the multipart boundary itself
  const response = await fetch('/api/imports/preview', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': csrfToken() },
    body: form,
  });
  if (response.status === 401 && unauthorizedHandler) unauthorizedHandler();
  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      /* not json */
    }
    throw new ApiError(response.status, errorBody);
  }
  return response.json();
}

export const confirmImport = (token: string, accounts: AccountDecision[], cash: CashDecision[]) =>
  request<ImportReport>('POST', '/api/imports/confirm', { token, accounts, cash });
export const getImportRuns = () => request<ImportRun[]>('GET', '/api/imports');

// --- AI parser generator (FR-4.10)
export const getParsers = () => request<ParserInfo[]>('GET', '/api/parsers');
export const getGeneratorOptions = () =>
  request<GeneratorOptions>('GET', '/api/parser-generator/options');
export const getGeneratorSettings = () =>
  request<GeneratorSettings>('GET', '/api/parser-generator/settings');
export const updateGeneratorSettings = (body: GeneratorSettings) =>
  request<GeneratorSettings>('PUT', '/api/parser-generator/settings', body);
export const getCurrentGeneration = async (): Promise<GeneratorTask | null> =>
  (await request<GeneratorTask | undefined>('GET', '/api/parser-generator/current')) ?? null;
export const cancelGeneration = (taskId: string) =>
  request<void>('POST', `/api/parser-generator/${taskId}/cancel`);

export async function startParserGeneration(
  files: File[],
  bankName: string,
  scriptName: string,
): Promise<{ taskId: string }> {
  const form = new FormData();
  for (const file of files) form.append('files', file);
  form.set('bankName', bankName);
  form.set('scriptName', scriptName);
  // no Content-Type header: the browser sets the multipart boundary itself
  const response = await fetch('/api/parser-generator', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': csrfToken() },
    body: form,
  });
  if (response.status === 401 && unauthorizedHandler) unauthorizedHandler();
  if (!response.ok) {
    let errorBody: unknown = null;
    try {
      errorBody = await response.json();
    } catch {
      /* not json */
    }
    throw new ApiError(response.status, errorBody);
  }
  return response.json();
}

/**
 * Subscribes to the generation SSE stream (replays the buffered log first).
 * Returns a close function; the terminal state always arrives as a `status`
 * event, after which the server completes the stream.
 */
export function openGenerationEvents(
  taskId: string,
  onLog: (event: GeneratorLogEvent) => void,
  onStatus: (task: GeneratorTask) => void,
): () => void {
  const source = new EventSource(`/api/parser-generator/${taskId}/events`);
  source.addEventListener('log', (e) => onLog(JSON.parse((e as MessageEvent).data)));
  source.addEventListener('status', (e) => {
    onStatus(JSON.parse((e as MessageEvent).data));
    source.close();
  });
  // on transient drops EventSource reconnects and the server replays the
  // buffered log — consumers dedupe by seq; after `status` we close ourselves
  return () => source.close();
}

// --- category rules
export const getCategoryRules = () => request<CategoryRule[]>('GET', '/api/category-rules');
export const createCategoryRule = (body: {
  pattern: string;
  categoryId: number;
  applyToExisting: boolean;
}) => request<{ rule: CategoryRule; appliedCount: number }>('POST', '/api/category-rules', body);
export const updateCategoryRule = (
  id: number,
  body: { pattern: string; categoryId: number; archived?: boolean; applyToExisting: boolean },
) => request<{ rule: CategoryRule; appliedCount: number }>('PUT', `/api/category-rules/${id}`, body);
export const deleteCategoryRule = (id: number) =>
  request<void>('DELETE', `/api/category-rules/${id}`);
export const previewCategoryRule = (pattern: string, categoryId: number) =>
  request<{ matchCount: number }>(
    'GET',
    `/api/category-rules/preview${query({ pattern, categoryId })}`,
  );

// --- reconciliation
export const getReconciliation = (date: string) =>
  request<ReconciliationRow[]>('GET', `/api/reconciliation${query({ date })}`);
export const postReconciliation = (
  date: string,
  entries: { accountId: number; actualBalance: number }[],
) => request<Correction[]>('POST', '/api/reconciliation', { date, entries });
export const getReconciliationHistory = () =>
  request<ReconciliationHistoryEntry[]>('GET', '/api/reconciliation/history');

// --- recurring (stage 3)
export const getRecurringRules = (today: string) =>
  request<RecurringRule[]>('GET', `/api/recurring${query({ today })}`);
export const createRecurringRule = (body: RecurringRuleRequest, today: string) =>
  request<RecurringRule>('POST', `/api/recurring${query({ today })}`, body);
export const updateRecurringRule = (id: number, body: RecurringRuleRequest, today: string) =>
  request<RecurringRule>('PUT', `/api/recurring/${id}${query({ today })}`, body);
export const deleteRecurringRule = (id: number) =>
  request<void>('DELETE', `/api/recurring/${id}`);
export const getRecurringQueue = (today: string) =>
  request<QueueItem[]>('GET', `/api/recurring/queue${query({ today })}`);
export const confirmRecurring = (body: {
  ruleId: number;
  dueDate: string;
  date?: string;
  amount?: number;
  counterAmount?: number;
}) => request<unknown>('POST', '/api/recurring/confirm', body);
export const skipRecurring = (ruleId: number, dueDate: string) =>
  request<unknown>('POST', '/api/recurring/skip', { ruleId, dueDate });

// --- planned
export const getPlanned = () => request<Planned[]>('GET', '/api/planned');
export const createPlanned = (body: Partial<Planned>) =>
  request<Planned>('POST', '/api/planned', body);
export const updatePlanned = (id: number, body: Partial<Planned>) =>
  request<Planned>('PUT', `/api/planned/${id}`, body);
export const deletePlanned = (id: number) => request<void>('DELETE', `/api/planned/${id}`);
export const confirmPlanned = (
  id: number,
  body: { date?: string; amount?: number; counterAmount?: number },
) => request<Planned>('POST', `/api/planned/${id}/confirm`, body);
export const dismissPlanned = (id: number) =>
  request<Planned>('POST', `/api/planned/${id}/dismiss`, {});

// --- averaging profiles
export const getAveragingProfiles = () =>
  request<AveragingProfile[]>('GET', '/api/averaging-profiles');
export const createAveragingProfile = (body: unknown) =>
  request<AveragingProfile>('POST', '/api/averaging-profiles', body);
export const updateAveragingProfile = (id: number, body: unknown) =>
  request<AveragingProfile>('PUT', `/api/averaging-profiles/${id}`, body);
export const deleteAveragingProfile = (id: number) =>
  request<void>('DELETE', `/api/averaging-profiles/${id}`);
export const previewAveragingProfile = (body: unknown) =>
  request<ComputedValue>('POST', '/api/averaging-profiles/preview', body);

// --- forecast
export const getForecast = (today: string, horizonMonths: number, currency: string) =>
  request<ForecastResponse>('GET', `/api/forecast${query({ today, horizonMonths, currency })}`);

// --- statistics (stage 4)
export const getStatsCategories = (
  from: string,
  to: string,
  granularity: StatsGranularity,
  currency: string,
  includeSystem: boolean,
) =>
  request<StatsCategoriesResponse>(
    'GET',
    `/api/stats/categories${query({ from, to, granularity, currency, includeSystem })}`,
  );
export const getFactVsForecast = (from: string, to: string, currency: string) =>
  request<FactVsForecast>('GET', `/api/stats/fact-vs-forecast${query({ from, to, currency })}`);

// --- conversion routes
export const getConversionRoutes = () => request<ConversionRoute[]>('GET', '/api/conversion-routes');
export const createConversionRoute = (body: {
  name: string;
  steps: ConversionStepDef[];
  archived?: boolean;
}) => request<ConversionRoute>('POST', '/api/conversion-routes', body);
export const updateConversionRoute = (
  id: number,
  body: { name: string; steps: ConversionStepDef[]; archived?: boolean },
) => request<ConversionRoute>('PUT', `/api/conversion-routes/${id}`, body);
export const deleteConversionRoute = (id: number) =>
  request<void>('DELETE', `/api/conversion-routes/${id}`);
export const calculateRoute = (id: number, direction: 'FORWARD' | 'REVERSE', amount: number) =>
  request<CalcResult>('POST', `/api/conversion-routes/${id}/calculate`, { direction, amount });
export const compareRoutes = (from: string, to: string, amount: number) =>
  request<CompareRow[]>('GET', `/api/conversion-routes/compare${query({ from, to, amount })}`);
