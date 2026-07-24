import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  createAccount,
  createBank,
  createCounterparty,
  deleteAccount,
  deleteBank,
  deleteCategory,
  deleteCounterparty,
  getAccounts,
  getBanks,
  getCategories,
  getCounterparties,
  getGeneratorOptions,
  getGeneratorSettings,
  getParsers,
  logout,
  updateAccount,
  updateBank,
  updateCategory,
  updateCounterparty,
  updateGeneratorSettings,
} from '../api';
import { DISPLAY_CURRENCIES, useSettings, type DisplayCurrency } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Modal } from '../components/Modal';
import { Spinner } from '../components/Spinner';
import { CategoryForm } from '../components/CategoryForm';
import { CategoryRulesCard } from '../components/CategoryRules';
import { formatMoney, KNOWN_CURRENCIES } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type {
  Account,
  AccountType,
  AgentProvider,
  Bank,
  Category,
  Counterparty,
  GeneratorOptions,
  GeneratorSettings,
  ParserInfo,
} from '../types';

export function SettingsPage() {
  const { displayCurrency, setDisplayCurrency, fxRates } = useSettings();
  const { toast } = useToast();
  const [banks, setBanks] = useState<Bank[] | null>(null);
  const [accounts, setAccounts] = useState<Account[] | null>(null);
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [counterparties, setCounterparties] = useState<Counterparty[] | null>(null);
  const [bankModal, setBankModal] = useState<Bank | 'new' | null>(null);
  const [accountModal, setAccountModal] = useState<Account | 'new' | null>(null);
  const [categoryModal, setCategoryModal] = useState<Category | 'new' | null>(null);
  const [counterpartyModal, setCounterpartyModal] = useState<Counterparty | 'new' | null>(null);
  const [parsers, setParsers] = useState<ParserInfo[]>([]);
  const [tab, setTab] = useState<'general' | 'categories'>('general');

  const reload = useCallback(() => {
    getBanks().then(setBanks).catch((e) => toast(apiErrorMessage(e), true));
    getAccounts(true).then(setAccounts).catch((e) => toast(apiErrorMessage(e), true));
    getCategories(true).then(setCategories).catch((e) => toast(apiErrorMessage(e), true));
    getCounterparties(true).then(setCounterparties).catch((e) => toast(apiErrorMessage(e), true));
    getParsers().then(setParsers).catch(() => setParsers([]));
  }, [toast]);

  useEffect(reload, [reload]);

  const remove = async (kind: 'bank' | 'account' | 'category' | 'counterparty', id: number) => {
    if (!confirm('Delete permanently?')) return;
    try {
      if (kind === 'bank') await deleteBank(id);
      else if (kind === 'account') await deleteAccount(id);
      else if (kind === 'counterparty') await deleteCounterparty(id);
      else await deleteCategory(id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const toggleCounterpartyArchived = async (counterparty: Counterparty) => {
    try {
      await updateCounterparty(counterparty.id, {
        name: counterparty.name,
        note: counterparty.note,
        archived: !counterparty.archived,
      });
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
      {/* Import/Reconcile left the mobile tab bar for Recurring/Forecast — keep
          them reachable on phones (NFR-7: desktop-first, not unreachable) */}
      <div className="card mobile-only-links">
        <div className="settings-row">
          <a className="grow" href="/import">
            ⇪ Import bank statement
          </a>
          <a href="/reconcile">⚖ Reconcile balances</a>
        </div>
        <div className="settings-row">
          <a className="grow" href="/statistics">
            📊 Statistics
          </a>
          <a href="/convert">⇄ Conversion routes</a>
        </div>
      </div>

      <div className="filters">
        <button
          className={`pill${tab === 'general' ? ' active' : ''}`}
          onClick={() => setTab('general')}
        >
          General
        </button>
        <button
          className={`pill${tab === 'categories' ? ' active' : ''}`}
          onClick={() => setTab('categories')}
        >
          Categories &amp; rules
        </button>
      </div>

      <div className="settings-grid" style={tab !== 'general' ? { display: 'none' } : undefined}>
      <div className="settings-col">
      <div className="card-deep">
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

      <GeneratorSettingsCard />
      </div>

      <div className="settings-col">
      <div className="card-deep">
        <h2>
          Banks &amp; parsers{' '}
          <button className="link-add" style={{ float: 'right' }} onClick={() => setBankModal('new')}>
            + Add bank
          </button>
        </h2>
        {banks == null ? (
          <Spinner />
        ) : (
          banks.map((bank) => (
            <div key={bank.id} className="settings-row">
              <div className="grow">
                <div style={{ fontWeight: 600 }}>{bank.name}</div>
                <div className="muted" style={{ fontSize: 11.5 }}>
                  {bank.parserId ? (
                    <span className="mono">{bank.parserId}</span>
                  ) : (
                    <span style={{ color: 'var(--dim)' }}>no parser</span>
                  )}{' '}
                  · {bank.accountCount} accounts
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

      <div className="card-deep">
        <h2>
          Accounts{' '}
          <button
            className="link-add"
            style={{ float: 'right' }}
            onClick={() => setAccountModal('new')}
          >
            + Add account
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

      <div className="card-deep">
        <h2>
          Counterparties · debts{' '}
          <button
            className="link-add"
            style={{ float: 'right' }}
            onClick={() => setCounterpartyModal('new')}
          >
            + Add counterparty
          </button>
        </h2>
        {counterparties == null ? (
          <Spinner />
        ) : counterparties.length === 0 ? (
          <div className="placeholder">Who owes you, or whom you owe — used on the Debts page</div>
        ) : (
          counterparties.map((counterparty) => (
            <div key={counterparty.id} className="settings-row">
              <div className="grow">
                <div>
                  {counterparty.name}{' '}
                  {counterparty.archived && <span className="badge badge-review">archived</span>}
                </div>
                <div className="muted" style={{ fontSize: 12.5 }}>
                  {counterparty.debtCount} debts
                  {counterparty.note ? ` · ${counterparty.note}` : ''}
                </div>
              </div>
              <div className="row-actions">
                <button className="row-btn" onClick={() => setCounterpartyModal(counterparty)}>
                  Edit
                </button>
                <button className="row-btn" onClick={() => toggleCounterpartyArchived(counterparty)}>
                  {counterparty.archived ? 'Unarchive' : 'Archive'}
                </button>
                <button className="row-btn" onClick={() => remove('counterparty', counterparty.id)}>
                  Delete
                </button>
              </div>
            </div>
          ))
        )}
      </div>
      </div>
      </div>

      <div className="settings-grid" style={tab !== 'categories' ? { display: 'none' } : undefined}>
        <div className="settings-col">
          <div className="card-deep">
            <h2>
              Categories{categories != null ? ` · ${categories.length}` : ''}{' '}
              <button
                className="link-add"
                style={{ float: 'right' }}
                onClick={() => setCategoryModal('new')}
              >
                + Add category
              </button>
            </h2>
            {categories == null ? (
              <Spinner />
            ) : (
              categories.map((category) => (
                <div key={category.id} className="settings-row">
                  <div className="grow">
                    <span className="color-dot" style={{ background: category.color }} />
                    <span style={{ fontWeight: 600 }}>{category.name}</span>
                    {category.system && <span className="tag"> system</span>}
                    {category.archived && <span className="badge badge-review"> archived</span>}
                  </div>
                  <span
                    style={{ fontSize: 11, fontWeight: 600 }}
                    className={
                      category.system ? 'muted' : category.type === 'EXPENSE' ? 'expense' : 'income'
                    }
                  >
                    {category.system ? 'HIDDEN' : category.type}
                  </span>
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
        </div>
        <div className="settings-col">
          <CategoryRulesCard />
        </div>
      </div>

      {bankModal && (
        <BankForm
          existing={bankModal === 'new' ? null : bankModal}
          parsers={parsers}
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
      {counterpartyModal && (
        <CounterpartyForm
          existing={counterpartyModal === 'new' ? null : counterpartyModal}
          onClose={() => setCounterpartyModal(null)}
          onSaved={reload}
        />
      )}
    </>
  );
}

function CounterpartyForm({
  existing,
  onClose,
  onSaved,
}: {
  existing: Counterparty | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [note, setNote] = useState(existing?.note ?? '');
  const { toast } = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const body = { name, note: note || null };
      if (existing) await updateCounterparty(existing.id, { ...body, archived: existing.archived });
      else await createCounterparty(body);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    }
  }

  return (
    <Modal title={existing ? 'Edit counterparty' : 'New counterparty'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="cp-name">Name</label>
          <input id="cp-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-row">
          <label htmlFor="cp-note">Note</label>
          <input id="cp-note" value={note} onChange={(e) => setNote(e.target.value)} />
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

function BankForm({
  existing,
  parsers,
  onClose,
  onSaved,
}: {
  existing: Bank | null;
  parsers: ParserInfo[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [parserId, setParserId] = useState(existing?.parserId ?? '');
  const { toast } = useToast();
  // a bank may point at a script that was renamed/removed — keep the stale
  // binding visible instead of silently dropping it on the next save
  const staleParser = parserId && !parsers.some((p) => p.id === parserId);

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
          <label htmlFor="bank-parser">Statement parser</label>
          <select id="bank-parser" value={parserId} onChange={(e) => setParserId(e.target.value)}>
            <option value="">— no parser —</option>
            {parsers.map((parser) => (
              <option key={parser.id} value={parser.id}>
                {parser.id}
              </option>
            ))}
            {staleParser && <option value={parserId}>{parserId} (script missing)</option>}
          </select>
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

/**
 * App-wide AI provider/model for the parser generator (FR-4.10). Hidden note
 * when the feature is disabled (Docker) — generation is local-only.
 */
function GeneratorSettingsCard() {
  const [options, setOptions] = useState<GeneratorOptions | null>(null);
  const [settings, setSettings] = useState<GeneratorSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getGeneratorOptions().then(setOptions).catch(() => setOptions({ enabled: false, providers: [] }));
    getGeneratorSettings().then(setSettings).catch(() => {});
  }, []);

  if (options == null || settings == null) return null;

  const provider = options.providers.find((p) => p.id === settings.provider);
  const model = settings.provider === 'CLAUDE' ? settings.claudeModel : settings.codexModel;
  const setModel = (value: string) =>
    setSettings(
      settings.provider === 'CLAUDE'
        ? { ...settings, claudeModel: value || null }
        : { ...settings, codexModel: value || null },
    );

  async function save() {
    if (!settings) return;
    setSaving(true);
    try {
      setSettings(await updateGeneratorSettings(settings));
      toast('AI generator settings saved');
    } catch (e) {
      toast(apiErrorMessage(e), true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card-deep">
      <h2>AI parser generator</h2>
      {!options.enabled ? (
        <div className="placeholder">
          Available only when the app runs locally (needs the claude/codex CLI on this machine).
        </div>
      ) : (
        <>
          <div className="settings-row">
            <div className="grow">
              <div>Provider</div>
              <div className="muted" style={{ fontSize: 12.5 }}>
                {provider?.available
                  ? `CLI found: ${provider.version ?? ''}`
                  : `CLI not available: ${provider?.error ?? 'unknown'}`}
              </div>
            </div>
            <select
              style={{ width: 'auto' }}
              value={settings.provider}
              onChange={(e) => setSettings({ ...settings, provider: e.target.value as AgentProvider })}
            >
              {options.providers.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                  {p.available ? '' : ' (unavailable)'}
                </option>
              ))}
            </select>
          </div>
          <div className="settings-row">
            <div className="grow">Model</div>
            <select style={{ width: 'auto' }} value={model ?? ''} onChange={(e) => setModel(e.target.value)}>
              {(provider?.models ?? ['']).map((m) => (
                <option key={m} value={m}>
                  {m === '' ? 'CLI default' : m}
                </option>
              ))}
            </select>
          </div>
          <div className="form-actions">
            <button className="btn" onClick={save} disabled={saving}>
              Save
            </button>
          </div>
        </>
      )}
    </div>
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

