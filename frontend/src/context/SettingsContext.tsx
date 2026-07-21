import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { getFxRates } from '../api';
import type { FxRates } from '../types';

/** Display currency: 'ORIGINAL' = no conversion, otherwise an ISO 4217 code (FR-6.2). */
export const DISPLAY_CURRENCIES = ['NZD', 'USD', 'EUR', 'KZT', 'RUB', 'THB', 'VND', 'BYN'] as const;

export type DisplayCurrency = 'ORIGINAL' | (typeof DISPLAY_CURRENCIES)[number];

const CURRENCY_STORAGE_KEY = 'displayCurrency';

function readStored<T extends string>(key: string, valid: readonly string[], fallback: T): T {
  try {
    const stored = localStorage.getItem(key);
    return stored && valid.includes(stored) ? (stored as T) : fallback;
  } catch {
    return fallback;
  }
}

function store(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode — the choice just won't persist */
  }
}

interface SettingsContextValue {
  displayCurrency: DisplayCurrency;
  setDisplayCurrency: (currency: DisplayCurrency) => void;
  /** Daily FX table; null while loading or when the rate source is unavailable. */
  fxRates: FxRates | null;
}

const SettingsContext = createContext<SettingsContextValue>({
  displayCurrency: 'NZD',
  setDisplayCurrency: () => {},
  fxRates: null,
});

export const useSettings = () => useContext(SettingsContext);

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [displayCurrency, setCurrency] = useState<DisplayCurrency>(() =>
    readStored(CURRENCY_STORAGE_KEY, ['ORIGINAL', ...DISPLAY_CURRENCIES], 'NZD'),
  );
  const [fxRates, setFxRates] = useState<FxRates | null>(null);

  // fetch once per session, only when conversion is actually on; fail-soft (stay null)
  useEffect(() => {
    if (displayCurrency === 'ORIGINAL' || fxRates) return;
    getFxRates()
      .then(setFxRates)
      .catch(() => setFxRates(null));
  }, [displayCurrency, fxRates]);

  const value = useMemo<SettingsContextValue>(
    () => ({
      displayCurrency,
      setDisplayCurrency: (currency) => {
        store(CURRENCY_STORAGE_KEY, currency);
        setCurrency(currency);
      },
      fxRates,
    }),
    [displayCurrency, fxRates],
  );

  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}
