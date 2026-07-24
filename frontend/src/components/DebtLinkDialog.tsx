import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { apiErrorMessage, getTransactions, linkDebtTransaction } from '../api';
import { useToast } from '../context/ToastContext';
import { Modal } from './Modal';
import { Spinner } from './Spinner';
import { formatMoney } from '../utils/money';
import { today } from '../utils/dates';
import type {
  Debt,
  DebtEntryRole,
  LoanScheduleItem,
  Transaction,
  TransactionType,
} from '../types';

/** Which transaction type settles the movement — mirror of the backend rule. */
function expectedTxnType(direction: Debt['direction'], role: DebtEntryRole): TransactionType {
  const income =
    (direction === 'I_OWE' && role === 'ISSUE') ||
    (direction === 'OWED_TO_ME' && role === 'REPAYMENT');
  return income ? 'INCOME' : 'EXPENSE';
}

/** Role is unambiguous once the transaction type and debt direction are known. */
function roleForTransaction(direction: Debt['direction'], type: TransactionType): DebtEntryRole {
  if (direction === 'I_OWE') return type === 'INCOME' ? 'ISSUE' : 'REPAYMENT';
  return type === 'EXPENSE' ? 'ISSUE' : 'REPAYMENT';
}

function shiftDate(iso: string, days: number): string {
  const date = new Date(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/**
 * Attaches an existing (typically imported) transaction to a debt (FR-13.2):
 * either a free-form issue/repayment, or — with {@code scheduleItem} — the
 * confirmation of a specific loan payment. Repayments may live on any account
 * and far from the issue date, so the free-form window spans from the debt's
 * anchor date to today.
 */
export function DebtLinkDialog({
  debt,
  scheduleItem,
  presetTransaction,
  onClose,
  onSaved,
}: {
  debt: Debt;
  scheduleItem?: LoanScheduleItem | null;
  presetTransaction?: Transaction | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [role, setRole] = useState<DebtEntryRole>(
    presetTransaction
      ? roleForTransaction(debt.direction, presetTransaction.type)
      : 'REPAYMENT',
  );
  const [candidates, setCandidates] = useState<Transaction[] | null>(
    presetTransaction ? [presetTransaction] : null,
  );
  const [transactionId, setTransactionId] = useState<number | ''>(presetTransaction?.id ?? '');
  const [txnPrincipal, setTxnPrincipal] = useState('');
  const [debtPrincipal, setDebtPrincipal] = useState('');
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  const effectiveRole = scheduleItem ? 'REPAYMENT' : role;

  useEffect(() => {
    if (presetTransaction) return;
    setCandidates(null);
    setTransactionId('');
    const filters = scheduleItem
      ? {
          type: expectedTxnType(debt.direction, 'REPAYMENT'),
          accountId: debt.accountId ?? undefined,
          from: shiftDate(scheduleItem.dueDate, -15),
          to: shiftDate(scheduleItem.dueDate, 15),
        }
      : {
          type: expectedTxnType(debt.direction, role),
          from: debt.anchorDate,
          to: today(),
        };
    getTransactions(filters, 0, 100)
      .then((page) => setCandidates(page.items.filter((t) => t.debtId == null)))
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [debt, scheduleItem, role, presetTransaction, toast]);

  const selected = useMemo(
    () => (transactionId ? (candidates ?? []).find((t) => t.id === transactionId) ?? null : null),
    [transactionId, candidates],
  );
  const crossCurrency =
    selected != null && selected.accountCurrency.toUpperCase() !== debt.currency.toUpperCase();

  const sorted = useMemo(() => {
    if (!candidates) return null;
    const target = scheduleItem?.totalAmount ?? debt.outstanding;
    return [...candidates].sort(
      (a, b) => Math.abs(a.amount - target) - Math.abs(b.amount - target),
    );
  }, [candidates, scheduleItem, debt]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!transactionId) return;
    setBusy(true);
    try {
      await linkDebtTransaction(debt.id, {
        transactionId: Number(transactionId),
        role: scheduleItem ? undefined : effectiveRole,
        scheduleItemId: scheduleItem?.id ?? null,
        txnPrincipal: txnPrincipal ? Number(txnPrincipal) : null,
        debtPrincipal: debtPrincipal ? Number(debtPrincipal) : null,
      });
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={
        scheduleItem
          ? `Link payment #${scheduleItem.position} · ${debt.name}`
          : `Link transaction · ${debt.name}`
      }
      onClose={onClose}
    >
      <form onSubmit={submit}>
        {!scheduleItem && !presetTransaction && (
          <div className="form-row" style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className={role === 'REPAYMENT' ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setRole('REPAYMENT')}
            >
              Repayment
            </button>
            <button
              type="button"
              className={role === 'ISSUE' ? 'btn' : 'btn-ghost'}
              style={{ flex: 1 }}
              onClick={() => setRole('ISSUE')}
            >
              Debt issued
            </button>
          </div>
        )}
        {presetTransaction ? (
          <div className="form-row muted" style={{ fontSize: 13 }}>
            {presetTransaction.date} · {presetTransaction.type === 'INCOME' ? '+' : '−'}
            {formatMoney(presetTransaction.amount, presetTransaction.accountCurrency)} ·{' '}
            {presetTransaction.description ?? presetTransaction.accountName} — recorded as{' '}
            {effectiveRole === 'ISSUE' ? 'debt issued' : 'repayment'}
          </div>
        ) : sorted == null ? (
          <Spinner />
        ) : (
          <div className="form-row">
            <label htmlFor="dl-txn">Transaction</label>
            <select
              id="dl-txn"
              value={transactionId}
              onChange={(e) => setTransactionId(e.target.value ? Number(e.target.value) : '')}
              required
            >
              <option value="" disabled>
                {sorted.length === 0 ? 'No matching transactions found' : 'Choose…'}
              </option>
              {sorted.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.date} · {t.type === 'INCOME' ? '+' : '−'}
                  {formatMoney(t.amount, t.accountCurrency)} · {t.accountName}
                  {t.description ? ` · ${t.description}` : ''}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="dl-principal">
              Principal part{selected ? ` (${selected.accountCurrency})` : ''} · blank ={' '}
              {scheduleItem ? 'from schedule' : 'full amount'}
            </label>
            <input
              id="dl-principal"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={txnPrincipal}
              onChange={(e) => setTxnPrincipal(e.target.value)}
            />
          </div>
          {crossCurrency && !scheduleItem && (
            <div className="form-row">
              <label htmlFor="dl-debt-principal">Debt reduced by ({debt.currency})</label>
              <input
                id="dl-debt-principal"
                type="number"
                step="any"
                min="0"
                inputMode="decimal"
                value={debtPrincipal}
                onChange={(e) => setDebtPrincipal(e.target.value)}
                required
              />
            </div>
          )}
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !transactionId}>
            Link
          </button>
        </div>
      </form>
    </Modal>
  );
}
