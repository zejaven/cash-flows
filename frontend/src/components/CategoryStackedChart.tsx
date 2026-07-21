import { useMemo, useState } from 'react';
import { formatMoney } from '../utils/money';
import { compact, niceStep } from '../utils/chart';

export interface ChartBucket {
  label: string;
  total: number;
  byCategory: { categoryId: number | null; name: string; color: string | null; amount: number }[];
}

interface Series {
  key: string;
  name: string;
  color: string;
}

const OTHER_KEY = '__other__';
const UNCAT_KEY = '__uncat__';
const MAX_SERIES = 6; // + Other = ≤ 8 stacks total with uncategorized (dataviz non-negotiable)

/**
 * Stacked expense bars per period. Colors come from the user's own categories
 * (color follows the entity); an arbitrary user palette cannot be run through
 * the six-checks validator, so identity is never color-alone by construction:
 * a 1px surface stroke + 2px gaps separate look-alike segments geometrically,
 * and the legend, per-segment tooltip and the share table (the data view)
 * carry the names. Series order is fixed once per range, never per bar;
 * "Other"/"uncategorized" wear neutral tokens.
 */
export function CategoryStackedChart({
  buckets,
  currency,
  selected,
  onSelect,
}: {
  buckets: ChartBucket[];
  currency: string;
  selected: number | null;
  onSelect: (index: number) => void;
}) {
  const width = 720;
  const height = 250;
  const pad = { top: 12, right: 12, bottom: 24, left: 52 };
  const [hover, setHover] = useState<{ bucket: number; series: string } | null>(null);

  const { series, valueOf } = useMemo(() => {
    // fixed order for the whole range: top categories by grand total
    const totals = new Map<string, { name: string; color: string | null; sum: number }>();
    for (const bucket of buckets) {
      for (const row of bucket.byCategory) {
        const key = row.categoryId == null ? UNCAT_KEY : String(row.categoryId);
        const entry = totals.get(key) ?? { name: row.name, color: row.color, sum: 0 };
        entry.sum += row.amount;
        totals.set(key, entry);
      }
    }
    const ranked = [...totals.entries()]
      .filter(([key]) => key !== UNCAT_KEY)
      .sort((a, b) => b[1].sum - a[1].sum);
    const top = ranked.slice(0, MAX_SERIES);
    const hasOther = ranked.length > MAX_SERIES;
    const hasUncat = totals.has(UNCAT_KEY);

    const series: Series[] = [
      ...top.map(([key, meta]) => ({
        key,
        name: meta.name,
        color: meta.color ?? 'var(--muted)',
      })),
      ...(hasOther ? [{ key: OTHER_KEY, name: 'Other', color: 'var(--muted)' }] : []),
      ...(hasUncat
        ? [{ key: UNCAT_KEY, name: 'uncategorized', color: 'var(--review)' }]
        : []),
    ];
    const topKeys = new Set(top.map(([key]) => key));

    const valueOf = (bucket: ChartBucket, key: string): number => {
      if (key === UNCAT_KEY) {
        return bucket.byCategory
          .filter((row) => row.categoryId == null)
          .reduce((sum, row) => sum + row.amount, 0);
      }
      if (key === OTHER_KEY) {
        return bucket.byCategory
          .filter((row) => row.categoryId != null && !topKeys.has(String(row.categoryId)))
          .reduce((sum, row) => sum + row.amount, 0);
      }
      return bucket.byCategory
        .filter((row) => String(row.categoryId) === key)
        .reduce((sum, row) => sum + row.amount, 0);
    };
    return { series, valueOf };
  }, [buckets]);

  const maxTotal = Math.max(...buckets.map((b) => b.total), 1);
  const step = niceStep(maxTotal / 3);
  const ticks: number[] = [];
  for (let v = 0; v <= maxTotal * 1.05; v += step) ticks.push(v);
  const yOf = (value: number) =>
    pad.top + (1 - value / (ticks[ticks.length - 1] || 1)) * (height - pad.top - pad.bottom);

  const plotWidth = width - pad.left - pad.right;
  const slot = plotWidth / Math.max(buckets.length, 1);
  const barWidth = Math.min(slot * 0.62, 56);

  const hovered =
    hover != null
      ? {
          bucket: buckets[hover.bucket],
          series: series.find((s) => s.key === hover.series)!,
          value: valueOf(buckets[hover.bucket], hover.series),
        }
      : null;

  if (buckets.length === 0) return null;

  return (
    <div style={{ position: 'relative' }}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', height: 'auto', display: 'block' }}
        role="img"
        aria-label={`Spending by category, ${currency}`}
      >
        {ticks.map((tick) => (
          <g key={tick}>
            <line
              x1={pad.left}
              x2={width - pad.right}
              y1={yOf(tick)}
              y2={yOf(tick)}
              stroke="var(--border)"
              strokeWidth="1"
            />
            <text
              x={pad.left - 8}
              y={yOf(tick) + 3.5}
              textAnchor="end"
              fontSize="10.5"
              fill="var(--muted)"
            >
              {compact(tick)}
            </text>
          </g>
        ))}
        {buckets.map((bucket, bucketIndex) => {
          const x = pad.left + bucketIndex * slot + (slot - barWidth) / 2;
          let y = yOf(0);
          const segments = series
            .map((s) => ({ s, value: valueOf(bucket, s.key) }))
            .filter(({ value }) => value > 0);
          return (
            <g
              key={bucket.label}
              style={{ cursor: 'pointer' }}
              onClick={() => onSelect(bucketIndex)}
              onMouseLeave={() => setHover(null)}
            >
              {/* full-column hit target */}
              <rect
                x={pad.left + bucketIndex * slot}
                y={pad.top}
                width={slot}
                height={height - pad.top - pad.bottom}
                fill="transparent"
              />
              {segments.map(({ s, value }, segmentIndex) => {
                const segmentHeight = yOf(0) - yOf(value);
                y -= segmentHeight;
                const isTop = segmentIndex === segments.length - 1;
                return (
                  <rect
                    key={s.key}
                    x={x}
                    y={y + 1}
                    width={barWidth}
                    height={Math.max(segmentHeight - 2, 1)}
                    rx={isTop ? 4 : 0}
                    fill={s.color}
                    stroke="var(--panel)"
                    strokeWidth="1"
                    opacity={s.key === OTHER_KEY ? 0.55 : s.key === UNCAT_KEY ? 0.75 : 1}
                    onMouseEnter={() => setHover({ bucket: bucketIndex, series: s.key })}
                  />
                );
              })}
              <text
                x={x + barWidth / 2}
                y={height - pad.bottom + 15}
                textAnchor="middle"
                fontSize="10.5"
                fontWeight={selected === bucketIndex ? 700 : 400}
                fill={selected === bucketIndex ? 'var(--text)' : 'var(--muted)'}
              >
                {bucket.label}
              </text>
              {selected === bucketIndex && (
                <line
                  x1={x}
                  x2={x + barWidth}
                  y1={height - pad.bottom + 19}
                  y2={height - pad.bottom + 19}
                  stroke="var(--accent)"
                  strokeWidth="2"
                />
              )}
            </g>
          );
        })}
      </svg>
      {hovered && hover && (
        <div
          className="chart-tooltip amount"
          style={{
            left: `${((pad.left + hover.bucket * slot + slot / 2) / width) * 100}%`,
            transform:
              hover.bucket > buckets.length * 0.6 ? 'translateX(-105%)' : 'translateX(8px)',
          }}
        >
          <div className="muted" style={{ fontSize: 11 }}>
            {hovered.series.name} · {hovered.bucket.label}
          </div>
          <b>{formatMoney(hovered.value, currency)}</b>
          <span className="muted">
            {' '}
            · {hovered.bucket.total > 0 ? Math.round((hovered.value / hovered.bucket.total) * 100) : 0}%
          </span>
        </div>
      )}
      {/* legend: always present for ≥2 series, text tokens */}
      <div className="chip-row" style={{ marginTop: 10 }}>
        {series.map((s) => (
          <span key={s.key} className="muted" style={{ fontSize: 12, display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <span
              style={{
                width: 10,
                height: 10,
                borderRadius: 3,
                background: s.color,
                display: 'inline-block',
                opacity: s.key === OTHER_KEY ? 0.55 : 1,
              }}
            />
            {s.name}
          </span>
        ))}
      </div>
    </div>
  );
}
