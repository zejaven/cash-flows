import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  createTransaction,
  deleteTransaction,
  getAccounts,
  getCategories,
  updateTransaction,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Modal } from './Modal';
import { Spinner } from './Spinner';
import { today } from '../utils/dates';
import type { Account, Category, Transaction, TransactionType } from '../types';

const LAST_ACCOUNT_KEY = 'lastAccountId';
const LAST_CATEGORY_KEY = 'lastCategoryId';

function readLast(key: string): number | null {
  try {
    const value = localStorage.getItem(key);
    return value ? Number(value) : null;
  } catch {
    return null;
  }
}

function storeLast(key: string, value: number) {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    /* ignore */
  }
}

/**
 * Expense/income entry (FR-2.1, FR-2.6): date defaults to the client's today,
 * account and category default to the last used ones. Optional original
 * currency+amount pair for charges made in a foreign currency.
 */
export function TransactionModal({
  existing,
  onClose,
  onSaved,
  onCategorized,
  onSwitchToTransfer,
}: {
  existing?: Transaction;
  onClose: () => void;
  onSaved: () => void;
  /** Fired when a save assigned a category — hook for the rule-suggestion dialog. */
  onCategorized?: (categoryId: number) => void;
  /** Swaps this modal for the transfer form (new entry or convert-to-transfer). */
  onSwitchToTransfer?: () => void;
}) {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [type, setType] = useState<TransactionType>(existing?.type ?? 'EXPENSE');
  const [date, setDate] = useState(existing?.date ?? today());
  const [accountId, setAccountId] = useState<number | null>(
    existing?.accountId ?? readLast(LAST_ACCOUNT_KEY),
  );
  const [amount, setAmount] = useState(existing ? String(existing.amount) : '');
  const [categoryId, setCategoryId] = useState<number | null>(
    existing ? existing.categoryId : readLast(LAST_CATEGORY_KEY),
  );
  const [description, setDescription] = useState(existing?.description ?? '');
  const [withOriginal, setWithOriginal] = useState(existing?.originalCurrency != null);
  const [originalCurrency, setOriginalCurrency] = useState(existing?.originalCurrency ?? 'NZD');
  const [originalAmount, setOriginalAmount] = useState(
    existing?.originalAmount != null ? String(existing.originalAmount) : '',
  );
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getAccounts()
      .then(setAccounts)
      .catch((e) => toast(apiErrorMessage(e), true));
    getCategories()
      .then(setCategories)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  const account = accounts?.find((a) => a.id === accountId) ?? null;

  const matchingCategories = useMemo(() => {
    if (!categories) return [];
    const wanted = type === 'EXPENSE' ? 'EXPENSE' : 'INCOME';
    const list = categories.filter((c) => c.type === wanted);
    const last = readLast(LAST_CATEGORY_KEY);
    // last used first (FR-2.6)
    return list.sort((a, b) => (a.id === last ? -1 : b.id === last ? 1 : 0));
  }, [categories, type]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!accountId || !amount) return;
    setBusy(true);
    try {
      const body = {
        type,
        date,
        accountId,
        amount: Number(amount),
        categoryId: categoryId ?? null,
        description: description || null,
        originalCurrency: withOriginal ? originalCurrency : null,
        originalAmount: withOriginal && originalAmount ? Number(originalAmount) : null,
      };
      if (existing) await updateTransaction(existing.id, body);
      else await createTransaction(body);
      storeLast(LAST_ACCOUNT_KEY, accountId);
      if (categoryId) storeLast(LAST_CATEGORY_KEY, categoryId);
      onSaved();
      if (categoryId && onCategorized) onCategorized(categoryId);
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  if (accounts == null || categories == null) {
    return (
      <Modal title={existing ? 'Edit transaction' : 'New transaction'} onClose={onClose}>
        <Spinner />
      </Modal>
    );
  }

  return (
    <Modal title={existing ? 'Edit transaction' : 'New transaction'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            className={type === 'EXPENSE' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => {
              setType('EXPENSE');
              setCategoryId(null);
            }}
          >
            Expense
          </button>
          <button
            type="button"
            className={type === 'INCOME' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => {
              setType('INCOME');
              setCategoryId(null);
            }}
          >
            Income
          </button>
          {onSwitchToTransfer && (
            <button
              type="button"
              className="btn-ghost"
              style={{ flex: 1 }}
              onClick={onSwitchToTransfer}
            >
              Transfer
            </button>
          )}
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="txn-amount">
              Amount{account ? ` (${account.currency})` : ''}
            </label>
            <input
              id="txn-amount"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              autoFocus={!existing}
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="txn-date">Date</label>
            <input
              id="txn-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
            />
          </div>
        </div>
        <div className="form-row">
          <label htmlFor="txn-account">Account</label>
          <select
            id="txn-account"
            value={accountId ?? ''}
            onChange={(e) => setAccountId(Number(e.target.value))}
            required
          >
            <option value="" disabled>
              Choose account…
            </option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.currency})
              </option>
            ))}
          </select>
        </div>
        <div className="form-row">
          <label>Category · last used first</label>
          <div className="chip-row" style={{ marginBottom: 0 }}>
            {matchingCategories.map((c) => (
              <button
                type="button"
                key={c.id}
                className="chip"
                style={
                  categoryId === c.id
                    ? { borderColor: c.color, color: c.color, fontWeight: 700 }
                    : undefined
                }
                onClick={() => setCategoryId(categoryId === c.id ? null : c.id)}
              >
                {c.name}
              </button>
            ))}
          </div>
        </div>
        <div className="form-row">
          <label htmlFor="txn-description">Description</label>
          <input
            id="txn-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="PAK N SAVE Albany"
          />
        </div>
        <div className="form-row">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input
              type="checkbox"
              style={{ width: 'auto' }}
              checked={withOriginal}
              onChange={(e) => setWithOriginal(e.target.checked)}
            />
            Charged in a different currency
          </label>
        </div>
        {withOriginal && (
          <div className="form-grid">
            <div className="form-row">
              <label htmlFor="txn-original-amount">Original amount</label>
              <input
                id="txn-original-amount"
                type="number"
                step="any"
                min="0"
                inputMode="decimal"
                value={originalAmount}
                onChange={(e) => setOriginalAmount(e.target.value)}
              />
            </div>
            <div className="form-row">
              <label htmlFor="txn-original-currency">Currency</label>
              <input
                id="txn-original-currency"
                value={originalCurrency}
                onChange={(e) => setOriginalCurrency(e.target.value.toUpperCase())}
                maxLength={8}
              />
            </div>
          </div>
        )}
        <div className="form-actions">
          {existing && (
            <button
              type="button"
              className="btn-danger"
              style={{ marginRight: 'auto' }}
              disabled={busy}
              onClick={async () => {
                if (!confirm('Delete this transaction?')) return;
                setBusy(true);
                try {
                  await deleteTransaction(existing.id);
                  onSaved();
                  onClose();
                } catch (err) {
                  toast(apiErrorMessage(err), true);
                  setBusy(false);
                }
              }}
            >
              Delete
            </button>
          )}
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !accountId || !amount}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}
