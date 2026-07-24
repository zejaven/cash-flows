import { useMemo, useRef, useState } from 'react';
import { formatMoney } from '../utils/money';
import { formatDate } from '../utils/dates';
import { compact, niceStep } from '../utils/chart';

interface Point {
  date: string;
  value: number;
  phase: 'history' | 'forecast';
}

/**
 * One measure in two phases: history (solid, --chart-history) continued by the
 * projection (dashed, --chart-forecast). Colors are theme tokens validated by
 * the dataviz six-checks against both surfaces. Single series — no legend box,
 * direct labels name the phases; the events table below is the data view.
 */
export function ForecastChart({
  history,
  forecast,
  currency,
  negativeDate,
  horizonMonths,
}: {
  history: { date: string; value: number }[];
  forecast: { date: string; value: number }[];
  currency: string;
  negativeDate: string | null;
  horizonMonths: number;
}) {
  const width = 720;
  const height = 288;
  // deep bottom band holds the vertical date labels; x-axis sits at its top edge
  const pad = { top: 18, right: 14, bottom: 62, left: 56 };
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [hover, setHover] = useState<number | null>(null);

  const points: Point[] = useMemo(
    () => [
      ...history.map((p) => ({ ...p, phase: 'history' as const })),
      ...forecast.map((p) => ({ ...p, phase: 'forecast' as const })),
    ],
    [history, forecast],
  );

  const { xOf, yOf, ticks, todayX } = useMemo(() => {
    // floor the domain at zero: a negative projected balance is meaningless here,
    // so the zero line becomes the bottom axis (merged with the date axis)
    const values = points.map((p) => Math.max(0, p.value));
    const yLo = 0;
    const max = Math.max(...values, 1);
    const yHi = max * 1.06;
    const spread = yHi - yLo;
    const xOf = (index: number) =>
      pad.left + (index / Math.max(points.length - 1, 1)) * (width - pad.left - pad.right);
    const yOf = (value: number) =>
      pad.top + ((yHi - value) / (yHi - yLo)) * (height - pad.top - pad.bottom);
    const step = niceStep(spread / 3);
    const ticks: number[] = [];
    for (let v = Math.ceil(yLo / step) * step; v <= yHi; v += step) ticks.push(v);
    return {
      xOf,
      yOf,
      ticks,
      todayX: history.length > 0 ? xOf(history.length - 1) : null,
    };
  }, [points, history.length]);

  /** Balance clamped at the zero floor — the chart never dips below the x-axis. */
  const clampedY = (value: number) => yOf(Math.max(0, value));

  // Vertical x-axis date labels; the interval scales with the horizon so they
  // never crowd: 3 mo → weekly, 6 mo → biweekly, 12 mo → monthly, 24 mo → bimonthly.
  const dateTicks = useMemo(() => {
    if (points.length < 2) return [];
    const parse = (iso: string) => {
      const [y, m, d] = iso.split('-').map(Number);
      return new Date(y, m - 1, d);
    };
    const first = parse(points[0].date);
    const last = parse(points[points.length - 1].date);
    const dayMs = 86_400_000;
    const spec =
      horizonMonths <= 3
        ? { mode: 'days' as const, interval: 7 }
        : horizonMonths <= 6
          ? { mode: 'days' as const, interval: 14 }
          : horizonMonths <= 12
            ? { mode: 'months' as const, interval: 1 }
            : { mode: 'months' as const, interval: 2 };

    const raw: { index: number; date: Date }[] = [];
    if (spec.mode === 'days') {
      // points are daily-contiguous, so the array index is the day offset
      for (let i = 0; i < points.length; i += spec.interval) {
        raw.push({ index: i, date: parse(points[i].date) });
      }
    } else {
      // align to calendar months divisible by the step (e.g. step 2 → Jan/Mar/May…)
      let mi = Math.ceil((first.getFullYear() * 12 + first.getMonth()) / spec.interval) * spec.interval;
      for (; ; mi += spec.interval) {
        const d = new Date(Math.floor(mi / 12), mi % 12, 1);
        if (d > last) break;
        if (d < first) continue;
        raw.push({ index: Math.round((d.getTime() - first.getTime()) / dayMs), date: d });
      }
    }

    let prevYear: number | null = null;
    return raw.map(({ index, date }) => {
      const mon = date.toLocaleDateString('en-NZ', { month: 'short' });
      const base = spec.mode === 'days' ? `${date.getDate()} ${mon}` : mon;
      const year = date.getFullYear();
      // append the year only where it changes (or on the first tick), to keep labels short
      const label = prevYear === null || year !== prevYear ? `${base} '${String(year).slice(2)}` : base;
      prevYear = year;
      return { index, label };
    });
  }, [points, horizonMonths]);

  const path = (phase: 'history' | 'forecast') => {
    const parts: string[] = [];
    points.forEach((point, index) => {
      if (point.phase !== phase && !(phase === 'forecast' && index === history.length - 1)) return;
      parts.push(`${parts.length === 0 ? 'M' : 'L'}${xOf(index).toFixed(1)},${clampedY(point.value).toFixed(1)}`);
    });
    return parts.join(' ');
  };

  const onMove = (event: React.MouseEvent<SVGSVGElement>) => {
    const svg = svgRef.current;
    if (!svg) return;
    const rect = svg.getBoundingClientRect();
    const x = ((event.clientX - rect.left) / rect.width) * width;
    const ratio = (x - pad.left) / (width - pad.left - pad.right);
    const index = Math.round(ratio * (points.length - 1));
    setHover(index >= 0 && index < points.length ? index : null);
  };

  if (points.length < 2) return null;

  const negativeIndex = negativeDate ? points.findIndex((p) => p.date === negativeDate) : -1;
  const hovered = hover != null ? points[hover] : null;

  return (
    <div style={{ position: 'relative' }}>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', height: 'auto', display: 'block' }}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        role="img"
        aria-label={`Balance history and forecast in ${currency}`}
      >
        {/* recessive grid + y labels (text tokens, never series color) */}
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
        {/* today divider */}
        {todayX != null && (
          <g>
            <line
              x1={todayX}
              x2={todayX}
              y1={pad.top}
              y2={height - pad.bottom}
              stroke="var(--muted)"
              strokeWidth="1"
              strokeDasharray="3 3"
            />
            <text x={todayX} y={pad.top - 5} textAnchor="middle" fontSize="10" fill="var(--muted)">
              today
            </text>
          </g>
        )}
        {/* series: 2px lines, thin marks */}
        <path d={path('history')} fill="none" stroke="var(--chart-history)" strokeWidth="2" />
        <path
          d={path('forecast')}
          fill="none"
          stroke="var(--chart-forecast)"
          strokeWidth="2"
          strokeDasharray="5 4"
        />
        {/* x-axis pinned to the bottom (never rides up with a negative forecast) */}
        <line
          x1={pad.left}
          x2={width - pad.right}
          y1={height - pad.bottom}
          y2={height - pad.bottom}
          stroke="var(--border)"
          strokeWidth="1"
        />
        {/* vertical date labels — interval scales with the horizon so they never collide */}
        {dateTicks.map((tick) => (
          <g key={tick.index}>
            <line
              x1={xOf(tick.index)}
              x2={xOf(tick.index)}
              y1={height - pad.bottom}
              y2={height - pad.bottom + 4}
              stroke="var(--border)"
              strokeWidth="1"
            />
            <text
              x={xOf(tick.index)}
              y={height - pad.bottom + 8}
              textAnchor="end"
              fontSize="9.5"
              fill="var(--muted)"
              transform={`rotate(-90 ${xOf(tick.index).toFixed(1)} ${height - pad.bottom + 8})`}
            >
              {tick.label}
            </text>
          </g>
        ))}
        {/* direct phase labels — kept clear of the date axis at the top */}
        {history.length > 2 && (
          <text
            x={xOf(Math.floor(history.length / 2))}
            y={pad.top - 5}
            textAnchor="middle"
            fontSize="10.5"
            fill="var(--muted)"
          >
            History
          </text>
        )}
        {forecast.length > 2 && (
          <text
            x={xOf(history.length + Math.floor(forecast.length / 2))}
            y={pad.top - 5}
            textAnchor="middle"
            fontSize="10.5"
            fill="var(--muted)"
          >
            Forecast
          </text>
        )}
        {/* negative-date marker (status color + label, not color-alone) */}
        {negativeIndex >= 0 && (
          <g>
            <circle
              cx={xOf(negativeIndex)}
              cy={clampedY(points[negativeIndex].value)}
              r="4.5"
              fill="var(--expense)"
              stroke="var(--panel)"
              strokeWidth="2"
            />
            <text
              x={xOf(negativeIndex)}
              y={clampedY(points[negativeIndex].value) - 9}
              textAnchor="middle"
              fontSize="10"
              fill="var(--expense)"
            >
              {'< 0'}
            </text>
          </g>
        )}
        {/* crosshair */}
        {hovered && hover != null && (
          <g pointerEvents="none">
            <line
              x1={xOf(hover)}
              x2={xOf(hover)}
              y1={pad.top}
              y2={height - pad.bottom}
              stroke="var(--muted)"
              strokeWidth="1"
            />
            <circle
              cx={xOf(hover)}
              cy={clampedY(hovered.value)}
              r="4"
              fill={hovered.phase === 'history' ? 'var(--chart-history)' : 'var(--chart-forecast)'}
              stroke="var(--panel)"
              strokeWidth="2"
            />
          </g>
        )}
      </svg>
      {hovered && hover != null && (
        <div
          className="chart-tooltip amount"
          style={{
            left: `${(xOf(hover) / width) * 100}%`,
            transform: xOf(hover) > width * 0.7 ? 'translateX(-105%)' : 'translateX(8px)',
          }}
        >
          <div className="muted" style={{ fontSize: 11 }}>
            {formatDate(hovered.date)} · {hovered.phase}
          </div>
          <b>{formatMoney(hovered.value, currency)}</b>
        </div>
      )}
    </div>
  );
}

