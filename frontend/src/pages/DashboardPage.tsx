import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  apiErrorMessage,
  getAccounts,
  getAveragingProfiles,
  getForecast,
  getRecurringQueue,
  getSummary,
  getTransactions,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Approx } from '../components/Money';
import { TransactionModal } from '../components/TransactionForm';
import { TransferModal } from '../components/TransferForm';
import { convert, formatMoney } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type { Account, AveragingProfile, ForecastResponse, Summary } from '../types';

export function DashboardPage() {
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [queueCount, setQueueCount] = useState<number | null>(null);
  const [reviewCount, setReviewCount] = useState<number | null>(null);
  const [profiles, setProfiles] = useState<AveragingProfile[]>([]);
  const [forecast, setForecast] = useState<ForecastResponse | null>(null);
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
    getAveragingProfiles()
      .then((list) => setProfiles(list.filter((profile) => !profile.archived)))
      .catch(() => setProfiles([]));
    getForecast(day, 12, displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency)
      .then(setForecast)
      .catch(() => setForecast(null));
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

  if (accounts == null) return <Spinner />;

  return (
    <>
      <div className="card total-card">
        <h2>Total · all accounts</h2>
        <div className="total amount">
          {total ? `≈ ${formatMoney(total.sum, displayCurrency)}` : '—'}
          {total?.incomplete && <span className="approx"> (partial)</span>}
        </div>
        {summary && (
          <div className="sub amount">
            −{formatMoney(summary.week.expense, summary.currency)} this week · −
            {formatMoney(summary.month.expense, summary.currency)} this month · +
            {formatMoney(summary.month.income, summary.currency)} income
          </div>
        )}
        <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
          <button className="btn" onClick={() => setModal('expense')}>
            + Expense
          </button>
          <button className="btn-ghost" onClick={() => setModal('transfer')}>
            ⇄ Transfer
          </button>
        </div>
      </div>

      {(queueCount != null || reviewCount != null || forecast != null || profiles.length > 0) && (
        <div className="card">
          <h2>Needs attention</h2>
          {reviewCount != null && (
            <div className="settings-row">
              <div className="grow">Transactions to categorize</div>
              <Link to="/transactions?needsReview=true" className="amount">
                <b className={reviewCount > 0 ? 'expense' : 'income'}>{reviewCount}</b>
              </Link>
            </div>
          )}
          {queueCount != null && (
            <div className="settings-row">
              <div className="grow">Recurring to confirm</div>
              <Link to="/recurring" className="amount">
                <b className={queueCount > 0 ? 'expense' : 'income'}>{queueCount}</b>
              </Link>
            </div>
          )}
          {forecast != null && (
            <div className="settings-row">
              <div className="grow">Forecast</div>
              <Link to="/forecast" style={{ fontSize: 13 }}>
                {forecast.firstNegative
                  ? `⚠ below zero on ${formatDate(forecast.firstNegative.date)}`
                  : 'all accounts positive in horizon →'}
              </Link>
            </div>
          )}
          {profiles.map((profile) => (
            <div key={profile.id} className="settings-row">
              <div className="grow muted">{profile.name}</div>
              <span className="amount muted" style={{ fontSize: 13 }}>
                {profile.effectiveValue != null
                  ? `${formatMoney(profile.effectiveValue, profile.accountCurrency)}/${profile.granularity === 'DAILY' ? 'day' : 'mo'}`
                  : '—'}
              </span>
            </div>
          ))}
        </div>
      )}

      {accounts.length === 0 && (
        <div className="placeholder">No accounts yet — add them in Settings</div>
      )}

      {groups.map(([groupName, groupAccounts]) => {
        const sum = groupTotal(groupAccounts);
        return (
          <div key={groupName}>
            <div className="group-header">
              <span>{groupName}</span>
              {sum != null && (
                <span className="group-total amount">≈ {formatMoney(sum, displayCurrency)}</span>
              )}
            </div>
            <div className="card" style={{ paddingTop: 6, paddingBottom: 6 }}>
              {groupAccounts.map((account) => (
                <div key={account.id} className="account-row">
                  <div className="currency-chip">{account.currency}</div>
                  <div className="info">
                    <div className="name">{account.name}</div>
                    <div className="meta">
                      {account.accountNumber
                        ? `…${account.accountNumber.slice(-4)}`
                        : account.type.toLowerCase()}
                    </div>
                  </div>
                  <div className="balances">
                    <div className="native amount">
                      {formatMoney(account.balance, account.currency)}
                    </div>
                    <div className="converted">
                      <Approx value={account.balance} currency={account.currency} />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        );
      })}

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
