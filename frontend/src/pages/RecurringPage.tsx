import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  confirmLoanPayment,
  confirmPlanned,
  confirmRecurring,
  createPlanned,
  createRecurringRule,
  deletePlanned,
  deleteRecurringRule,
  dismissPlanned,
  getAccounts,
  getCategories,
  getPlanned,
  getRecurringQueue,
  getRecurringRules,
  setPlannedForecastVisible,
  setRecurringForecastVisible,
  skipRecurring,
  skipScheduleItem,
  updatePlanned,
  updateRecurringRule,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Modal } from '../components/Modal';
import { formatMoney } from '../utils/money';
import { formatDate, formatDayHeader, today } from '../utils/dates';
import type {
  Account,
  AmountStrategy,
  Category,
  Planned,
  QueueItem,
  RecurringRule,
  RecurringRuleRequest,
  ScheduleFrequency,
  TransactionType,
} from '../types';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function scheduleLabel(rule: RecurringRule): string {
  switch (rule.scheduleFreq) {
    case 'MONTHLY':
      return `monthly · day ${rule.dayOfMonth}`;
    case 'WEEKLY':
      return `weekly · ${WEEKDAYS[(rule.dayOfWeek ?? 1) - 1]}`;
    case 'EVERY_N_DAYS':
      return `every ${rule.intervalDays} days`;
  }
}

export function RecurringPage() {
  const [queue, setQueue] = useState<QueueItem[] | null>(null);
  const [rules, setRules] = useState<RecurringRule[] | null>(null);
  const [planned, setPlanned] = useState<Planned[] | null>(null);
  const [confirming, setConfirming] = useState<QueueItem | null>(null);
  const [ruleModal, setRuleModal] = useState<RecurringRule | 'new' | null>(null);
  const [plannedModal, setPlannedModal] = useState<Planned | 'new' | null>(null);
  const { toast } = useToast();

  const reload = useCallback(() => {
    const day = today();
    getRecurringQueue(day)
      .then(setQueue)
      .catch((e) => toast(apiErrorMessage(e), true));
    getRecurringRules(day)
      .then(setRules)
      .catch((e) => toast(apiErrorMessage(e), true));
    getPlanned()
      .then(setPlanned)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  useEffect(reload, [reload]);

  const skip = async (item: QueueItem) => {
    try {
      if (item.kind === 'RECURRING') await skipRecurring(item.ruleId!, item.dueDate);
      else if (item.kind === 'PLANNED') await dismissPlanned(item.plannedId!);
      else await skipScheduleItem(item.scheduleItemId!);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  /** One-click confirm with the scheduled amount/date — no modal (FR-13.7). */
  const canQuickConfirm = (item: QueueItem) =>
    item.kind === 'PLANNED' ||
    (item.kind === 'RECURRING' && item.suggestedAmount != null) ||
    (item.kind === 'LOAN' && item.accountId != null);

  const quickConfirm = async (item: QueueItem) => {
    try {
      if (item.kind === 'RECURRING') {
        await confirmRecurring({ ruleId: item.ruleId!, dueDate: item.dueDate });
      } else if (item.kind === 'PLANNED') {
        await confirmPlanned(item.plannedId!, {});
      } else {
        await confirmLoanPayment({ scheduleItemId: item.scheduleItemId! });
      }
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const removeRule = async (rule: RecurringRule) => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return;
    try {
      await deleteRecurringRule(rule.id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const toggleRuleVisible = async (rule: RecurringRule) => {
    try {
      await setRecurringForecastVisible(rule.id, !rule.forecastVisible, today());
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const removePlanned = async (item: Planned) => {
    if (!confirm('Delete planned transaction?')) return;
    try {
      await deletePlanned(item.id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const togglePlannedVisible = async (item: Planned) => {
    try {
      await setPlannedForecastVisible(item.id, !item.forecastVisible);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  return (
    <>
      <div className="section-head">
        <span className="overline review">To confirm · {queue?.length ?? '…'}</span>
        <span className="note">counted in the forecast until confirmed or skipped</span>
      </div>
      {queue == null ? (
        <Spinner />
      ) : queue.length === 0 ? (
        <div className="placeholder">Nothing due — all caught up</div>
      ) : (
        <div className="queue-grid">
          {queue.map((item) => (
            <div
              key={`${item.kind}-${item.ruleId ?? item.plannedId ?? item.scheduleItemId}-${item.dueDate}`}
              className="queue-card"
            >
              <div className="queue-head">
                <b>{item.name}</b>
                <span
                  className={`amount ${
                    item.type === 'TRANSFER' ? 'muted' : item.type === 'INCOME' ? 'income' : 'expense'
                  }`}
                >
                  {item.suggestedAmount != null ? (
                    <>
                      {item.approx && '≈ '}
                      {item.type === 'INCOME' ? '+' : item.type === 'TRANSFER' ? '⇄ ' : '−'}
                      {formatMoney(item.suggestedAmount, item.accountCurrency ?? '')}
                    </>
                  ) : (
                    <span className="muted">amount needed</span>
                  )}
                </span>
              </div>
              <div className="queue-meta">
                due {formatDayHeader(item.dueDate)} ·{' '}
                {item.kind === 'RECURRING'
                  ? 'recurring'
                  : item.kind === 'PLANNED'
                    ? 'planned one-off'
                    : 'loan payment'}
                {item.type === 'TRANSFER' && ` · ${item.accountName} → ${item.counterAccountName}`}
              </div>
              <div className="queue-actions">
                {canQuickConfirm(item) && (
                  <button className="btn" onClick={() => quickConfirm(item)}>
                    Confirm
                  </button>
                )}
                <button
                  className={canQuickConfirm(item) ? 'btn-flat' : 'btn'}
                  onClick={() => setConfirming(item)}
                >
                  {canQuickConfirm(item) ? 'Edit…' : 'Confirm…'}
                </button>
                <button className="btn-flat" onClick={() => skip(item)}>
                  Skip
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="section-head">
        <span className="overline">Recurring rules</span>
        <button className="btn btn-sm" onClick={() => setRuleModal('new')}>
          + Recurring rule
        </button>
      </div>
      {rules == null ? (
        <Spinner />
      ) : rules.length === 0 ? (
        <div className="placeholder">No recurring rules yet</div>
      ) : (
        <div className="list-table">
          {rules.map((rule) => (
            <div key={rule.id} className="settings-row">
              <div className="grow">
                <div>
                  {rule.name}{' '}
                  {rule.type === 'INCOME' && <span className="badge badge-income">income</span>}
                  {rule.amountStrategy === 'AVERAGE' && (
                    <span className="badge badge-review">avg {rule.avgLookback}</span>
                  )}
                  {rule.forecastOnly && (
                    <span className="badge badge-imported">forecast only</span>
                  )}
                  {!rule.forecastVisible && (
                    <span className="badge badge-review">not in forecast</span>
                  )}
                  {rule.archived && <span className="badge badge-review"> archived</span>}
                </div>
                <div className="muted" style={{ fontSize: 12.5 }}>
                  {scheduleLabel(rule)} · {rule.accountName}
                  {rule.type === 'TRANSFER' && ` → ${rule.counterAccountName}`}
                  {rule.nextDue && ` · next ${formatDate(rule.nextDue)}`}
                </div>
              </div>
              <span
                className={`amount ${
                  rule.type === 'TRANSFER' ? 'muted' : rule.type === 'INCOME' ? 'income' : 'expense'
                }`}
              >
                {rule.currentAmount != null ? (
                  <>
                    {rule.approxAmount && '≈ '}
                    {rule.type === 'INCOME' ? '+' : rule.type === 'TRANSFER' ? '⇄ ' : '−'}
                    {formatMoney(rule.currentAmount, rule.accountCurrency)}
                  </>
                ) : (
                  <span className="muted">no history</span>
                )}
              </span>
              <div className="row-actions">
                <button
                  className="row-btn"
                  title={rule.forecastVisible ? 'Hide from forecast' : 'Show in forecast'}
                  onClick={() => toggleRuleVisible(rule)}
                >
                  {rule.forecastVisible ? '👁' : '🙈'}
                </button>
                <button className="row-btn" onClick={() => setRuleModal(rule)}>
                  Edit
                </button>
                <button className="row-btn" onClick={() => removeRule(rule)}>
                  Delete
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="section-head">
        <span className="overline">Planned one-offs</span>
        <button className="btn-ghost btn-sm" onClick={() => setPlannedModal('new')}>
          + Planned one-off
        </button>
      </div>
      {planned == null ? (
        <Spinner />
      ) : planned.filter((item) => item.status === 'PENDING').length === 0 ? (
        <div className="placeholder">No planned one-offs</div>
      ) : (
        <div className="list-table">
          {planned
            .filter((item) => item.status === 'PENDING')
            .map((item) => (
              <div key={item.id} className="settings-row">
                <div className="grow">
                  <div>
                    {item.description || 'Planned'}
                    {!item.forecastVisible && (
                      <span className="badge badge-review"> not in forecast</span>
                    )}
                  </div>
                  <div className="muted" style={{ fontSize: 12.5 }}>
                    {formatDate(item.dueDate)} · {item.accountName}
                    {item.categoryName && ` · ${item.categoryName}`}
                  </div>
                </div>
                <span className={`amount ${item.type === 'INCOME' ? 'income' : 'expense'}`}>
                  {item.type === 'INCOME' ? '+' : '−'}
                  {formatMoney(item.amount, item.accountCurrency)}
                </span>
                <div className="row-actions">
                  <button
                    className="row-btn"
                    title={item.forecastVisible ? 'Hide from forecast' : 'Show in forecast'}
                    onClick={() => togglePlannedVisible(item)}
                  >
                    {item.forecastVisible ? '👁' : '🙈'}
                  </button>
                  <button className="row-btn" onClick={() => setPlannedModal(item)}>
                    Edit
                  </button>
                  <button className="row-btn" onClick={() => removePlanned(item)}>
                    Delete
                  </button>
                </div>
              </div>
            ))}
        </div>
      )}

      {confirming && (
        <ConfirmModal item={confirming} onClose={() => setConfirming(null)} onSaved={reload} />
      )}
      {ruleModal && (
        <RuleForm
          existing={ruleModal === 'new' ? null : ruleModal}
          onClose={() => setRuleModal(null)}
          onSaved={reload}
        />
      )}
      {plannedModal && (
        <PlannedForm
          existing={plannedModal === 'new' ? null : plannedModal}
          onClose={() => setPlannedModal(null)}
          onSaved={reload}
        />
      )}
    </>
  );
}

function ConfirmModal({
  item,
  onClose,
  onSaved,
}: {
  item: QueueItem;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(item.dueDate);
  const [amount, setAmount] = useState(
    item.suggestedAmount != null ? String(item.suggestedAmount) : '',
  );
  const [counterAmount, setCounterAmount] = useState(
    item.suggestedCounterAmount != null ? String(item.suggestedCounterAmount) : '',
  );
  // LOAN payments from a debt without a bound account need an explicit one
  const needsAccount = item.kind === 'LOAN' && item.accountId == null;
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountId, setAccountId] = useState<number | ''>('');
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    if (needsAccount) getAccounts().then(setAccounts).catch(() => {});
  }, [needsAccount]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      if (item.kind === 'RECURRING') {
        await confirmRecurring({
          ruleId: item.ruleId!,
          dueDate: item.dueDate,
          date,
          amount: amount ? Number(amount) : undefined,
          counterAmount: counterAmount ? Number(counterAmount) : undefined,
        });
      } else if (item.kind === 'PLANNED') {
        await confirmPlanned(item.plannedId!, {
          date,
          amount: amount ? Number(amount) : undefined,
          counterAmount: counterAmount ? Number(counterAmount) : undefined,
        });
      } else {
        await confirmLoanPayment({
          scheduleItemId: item.scheduleItemId!,
          date,
          amount: amount ? Number(amount) : undefined,
          accountId: accountId ? Number(accountId) : undefined,
        });
      }
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`Confirm · ${item.name}`} onClose={onClose}>
      <form onSubmit={submit}>
        {needsAccount && (
          <div className="form-row">
            <label htmlFor="cf-account">Account</label>
            <select
              id="cf-account"
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
        )}
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="cf-amount">
              Amount{item.accountCurrency ? ` (${item.accountCurrency})` : ''}
            </label>
            <input
              id="cf-amount"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              autoFocus
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="cf-date">Date</label>
            <input id="cf-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </div>
        </div>
        {item.type === 'TRANSFER' && (
          <div className="form-row">
            <label htmlFor="cf-counter">
              Amount received ({item.counterAccountCurrency})
            </label>
            <input
              id="cf-counter"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={counterAmount}
              onChange={(e) => setCounterAmount(e.target.value)}
              required
            />
          </div>
        )}
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !amount}>
            Confirm
          </button>
        </div>
      </form>
    </Modal>
  );
}

function RuleForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: RecurringRule | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [name, setName] = useState(existing?.name ?? '');
  const [type, setType] = useState<TransactionType>(existing?.type ?? 'EXPENSE');
  const [accountId, setAccountId] = useState<number | ''>(existing?.accountId ?? '');
  const [counterAccountId, setCounterAccountId] = useState<number | ''>(
    existing?.counterAccountId ?? '',
  );
  const [categoryId, setCategoryId] = useState<number | ''>(existing?.categoryId ?? '');
  const [freq, setFreq] = useState<ScheduleFrequency>(existing?.scheduleFreq ?? 'MONTHLY');
  const [dayOfMonth, setDayOfMonth] = useState(String(existing?.dayOfMonth ?? 1));
  const [dayOfWeek, setDayOfWeek] = useState(String(existing?.dayOfWeek ?? 1));
  const [intervalDays, setIntervalDays] = useState(String(existing?.intervalDays ?? 30));
  const [startDate, setStartDate] = useState(existing?.startDate ?? today());
  const [endDate, setEndDate] = useState(existing?.endDate ?? '');
  const [strategy, setStrategy] = useState<AmountStrategy>(existing?.amountStrategy ?? 'FIXED');
  const [amount, setAmount] = useState(existing?.amount != null ? String(existing.amount) : '');
  const [counterAmount, setCounterAmount] = useState(
    existing?.counterAmount != null ? String(existing.counterAmount) : '',
  );
  const [avgLookback, setAvgLookback] = useState(String(existing?.avgLookback ?? 3));
  const [forecastOnly, setForecastOnly] = useState(existing?.forecastOnly ?? false);
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getAccounts().then(setAccounts).catch(() => {});
    getCategories().then(setCategories).catch(() => {});
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!accountId) return;
    setBusy(true);
    try {
      const body: RecurringRuleRequest = {
        name,
        type,
        accountId: Number(accountId),
        counterAccountId: type === 'TRANSFER' && counterAccountId ? Number(counterAccountId) : null,
        categoryId: type !== 'TRANSFER' && categoryId ? Number(categoryId) : null,
        scheduleFreq: freq,
        dayOfMonth: freq === 'MONTHLY' ? Number(dayOfMonth) : null,
        dayOfWeek: freq === 'WEEKLY' ? Number(dayOfWeek) : null,
        intervalDays: freq === 'EVERY_N_DAYS' ? Number(intervalDays) : null,
        startDate,
        endDate: endDate || null,
        amountStrategy: type === 'TRANSFER' ? 'FIXED' : strategy,
        amount: strategy === 'FIXED' || type === 'TRANSFER' ? Number(amount) : null,
        counterAmount: type === 'TRANSFER' && counterAmount ? Number(counterAmount) : null,
        avgLookback: strategy === 'AVERAGE' ? Number(avgLookback) : null,
        archived: existing?.archived ?? false,
        forecastOnly,
      };
      if (existing) await updateRecurringRule(existing.id, body, today());
      else await createRecurringRule(body, today());
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  const matchingCategories = categories.filter(
    (c) => c.type === (type === 'INCOME' ? 'INCOME' : 'EXPENSE') && !c.system,
  );

  return (
    <Modal title={existing ? 'Edit rule' : 'New recurring rule'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="rr-name">Name</label>
          <input
            id="rr-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Rent"
            required
          />
        </div>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          {(['EXPENSE', 'INCOME', 'TRANSFER'] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={type === t ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setType(t)}
            >
              {t.toLowerCase()}
            </button>
          ))}
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="rr-account">{type === 'TRANSFER' ? 'From account' : 'Account'}</label>
            <select
              id="rr-account"
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
          {type === 'TRANSFER' ? (
            <div className="form-row">
              <label htmlFor="rr-counter">To account</label>
              <select
                id="rr-counter"
                value={counterAccountId}
                onChange={(e) => setCounterAccountId(Number(e.target.value))}
                required
              >
                <option value="" disabled>
                  Choose…
                </option>
                {accounts
                  .filter((a) => a.id !== accountId)
                  .map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name} ({a.currency})
                    </option>
                  ))}
              </select>
            </div>
          ) : (
            <div className="form-row">
              <label htmlFor="rr-category">Category</label>
              <select
                id="rr-category"
                value={categoryId}
                onChange={(e) => setCategoryId(Number(e.target.value))}
                required
              >
                <option value="" disabled>
                  Choose…
                </option>
                {matchingCategories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="rr-freq">Schedule</label>
            <select
              id="rr-freq"
              value={freq}
              onChange={(e) => setFreq(e.target.value as ScheduleFrequency)}
            >
              <option value="MONTHLY">Monthly</option>
              <option value="WEEKLY">Weekly</option>
              <option value="EVERY_N_DAYS">Every N days</option>
            </select>
          </div>
          {freq === 'MONTHLY' && (
            <div className="form-row">
              <label htmlFor="rr-dom">Day of month</label>
              <input
                id="rr-dom"
                type="number"
                min="1"
                max="31"
                value={dayOfMonth}
                onChange={(e) => setDayOfMonth(e.target.value)}
                required
              />
            </div>
          )}
          {freq === 'WEEKLY' && (
            <div className="form-row">
              <label htmlFor="rr-dow">Day of week</label>
              <select id="rr-dow" value={dayOfWeek} onChange={(e) => setDayOfWeek(e.target.value)}>
                {WEEKDAYS.map((day, index) => (
                  <option key={day} value={index + 1}>
                    {day}
                  </option>
                ))}
              </select>
            </div>
          )}
          {freq === 'EVERY_N_DAYS' && (
            <div className="form-row">
              <label htmlFor="rr-interval">Interval (days)</label>
              <input
                id="rr-interval"
                type="number"
                min="1"
                value={intervalDays}
                onChange={(e) => setIntervalDays(e.target.value)}
                required
              />
            </div>
          )}
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="rr-start">Start date</label>
            <input
              id="rr-start"
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="rr-end">End date (optional)</label>
            <input
              id="rr-end"
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
            />
          </div>
        </div>
        {type !== 'TRANSFER' && (
          <div className="form-row" style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className={strategy === 'FIXED' ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setStrategy('FIXED')}
            >
              Fixed amount
            </button>
            <button
              type="button"
              className={strategy === 'AVERAGE' ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setStrategy('AVERAGE')}
            >
              Avg of last payments
            </button>
          </div>
        )}
        <div className="form-grid">
          {(strategy === 'FIXED' || type === 'TRANSFER') && (
            <div className="form-row">
              <label htmlFor="rr-amount">Amount</label>
              <input
                id="rr-amount"
                type="number"
                step="any"
                min="0"
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                required
              />
            </div>
          )}
          {type === 'TRANSFER' && (
            <div className="form-row">
              <label htmlFor="rr-counter-amount">Received (blank = FX estimate)</label>
              <input
                id="rr-counter-amount"
                type="number"
                step="any"
                min="0"
                inputMode="decimal"
                value={counterAmount}
                onChange={(e) => setCounterAmount(e.target.value)}
              />
            </div>
          )}
          {strategy === 'AVERAGE' && type !== 'TRANSFER' && (
            <div className="form-row">
              <label htmlFor="rr-lookback">Average of last N payments</label>
              <input
                id="rr-lookback"
                type="number"
                min="1"
                value={avgLookback}
                onChange={(e) => setAvgLookback(e.target.value)}
                required
              />
            </div>
          )}
        </div>
        <div className="form-row">
          <label>Confirmation</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className={!forecastOnly ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setForecastOnly(false)}
            >
              Needs confirmation
            </button>
            <button
              type="button"
              className={forecastOnly ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setForecastOnly(true)}
            >
              Forecast only
            </button>
          </div>
          <div className="muted" style={{ fontSize: 12.5, marginTop: 4 }}>
            {forecastOnly
              ? 'Counted in the forecast, but never appears in the confirm queue and never becomes a transaction.'
              : 'Due occurrences appear in the confirm queue to be confirmed or skipped.'}
          </div>
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy}>
            Save rule
          </button>
        </div>
      </form>
    </Modal>
  );
}

function PlannedForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: Planned | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [type, setType] = useState<TransactionType>(existing?.type ?? 'EXPENSE');
  const [dueDate, setDueDate] = useState(existing?.dueDate ?? today());
  const [accountId, setAccountId] = useState<number | ''>(existing?.accountId ?? '');
  const [amount, setAmount] = useState(existing ? String(existing.amount) : '');
  const [categoryId, setCategoryId] = useState<number | ''>(existing?.categoryId ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getAccounts().then(setAccounts).catch(() => {});
    getCategories().then(setCategories).catch(() => {});
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!accountId || !amount) return;
    setBusy(true);
    try {
      const body = {
        type,
        dueDate,
        accountId: Number(accountId),
        amount: Number(amount),
        categoryId: categoryId ? Number(categoryId) : null,
        description: description || null,
      };
      if (existing) await updatePlanned(existing.id, body as Partial<Planned>);
      else await createPlanned(body as Partial<Planned>);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  const matchingCategories = categories.filter(
    (c) => c.type === (type === 'INCOME' ? 'INCOME' : 'EXPENSE') && !c.system,
  );

  return (
    <Modal title={existing ? 'Edit planned' : 'New planned one-off'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          {(['EXPENSE', 'INCOME'] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={type === t ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setType(t)}
            >
              {t.toLowerCase()}
            </button>
          ))}
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="pl-amount">Amount</label>
            <input
              id="pl-amount"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="pl-date">Due date</label>
            <input
              id="pl-date"
              type="date"
              value={dueDate}
              onChange={(e) => setDueDate(e.target.value)}
              required
            />
          </div>
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="pl-account">Account</label>
            <select
              id="pl-account"
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
          <div className="form-row">
            <label htmlFor="pl-category">Category</label>
            <select
              id="pl-category"
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : '')}
            >
              <option value="">— none —</option>
              {matchingCategories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="form-row">
          <label htmlFor="pl-description">Description</label>
          <input
            id="pl-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Flight AKL → BKK"
          />
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !accountId || !amount}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}
