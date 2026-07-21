import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  createAccount,
  createBank,
  createCategory,
  deleteAccount,
  deleteBank,
  deleteCategory,
  getAccounts,
  getBanks,
  getCategories,
  logout,
  updateAccount,
  updateBank,
  updateCategory,
} from '../api';
import { DISPLAY_CURRENCIES, useSettings, type DisplayCurrency } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Modal } from '../components/Modal';
import { Spinner } from '../components/Spinner';
import { formatMoney, KNOWN_CURRENCIES } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type { Account, AccountType, Bank, Category, CategoryType } from '../types';

export function SettingsPage() {
  const { displayCurrency, setDisplayCurrency, fxRates } = useSettings();
  const { toast } = useToast();
  const [banks, setBanks] = useState<Bank[] | null>(null);
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [bankModal, setBankModal] = useState<Bank | 'new' | null>(null);
  const [accountModal, setAccountModal] = useState<Account | 'new' | null>(null);
  const [categoryModal, setCategoryModal] = useState<Category | 'new' | null>(null);

  const reload = useCallback(() => {
    getBanks().then(setBanks).catch((e) => toast(apiErrorMessage(e), true));
    getAccounts(true).then(setAccounts).catch((e) => toast(apiErrorMessage(e), true));
    getCategories(true).then(setCategories).catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  useEffect(reload, [reload]);

  const remove = async (kind: 'bank' | 'account' | 'category', id: number) => {
    if (!confirm('Delete permanently?')) return;
    try {
      if (kind === 'bank') await deleteBank(id);
      else if (kind === 'account') await deleteAccount(id);
      else await deleteCategory(id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const toggleAccountArchived = async (account: Account) => {
    try {
      await updateAccount(account.id, { ...account, archived: !account.archived });
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const toggleCategoryArchived = async (category: Category) => {
    try {
      await updateCategory(category.id, { ...category, archived: !category.archived });
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  return (
    <>
      <div className="card">
        <h2>Display</h2>
        <div className="settings-row">
          <div className="grow">
            <div>Display currency</div>
            <div className="muted" style={{ fontSize: 12.5 }}>
              Converted amounts are marked «≈». ORIGINAL shows native currencies only.
            </div>
          </div>
          <select
            style={{ width: 'auto' }}
            value={displayCurrency}
            onChange={(e) => setDisplayCurrency(e.target.value as DisplayCurrency)}
          >
            <option value="ORIGINAL">ORIGINAL</option>
            {DISPLAY_CURRENCIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
        <div className="settings-row">
          <div className="grow">Exchange rates · open.er-api.com</div>
          <span className="muted" style={{ fontSize: 12.5 }}>
            {fxRates
              ? `updated ${new Date(fxRates.updatedAt).toLocaleString('en-NZ')}`
              : displayCurrency === 'ORIGINAL'
                ? 'not loaded (ORIGINAL mode)'
                : 'unavailable — showing original currencies'}
          </span>
        </div>
        <div className="settings-row">
          <div className="grow">Session</div>
          <button
            className="row-btn"
            onClick={() => logout().then(() => location.reload())}
          >
            Sign out
          </button>
        </div>
      </div>

      <div className="card">
        <h2>
          Banks{' '}
          <button className="row-btn" style={{ float: 'right' }} onClick={() => setBankModal('new')}>
            + Add
          </button>
        </h2>
        {banks == null ? (
          <Spinner />
        ) : (
          banks.map((bank) => (
            <div key={bank.id} className="settings-row">
              <div className="grow">
                <div>{bank.name}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>
                  {bank.parserId ? `parser: ${bank.parserId}` : 'no parser'} · {bank.accountCount}{' '}
                  accounts
                </div>
              </div>
              <div className="row-actions">
                <button className="row-btn" onClick={() => setBankModal(bank)}>
                  Edit
                </button>
                <button className="row-btn" onClick={() => remove('bank', bank.id)}>
                  Delete
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      <div className="card">
        <h2>
          Accounts{' '}
          <button
            className="row-btn"
            style={{ float: 'right' }}
            onClick={() => setAccountModal('new')}
          >
            + Add
          </button>
        </h2>
        {accounts == null ? (
          <Spinner />
        ) : (
          accounts.map((account) => (
            <div key={account.id} className="settings-row">
              <div className="grow">
                <div>
                  {account.name}{' '}
                  {account.archived && <span className="badge badge-review">archived</span>}
                </div>
                <div className="muted amount" style={{ fontSize: 12.5 }}>
                  {account.currency} · start {formatMoney(account.startBalance, account.currency)} ·{' '}
                  {formatDate(account.startDate)}
                </div>
              </div>
              <div className="row-actions">
                <button className="row-btn" onClick={() => setAccountModal(account)}>
                  Edit
                </button>
                <button className="row-btn" onClick={() => toggleAccountArchived(account)}>
                  {account.archived ? 'Unarchive' : 'Archive'}
                </button>
                <button className="row-btn" onClick={() => remove('account', account.id)}>
                  Delete
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      <div className="card">
        <h2>
          Categories{' '}
          <button
            className="row-btn"
            style={{ float: 'right' }}
            onClick={() => setCategoryModal('new')}
          >
            + Add
          </button>
        </h2>
        {categories == null ? (
          <Spinner />
        ) : (
          categories.map((category) => (
            <div key={category.id} className="settings-row">
              <div className="grow">
                <span className="color-dot" style={{ background: category.color }} />
                {category.name}{' '}
                <span className="muted" style={{ fontSize: 12 }}>
                  {category.type.toLowerCase()}
                </span>
                {category.system && <span className="badge badge-imported"> system</span>}
                {category.archived && <span className="badge badge-review"> archived</span>}
              </div>
              <div className="row-actions">
                <button className="row-btn" onClick={() => setCategoryModal(category)}>
                  Edit
                </button>
                <button className="row-btn" onClick={() => toggleCategoryArchived(category)}>
                  {category.archived ? 'Unarchive' : 'Archive'}
                </button>
                {!category.system && (
                  <button className="row-btn" onClick={() => remove('category', category.id)}>
                    Delete
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </div>

      {bankModal && (
        <BankForm
          existing={bankModal === 'new' ? null : bankModal}
          onClose={() => setBankModal(null)}
          onSaved={reload}
        />
      )}
      {accountModal && (
        <AccountForm
          existing={accountModal === 'new' ? null : accountModal}
          banks={banks ?? []}
          onClose={() => setAccountModal(null)}
          onSaved={reload}
        />
      )}
      {categoryModal && (
        <CategoryForm
          existing={categoryModal === 'new' ? null : categoryModal}
          onClose={() => setCategoryModal(null)}
          onSaved={reload}
        />
      )}
    </>
  );
}

function BankForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: Bank | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [parserId, setParserId] = useState(existing?.parserId ?? '');
  const { toast } = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const body = { name, parserId: parserId || null };
      if (existing) await updateBank(existing.id, body);
      else await createBank(body);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    }
  }

  return (
    <Modal title={existing ? 'Edit bank' : 'New bank'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="bank-name">Name</label>
          <input id="bank-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-row">
          <label htmlFor="bank-parser">Statement parser (stage 2)</label>
          <input
            id="bank-parser"
            value={parserId}
            onChange={(e) => setParserId(e.target.value)}
            placeholder="alatau-pdf"
          />
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn">Save</button>
        </div>
      </form>
    </Modal>
  );
}

function AccountForm({
  existing,
  banks,
  onClose,
  onSaved,
}: {
  existing: Account | null;
  banks: Bank[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [currency, setCurrency] = useState(existing?.currency ?? 'NZD');
  const [type, setType] = useState<AccountType>(existing?.type ?? 'BANK');
  const [bankId, setBankId] = useState<number | ''>(existing?.bankId ?? '');
  const [accountNumber, setAccountNumber] = useState(existing?.accountNumber ?? '');
  const [startBalance, setStartBalance] = useState(
    existing ? String(existing.startBalance) : '0',
  );
  const [startDate, setStartDate] = useState(existing?.startDate ?? today());
  const { toast } = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const body = {
        name,
        currency: currency.toUpperCase(),
        type,
        bankId: bankId === '' ? null : bankId,
        accountNumber: accountNumber || null,
        startBalance: Number(startBalance),
        startDate,
        archived: existing?.archived ?? false,
      };
      if (existing) await updateAccount(existing.id, body as Partial<Account>);
      else await createAccount(body as Partial<Account>);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    }
  }

  return (
    <Modal title={existing ? 'Edit account' : 'New account'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="acc-name">Name</label>
          <input id="acc-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="acc-currency">Currency</label>
            <input
              id="acc-currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              list="currency-suggestions"
              maxLength={8}
              required
            />
            <datalist id="currency-suggestions">
              {KNOWN_CURRENCIES.map((c) => (
                <option key={c} value={c} />
              ))}
            </datalist>
          </div>
          <div className="form-row">
            <label htmlFor="acc-type">Type</label>
            <select
              id="acc-type"
              value={type}
              onChange={(e) => setType(e.target.value as AccountType)}
            >
              <option value="BANK">Bank</option>
              <option value="CASH">Cash</option>
              <option value="CRYPTO">Crypto</option>
            </select>
          </div>
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="acc-bank">Bank</label>
            <select
              id="acc-bank"
              value={bankId}
              onChange={(e) => setBankId(e.target.value ? Number(e.target.value) : '')}
            >
              <option value="">— none —</option>
              {banks.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </select>
          </div>
          <div className="form-row">
            <label htmlFor="acc-number">Account number</label>
            <input
              id="acc-number"
              value={accountNumber}
              onChange={(e) => setAccountNumber(e.target.value)}
              placeholder="KZ61…3905"
            />
          </div>
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="acc-start-balance">Start balance</label>
            <input
              id="acc-start-balance"
              type="number"
              step="any"
              inputMode="decimal"
              value={startBalance}
              onChange={(e) => setStartBalance(e.target.value)}
              required
            />
          </div>
          <div className="form-row">
            <label htmlFor="acc-start-date">Start date</label>
            <input
              id="acc-start-date"
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
            />
          </div>
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn">Save</button>
        </div>
      </form>
    </Modal>
  );
}

function CategoryForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: Category | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [type, setType] = useState<CategoryType>(existing?.type ?? 'EXPENSE');
  const [color, setColor] = useState(existing?.color ?? '#5a83f7');
  const { toast } = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const body = { name, type, color, archived: existing?.archived ?? false };
      if (existing) await updateCategory(existing.id, body as Partial<Category>);
      else await createCategory(body as Partial<Category>);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    }
  }

  return (
    <Modal title={existing ? 'Edit category' : 'New category'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="cat-name">Name</label>
          <input id="cat-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-grid">
          <div className="form-row">
            <label htmlFor="cat-type">Type</label>
            <select
              id="cat-type"
              value={type}
              onChange={(e) => setType(e.target.value as CategoryType)}
              disabled={existing?.system}
            >
              <option value="EXPENSE">Expense</option>
              <option value="INCOME">Income</option>
            </select>
          </div>
          <div className="form-row">
            <label htmlFor="cat-color">Color</label>
            <input
              id="cat-color"
              type="color"
              value={color}
              onChange={(e) => setColor(e.target.value)}
              style={{ padding: 4, height: 40 }}
            />
          </div>
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn">Save</button>
        </div>
      </form>
    </Modal>
  );
}
