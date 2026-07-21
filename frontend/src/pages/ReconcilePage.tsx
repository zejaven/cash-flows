import { useCallback, useEffect, useState } from 'react';
import {
  apiErrorMessage,
  getReconciliation,
  getReconciliationHistory,
  postReconciliation,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { formatMoney } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type { ReconciliationHistoryEntry, ReconciliationRow } from '../types';

/**
 * Balance reconciliation (mockup 1j/7e): enter actual balances, differences
 * become Correction transactions — excluded from stats, included in balances.
 */
export function ReconcilePage() {
  const [date, setDate] = useState(today());
  const [rows, setRows] = useState<ReconciliationRow[] | null>(null);
  const [actuals, setActuals] = useState<Map<number, string>>(new Map());
  const [history, setHistory] = useState<ReconciliationHistoryEntry[] | null>(null);
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  const reload = useCallback(() => {
    setRows(null);
    setActuals(new Map());
    getReconciliation(date)
      .then(setRows)
      .catch((e) => toast(apiErrorMessage(e), true));
    getReconciliationHistory()
      .then(setHistory)
      .catch(() => setHistory([]));
  }, [date, toast]);

  useEffect(reload, [reload]);

  const diffOf = (row: ReconciliationRow): number | null => {
    const raw = actuals.get(row.accountId);
    if (raw == null || raw.trim() === '') return null;
    const actual = Number(raw);
    if (Number.isNaN(actual)) return null;
    return actual - row.computedBalance;
  };

  const corrections = (rows ?? [])
    .map((row) => ({ row, diff: diffOf(row) }))
    .filter((entry) => entry.diff != null && entry.diff !== 0);

  async function submit() {
    setBusy(true);
    try {
      const entries = (rows ?? [])
        .filter((row) => {
          const raw = actuals.get(row.accountId);
          return raw != null && raw.trim() !== '' && !Number.isNaN(Number(raw));
        })
        .map((row) => ({ accountId: row.accountId, actualBalance: Number(actuals.get(row.accountId)) }));
      const created = await postReconciliation(date, entries);
      toast(
        created.length === 0
          ? 'Everything matches — no corrections needed'
          : `${created.length} correction${created.length > 1 ? 's' : ''} created`,
      );
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="card">
        <h2>Balance reconciliation</h2>
        <div className="form-row" style={{ maxWidth: 220 }}>
          <label htmlFor="rec-date">As of</label>
          <input id="rec-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </div>
        <div className="muted" style={{ fontSize: 13, marginBottom: 10 }}>
          Enter actual balances — differences become Correction transactions, excluded from stats
          &amp; averages, included in balances.
        </div>
        {rows == null ? (
          <Spinner />
        ) : (
          rows.map((row) => {
            const diff = diffOf(row);
            return (
              <div key={row.accountId} className="settings-row">
                <div className="grow">
                  <div>{row.name}</div>
                  <div className="muted amount" style={{ fontSize: 12.5 }}>
                    calc {formatMoney(row.computedBalance, row.currency)}
                  </div>
                </div>
                <input
                  type="number"
                  step="any"
                  inputMode="decimal"
                  placeholder="actual…"
                  style={{ width: 140, textAlign: 'right' }}
                  className="amount"
                  value={actuals.get(row.accountId) ?? ''}
                  onChange={(e) =>
                    setActuals((current) => {
                      const next = new Map(current);
                      next.set(row.accountId, e.target.value);
                      return next;
                    })
                  }
                />
                <span
                  className={`amount ${diff == null || diff === 0 ? 'muted' : diff < 0 ? 'expense' : 'income'}`}
                  style={{ width: 110, textAlign: 'right' }}
                >
                  {diff == null
                    ? '—'
                    : diff === 0
                      ? 'MATCHES ✓'
                      : `${diff > 0 ? '+' : '−'}${formatMoney(Math.abs(diff), row.currency)}`}
                </span>
              </div>
            );
          })
        )}
        <div className="form-actions">
          <button className="btn" onClick={submit} disabled={busy || corrections.length === 0}>
            Create {corrections.length || ''} correction{corrections.length === 1 ? '' : 's'} →
          </button>
        </div>
      </div>

      {history != null && history.length > 0 && (
        <div className="card">
          <h2>Past reconciliations</h2>
          {history.map((entry) => (
            <div key={entry.date} className="settings-row">
              <div className="grow">{formatDate(entry.date)}</div>
              <span className="muted amount" style={{ fontSize: 12.5 }}>
                {entry.corrections
                  .map(
                    (c) =>
                      `${c.type === 'EXPENSE' ? '−' : '+'}${formatMoney(c.amount, c.currency)} ${c.accountName}`,
                  )
                  .join(' · ')}
              </span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
