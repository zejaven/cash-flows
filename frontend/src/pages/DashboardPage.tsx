import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  apiErrorMessage,
  getAccounts,
  getAveragingProfiles,
  getDebtSummary,
  getForecast,
  getRecurringQueue,
  getSummary,
  getTransactions,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { TransactionModal } from '../components/TransactionForm';
import { TransferModal } from '../components/TransferForm';
import { CURRENCY_SYMBOLS, convert, currencyTileClass, formatMoney } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type { Account, AveragingProfile, DebtSummary, ForecastResponse, Summary } from '../types';

/** "NZ$27,779.64" -> ["NZ$27,779", ".64"] so the cents render smaller (mockup 2a). */
function splitCents(text: string): [string, string] {
  const dot = text.lastIndexOf('.');
  return dot < 0 ? [text, ''] : [text.slice(0, dot), text.slice(dot)];
}

/** Rounded companion amount for table cells: "≈ NZ$612". */
function approxShort(value: number, currency: string): string {
  const symbol = CURRENCY_SYMBOLS[currency];
  const amount = Math.round(value).toLocaleString('en-US');
  return symbol ? `≈ ${symbol}${amount}` : `≈ ${amount} ${currency}`;
}

export function DashboardPage() {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [queueCount, setQueueCount] = useState<number | null>(null);
  const [reviewCount, setReviewCount] = useState<number | null>(null);
  const [profiles, setProfiles] = useState<AveragingProfile[]>([]);
  const [forecast, setForecast] = useState<ForecastResponse | null>(null);
  const [debtSummary, setDebtSummary] = useState<DebtSummary | null>(null);
  const [modal, setModal] = useState<'expense' | 'transfer' | null>(null);
  const { displayCurrency, fxRates } = useSettings();
  const { toast } = useToast();

  const reload = useCallback(() => {
    const day = today();
    getAccounts()
      .then(setAccounts)
      .catch((e) => toast(apiErrorMessage(e), true));
    if (displayCurrency !== 'ORIGINAL') {
      getSummary({}, day, displayCurrency)
        .then(setSummary)
        .catch(() => setSummary(null));
    } else {
      setSummary(null);
    }
    getRecurringQueue(day)
      .then((queue) => setQueueCount(queue.length))
      .catch(() => setQueueCount(null));
    getTransactions({ needsReview: true }, 0, 1)
      .then((page) => setReviewCount(page.totalItems))
      .catch(() => setReviewCount(null));
    getAveragingProfiles(day)
      .then((list) => setProfiles(list.filter((profile) => !profile.archived)))
      .catch(() => setProfiles([]));
    getForecast(day, 12, displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency)
      .then(setForecast)
      .catch(() => setForecast(null));
    getDebtSummary(displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency)
      .then(setDebtSummary)
      .catch(() => setDebtSummary(null));
  }, [displayCurrency, toast]);

  useEffect(reload, [reload]);

  const groups = useMemo(() => {
    if (!accounts) return [];
    const map = new Map<string, Account[]>();
    for (const account of accounts) {
      const key = account.bankName ?? 'Other';
      map.set(key, [...(map.get(key) ?? []), account]);
    }
    // banks first (as encountered), "Other" last
    return [...map.entries()].sort((a, b) =>
      a[0] === 'Other' ? 1 : b[0] === 'Other' ? -1 : 0,
    );
  }, [accounts]);

  const total = useMemo(() => {
    if (!accounts || displayCurrency === 'ORIGINAL' || !fxRates) return null;
    let sum = 0;
    let incomplete = false;
    for (const account of accounts) {
      const converted = convert(account.balance, account.currency, displayCurrency, fxRates.rates);
      if (converted == null) incomplete = true;
      else sum += converted;
    }
    return { sum, incomplete };
  }, [accounts, displayCurrency, fxRates]);

  const groupTotal = (list: Account[]) => {
    if (displayCurrency === 'ORIGINAL' || !fxRates) return null;
    let sum = 0;
    for (const account of list) {
      const converted = convert(account.balance, account.currency, displayCurrency, fxRates.rates);
      if (converted != null) sum += converted;
    }
    return sum;
  };

  const showConv = displayCurrency !== 'ORIGINAL' && fxRates != null;

  const convText = (account: Account): string => {
    if (!showConv || account.currency === displayCurrency) return '';
    const converted = convert(account.balance, account.currency, displayCurrency, fxRates!.rates);
    return converted == null ? '' : approxShort(converted, displayCurrency);
  };

  if (accounts == null) return <Spinner />;

  const [totalInt, totalCents] = total ? splitCents(formatMoney(total.sum, displayCurrency)) : ['—', ''];

  return (
    <>
      <div className="dash-hero">
        <div>
          <span className="overline">Total · all accounts</span>
          <div className="total amount">
            {total ? (
              <>
                ≈ {totalInt}
                {totalCents && <span className="cents">{totalCents}</span>}
                {total.incomplete && <span className="approx"> (partial)</span>}
              </>
            ) : (
              '—'
            )}
          </div>
          {summary && (
            <div className="hero-strip amount">
              <span className="expense">
                −{formatMoney(summary.week.expense, summary.currency)} this week
              </span>
              <span className="sep">·</span>
              <span className="expense">
                −{formatMoney(summary.month.expense, summary.currency)} this month
              </span>
              <span className="sep">·</span>
              <span className="income">
                +{formatMoney(summary.month.income, summary.currency)} income
              </span>
            </div>
          )}
        </div>
        <div className="hero-actions">
          <button className="btn" onClick={() => setModal('expense')}>
            + Expense
          </button>
          <button className="btn-ghost" onClick={() => setModal('transfer')}>
            ⇄ Transfer
          </button>
        </div>
      </div>

      <div className="dash-grid">
        <div>
          {accounts.length === 0 ? (
            <div className="placeholder">No accounts yet — add them in Settings</div>
          ) : (
            <div className="acct-table">
              <div className="acct-head overline">
                <span>Account</span>
                <span className="num">Native</span>
                <span className="num">{showConv ? `≈ ${displayCurrency}` : ''}</span>
              </div>
              {groups.map(([groupName, groupAccounts]) => {
                const sum = groupTotal(groupAccounts);
                return (
                  <Fragment key={groupName}>
                    <div className="acct-group overline">
                      <span className="group-name">{groupName}</span>
                      <span className="only-desktop" />
                      <span className="num amount">
                        {sum != null ? approxShort(sum, displayCurrency) : ''}
                      </span>
                    </div>
                    {groupAccounts.map((account) => (
                      <div key={account.id} className="acct-row">
                        <span className={`tile ${currencyTileClass(account.currency)}`}>
                          {account.currency}
                        </span>
                        <span className="acct-name">
                          {account.name}{' '}
                          <span className="acct-sub">
                            {account.accountNumber
                              ? `…${account.accountNumber.slice(-4)}`
                              : account.type.toLowerCase()}
                          </span>
                        </span>
                        <span className="acct-native amount">
                          {formatMoney(account.balance, account.currency)}
                        </span>
                        <span className="acct-conv amount">{convText(account)}</span>
                      </div>
                    ))}
                  </Fragment>
                );
              })}
              {total && (
                <div className="acct-total">
                  <span className="total-label">Total</span>
                  <span className="only-desktop" />
                  <span className="num amount">≈ {formatMoney(total.sum, displayCurrency)}</span>
                </div>
              )}
            </div>
          )}
          <div className="footnote">
            Balances = starting balance + transactions · Rates: open.er-api.com
            {fxRates ? `, ${formatDate(fxRates.updatedAt.slice(0, 10))}` : ''}
          </div>
        </div>

        <div className="dash-side">
          {(reviewCount != null || queueCount != null) && (
            <div className="side-card">
              <span className="overline review side-title">Needs attention</span>
              {reviewCount != null && (
                <div className="side-row">
                  <span className="lbl">Transactions to categorize</span>
                  <Link
                    to="/transactions?needsReview=true"
                    className={`amount ${reviewCount > 0 ? 'review-text' : 'income'}`}
                  >
                    <b>{reviewCount}</b>
                  </Link>
                </div>
              )}
              {queueCount != null && (
                <div className="side-row">
                  <span className="lbl">Recurring to confirm</span>
                  <Link
                    to="/recurring"
                    className={`amount ${queueCount > 0 ? 'review-text' : 'income'}`}
                  >
                    <b>{queueCount}</b>
                  </Link>
                </div>
              )}
            </div>
          )}

          {forecast != null && (
            <div className="side-card">
              <span className="overline side-title">Forecast</span>
              <div className="body">
                {forecast.firstNegative ? (
                  <>
                    Goes below zero on{' '}
                    <b className="amount review-text">{formatDate(forecast.firstNegative.date)}</b>.
                  </>
                ) : (
                  'All accounts stay positive in the 12-month horizon.'
                )}
              </div>
              <Link to="/forecast" className="go">
                Open forecast →
              </Link>
            </div>
          )}

          {profiles.length > 0 && (
            <div className="side-card">
              <span className="overline side-title">Averaging profiles</span>
              {profiles.map((profile) => (
                <div key={profile.id} className="side-row">
                  <span className="lbl">{profile.name}</span>
                  <span className="amount">
                    {profile.effectiveValue != null
                      ? `${formatMoney(profile.effectiveValue, profile.accountCurrency)}/${profile.granularity === 'DAILY' ? 'day' : 'mo'}`
                      : '—'}
                  </span>
                </div>
              ))}
            </div>
          )}

          {debtSummary != null && (debtSummary.owedToMe > 0 || debtSummary.iOwe > 0) && (
            <div className="side-card">
              <span className="overline side-title">Debts</span>
              <div className="side-row">
                <span className="lbl">Owed to me</span>
                <span className="amount income">
                  {debtSummary.approx && '≈ '}+{formatMoney(debtSummary.owedToMe, debtSummary.currency)}
                </span>
              </div>
              <div className="side-row">
                <span className="lbl">I owe</span>
                <span className="amount expense">
                  {debtSummary.approx && '≈ '}−{formatMoney(debtSummary.iOwe, debtSummary.currency)}
                </span>
              </div>
              <Link to="/debts" className="go">
                Manage debts →
              </Link>
            </div>
          )}
        </div>
      </div>

      <button className="fab" onClick={() => setModal('expense')} aria-label="Add expense">
        +
      </button>

      {modal === 'expense' && (
        <TransactionModal
          onClose={() => setModal(null)}
          onSaved={reload}
          onSwitchToTransfer={() => setModal('transfer')}
        />
      )}
      {modal === 'transfer' && <TransferModal onClose={() => setModal(null)} onSaved={reload} />}
    </>
  );
}
