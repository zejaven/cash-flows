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
}: {
  history: { date: string; value: number }[];
  forecast: { date: string; value: number }[];
  currency: string;
  negativeDate: string | null;
}) {
  const width = 720;
  const height = 260;
  const pad = { top: 18, right: 14, bottom: 26, left: 56 };
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [hover, setHover] = useState<number | null>(null);

  const points: Point[] = useMemo(
    () => [
      ...history.map((p) => ({ ...p, phase: 'history' as const })),
      ...forecast.map((p) => ({ ...p, phase: 'forecast' as const })),
    ],
    [history, forecast],
  );

  const { xOf, yOf, ticks, zeroY, todayX } = useMemo(() => {
    const values = points.map((p) => p.value);
    const min = Math.min(0, ...values);
    const max = Math.max(...values, 1);
    const spread = max - min || 1;
    const yLo = min - spread * 0.06;
    const yHi = max + spread * 0.06;
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
      zeroY: min < 0 ? yOf(0) : null,
      todayX: history.length > 0 ? xOf(history.length - 1) : null,
    };
  }, [points, history.length]);

  const path = (phase: 'history' | 'forecast') => {
    const parts: string[] = [];
    points.forEach((point, index) => {
      if (point.phase !== phase && !(phase === 'forecast' && index === history.length - 1)) return;
      parts.push(`${parts.length === 0 ? 'M' : 'L'}${xOf(index).toFixed(1)},${yOf(point.value).toFixed(1)}`);
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
        {zeroY != null && (
          <line
            x1={pad.left}
            x2={width - pad.right}
            y1={zeroY}
            y2={zeroY}
            stroke="var(--muted)"
            strokeWidth="1"
            strokeDasharray="2 3"
          />
        )}
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
        {/* direct phase labels */}
        {history.length > 2 && (
          <text
            x={xOf(Math.floor(history.length / 2))}
            y={height - pad.bottom + 16}
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
            y={height - pad.bottom + 16}
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
              cy={yOf(points[negativeIndex].value)}
              r="4.5"
              fill="var(--expense)"
              stroke="var(--panel)"
              strokeWidth="2"
            />
            <text
              x={xOf(negativeIndex)}
              y={yOf(points[negativeIndex].value) - 9}
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
              cy={yOf(hovered.value)}
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

