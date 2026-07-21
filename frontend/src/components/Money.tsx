import { useSettings } from '../context/SettingsContext';
import { convert, formatMoney } from '../utils/money';

/**
 * A converted "≈ NZ$123.45" companion amount, or null when conversion is off,
 * impossible (missing rate — fail-soft, FR-6.1) or pointless (same currency).
 * Current cache rates are correct here: this renders "now"-amounts (balances,
 * row companions); historical aggregates are converted by the backend.
 */
export function Approx({ value, currency }: { value: number; currency: string }) {
  const { displayCurrency, fxRates } = useSettings();
  if (displayCurrency === 'ORIGINAL' || displayCurrency === currency || !fxRates) return null;
  const converted = convert(value, currency, displayCurrency, fxRates.rates);
  if (converted == null) return null;
  return <span className="approx amount">≈ {formatMoney(converted, displayCurrency)}</span>;
}
