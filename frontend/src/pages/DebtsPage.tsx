import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  closeDebt,
  deleteDebt,
  getAccounts,
  getDebtEntries,
  getDebts,
  recordDebtMovement,
  reopenDebt,
  unlinkDebtEntry,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Modal } from '../components/Modal';
import { DebtForm } from '../components/DebtForm';
import { DebtLinkDialog } from '../components/DebtLinkDialog';
import { LoanScheduleTable } from '../components/LoanScheduleTable';
import { formatMoney } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type { Account, Debt, DebtEntry, DebtEntryRole } from '../types';

export function DebtsPage() {
  const [debts, setDebts] = useState<Debt[] | null>(null);
  const [modal, setModal] = useState<Debt | 'new' | null>(null);
  const [recording, setRecording] = useState<Debt | null>(null);
  const [linking, setLinking] = useState<Debt | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const { toast } = useToast();

  const reload = useCallback(() => {
    getDebts(true)
      .then(setDebts)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  useEffect(reload, [reload]);

  const toggleClosed = async (debt: Debt) => {
    try {
      if (debt.closed) await reopenDebt(debt.id);
      else await closeDebt(debt.id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const remove = async (debt: Debt) => {
    if (!confirm(`Delete debt "${debt.name}"?`)) return;
    try {
      await deleteDebt(debt.id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  if (debts == null) return <Spinner />;

  const open = debts.filter((debt) => !debt.closed);
  const owedToMe = open.filter((debt) => debt.direction === 'OWED_TO_ME');
  const iOwe = open.filter((debt) => debt.direction === 'I_OWE');
  const settled = debts.filter((debt) => debt.closed);

  const renderGroup = (title: string, list: Debt[]) => (
    <div className="card">
      <h2>{title}</h2>
      {list.length === 0 ? (
        <div className="placeholder">Nothing here</div>
      ) : (
        list.map((debt) => (
          <DebtRow
            key={debt.id}
            debt={debt}
            expanded={expandedId === debt.id}
            onToggle={() => setExpandedId(expandedId === debt.id ? null : debt.id)}
            onEdit={() => setModal(debt)}
            onRecord={() => setRecording(debt)}
            onLink={() => setLinking(debt)}
            onCloseToggle={() => toggleClosed(debt)}
            onDelete={() => remove(debt)}
            onChanged={reload}
          />
        ))
      )}
    </div>
  );

  return (
    <>
      <div className="card">
        <h2>
          Debts & loans{' '}
          <button className="row-btn" style={{ float: 'right' }} onClick={() => setModal('new')}>
            + Debt
          </button>
        </h2>
        <div className="muted" style={{ fontSize: 13 }}>
          Principal movements are excluded from spending statistics; loan interest stays a real
          expense. Repayments can arrive on any account — link the imported transaction to the
          debt.
        </div>
      </div>

      {renderGroup('Owed to me', owedToMe)}
      {renderGroup('I owe', iOwe)}
      {settled.length > 0 && renderGroup('Settled', settled)}

      {modal && (
        <DebtForm
          existing={modal === 'new' ? null : modal}
          onClose={() => setModal(null)}
          onSaved={reload}
        />
      )}
      {recording && (
        <RecordModal debt={recording} onClose={() => setRecording(null)} onSaved={reload} />
      )}
      {linking && (
        <DebtLinkDialog debt={linking} onClose={() => setLinking(null)} onSaved={reload} />
      )}
    </>
  );
}

function DebtRow({
  debt,
  expanded,
  onToggle,
  onEdit,
  onRecord,
  onLink,
  onCloseToggle,
  onDelete,
  onChanged,
}: {
  debt: Debt;
  expanded: boolean;
  onToggle: () => void;
  onEdit: () => void;
  onRecord: () => void;
  onLink: () => void;
  onCloseToggle: () => void;
  onDelete: () => void;
  onChanged: () => void;
}) {
  const progress =
    debt.totalPrincipal > 0
      ? Math.min(100, Math.round((debt.totalRepaid / debt.totalPrincipal) * 100))
      : 0;
  const overdue =
    (debt.kind === 'LOAN' && debt.nextPaymentDate != null && debt.nextPaymentDate < today()) ||
    (debt.kind === 'PERSONAL' &&
      debt.expectedReturnDate != null &&
      debt.expectedReturnDate < today() &&
      debt.outstanding > 0);

  return (
    <div style={{ borderBottom: '1px solid var(--border, #333)', padding: '6px 0' }}>
      <div className="settings-row" style={{ borderBottom: 'none' }}>
        <div className="grow" onClick={onToggle} style={{ cursor: 'pointer' }}>
          <div>
            {debt.name} <span className="badge badge-imported">{debt.counterpartyName}</span>
            {debt.kind === 'LOAN' && <span className="badge badge-imported"> loan</span>}
            {overdue && <span className="badge badge-review"> overdue</span>}
          </div>
          <div className="muted amount" style={{ fontSize: 12.5 }}>
            {debt.kind === 'LOAN' && debt.nextPaymentDate != null
              ? `next payment ${formatDate(debt.nextPaymentDate)} · ${formatMoney(
                  debt.nextPaymentAmount ?? 0,
                  debt.currency,
                )}`
              : debt.expectedReturnDate != null
                ? `expected back ${formatDate(debt.expectedReturnDate)}`
                : `since ${formatDate(debt.anchorDate)}`}
            {' · '}repaid {progress}%
          </div>
        </div>
        <span className={`amount ${debt.direction === 'OWED_TO_ME' ? 'income' : 'expense'}`}>
          {formatMoney(debt.outstanding, debt.currency)}
        </span>
        <div className="row-actions">
          {!debt.closed && (
            <>
              <button className="row-btn" onClick={onRecord}>
                Record
              </button>
              <button className="row-btn" onClick={onLink}>
                Link
              </button>
            </>
          )}
          <button className="row-btn" onClick={onEdit}>
            Edit
          </button>
          <button className="row-btn" onClick={onCloseToggle}>
            {debt.closed ? 'Reopen' : 'Close'}
          </button>
          <button className="row-btn" onClick={onDelete}>
            Delete
          </button>
        </div>
      </div>
      {expanded && (
        <div style={{ paddingLeft: 8 }}>
          {debt.kind === 'LOAN' && <LoanScheduleTable debt={debt} onChanged={onChanged} />}
          <Movements debt={debt} onChanged={onChanged} />
        </div>
      )}
    </div>
  );
}

function Movements({ debt, onChanged }: { debt: Debt; onChanged: () => void }) {
  const [entries, setEntries] = useState<DebtEntry[] | null>(null);
  const { toast } = useToast();

  const reload = useCallback(() => {
    getDebtEntries(debt.id)
      .then(setEntries)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [debt.id, toast]);

  useEffect(reload, [reload]);

  const unlink = async (entry: DebtEntry) => {
    if (!confirm('Unlink this transaction from the debt? The transaction itself stays.')) return;
    try {
      await unlinkDebtEntry(entry.id);
      reload();
      onChanged();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  if (entries == null) return <Spinner />;
  if (entries.length === 0) {
    return <div className="placeholder">No linked transactions yet</div>;
  }
  return (
    <div style={{ marginTop: 8 }}>
      <div className="muted" style={{ fontSize: 12.5, marginBottom: 4 }}>
        Movements
      </div>
      {entries.map((entry) => (
        <div key={entry.id} className="settings-row">
          <div className="grow">
            <div>
              {formatDate(entry.txnDate)}{' '}
              <span className="badge badge-imported">
                {entry.role === 'ISSUE' ? 'issued' : 'repayment'}
              </span>
              {entry.scheduleItemId != null && (
                <span className="badge badge-imported"> schedule</span>
              )}
            </div>
            <div className="muted" style={{ fontSize: 12.5 }}>
              {entry.accountName}
              {entry.description ? ` · ${entry.description}` : ''}
            </div>
          </div>
          <span className="amount">
            {formatMoney(entry.debtPrincipal, debt.currency)}
            {entry.txnPrincipal !== entry.txnAmount &&
              ` (of ${formatMoney(entry.txnAmount, entry.txnCurrency)})`}
          </span>
          <div className="row-actions">
            <button className="row-btn" onClick={() => unlink(entry)}>
              Unlink
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * Creates the transaction and the debt entry in one step — "a friend just
 * paid me back" without leaving the page.
 */
function RecordModal({
  debt,
  onClose,
  onSaved,
}: {
  debt: Debt;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [role, setRole] = useState<DebtEntryRole>('REPAYMENT');
  const [date, setDate] = useState(today());
  const [accountId, setAccountId] = useState<number | ''>(debt.accountId ?? '');
  const [amount, setAmount] = useState('');
  const [debtPrincipal, setDebtPrincipal] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getAccounts().then(setAccounts).catch(() => {});
  }, []);

  const account = accounts.find((a) => a.id === accountId) ?? null;
  const crossCurrency =
    account != null && account.currency.toUpperCase() !== debt.currency.toUpperCase();

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!accountId || !amount) return;
    setBusy(true);
    try {
      await recordDebtMovement(debt.id, {
        role,
        date,
        accountId: Number(accountId),
        amount: Number(amount),
        debtPrincipal: debtPrincipal ? Number(debtPrincipal) : null,
        description: description || null,
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
    <Modal title={`Record · ${debt.name}`} onClose={onClose}>
      <form onSubmit={submit}>
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
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="rec-amount">Amount{account ? ` (${account.currency})` : ''}</label>
            <input
              id="rec-amount"
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
            <label htmlFor="rec-date">Date</label>
            <input
              id="rec-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
            />
          </div>
        </div>
        <div className="form-row">
          <label htmlFor="rec-account">Account</label>
          <select
            id="rec-account"
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
        {crossCurrency && (
          <div className="form-row">
            <label htmlFor="rec-debt-principal">Debt changed by ({debt.currency})</label>
            <input
              id="rec-debt-principal"
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
        <div className="form-row">
          <label htmlFor="rec-description">Description</label>
          <input
            id="rec-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={debt.name}
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
