import type {
  Account,
  Bank,
  Category,
  DayTotalsResponse,
  FxRates,
  Page,
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
export const bulkCategory = (transactionIds: number[], categoryId: number) =>
  request<void>('POST', '/api/transactions/bulk-category', { transactionIds, categoryId });
export const getDayTotals = (filters: TransactionFilters, currency: string) =>
  request<DayTotalsResponse>('GET', `/api/transactions/day-totals${query({ ...filters, currency })}`);
export const getSummary = (filters: TransactionFilters, date: string, currency: string) =>
  request<Summary>('GET', `/api/transactions/summary${query({ ...filters, date, currency })}`);
