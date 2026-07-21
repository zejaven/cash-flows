import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  apiErrorMessage,
  bulkCategory,
  getCategories,
  getAccounts,
  getDayTotals,
  getSummary,
  getTransactions,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Approx } from '../components/Money';
import { TransactionModal } from '../components/TransactionForm';
import { TransferModal } from '../components/TransferForm';
import { formatDayHeader, today } from '../utils/dates';
import { formatMoney, formatSigned } from '../utils/money';
import type {
  Account,
  Category,
  DayTotal,
  Summary,
  Transaction,
  TransactionFilters,
  TransactionType,
} from '../types';

const PAGE_SIZE = 50;

export function TransactionsPage() {
  const [items, setItems] = useState<Transaction[] | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [dayTotals, setDayTotals] = useState<Map<string, DayTotal>>(new Map());
  const [summary, setSummary] = useState<Summary | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [filters, setFilters] = useState<TransactionFilters>({});
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkCategoryId, setBulkCategoryId] = useState<number | ''>('');
  const [editing, setEditing] = useState<Transaction | null>(null);
  const [adding, setAdding] = useState(false);
  const { displayCurrency } = useSettings();
  const { toast } = useToast();

  useEffect(() => {
    getAccounts(true).then(setAccounts).catch(() => {});
    getCategories(true).then(setCategories).catch(() => {});
  }, []);

  const reload = useCallback(() => {
    setItems(null);
    setSelected(new Set());
    getTransactions(filters, 0, PAGE_SIZE)
      .then((result) => {
        setItems(result.items);
        setPage(0);
        setTotalPages(result.totalPages);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
    getDayTotals(filters, displayCurrency)
      .then((result) => setDayTotals(new Map(result.days.map((d) => [d.date, d]))))
      .catch(() => setDayTotals(new Map()));
    if (displayCurrency !== 'ORIGINAL') {
      getSummary(filters, today(), displayCurrency)
        .then(setSummary)
        .catch(() => setSummary(null));
    } else {
      setSummary(null);
    }
  }, [filters, displayCurrency, toast]);

  useEffect(reload, [reload]);

  const loadMore = () => {
    getTransactions(filters, page + 1, PAGE_SIZE)
      .then((result) => {
        setItems((current) => [...(current ?? []), ...result.items]);
        setPage(result.page);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
  };

  const groups = useMemo(() => {
    if (!items) return [];
    const map = new Map<string, Transaction[]>();
    for (const item of items) {
      map.set(item.date, [...(map.get(item.date) ?? []), item]);
    }
    return [...map.entries()];
  }, [items]);

  const dayHeaderTotal = (date: string): string | null => {
    const totals = dayTotals.get(date);
    if (!totals) return null;
    if (totals.byCurrency) {
      return totals.byCurrency
        .filter((c) => c.expense > 0)
        .map((c) => `−${formatMoney(c.expense, c.currency)}`)
        .join(' · ');
    }
    if (totals.expense == null) return null;
    return `−${formatMoney(totals.expense, displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency)}`;
  };

  const toggleSelect = (id: number) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const applyBulk = async () => {
    if (!bulkCategoryId || selected.size === 0) return;
    try {
      await bulkCategory([...selected], Number(bulkCategoryId));
      toast(`Category set for ${selected.size} transactions`);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const set = (patch: Partial<TransactionFilters>) =>
    setFilters((current) => ({ ...current, ...patch }));

  return (
    <>
      <div className="filters">
        <input
          placeholder="🔍 Search description…"
          value={filters.q ?? ''}
          onChange={(e) => set({ q: e.target.value || undefined })}
          style={{ flexBasis: 180 }}
        />
        <select
          value={filters.accountId ?? ''}
          onChange={(e) => set({ accountId: e.target.value ? Number(e.target.value) : undefined })}
        >
          <option value="">Account: all</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
        <select
          value={filters.categoryId ?? ''}
          onChange={(e) => set({ categoryId: e.target.value ? Number(e.target.value) : undefined })}
        >
          <option value="">Category: all</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        <select
          value={filters.type ?? ''}
          onChange={(e) =>
            set({ type: (e.target.value || undefined) as TransactionType | undefined })
          }
        >
          <option value="">Type: all</option>
          <option value="EXPENSE">Expense</option>
          <option value="INCOME">Income</option>
          <option value="TRANSFER">Transfer</option>
        </select>
        <button
          className={filters.needsReview ? 'btn' : 'btn-ghost'}
          onClick={() => set({ needsReview: filters.needsReview ? undefined : true })}
        >
          Needs review
        </button>
      </div>

      {summary && (
        <div className="chip-row">
          <span className="chip amount">
            Day <b>−{formatMoney(summary.day.expense, summary.currency)}</b>
          </span>
          <span className="chip amount">
            Week <b>−{formatMoney(summary.week.expense, summary.currency)}</b>
          </span>
          <span className="chip amount">
            Month <b>−{formatMoney(summary.month.expense, summary.currency)}</b>
          </span>
          <span className="chip amount income">
            Income <b>+{formatMoney(summary.month.income, summary.currency)}</b>
          </span>
          {summary.incomplete && <span className="chip badge-review">partial rates</span>}
        </div>
      )}

      {selected.size > 0 && (
        <div className="bulk-bar">
          <b>{selected.size} selected</b>
          <select
            value={bulkCategoryId}
            onChange={(e) => setBulkCategoryId(e.target.value ? Number(e.target.value) : '')}
            style={{ width: 'auto', flex: 1 }}
          >
            <option value="">Set category…</option>
            {categories
              .filter((c) => !c.archived)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.type.toLowerCase()})
                </option>
              ))}
          </select>
          <button className="btn" onClick={applyBulk} disabled={!bulkCategoryId}>
            Apply
          </button>
          <button className="btn-ghost" onClick={() => setSelected(new Set())}>
            Clear
          </button>
        </div>
      )}

      {items == null ? (
        <Spinner />
      ) : items.length === 0 ? (
        <div className="placeholder">No transactions match the filters</div>
      ) : (
        groups.map(([date, dayItems]) => (
          <div key={date}>
            <div className="day-header">
              <span>{formatDayHeader(date)}</span>
              <span className="amount">{dayHeaderTotal(date)}</span>
            </div>
            {dayItems.map((txn) => (
              <TransactionRow
                key={txn.id}
                txn={txn}
                selected={selected.has(txn.id)}
                onToggle={() => toggleSelect(txn.id)}
                onOpen={() => setEditing(txn)}
              />
            ))}
          </div>
        ))
      )}

      {items != null && page + 1 < totalPages && (
        <button className="btn-ghost load-more" onClick={loadMore}>
          Load more
        </button>
      )}

      <button className="fab" onClick={() => setAdding(true)} aria-label="Add transaction">
        +
      </button>

      {adding && <TransactionModal onClose={() => setAdding(false)} onSaved={reload} />}
      {editing && editing.type !== 'TRANSFER' && (
        <TransactionModal existing={editing} onClose={() => setEditing(null)} onSaved={reload} />
      )}
      {editing && editing.type === 'TRANSFER' && (
        <TransferModal existing={editing} onClose={() => setEditing(null)} onSaved={reload} />
      )}
    </>
  );
}

function TransactionRow({
  txn,
  selected,
  onToggle,
  onOpen,
}: {
  txn: Transaction;
  selected: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  const isTransfer = txn.type === 'TRANSFER';
  const needsReview = !isTransfer && txn.categoryId == null;
  const title = txn.description || txn.categoryName || txn.type.toLowerCase();

  return (
    <div className="txn-row">
      {!isTransfer && (
        <input
          type="checkbox"
          checked={selected}
          onChange={onToggle}
          onClick={(e) => e.stopPropagation()}
        />
      )}
      <span
        className="cat-dot"
        style={{ background: txn.categoryColor ?? (isTransfer ? 'var(--accent)' : 'var(--review)') }}
      />
      <div className="txn-info" onClick={onOpen} style={{ cursor: 'pointer' }}>
        <div className="txn-title">{title}</div>
        <div className="txn-meta">
          {needsReview ? (
            <span className="badge badge-review">needs review</span>
          ) : isTransfer ? (
            `${txn.accountName} → ${txn.counterAccountName}`
          ) : (
            `${txn.categoryName} · ${txn.accountName}`
          )}
          {txn.source === 'IMPORT' && <span className="badge badge-imported"> imported</span>}
        </div>
      </div>
      <div className="txn-amounts" onClick={onOpen} style={{ cursor: 'pointer' }}>
        {isTransfer ? (
          <div className="amount">
            ⇄ {formatMoney(txn.amount, txn.accountCurrency)} →{' '}
            {formatMoney(txn.counterAmount ?? 0, txn.counterAccountCurrency ?? '')}
          </div>
        ) : (
          <>
            <div className={`amount ${txn.type === 'EXPENSE' ? 'expense' : 'income'}`}>
              {formatSigned(txn.amount, txn.accountCurrency, txn.type === 'EXPENSE' ? '-' : '+')}
            </div>
            <div className="txn-original">
              {txn.originalCurrency ? (
                <span className="amount">
                  {formatMoney(txn.originalAmount ?? 0, txn.originalCurrency)}{' '}
                  {txn.originalCurrency}
                </span>
              ) : (
                <Approx value={txn.amount} currency={txn.accountCurrency} />
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
