import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  apiErrorMessage,
  bulkCategory,
  getCategories,
  getAccounts,
  getDayTotals,
  getDebts,
  getSummary,
  getTransactions,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Approx } from '../components/Money';
import { Modal } from '../components/Modal';
import { TransactionModal } from '../components/TransactionForm';
import { TransferModal } from '../components/TransferForm';
import { DebtLinkDialog } from '../components/DebtLinkDialog';
import { RuleModal } from '../components/CategoryRules';
import { formatDayHeader, today } from '../utils/dates';
import { formatMoney, formatSigned } from '../utils/money';
import type {
  Account,
  Category,
  DayTotal,
  Debt,
  Summary,
  Transaction,
  TransactionFilters,
  TransactionType,
} from '../types';

const PAGE_SIZE = 50;

/** Read persisted filters back from the URL query string (survives page refresh). */
function filtersFromParams(params: URLSearchParams): TransactionFilters {
  const filters: TransactionFilters = {};
  const from = params.get('from');
  const to = params.get('to');
  const accountId = params.get('accountId');
  const categoryId = params.get('categoryId');
  const type = params.get('type');
  const q = params.get('q');
  if (from) filters.from = from;
  if (to) filters.to = to;
  if (accountId) filters.accountId = Number(accountId);
  if (categoryId) filters.categoryId = Number(categoryId);
  if (type) filters.type = type as TransactionType;
  if (q) filters.q = q;
  if (params.get('needsReview') === 'true') filters.needsReview = true;
  return filters;
}

/** Serialize filters into a URLSearchParams so they persist across reloads. */
function filtersToParams(filters: TransactionFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.from) params.set('from', filters.from);
  if (filters.to) params.set('to', filters.to);
  if (filters.accountId != null) params.set('accountId', String(filters.accountId));
  if (filters.categoryId != null) params.set('categoryId', String(filters.categoryId));
  if (filters.type) params.set('type', filters.type);
  if (filters.q) params.set('q', filters.q);
  if (filters.needsReview) params.set('needsReview', 'true');
  return params;
}

export function TransactionsPage() {
  const [items, setItems] = useState<Transaction[] | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [reviewCount, setReviewCount] = useState<number | null>(null);
  const [dayTotals, setDayTotals] = useState<Map<string, DayTotal>>(new Map());
  const [summary, setSummary] = useState<Summary | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [searchParams, setSearchParams] = useSearchParams();
  const [filters, setFilters] = useState<TransactionFilters>(() =>
    filtersFromParams(searchParams),
  );
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkCategoryId, setBulkCategoryId] = useState<number | ''>('');
  const [editing, setEditing] = useState<Transaction | null>(null);
  /** Edit modal swapped to the transfer form («convert to transfer»). */
  const [convertingToTransfer, setConvertingToTransfer] = useState(false);
  /** Edit modal swapped to the link-to-debt flow: pick a debt, then link. */
  const [linkingToDebt, setLinkingToDebt] = useState(false);
  const [linkDebt, setLinkDebt] = useState<Debt | null>(null);
  const [openDebts, setOpenDebts] = useState<Debt[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [addingTransfer, setAddingTransfer] = useState(false);
  /** «Create rule?» suggestion after manually categorizing an imported txn (FR-4.7) */
  const [ruleSuggestion, setRuleSuggestion] = useState<{
    pattern: string;
    categoryId?: number;
  } | null>(null);
  const { displayCurrency } = useSettings();
  const { toast } = useToast();

  useEffect(() => {
    getAccounts(true).then(setAccounts).catch(() => {});
    getCategories(true).then(setCategories).catch(() => {});
  }, []);

  /** Review count, day totals and summary — everything around the list itself. */
  const reloadAside = useCallback(() => {
    getTransactions({ needsReview: true }, 0, 1)
      .then((result) => setReviewCount(result.totalItems))
      .catch(() => setReviewCount(null));
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
  }, [filters, displayCurrency]);

  /** Filters changed: start over from page 0 with a spinner. */
  const reload = useCallback(() => {
    setItems(null);
    setSelected(new Set());
    getTransactions(filters, 0, PAGE_SIZE)
      .then((result) => {
        setItems(result.items);
        setPage(0);
        setTotalPages(result.totalPages);
        setTotalItems(result.totalItems);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
    reloadAside();
  }, [filters, toast, reloadAside]);

  useEffect(reload, [reload]);

  /**
   * After an edit: refetch every page loaded so far while the old rows stay on
   * screen — swapping the list for a spinner collapses the page and throws the
   * scroll position back to the top.
   */
  const refresh = useCallback(() => {
    setSelected(new Set());
    Promise.all(
      Array.from({ length: page + 1 }, (_, p) => getTransactions(filters, p, PAGE_SIZE)),
    )
      .then((results) => {
        const last = results[results.length - 1];
        setItems(results.flatMap((result) => result.items));
        setPage(Math.max(0, Math.min(page, last.totalPages - 1)));
        setTotalPages(last.totalPages);
        setTotalItems(last.totalItems);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
    reloadAside();
  }, [filters, page, toast, reloadAside]);

  // Keep filters in the URL so they survive a page refresh.
  useEffect(() => {
    setSearchParams(filtersToParams(filters), { replace: true });
  }, [filters, setSearchParams]);

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

  /** Day subheader total: net for converted mode, expense list for ORIGINAL. */
  const dayHeaderTotal = (date: string) => {
    const totals = dayTotals.get(date);
    if (!totals) return null;
    if (totals.byCurrency) {
      const text = totals.byCurrency
        .filter((c) => c.expense > 0)
        .map((c) => `−${formatMoney(c.expense, c.currency)}`)
        .join(' · ');
      return text ? <span className="amount muted">{text}</span> : null;
    }
    if (totals.expense == null && totals.income == null) return null;
    const net = (totals.income ?? 0) - (totals.expense ?? 0);
    const currency = displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency;
    return (
      <span className={`amount ${net > 0 ? 'income' : net < 0 ? 'expense' : 'muted'}`}>
        {formatSigned(net, currency, net >= 0 ? '+' : '-')}
      </span>
    );
  };

  const toggleSelect = (id: number) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  /** One shared merchant across the whole selection -> "Create rule" is offered. */
  const sharedPattern = useMemo(() => {
    const picked = (items ?? []).filter((t) => selected.has(t.id));
    const descriptions = new Set(picked.map((t) => t.description ?? ''));
    return picked.length > 0 && descriptions.size === 1 && picked[0].description
      ? picked[0].description
      : null;
  }, [items, selected]);

  const applyBulk = async () => {
    if (!bulkCategoryId || selected.size === 0) return;
    try {
      await bulkCategory([...selected], Number(bulkCategoryId));
      toast(`Category set for ${selected.size} transactions`);
      // one shared merchant just hand-categorized -> offer a rule (FR-4.7)
      const picked = (items ?? []).filter((t) => selected.has(t.id));
      const wasUncategorizedImport = picked.every(
        (t) => t.source === 'IMPORT' && t.categoryId == null,
      );
      if (wasUncategorizedImport && sharedPattern) {
        setRuleSuggestion({ pattern: sharedPattern, categoryId: Number(bulkCategoryId) });
      }
      refresh();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const set = (patch: Partial<TransactionFilters>) =>
    setFilters((current) => ({ ...current, ...patch }));

  const hasActiveFilters = Object.values(filters).some((v) => v != null && v !== '');

  const convHeader = displayCurrency !== 'ORIGINAL' ? `≈ ${displayCurrency}` : '';

  return (
    <>
      <div className="filters">
        <button
          className={`pill${!filters.needsReview ? ' active' : ''}`}
          onClick={() => set({ needsReview: undefined })}
        >
          All
        </button>
        <button
          className={`pill review${filters.needsReview ? ' on' : ''}`}
          onClick={() => set({ needsReview: filters.needsReview ? undefined : true })}
        >
          Needs review
          {reviewCount != null && reviewCount > 0 && <span className="count">{reviewCount}</span>}
        </button>
        <input
          placeholder="🔍 Search description…"
          value={filters.q ?? ''}
          onChange={(e) => set({ q: e.target.value || undefined })}
        />
        <select
          value={filters.accountId ?? ''}
          onChange={(e) => set({ accountId: e.target.value ? Number(e.target.value) : undefined })}
        >
          <option value="">Account ▾</option>
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
          <option value="">Category ▾</option>
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
          <option value="">Type ▾</option>
          <option value="EXPENSE">Expense</option>
          <option value="INCOME">Income</option>
          <option value="TRANSFER">Transfer</option>
        </select>
        {hasActiveFilters && (
          <button className="btn-ghost" onClick={() => setFilters({})}>
            ✕ Reset
          </button>
        )}
      </div>

      {summary && (
        <div className="summary-strip amount">
          <span>
            Day <b className="expense">−{formatMoney(summary.day.expense, summary.currency)}</b>
          </span>
          <span>
            Week <b className="expense">−{formatMoney(summary.week.expense, summary.currency)}</b>
          </span>
          <span>
            Month <b className="expense">−{formatMoney(summary.month.expense, summary.currency)}</b>
          </span>
          <span>
            Income <b className="income">+{formatMoney(summary.month.income, summary.currency)}</b>
          </span>
          {summary.incomplete && <span className="badge badge-review">partial rates</span>}
        </div>
      )}

      {selected.size > 0 && (
        <div className="bulk-bar">
          <b>{selected.size} selected</b>
          <select
            value={bulkCategoryId}
            onChange={(e) => setBulkCategoryId(e.target.value ? Number(e.target.value) : '')}
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
          <button className="btn btn-sm" onClick={applyBulk} disabled={!bulkCategoryId}>
            Apply
          </button>
          <button
            className="btn-ghost btn-sm"
            disabled={!sharedPattern}
            onClick={() =>
              sharedPattern &&
              setRuleSuggestion({
                pattern: sharedPattern,
                categoryId: bulkCategoryId ? Number(bulkCategoryId) : undefined,
              })
            }
          >
            Create rule from merchant
          </button>
          <button className="btn-ghost btn-sm" onClick={() => setSelected(new Set())}>
            Clear
          </button>
        </div>
      )}

      {items == null ? (
        <Spinner />
      ) : items.length === 0 ? (
        <div className="placeholder">No transactions match the filters</div>
      ) : (
        <div className="txn-table">
          <div className="txn-head overline">
            <span />
            <span>Description</span>
            <span>Category</span>
            <span>Account</span>
            <span className="num">Amount</span>
            <span className="num">{convHeader}</span>
          </div>
          {groups.map(([date, dayItems]) => (
            <Fragment key={date}>
              <div className="txn-day overline">
                <span>{formatDayHeader(date)}</span>
                {dayHeaderTotal(date)}
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
            </Fragment>
          ))}
          <div className="txn-foot">
            <span>
              {totalItems} transaction{totalItems === 1 ? '' : 's'}
              {totalPages > 1 ? ` · page ${page + 1} of ${totalPages}` : ''}
            </span>
            {page + 1 < totalPages && <button onClick={loadMore}>Load more ›</button>}
          </div>
        </div>
      )}

      <button className="fab" onClick={() => setAdding(true)} aria-label="Add transaction">
        +
      </button>

      {adding && (
        <TransactionModal
          onClose={() => setAdding(false)}
          onSaved={refresh}
          onSwitchToTransfer={() => {
            setAdding(false);
            setAddingTransfer(true);
          }}
        />
      )}
      {addingTransfer && (
        <TransferModal onClose={() => setAddingTransfer(false)} onSaved={refresh} />
      )}
      {editing && editing.type !== 'TRANSFER' && !convertingToTransfer && !linkingToDebt && (
        <TransactionModal
          existing={editing}
          onClose={() => setEditing(null)}
          onSaved={refresh}
          onCategorized={(categoryId) => {
            if (editing.source === 'IMPORT' && editing.categoryId == null && editing.description) {
              setRuleSuggestion({ pattern: editing.description, categoryId });
            }
          }}
          onSwitchToTransfer={() => setConvertingToTransfer(true)}
          onLinkToDebt={
            editing.debtId == null
              ? () => {
                  setLinkingToDebt(true);
                  getDebts()
                    .then(setOpenDebts)
                    .catch((e) => toast(apiErrorMessage(e), true));
                }
              : undefined
          }
        />
      )}
      {editing && linkingToDebt && linkDebt == null && (
        <Modal
          title="Link to debt"
          onClose={() => {
            setLinkingToDebt(false);
            setOpenDebts(null);
          }}
        >
          {openDebts == null ? (
            <Spinner />
          ) : openDebts.length === 0 ? (
            <div className="placeholder">No open debts — create one on the Debts page first</div>
          ) : (
            openDebts.map((debt) => (
              <div key={debt.id} className="settings-row">
                <div className="grow">
                  <div>{debt.name}</div>
                  <div className="muted amount" style={{ fontSize: 12.5 }}>
                    {debt.counterpartyName} · {formatMoney(debt.outstanding, debt.currency)}{' '}
                    {debt.direction === 'OWED_TO_ME' ? 'owed to me' : 'I owe'}
                  </div>
                </div>
                <button className="row-btn" onClick={() => setLinkDebt(debt)}>
                  Choose
                </button>
              </div>
            ))
          )}
        </Modal>
      )}
      {editing && linkingToDebt && linkDebt != null && (
        <DebtLinkDialog
          debt={linkDebt}
          presetTransaction={editing}
          onClose={() => {
            setEditing(null);
            setLinkingToDebt(false);
            setLinkDebt(null);
            setOpenDebts(null);
          }}
          onSaved={refresh}
        />
      )}
      {editing && (editing.type === 'TRANSFER' || convertingToTransfer) && (
        <TransferModal
          existing={editing}
          onClose={() => {
            setEditing(null);
            setConvertingToTransfer(false);
          }}
          onSaved={refresh}
        />
      )}
      {ruleSuggestion && (
        <RuleModal
          existing={null}
          prefillPattern={ruleSuggestion.pattern}
          prefillCategoryId={ruleSuggestion.categoryId}
          onClose={() => setRuleSuggestion(null)}
          onSaved={refresh}
        />
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
  const dotColor = txn.categoryColor ?? (isTransfer ? 'var(--muted)' : 'var(--review)');

  const meta = needsReview
    ? 'needs review · tap to categorize'
    : isTransfer
      ? `${formatMoney(txn.amount, txn.accountCurrency)} → ${formatMoney(
          txn.counterAmount ?? 0,
          txn.counterAccountCurrency ?? '',
        )}`
      : `${txn.categoryName} · ${txn.accountName}${txn.source === 'IMPORT' ? ' · imported' : ''}`;

  return (
    <div
      className={`txn-row${needsReview ? ' review' : ''}${selected ? ' selected' : ''}`}
      onClick={onOpen}
    >
      <span className="txn-check" onClick={(e) => e.stopPropagation()}>
        {!isTransfer && <input type="checkbox" checked={selected} onChange={onToggle} />}
      </span>
      <div className="txn-main">
        <div className="txn-title">
          <span className="cat-dot" style={{ background: dotColor }} />
          {title}
          {txn.source === 'IMPORT' && <span className="tag"> imported</span>}
          {txn.debtId != null && (
            <span className="tag" title={txn.debtName ?? undefined}>
              {' '}
              debt
            </span>
          )}
        </div>
        <div className={`txn-meta${needsReview ? ' review-hint' : ''}`}>{meta}</div>
      </div>
      <span
        className={`txn-cat${needsReview ? ' review-text' : ''}`}
        style={!needsReview && !isTransfer && txn.categoryColor ? { color: txn.categoryColor } : undefined}
      >
        {needsReview ? 'needs review' : isTransfer ? 'transfer' : txn.categoryName}
      </span>
      <span className="txn-acc">
        {isTransfer ? `${txn.accountName} → ${txn.counterAccountName}` : txn.accountName}
      </span>
      <span className={`txn-amt amount${isTransfer ? ' muted' : txn.type === 'EXPENSE' ? ' expense' : ' income'}`}>
        {isTransfer
          ? formatMoney(txn.amount, txn.accountCurrency)
          : formatSigned(txn.amount, txn.accountCurrency, txn.type === 'EXPENSE' ? '-' : '+')}
      </span>
      <span className="txn-conv amount">
        {isTransfer ? (
          '⇄'
        ) : txn.originalCurrency ? (
          `${formatMoney(txn.originalAmount ?? 0, txn.originalCurrency)}`
        ) : (
          <Approx value={txn.amount} currency={txn.accountCurrency} />
        )}
      </span>
    </div>
  );
}
