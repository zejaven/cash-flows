import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  createAveragingProfile,
  deleteAveragingProfile,
  getAccounts,
  getAveragingProfiles,
  getCategories,
  getForecast,
  previewAveragingProfile,
  setProfileForecastVisible,
  updateAveragingProfile,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Modal } from '../components/Modal';
import { ForecastChart } from '../components/ForecastChart';
import { formatMoney } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type {
  Account,
  AveragingGranularity,
  AveragingProfile,
  AveragingWindowMode,
  Category,
  CategoryFilterMode,
  ComputedValue,
  ForecastResponse,
} from '../types';

const HORIZONS = [3, 6, 12, 24];
const HORIZON_KEY = 'forecast-horizon';

export function ForecastPage() {
  const [tab, setTab] = useState<'forecast' | 'profiles'>('forecast');
  return (
    <>
      <div style={{ marginBottom: 14 }}>
        <div className="pill-switch">
          <button className={tab === 'forecast' ? 'active' : ''} onClick={() => setTab('forecast')}>
            Forecast
          </button>
          <button className={tab === 'profiles' ? 'active' : ''} onClick={() => setTab('profiles')}>
            Averaging profiles
          </button>
        </div>
      </div>
      {tab === 'forecast' ? <ForecastView /> : <ProfilesView />}
    </>
  );
}

function ForecastView() {
  const [data, setData] = useState<ForecastResponse | null>(null);
  const [horizon, setHorizon] = useState(() => {
    const stored = Number(localStorage.getItem(HORIZON_KEY));
    return HORIZONS.includes(stored) ? stored : 12;
  });
  const [accountId, setAccountId] = useState<number | 'all'>('all');
  const { displayCurrency } = useSettings();
  const { toast } = useToast();
  // total needs a concrete currency; in ORIGINAL mode fall back to NZD with a note
  const currency = displayCurrency === 'ORIGINAL' ? 'NZD' : displayCurrency;

  useEffect(() => {
    setData(null);
    getForecast(today(), horizon, currency)
      .then(setData)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [horizon, currency, toast]);

  const series = useMemo(() => {
    if (!data) return null;
    const pick = (s: ForecastResponse['history']) =>
      accountId === 'all' ? s.total : (s.series[String(accountId)] ?? []);
    return {
      history: data.history.dates.map((date, i) => ({ date, value: pick(data.history)[i] ?? 0 })),
      forecast: data.forecast.dates.map((date, i) => ({ date, value: pick(data.forecast)[i] ?? 0 })),
    };
  }, [data, accountId]);

  const negativeForSelection = useMemo(() => {
    if (!data) return null;
    if (accountId === 'all') return data.firstNegative?.date ?? null;
    return data.negativeDates.find((n) => n.accountId === accountId)?.date ?? null;
  }, [data, accountId]);

  if (data == null) return <Spinner />;

  const accountName = (id: number) => data.accounts.find((a) => a.id === id)?.name ?? `#${id}`;
  const chartCurrency =
    accountId === 'all'
      ? data.currency
      : (data.accounts.find((a) => a.id === accountId)?.currency ?? data.currency);

  return (
    <>
      <div className="filters">
        <select
          value={accountId === 'all' ? 'all' : String(accountId)}
          onChange={(e) =>
            setAccountId(e.target.value === 'all' ? 'all' : Number(e.target.value))
          }
        >
          <option value="all">All accounts · {data.currency}</option>
          {data.accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.name} ({account.currency})
            </option>
          ))}
        </select>
        <select
          value={horizon}
          onChange={(e) => {
            const next = Number(e.target.value);
            localStorage.setItem(HORIZON_KEY, String(next));
            setHorizon(next);
          }}
        >
          {HORIZONS.map((months) => (
            <option key={months} value={months}>
              Horizon {months} mo
            </option>
          ))}
        </select>
        {displayCurrency === 'ORIGINAL' && (
          <span className="chip">totals shown in NZD (ORIGINAL mode has no total currency)</span>
        )}
        {data.approxTotal && <span className="chip badge-review">partial rates</span>}
      </div>

      {data.firstNegative && (
        <div className="forecast-alert">
          ⚠ {accountName(data.firstNegative.accountId)} drops below zero on{' '}
          {formatDate(data.firstNegative.date)}
        </div>
      )}

      <div className="card">
        {series && (
          <ForecastChart
            history={series.history}
            forecast={series.forecast}
            currency={chartCurrency}
            negativeDate={negativeForSelection}
            horizonMonths={horizon}
          />
        )}
      </div>

      <div className="card" style={{ overflowX: 'auto' }}>
        <h2>Upcoming changes — computed live, nothing is materialized</h2>
        {data.eventDays.length === 0 ? (
          <div className="placeholder">
            No scheduled events in this horizon — add recurring rules or planned one-offs
          </div>
        ) : (
          <table className="event-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>Applied changes</th>
                <th style={{ textAlign: 'right' }}>Total ≈{data.currency}</th>
              </tr>
            </thead>
            <tbody>
              {data.eventDays.map((day) => {
                const negative = Object.values(day.balances).some((b) => b < 0);
                return (
                  <tr key={day.date} className={negative ? 'negative-row' : ''}>
                    <td className="mono" style={{ whiteSpace: 'nowrap' }}>
                      {formatDate(day.date)}
                    </td>
                    <td>
                      {day.items.map((item, index) => (
                        <div key={index} className="amount" style={{ fontSize: 12.5 }}>
                          <span className="muted">{item.label}</span>{' '}
                          <span className={item.delta < 0 ? 'expense' : 'income'}>
                            {item.approx && '≈'}
                            {item.delta < 0 ? '−' : '+'}
                            {formatMoney(
                              Math.abs(item.delta),
                              data.accounts.find((a) => a.id === item.accountId)?.currency ?? '',
                            )}
                          </span>
                          {item.counterDelta != null && item.counterAccountId != null && (
                            <span className="income">
                              {' '}
                              → +
                              {formatMoney(
                                item.counterDelta,
                                data.accounts.find((a) => a.id === item.counterAccountId)
                                  ?.currency ?? '',
                              )}
                            </span>
                          )}
                        </div>
                      ))}
                    </td>
                    <td className="amount" style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {formatMoney(day.total, data.currency)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}

function ProfilesView() {
  const [profiles, setProfiles] = useState<AveragingProfile[] | null>(null);
  const [modal, setModal] = useState<AveragingProfile | 'new' | null>(null);
  const { toast } = useToast();

  const reload = useCallback(() => {
    getAveragingProfiles(today())
      .then(setProfiles)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  useEffect(reload, [reload]);

  const remove = async (profile: AveragingProfile) => {
    if (!confirm(`Delete profile "${profile.name}"?`)) return;
    try {
      await deleteAveragingProfile(profile.id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const toggleVisible = async (profile: AveragingProfile) => {
    try {
      await setProfileForecastVisible(profile.id, !profile.forecastVisible, today());
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  return (
    <div className="card">
      <h2>
        Averaging profiles — feed the forecast as daily/monthly virtual expenses{' '}
        <button className="row-btn" style={{ float: 'right' }} onClick={() => setModal('new')}>
          + New profile
        </button>
      </h2>
      {profiles == null ? (
        <Spinner />
      ) : profiles.length === 0 ? (
        <div className="placeholder">No profiles yet</div>
      ) : (
        profiles.map((profile) => (
          <div key={profile.id} className="settings-row">
            <div className="grow">
              <div>
                {profile.name}
                {profile.manualOverride != null && (
                  <span className="badge badge-review"> manual ✎</span>
                )}
                {!profile.forecastVisible && (
                  <span className="badge badge-review"> not in forecast</span>
                )}
                {profile.archived && <span className="badge badge-review"> archived</span>}
              </div>
              <div className="muted" style={{ fontSize: 12.5 }}>
                {profile.windowMode === 'ROLLING'
                  ? `last ${profile.rollingDays} days`
                  : `${formatDate(profile.periodFrom ?? '')} – ${formatDate(profile.periodTo ?? '')}`}{' '}
                · {profile.categoryFilterMode === 'INCLUDE' ? 'only selected' : 'all except selected'}{' '}
                · {profile.accountName}
              </div>
            </div>
            <span className="amount expense">
              {profile.effectiveValue != null ? (
                <>
                  {profile.computed.approx && '≈ '}
                  {formatMoney(profile.effectiveValue, profile.accountCurrency)}/
                  {profile.granularity === 'DAILY' ? 'day' : 'mo'}
                </>
              ) : (
                <span className="muted">—</span>
              )}
            </span>
            <div className="row-actions">
              <button
                className="row-btn"
                title={profile.forecastVisible ? 'Hide from forecast' : 'Show in forecast'}
                onClick={() => toggleVisible(profile)}
              >
                {profile.forecastVisible ? '👁' : '🙈'}
              </button>
              <button className="row-btn" onClick={() => setModal(profile)}>
                Edit
              </button>
              <button className="row-btn" onClick={() => remove(profile)}>
                Delete
              </button>
            </div>
          </div>
        ))
      )}
      {modal && (
        <ProfileForm
          existing={modal === 'new' ? null : modal}
          onClose={() => setModal(null)}
          onSaved={reload}
        />
      )}
    </div>
  );
}

function ProfileForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: AveragingProfile | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [name, setName] = useState(existing?.name ?? '');
  const [accountId, setAccountId] = useState<number | ''>(existing?.accountId ?? '');
  const [granularity, setGranularity] = useState<AveragingGranularity>(
    existing?.granularity ?? 'DAILY',
  );
  const [windowMode, setWindowMode] = useState<AveragingWindowMode>(
    existing?.windowMode ?? 'FIXED',
  );
  const [periodFrom, setPeriodFrom] = useState(existing?.periodFrom ?? '');
  const [periodTo, setPeriodTo] = useState(existing?.periodTo ?? today());
  const [rollingDays, setRollingDays] = useState(String(existing?.rollingDays ?? 30));
  const [mode, setMode] = useState<CategoryFilterMode>(existing?.categoryFilterMode ?? 'INCLUDE');
  const [selected, setSelected] = useState<Set<number>>(new Set(existing?.categoryIds ?? []));
  const [override, setOverride] = useState(
    existing?.manualOverride != null ? String(existing.manualOverride) : '',
  );
  const [computed, setComputed] = useState<ComputedValue | null>(null);
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getAccounts().then(setAccounts).catch(() => {});
    getCategories()
      .then((list) => setCategories(list.filter((c) => c.type === 'EXPENSE' && !c.system)))
      .catch(() => {});
  }, []);

  const windowReady =
    !!accountId && (windowMode === 'ROLLING' ? Number(rollingDays) >= 1 : !!periodFrom && !!periodTo);

  // live "Computed result" (mockup 4a)
  useEffect(() => {
    if (!windowReady) {
      setComputed(null);
      return;
    }
    const timer = setTimeout(() => {
      previewAveragingProfile(
        {
          accountId: Number(accountId),
          granularity,
          windowMode,
          periodFrom: windowMode === 'FIXED' ? periodFrom : null,
          periodTo: windowMode === 'FIXED' ? periodTo : null,
          rollingDays: windowMode === 'ROLLING' ? Number(rollingDays) : null,
          categoryFilterMode: mode,
          categoryIds: [...selected],
        },
        today(),
      )
        .then(setComputed)
        .catch(() => setComputed(null));
    }, 350);
    return () => clearTimeout(timer);
  }, [accountId, granularity, windowMode, periodFrom, periodTo, rollingDays, mode, selected, windowReady]);

  const toggleCategory = (id: number) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!windowReady) return;
    setBusy(true);
    try {
      const body = {
        name,
        accountId: Number(accountId),
        granularity,
        windowMode,
        periodFrom: windowMode === 'FIXED' ? periodFrom : null,
        periodTo: windowMode === 'FIXED' ? periodTo : null,
        rollingDays: windowMode === 'ROLLING' ? Number(rollingDays) : null,
        categoryFilterMode: mode,
        categoryIds: [...selected],
        manualOverride: override ? Number(override) : null,
        archived: existing?.archived ?? false,
      };
      if (existing) await updateAveragingProfile(existing.id, body, today());
      else await createAveragingProfile(body, today());
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  const account = accounts.find((a) => a.id === accountId);

  return (
    <Modal title={existing ? 'Edit profile' : 'New averaging profile'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="pf-name">Name</label>
          <input
            id="pf-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Average groceries"
            required
          />
        </div>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            className={windowMode === 'FIXED' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setWindowMode('FIXED')}
          >
            Fixed period
          </button>
          <button
            type="button"
            className={windowMode === 'ROLLING' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setWindowMode('ROLLING')}
          >
            Rolling window
          </button>
        </div>
        {windowMode === 'FIXED' ? (
          <div className="form-grid">
            <div className="form-row">
              <label htmlFor="pf-from">Period from</label>
              <input
                id="pf-from"
                type="date"
                value={periodFrom}
                onChange={(e) => setPeriodFrom(e.target.value)}
                required
              />
            </div>
            <div className="form-row">
              <label htmlFor="pf-to">Period to</label>
              <input
                id="pf-to"
                type="date"
                value={periodTo}
                onChange={(e) => setPeriodTo(e.target.value)}
                required
              />
            </div>
          </div>
        ) : (
          <div className="form-row">
            <label htmlFor="pf-rolling">Last N days (ending today)</label>
            <input
              id="pf-rolling"
              type="number"
              min="1"
              value={rollingDays}
              onChange={(e) => setRollingDays(e.target.value)}
              required
            />
            <div className="muted" style={{ fontSize: 12.5, marginTop: 4 }}>
              Always averages the most recent {rollingDays || 'N'} days — no need to edit dates as
              time passes.
            </div>
          </div>
        )}
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            className={granularity === 'DAILY' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setGranularity('DAILY')}
          >
            Per day
          </button>
          <button
            type="button"
            className={granularity === 'MONTHLY' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setGranularity('MONTHLY')}
          >
            Per month · applied on the 1st
          </button>
        </div>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            className={mode === 'INCLUDE' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setMode('INCLUDE')}
          >
            Include selected
          </button>
          <button
            type="button"
            className={mode === 'EXCLUDE' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setMode('EXCLUDE')}
          >
            Exclude selected
          </button>
        </div>
        <div className="form-row">
          <label>Categories · Correction is always excluded</label>
          <div className="chip-row" style={{ marginBottom: 0 }}>
            {categories.map((category) => (
              <button
                type="button"
                key={category.id}
                className="chip"
                style={
                  selected.has(category.id)
                    ? { borderColor: category.color, color: category.color, fontWeight: 700 }
                    : undefined
                }
                onClick={() => toggleCategory(category.id)}
              >
                {category.name}
              </button>
            ))}
          </div>
        </div>
        <div className="form-row">
          <label htmlFor="pf-account">Applies to (virtual expense account)</label>
          <select
            id="pf-account"
            value={accountId}
            onChange={(e) => setAccountId(Number(e.target.value))}
            required
          >
            <option value="" disabled>
              Choose…
            </option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.currency})
              </option>
            ))}
          </select>
        </div>
        <div className="form-row muted amount" style={{ fontSize: 13 }}>
          {computed
            ? `Computed: ${computed.approx ? '≈ ' : ''}${formatMoney(computed.value, account?.currency ?? '')}/${granularity === 'DAILY' ? 'day' : 'mo'} · ${computed.dayCount} days · ${computed.txnCount} transactions · total ${formatMoney(computed.total, account?.currency ?? '')}`
            : 'Pick period and account to compute'}
        </div>
        <div className="form-row">
          <label htmlFor="pf-override">
            Manual override ({account?.currency ?? '…'}) — blank uses the computed value
          </label>
          <input
            id="pf-override"
            type="number"
            step="any"
            min="0"
            inputMode="decimal"
            value={override}
            onChange={(e) => setOverride(e.target.value)}
            placeholder="no history yet? enter an amount"
          />
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !windowReady}>
            Save profile
          </button>
        </div>
      </form>
    </Modal>
  );
}
