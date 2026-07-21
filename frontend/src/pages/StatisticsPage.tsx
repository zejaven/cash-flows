import { useEffect, useMemo, useState } from 'react';
import { apiErrorMessage, getFactVsForecast, getStatsCategories } from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { CategoryStackedChart } from '../components/CategoryStackedChart';
import { formatMoney } from '../utils/money';
import { today } from '../utils/dates';
import type { FactVsForecast, StatsCategoriesResponse, StatsGranularity } from '../types';

const PRESETS = [3, 6, 12];

function monthsAgoStart(months: number): string {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth() - (months - 1), 1);
  return `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}-01`;
}

function lastFinishedMonth(): { from: string; to: string; label: string } {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  const end = new Date(now.getFullYear(), now.getMonth(), 0);
  const iso = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return {
    from: iso(start),
    to: iso(end),
    label: start.toLocaleDateString('en-NZ', { month: 'long', year: 'numeric' }),
  };
}

export function StatisticsPage() {
  const [granularity, setGranularity] = useState<StatsGranularity>('MONTH');
  const [months, setMonths] = useState(6);
  const [includeSystem, setIncludeSystem] = useState(false);
  const [data, setData] = useState<StatsCategoriesResponse | null>(null);
  const [selectedBucket, setSelectedBucket] = useState<number | null>(null);
  const [comparison, setComparison] = useState<FactVsForecast | null>(null);
  const { displayCurrency } = useSettings();
  const { toast } = useToast();
  const currency = displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency;

  useEffect(() => {
    setData(null);
    setSelectedBucket(null);
    getStatsCategories(monthsAgoStart(months), today(), granularity, currency, includeSystem)
      .then((result) => {
        setData(result);
        setSelectedBucket(result.buckets.length - 1);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [granularity, months, includeSystem, currency, toast]);

  useEffect(() => {
    const period = lastFinishedMonth();
    getFactVsForecast(period.from, period.to, currency)
      .then(setComparison)
      .catch(() => setComparison(null));
  }, [currency]);

  const shareRows = useMemo(() => {
    if (!data) return [];
    const source =
      selectedBucket != null && data.buckets[selectedBucket]
        ? data.buckets[selectedBucket].byCategory
        : data.buckets.flatMap((bucket) => bucket.byCategory);
    const merged = new Map<
      string,
      { name: string; color: string | null; amount: number }
    >();
    for (const row of source) {
      const key = row.categoryId == null ? 'uncat' : String(row.categoryId);
      const entry = merged.get(key) ?? { name: row.name, color: row.color, amount: 0 };
      entry.amount += row.amount;
      merged.set(key, entry);
    }
    const rows = [...merged.values()].sort((a, b) => b.amount - a.amount);
    const total = rows.reduce((sum, row) => sum + row.amount, 0);
    return rows.map((row) => ({ ...row, pct: total > 0 ? (row.amount / total) * 100 : 0 }));
  }, [data, selectedBucket]);

  const shareTotal = shareRows.reduce((sum, row) => sum + row.amount, 0);
  const comparisonPeriod = lastFinishedMonth();

  return (
    <>
      <div className="filters">
        <div className="pill-switch">
          <button
            className={granularity === 'MONTH' ? 'active' : ''}
            onClick={() => setGranularity('MONTH')}
          >
            Monthly
          </button>
          <button
            className={granularity === 'WEEK' ? 'active' : ''}
            onClick={() => setGranularity('WEEK')}
          >
            Weekly
          </button>
        </div>
        <select value={months} onChange={(e) => setMonths(Number(e.target.value))}>
          {PRESETS.map((preset) => (
            <option key={preset} value={preset}>
              Last {preset} months
            </option>
          ))}
        </select>
        <label
          className="chip"
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}
        >
          <input
            type="checkbox"
            style={{ width: 'auto' }}
            checked={includeSystem}
            onChange={(e) => setIncludeSystem(e.target.checked)}
          />
          include Correction
        </label>
        {displayCurrency === 'ORIGINAL' && <span className="chip">shown in NZD</span>}
        {data?.incomplete && <span className="chip badge-review">partial rates</span>}
      </div>

      <div className="card">
        <h2>Spending by category</h2>
        {data == null ? (
          <Spinner />
        ) : data.buckets.every((bucket) => bucket.total === 0) ? (
          <div className="placeholder">No expenses in this range</div>
        ) : (
          <CategoryStackedChart
            buckets={data.buckets}
            currency={data.currency}
            selected={selectedBucket}
            onSelect={setSelectedBucket}
          />
        )}
      </div>

      {data != null && (
        <div className="card">
          <h2>
            Category share ·{' '}
            {selectedBucket != null && data.buckets[selectedBucket]
              ? data.buckets[selectedBucket].label
              : 'entire period'}{' '}
            {selectedBucket != null && (
              <button
                className="row-btn"
                style={{ float: 'right' }}
                onClick={() => setSelectedBucket(null)}
              >
                entire period
              </button>
            )}
          </h2>
          {shareRows.length === 0 ? (
            <div className="placeholder">Nothing spent</div>
          ) : (
            <>
              {shareRows.map((row) => (
                <div key={row.name} className="settings-row">
                  <div className="grow">
                    <span
                      className="color-dot"
                      style={{ background: row.color ?? 'var(--review)' }}
                    />
                    {row.name}
                  </div>
                  <span className="amount muted" style={{ marginRight: 12 }}>
                    {formatMoney(row.amount, data.currency)}
                  </span>
                  <span className="amount" style={{ width: 44, textAlign: 'right' }}>
                    {row.pct.toFixed(0)}%
                  </span>
                </div>
              ))}
              <div className="settings-row">
                <div className="grow muted">Total</div>
                <b className="amount">{formatMoney(shareTotal, data.currency)}</b>
              </div>
            </>
          )}
        </div>
      )}

      {comparison && (
        <div className="card">
          <h2>Fact vs forecast · {comparisonPeriod.label}</h2>
          <div className="chip-row">
            <span className="chip amount">
              Forecast (averages) <b>−{formatMoney(comparison.forecast, comparison.currency)}</b>
            </span>
            <span className="chip amount">
              Actual <b>−{formatMoney(comparison.actual, comparison.currency)}</b>
            </span>
            {comparison.diffPct != null && (
              <span className="chip amount">
                {comparison.diff >= 0 ? 'Under' : 'Over'} by{' '}
                <b className={comparison.diff >= 0 ? 'income' : 'expense'}>
                  {formatMoney(Math.abs(comparison.diff), comparison.currency)} ·{' '}
                  {Math.abs(comparison.diffPct).toFixed(0)}%
                </b>
              </span>
            )}
            {comparison.diffPct == null && (
              <span className="chip muted">no averaging profiles to compare against</span>
            )}
          </div>
        </div>
      )}
    </>
  );
}
