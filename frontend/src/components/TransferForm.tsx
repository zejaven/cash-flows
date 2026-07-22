import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  convertToTransfer,
  createTransaction,
  deleteTransaction,
  getAccounts,
  getCategories,
  getTransactions,
  updateTransaction,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Modal } from './Modal';
import { Spinner } from './Spinner';
import { today } from '../utils/dates';
import { convert, formatMoney } from '../utils/money';
import type { Account, Transaction } from '../types';

/** iso date ± days, staying in UTC so the day never shifts. */
function shiftDate(iso: string, days: number): string {
  const date = new Date(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/**
 * Transfer between own accounts (FR-2.2, mockup 5a): both amounts entered, the
 * effective rate is derived and compared with the market cross rate. An optional
 * fee is recorded as a separate expense with the "fees" category (design decision 9).
 *
 * Also converts a mis-parsed EXPENSE/INCOME into a transfer when {@code existing}
 * is not a TRANSFER: the row keeps its identity, and the already-imported opposite
 * side can be picked and absorbed so both import keys survive (FR-4.5).
 */
export function TransferModal({
  existing,
  prefill,
  onClose,
  onSaved,
}: {
  existing?: Transaction;
  /** Amounts computed by a conversion route (FR-5.5); accounts stay user-picked. */
  prefill?: { amountSent: number; amountReceived: number; description: string };
  onClose: () => void;
  onSaved: () => void;
}) {
  const converting = existing != null && existing.type !== 'TRANSFER';
  // A converted INCOME is the receiving side: its account/amount prefill "to".
  const existingIsIncoming = existing?.type === 'INCOME';
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [feesCategoryId, setFeesCategoryId] = useState<number | null>(null);
  const [date, setDate] = useState(existing?.date ?? today());
  const [fromId, setFromId] = useState<number | null>(
    existing ? (existingIsIncoming ? null : existing.accountId) : null,
  );
  const [toId, setToId] = useState<number | null>(
    existing ? (existingIsIncoming ? existing.accountId : existing.counterAccountId) : null,
  );
  const [amountSent, setAmountSent] = useState(
    existing
      ? existingIsIncoming
        ? ''
        : String(existing.amount)
      : prefill
        ? String(prefill.amountSent)
        : '',
  );
  const [amountReceived, setAmountReceived] = useState(
    existing
      ? existingIsIncoming
        ? String(existing.amount)
        : existing.counterAmount != null
          ? String(existing.counterAmount)
          : ''
      : prefill
        ? String(prefill.amountReceived)
        : '',
  );
  const [candidates, setCandidates] = useState<Transaction[] | null>(null);
  const [counterTxnId, setCounterTxnId] = useState<number | null>(null);
  const [fee, setFee] = useState('');
  const [description, setDescription] = useState(
    existing?.description ?? prefill?.description ?? '',
  );
  const [busy, setBusy] = useState(false);
  const { fxRates } = useSettings();
  const { toast } = useToast();

  useEffect(() => {
    getAccounts()
      .then(setAccounts)
      .catch((e) => toast(apiErrorMessage(e), true));
    getCategories()
      .then((categories) =>
        setFeesCategoryId(categories.find((c) => c.name === 'fees')?.id ?? null),
      )
      .catch(() => setFeesCategoryId(null));
  }, [toast]);

  const from = accounts?.find((a) => a.id === fromId) ?? null;
  const to = accounts?.find((a) => a.id === toId) ?? null;

  // Converting: offer the already-recorded opposite side (±7 days on the other
  // account) to absorb, instead of leaving it behind as a duplicate.
  const counterpartAccountId = converting ? (existingIsIncoming ? fromId : toId) : null;
  useEffect(() => {
    setCounterTxnId(null);
    if (!converting || !existing || counterpartAccountId == null) {
      setCandidates(null);
      return;
    }
    getTransactions(
      {
        accountId: counterpartAccountId,
        type: existingIsIncoming ? 'EXPENSE' : 'INCOME',
        from: shiftDate(existing.date, -7),
        to: shiftDate(existing.date, 7),
      },
      0,
      50,
    )
      .then((page) => setCandidates(page.items))
      .catch(() => setCandidates([]));
  }, [converting, existing, existingIsIncoming, counterpartAccountId]);

  const pickCounterpart = (id: number | null) => {
    setCounterTxnId(id);
    const picked = id != null ? candidates?.find((c) => c.id === id) : null;
    if (!picked) return;
    if (existingIsIncoming) setAmountSent(String(picked.amount));
    else setAmountReceived(String(picked.amount));
  };

  const effectiveRate = useMemo(() => {
    const sent = Number(amountSent);
    const received = Number(amountReceived);
    if (!sent || !received || !from || !to) return null;
    return sent / received;
  }, [amountSent, amountReceived, from, to]);

  const marketRate = useMemo(() => {
    if (!from || !to || !fxRates || from.currency === to.currency) return null;
    return convert(1, to.currency, from.currency, fxRates.rates);
  }, [from, to, fxRates]);

  const lossPct = useMemo(() => {
    if (effectiveRate == null || marketRate == null) return null;
    return ((effectiveRate - marketRate) / marketRate) * 100;
  }, [effectiveRate, marketRate]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!fromId || !toId || !amountSent || !amountReceived) return;
    setBusy(true);
    try {
      const body = {
        type: 'TRANSFER' as const,
        date,
        accountId: fromId,
        amount: Number(amountSent),
        counterAccountId: toId,
        counterAmount: Number(amountReceived),
        description: description || null,
      };
      if (converting && existing) {
        await convertToTransfer(existing.id, {
          date,
          accountId: fromId,
          amount: Number(amountSent),
          counterAccountId: toId,
          counterAmount: Number(amountReceived),
          description: description || null,
          counterTransactionId: counterTxnId,
        });
      } else if (existing) await updateTransaction(existing.id, body);
      else await createTransaction(body);
      if (!existing && fee && Number(fee) > 0) {
        await createTransaction({
          type: 'EXPENSE',
          date,
          accountId: fromId,
          amount: Number(fee),
          categoryId: feesCategoryId,
          description: `Transfer fee${description ? ` · ${description}` : ''}`,
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

  if (accounts == null) {
    return (
      <Modal title="New transfer" onClose={onClose}>
        <Spinner />
      </Modal>
    );
  }

  return (
    <Modal
      title={converting ? 'Convert to transfer' : existing ? 'Edit transfer' : 'New transfer'}
      onClose={onClose}
    >
      <form onSubmit={submit}>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="tr-from">From</label>
            <select
              id="tr-from"
              value={fromId ?? ''}
              onChange={(e) => setFromId(Number(e.target.value))}
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
            <label htmlFor="tr-to">To</label>
            <select
              id="tr-to"
              value={toId ?? ''}
              onChange={(e) => setToId(Number(e.target.value))}
              required
            >
              <option value="" disabled>
                Choose…
              </option>
              {accounts
                .filter((a) => a.id !== fromId)
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name} ({a.currency})
                  </option>
                ))}
            </select>
          </div>
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="tr-sent">Amount sent{from ? ` (${from.currency})` : ''}</label>
            <input
              id="tr-sent"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={amountSent}
              onChange={(e) => setAmountSent(e.target.value)}
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="tr-received">
              Amount received{to ? ` (${to.currency})` : ''}
            </label>
            <input
              id="tr-received"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={amountReceived}
              onChange={(e) => setAmountReceived(e.target.value)}
              required
            />
          </div>
        </div>
        {effectiveRate != null && from && to && from.currency !== to.currency && (
          <div className="form-row muted amount" style={{ fontSize: 13 }}>
            Effective rate {effectiveRate.toFixed(4)} {from.currency}/{to.currency}
            {marketRate != null && lossPct != null && (
              <>
                {' '}
                · market {marketRate.toFixed(4)} ·{' '}
                <span className={lossPct > 0 ? 'expense' : 'income'}>
                  {lossPct > 0 ? '−' : '+'}
                  {Math.abs(lossPct).toFixed(1)}%
                </span>
              </>
            )}
          </div>
        )}
        {converting && existing && (
          <div className="form-row">
            <label htmlFor="tr-counterpart">
              Matching {existingIsIncoming ? 'expense' : 'income'} on the{' '}
              {existingIsIncoming ? 'From' : 'To'} account · absorbed into this transfer
            </label>
            <select
              id="tr-counterpart"
              value={counterTxnId ?? ''}
              onChange={(e) => pickCounterpart(e.target.value ? Number(e.target.value) : null)}
              disabled={counterpartAccountId == null}
            >
              <option value="">
                {counterpartAccountId == null
                  ? 'Choose the other account first…'
                  : 'None — the other side is not recorded'}
              </option>
              {(candidates ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.date} · {existingIsIncoming ? '−' : '+'}
                  {formatMoney(c.amount, c.accountCurrency)}
                  {c.description ? ` · ${c.description}` : ''}
                </option>
              ))}
            </select>
            <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
              Pick the duplicate row this transfer replaces — it will be deleted, and
              re-importing the same statement won&apos;t bring it back.
            </div>
          </div>
        )}
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="tr-fee">
              Fee{from ? ` (${from.currency})` : ''} · recorded as expense
            </label>
            <input
              id="tr-fee"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={fee}
              onChange={(e) => setFee(e.target.value)}
              disabled={!!existing}
              placeholder={existing ? 'edit the fee expense separately' : '0'}
            />
          </div>
          <div className="form-row">
            <label htmlFor="tr-date">Date</label>
            <input
              id="tr-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
            />
          </div>
        </div>
        <div className="form-row">
          <label htmlFor="tr-description">Description</label>
          <input
            id="tr-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Salary exchange KZT → USD"
          />
        </div>
        <div className="form-actions">
          {existing && (
            <button
              type="button"
              className="btn-danger"
              style={{ marginRight: 'auto' }}
              disabled={busy}
              onClick={async () => {
                if (!confirm('Delete this transfer?')) return;
                setBusy(true);
                try {
                  await deleteTransaction(existing.id);
                  onSaved();
                  onClose();
                } catch (err) {
                  toast(apiErrorMessage(err), true);
                  setBusy(false);
                }
              }}
            >
              Delete
            </button>
          )}
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !fromId || !toId || !amountSent || !amountReceived}>
            Save transfer
          </button>
        </div>
      </form>
    </Modal>
  );
}
