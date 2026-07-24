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
      <div className="section-head">
        <span className="overline">Balance reconciliation</span>
        <span
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontSize: 11.5,
            fontWeight: 600,
            color: 'var(--muted)',
          }}
        >
          As of
          <input
            id="rec-date"
            type="date"
            className="amount"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            style={{ width: 'auto', padding: '5px 11px', fontSize: 12 }}
          />
        </span>
      </div>
      <div className="footnote" style={{ marginTop: 0, marginBottom: 12 }}>
        Enter actual balances — differences become <b>Correction</b> transactions, excluded from
        stats &amp; averages, included in balances.
      </div>

      {rows == null ? (
        <Spinner />
      ) : (
        <div className="list-table">
          <div className="rec-head overline">
            <span>Account</span>
            <span className="num">Computed</span>
            <span className="num">Actual</span>
            <span className="num">Difference</span>
            <span />
          </div>
          {rows.map((row) => {
            const diff = diffOf(row);
            const mismatch = diff != null && diff !== 0;
            return (
              <div key={row.accountId} className={`rec-row${mismatch ? ' mismatch' : ''}`}>
                <span className="rec-name">{row.name}</span>
                <span className="rec-computed amount">
                  {formatMoney(row.computedBalance, row.currency)}
                </span>
                <span className="rec-actual">
                  <input
                    type="number"
                    step="any"
                    inputMode="decimal"
                    placeholder="actual…"
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
                </span>
                <span
                  className={`rec-diff amount ${
                    diff == null ? 'muted' : diff === 0 ? 'muted' : diff < 0 ? 'expense' : 'income'
                  }`}
                >
                  {diff == null
                    ? '—'
                    : diff === 0
                      ? '0.00'
                      : `${diff > 0 ? '+' : '−'}${formatMoney(Math.abs(diff), row.currency)}`}
                </span>
                <span className={`rec-status ${mismatch ? 'expense' : diff === 0 ? 'income' : ''}`}>
                  {diff == null ? '' : diff === 0 ? 'MATCHES ✓' : '→ CORRECTION'}
                </span>
              </div>
            );
          })}
        </div>
      )}

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 10,
          flexWrap: 'wrap',
          marginTop: 14,
        }}
      >
        <span className="footnote" style={{ marginTop: 0 }}>
          {corrections.length > 0 ? (
            <>
              {corrections.length} correction{corrections.length > 1 ? 's' : ''} will be created:{' '}
              {corrections.map(({ row, diff }, index) => (
                <span key={row.accountId}>
                  {index > 0 && ' · '}
                  <span className={`amount ${diff! < 0 ? 'expense' : 'income'}`}>
                    {diff! < 0 ? '−' : '+'}
                    {formatMoney(Math.abs(diff!), row.currency)}
                  </span>{' '}
                  {row.name}
                </span>
              ))}
              <span style={{ color: 'var(--dim)' }}>
                {' '}
                — recategorize later if it was real spending.
              </span>
            </>
          ) : (
            'Enter an actual balance that differs from the computed one to create a correction.'
          )}
        </span>
        <button className="btn" onClick={submit} disabled={busy || corrections.length === 0}>
          Create {corrections.length || ''} correction{corrections.length === 1 ? '' : 's'} →
        </button>
      </div>

      {history != null && history.length > 0 && (
        <div className="card-deep" style={{ marginTop: 16 }}>
          <h2>Past reconciliations</h2>
          {history.map((entry) => (
            <div key={entry.date} className="settings-row" style={{ fontSize: 12 }}>
              <span className="amount muted">{formatDate(entry.date)}</span>
              <span className="muted" style={{ flex: 1, textAlign: 'right' }}>
                {entry.corrections.map((c, index) => (
                  <span key={index}>
                    {index > 0 && ' · '}
                    <span className={`amount ${c.type === 'EXPENSE' ? 'expense' : 'income'}`}>
                      {c.type === 'EXPENSE' ? '−' : '+'}
                      {formatMoney(c.amount, c.currency)}
                    </span>{' '}
                    {c.accountName}
                  </span>
                ))}
              </span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
