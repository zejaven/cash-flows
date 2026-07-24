import { useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  createCounterparty,
  createDebt,
  getAccounts,
  getCategories,
  getCounterparties,
  updateDebt,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Modal } from './Modal';
import { today } from '../utils/dates';
import type {
  Account,
  Category,
  Counterparty,
  Debt,
  DebtDirection,
  DebtKind,
  DebtRequest,
} from '../types';

const NEW_COUNTERPARTY = 'new';

/**
 * Debt create/edit (FR-13.1): direction + kind toggles, counterparty with
 * inline creation, opening principal as of an anchor date (a loan may predate
 * the app's history), optional payment account and — for loans — the interest
 * category that keeps the interest part of payments in spending stats.
 */
export function DebtForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: Debt | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [counterparties, setCounterparties] = useState<Counterparty[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [name, setName] = useState(existing?.name ?? '');
  const [direction, setDirection] = useState<DebtDirection>(existing?.direction ?? 'I_OWE');
  const [kind, setKind] = useState<DebtKind>(existing?.kind ?? 'PERSONAL');
  const [counterpartyId, setCounterpartyId] = useState<number | typeof NEW_COUNTERPARTY | ''>(
    existing?.counterpartyId ?? '',
  );
  const [newCounterparty, setNewCounterparty] = useState('');
  const [currency, setCurrency] = useState(existing?.currency ?? 'RUB');
  const [openingPrincipal, setOpeningPrincipal] = useState(
    existing != null ? String(existing.openingPrincipal) : '',
  );
  const [anchorDate, setAnchorDate] = useState(existing?.anchorDate ?? today());
  const [expectedReturnDate, setExpectedReturnDate] = useState(
    existing?.expectedReturnDate ?? '',
  );
  const [accountId, setAccountId] = useState<number | ''>(existing?.accountId ?? '');
  const [interestCategoryId, setInterestCategoryId] = useState<number | ''>(
    existing?.interestCategoryId ?? '',
  );
  const [note, setNote] = useState(existing?.note ?? '');
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getCounterparties().then(setCounterparties).catch(() => {});
    getAccounts().then(setAccounts).catch(() => {});
    getCategories().then(setCategories).catch(() => {});
  }, []);

  // interest is EXPENSE when I pay the loan, INCOME when my lending earns it
  const interestType = direction === 'I_OWE' ? 'EXPENSE' : 'INCOME';
  const interestCategories = categories.filter((c) => c.type === interestType && !c.system);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!counterpartyId || !openingPrincipal) return;
    setBusy(true);
    try {
      let resolvedCounterpartyId =
        counterpartyId === NEW_COUNTERPARTY ? null : Number(counterpartyId);
      if (counterpartyId === NEW_COUNTERPARTY) {
        const created = await createCounterparty({ name: newCounterparty });
        resolvedCounterpartyId = created.id;
      }
      const body: DebtRequest = {
        name,
        direction,
        kind,
        counterpartyId: resolvedCounterpartyId!,
        currency: currency.toUpperCase(),
        openingPrincipal: Number(openingPrincipal),
        anchorDate,
        expectedReturnDate: kind === 'PERSONAL' && expectedReturnDate ? expectedReturnDate : null,
        accountId: accountId ? Number(accountId) : null,
        interestCategoryId:
          kind === 'LOAN' && interestCategoryId ? Number(interestCategoryId) : null,
        note: note || null,
      };
      if (existing) await updateDebt(existing.id, body);
      else await createDebt(body);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={existing ? 'Edit debt' : 'New debt'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="dbt-name">Name</label>
          <input
            id="dbt-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Ипотека РСХБ"
            required
          />
        </div>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            className={direction === 'I_OWE' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setDirection('I_OWE')}
          >
            I owe
          </button>
          <button
            type="button"
            className={direction === 'OWED_TO_ME' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setDirection('OWED_TO_ME')}
          >
            Owed to me
          </button>
        </div>
        <div className="form-row" style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            className={kind === 'PERSONAL' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setKind('PERSONAL')}
          >
            One-off debt
          </button>
          <button
            type="button"
            className={kind === 'LOAN' ? 'btn' : 'btn-ghost'}
            style={{ flex: 1 }}
            onClick={() => setKind('LOAN')}
          >
            Loan with schedule
          </button>
        </div>
        <div className="form-row">
          <label htmlFor="dbt-counterparty">Counterparty</label>
          <select
            id="dbt-counterparty"
            value={counterpartyId}
            onChange={(e) =>
              setCounterpartyId(
                e.target.value === NEW_COUNTERPARTY ? NEW_COUNTERPARTY : Number(e.target.value),
              )
            }
            required
          >
            <option value="" disabled>
              Choose…
            </option>
            {counterparties.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
            <option value={NEW_COUNTERPARTY}>+ New counterparty…</option>
          </select>
        </div>
        {counterpartyId === NEW_COUNTERPARTY && (
          <div className="form-row">
            <label htmlFor="dbt-new-counterparty">New counterparty name</label>
            <input
              id="dbt-new-counterparty"
              value={newCounterparty}
              onChange={(e) => setNewCounterparty(e.target.value)}
              required
            />
          </div>
        )}
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="dbt-opening">Outstanding on the date</label>
            <input
              id="dbt-opening"
              type="number"
              step="any"
              min="0"
              inputMode="decimal"
              value={openingPrincipal}
              onChange={(e) => setOpeningPrincipal(e.target.value)}
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="dbt-anchor">As of date</label>
            <input
              id="dbt-anchor"
              type="date"
              value={anchorDate}
              onChange={(e) => setAnchorDate(e.target.value)}
              required
            />
          </div>
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="dbt-currency">Currency</label>
            <input
              id="dbt-currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              maxLength={8}
              required
            />
          </div>
          {kind === 'PERSONAL' && (
            <div className="form-row">
              <label htmlFor="dbt-return">Expected back (optional)</label>
              <input
                id="dbt-return"
                type="date"
                value={expectedReturnDate}
                onChange={(e) => setExpectedReturnDate(e.target.value)}
              />
            </div>
          )}
        </div>
        <div className="form-row">
          <label htmlFor="dbt-account">
            {direction === 'I_OWE' ? 'Payment account' : 'Repayment arrives at'} · used by forecast
          </label>
          <select
            id="dbt-account"
            value={accountId}
            onChange={(e) => setAccountId(e.target.value ? Number(e.target.value) : '')}
          >
            <option value="">— none —</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.currency})
              </option>
            ))}
          </select>
        </div>
        {kind === 'LOAN' && (
          <div className="form-row">
            <label htmlFor="dbt-interest">Interest category · stays in spending stats</label>
            <select
              id="dbt-interest"
              value={interestCategoryId}
              onChange={(e) =>
                setInterestCategoryId(e.target.value ? Number(e.target.value) : '')
              }
              required
            >
              <option value="" disabled>
                Choose…
              </option>
              {interestCategories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="form-row">
          <label htmlFor="dbt-note">Note</label>
          <input id="dbt-note" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !counterpartyId || !openingPrincipal}>
            Save debt
          </button>
        </div>
      </form>
    </Modal>
  );
}
