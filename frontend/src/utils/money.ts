/** Display scale per currency: VND/JPY have no minor units, USDT shown with 4. */
const SCALES: Record<string, number> = { VND: 0, JPY: 0, KRW: 0, USDT: 4 };

export const CURRENCY_SYMBOLS: Record<string, string> = {
  NZD: 'NZ$',
  USD: '$',
  EUR: '€',
  KZT: '₸',
  RUB: '₽',
  THB: '฿',
  VND: '₫',
  BYN: 'Br',
  AUD: 'A$',
};

/** Currencies seen in practice (FR-1.3) — suggestions only, free input allowed. */
export const KNOWN_CURRENCIES = ['NZD', 'USD', 'EUR', 'KZT', 'RUB', 'THB', 'VND', 'BYN', 'USDT'];

export function scaleOf(currency: string): number {
  return SCALES[currency] ?? 2;
}

export function formatAmount(value: number, currency: string): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: scaleOf(currency),
    maximumFractionDigits: scaleOf(currency),
  });
}

/** "NZ$1,234.50", "฿4,120", "2,150.0000 USDT" (no symbol -> code suffix). */
export function formatMoney(value: number, currency: string): string {
  const symbol = CURRENCY_SYMBOLS[currency];
  const amount = formatAmount(value, currency);
  return symbol ? `${symbol}${amount}` : `${amount} ${currency}`;
}

/** Signed variant for transaction rows: "−NZ$52.14" / "+₸407,922". */
export function formatSigned(value: number, currency: string, sign: '+' | '-'): string {
  return `${sign === '-' ? '−' : '+'}${formatMoney(Math.abs(value), currency)}`;
}

/** Tinted tile class per currency (mockup account rows); stable hash fallback. */
const TILE_CLASSES = ['tile-blue', 'tile-green', 'tile-amber', 'tile-purple'];
const TILE_OVERRIDES: Record<string, string> = {
  USD: 'tile-green',
  USDT: 'tile-green',
  KZT: 'tile-blue',
  NZD: 'tile-amber',
  THB: 'tile-purple',
};

export function currencyTileClass(currency: string): string {
  const override = TILE_OVERRIDES[currency];
  if (override) return override;
  let hash = 0;
  for (const ch of currency) hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  return TILE_CLASSES[Math.abs(hash) % TILE_CLASSES.length];
}

/** Cross rate via the USD-based table; null when either currency is missing. */
export function convert(
  value: number,
  from: string,
  to: string,
  rates: Record<string, number>,
): number | null {
  const peg = (c: string) => (c === 'USDT' ? 'USD' : c);
  const fromRate = rates[peg(from)];
  const toRate = rates[peg(to)];
  if (!fromRate || !toRate) return null;
  return (value * toRate) / fromRate;
}
