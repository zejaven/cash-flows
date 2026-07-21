import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiErrorMessage, confirmImport, getAccounts, getBanks, getImportRuns, previewImport } from '../api';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { formatMoney } from '../utils/money';
import { formatDate } from '../utils/dates';
import type {
  Account,
  AccountDecision,
  Bank,
  CashDecision,
  ImportPreview,
  ImportReport,
  ImportRun,
  MappingAction,
} from '../types';

type AccountChoice = { action: MappingAction; accountId: number | null; newName: string };
type CashChoice = { accountId: number | null; createNew: boolean };

/**
 * Three-step statement import (mockups 1f/7a): upload & parse -> map accounts -> report.
 */
export function ImportPage() {
  const [banks, setBanks] = useState<Bank[] | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [runs, setRuns] = useState<ImportRun[] | null>(null);
  const [bankId, setBankId] = useState<number | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [choices, setChoices] = useState<Map<string, AccountChoice>>(new Map());
  const [cashChoices, setCashChoices] = useState<Map<string, CashChoice>>(new Map());
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();
  const navigate = useNavigate();

  useEffect(() => {
    getBanks()
      .then((list) => {
        setBanks(list);
        const withParser = list.filter((b) => b.parserId);
        if (withParser.length === 1) setBankId(withParser[0].id);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
    getAccounts(true).then(setAccounts).catch(() => {});
    getImportRuns().then(setRuns).catch(() => setRuns([]));
  }, [toast]);

  const parserBanks = useMemo(() => (banks ?? []).filter((b) => b.parserId), [banks]);

  async function runPreview() {
    if (!bankId || !file) return;
    setBusy(true);
    setError(null);
    try {
      const result = await previewImport(bankId, file);
      setPreview(result);
      // prefill decisions from suggestions
      const map = new Map<string, AccountChoice>();
      for (const account of result.accounts) {
        if (account.suggestion.kind === 'AUTO') {
          map.set(account.accountNumber, {
            action: 'MAP',
            accountId: account.suggestion.accountId,
            newName: '',
          });
        } else if (account.suggestion.kind === 'SKIP') {
          map.set(account.accountNumber, { action: 'SKIP', accountId: null, newName: '' });
        } else {
          const bankName = parserBanks.find((b) => b.id === bankId)?.name ?? 'Account';
          map.set(account.accountNumber, {
            action: 'CREATE',
            accountId: null,
            newName: `${bankName.split(' ')[0]} ${account.currency}`,
          });
        }
      }
      setChoices(map);
      const cash = new Map<string, CashChoice>();
      for (const currency of result.cashCurrencies) {
        cash.set(currency.currency, {
          accountId: currency.suggestedAccountId,
          createNew: currency.suggestedAccountId == null,
        });
      }
      setCashChoices(cash);
    } catch (e) {
      setError(apiErrorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function runConfirm() {
    if (!preview) return;
    setBusy(true);
    try {
      const accountDecisions: AccountDecision[] = preview.accounts.map((account) => {
        const choice = choices.get(account.accountNumber)!;
        if (choice.action === 'MAP') {
          return { accountNumber: account.accountNumber, action: 'MAP', accountId: choice.accountId };
        }
        if (choice.action === 'CREATE') {
          return {
            accountNumber: account.accountNumber,
            action: 'CREATE',
            newAccount: { name: choice.newName },
          };
        }
        return { accountNumber: account.accountNumber, action: 'SKIP' };
      });
      const cashDecisions: CashDecision[] = preview.cashCurrencies.map((currency) => {
        const choice = cashChoices.get(currency.currency)!;
        return { currency: currency.currency, accountId: choice.accountId, createNew: choice.createNew };
      });
      const result = await confirmImport(preview.token, accountDecisions, cashDecisions);
      setReport(result);
      setPreview(null);
      getImportRuns().then(setRuns).catch(() => {});
    } catch (e) {
      toast(apiErrorMessage(e), true);
    } finally {
      setBusy(false);
    }
  }

  const setChoice = (number: string, patch: Partial<AccountChoice>) =>
    setChoices((current) => {
      const next = new Map(current);
      next.set(number, { ...next.get(number)!, ...patch });
      return next;
    });

  if (banks == null) return <Spinner />;

  if (parserBanks.length === 0) {
    return (
      <div className="card">
        <h2>Import bank statement</h2>
        <div className="placeholder">
          No bank has a statement parser bound. Add a bank with parser
          <span className="mono"> alatau-pdf</span> in Settings first.
        </div>
      </div>
    );
  }

  // step 3: report
  if (report) {
    return (
      <div className="card">
        <h2>Import report</h2>
        <div className="chip-row">
          <span className="chip amount">
            created <b>{report.created}</b>
          </span>
          <span className="chip amount">
            duplicates skipped <b>{report.duplicates}</b>
          </span>
          <span className="chip amount">
            need review <b className={report.needsReview > 0 ? 'expense' : ''}>{report.needsReview}</b>
          </span>
          <span className="chip amount">
            transfers detected <b>{report.transfers}</b>
          </span>
        </div>
        {report.warnings.length > 0 && (
          <div className="form-row">
            {report.warnings.map((warning, index) => (
              <div key={index} className="badge badge-review" style={{ display: 'block', marginBottom: 4 }}>
                {warning}
              </div>
            ))}
          </div>
        )}
        {report.accounts.map((account) => (
          <div key={account.accountId} className="settings-row">
            <div className="grow">{account.name}</div>
            <span className="amount muted">
              {formatMoney(account.balanceBefore, account.currency)} →{' '}
              <b style={{ color: 'var(--text)' }}>{formatMoney(account.balanceAfter, account.currency)}</b>
            </span>
          </div>
        ))}
        <div className="form-actions">
          <button className="btn-ghost" onClick={() => setReport(null)}>
            Import another
          </button>
          {report.needsReview > 0 && (
            <button className="btn" onClick={() => navigate('/transactions?needsReview=true')}>
              Review uncategorized →
            </button>
          )}
        </div>
      </div>
    );
  }

  // step 2: mapping
  if (preview) {
    const bankAccounts = accounts.filter((a) => a.bankId === bankId);
    return (
      <div className="card">
        <h2>Map statement accounts</h2>
        <div className="muted" style={{ marginBottom: 12, fontSize: 13 }}>
          ✓ Checksums verified · {preview.accounts.length} accounts · {preview.totalRows} rows ·{' '}
          {formatDate(preview.periodFrom)} – {formatDate(preview.periodTo)}
        </div>
        {preview.accounts.map((account) => {
          const choice = choices.get(account.accountNumber)!;
          return (
            <div key={account.accountNumber} className="settings-row">
              <div className="grow">
                <div className="mono">
                  {account.accountNumber} · {account.currency}
                  {account.suggestion.kind === 'AUTO' && (
                    <span className="badge badge-imported"> AUTO ✓</span>
                  )}
                </div>
                <div className="muted amount" style={{ fontSize: 12 }}>
                  {account.txnCount} rows · start{' '}
                  {account.balanceStart != null
                    ? formatMoney(account.balanceStart, account.currency)
                    : '—'}
                </div>
              </div>
              <div className="row-actions" style={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                <select
                  style={{ width: 'auto' }}
                  value={choice.action === 'MAP' ? `map-${choice.accountId}` : choice.action}
                  onChange={(e) => {
                    const value = e.target.value;
                    if (value === 'CREATE') setChoice(account.accountNumber, { action: 'CREATE' });
                    else if (value === 'SKIP') setChoice(account.accountNumber, { action: 'SKIP' });
                    else
                      setChoice(account.accountNumber, {
                        action: 'MAP',
                        accountId: Number(value.slice(4)),
                      });
                  }}
                >
                  <option value="CREATE">＋ Create new account</option>
                  <option value="SKIP">Skip</option>
                  {bankAccounts.map((a) => (
                    <option key={a.id} value={`map-${a.id}`}>
                      → {a.name}
                    </option>
                  ))}
                </select>
                {choice.action === 'CREATE' && (
                  <input
                    style={{ width: 150 }}
                    value={choice.newName}
                    onChange={(e) => setChoice(account.accountNumber, { newName: e.target.value })}
                    placeholder="Account name"
                  />
                )}
                {choice.action === 'SKIP' && account.txnCount > 0 && (
                  <span className="badge badge-review">{account.txnCount} rows dropped</span>
                )}
              </div>
            </div>
          );
        })}

        {preview.cashCurrencies.length > 0 && (
          <>
            <h2 style={{ marginTop: 18 }}>Cash withdrawals target</h2>
            {preview.cashCurrencies.map((currency) => {
              const choice = cashChoices.get(currency.currency)!;
              return (
                <div key={currency.currency} className="settings-row">
                  <div className="grow">
                    {currency.currency} · {currency.withdrawalCount} withdrawals
                  </div>
                  <select
                    style={{ width: 'auto' }}
                    value={choice.createNew ? 'new' : String(choice.accountId ?? '')}
                    onChange={(e) => {
                      const value = e.target.value;
                      setCashChoices((current) => {
                        const next = new Map(current);
                        next.set(
                          currency.currency,
                          value === 'new'
                            ? { accountId: null, createNew: true }
                            : { accountId: Number(value), createNew: false },
                        );
                        return next;
                      });
                    }}
                  >
                    <option value="new">＋ Create Cash {currency.currency}</option>
                    {accounts
                      .filter((a) => a.type === 'CASH' && a.currency === currency.currency)
                      .map((a) => (
                        <option key={a.id} value={a.id}>
                          → {a.name}
                        </option>
                      ))}
                  </select>
                </div>
              );
            })}
          </>
        )}

        <div className="form-actions">
          <button className="btn-ghost" onClick={() => setPreview(null)}>
            Back
          </button>
          <button className="btn" onClick={runConfirm} disabled={busy}>
            Run import → <span className="muted">idempotent</span>
          </button>
        </div>
      </div>
    );
  }

  // step 1: upload
  return (
    <>
      <div className="card">
        <h2>Import bank statement</h2>
        <div className="form-row">
          <label htmlFor="import-bank">Bank</label>
          {parserBanks.length === 1 ? (
            <div className="mono">
              {parserBanks[0].name} · parser {parserBanks[0].parserId}
            </div>
          ) : (
            <select
              id="import-bank"
              value={bankId ?? ''}
              onChange={(e) => setBankId(Number(e.target.value))}
            >
              <option value="" disabled>
                Choose bank…
              </option>
              {parserBanks.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name} · {b.parserId}
                </option>
              ))}
            </select>
          )}
        </div>
        <div className="form-row">
          <label htmlFor="import-file">Statement PDF</label>
          <input
            id="import-file"
            type="file"
            accept="application/pdf"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </div>
        {error && (
          <div className="form-row expense" style={{ whiteSpace: 'pre-wrap' }}>
            Import rejected: {error}
          </div>
        )}
        <div className="form-actions">
          <button className="btn" onClick={runPreview} disabled={!bankId || !file || busy}>
            {busy ? 'Parsing…' : 'Upload & parse'}
          </button>
        </div>
      </div>

      {runs != null && runs.length > 0 && (
        <div className="card">
          <h2>Past imports</h2>
          {runs.map((run) => (
            <div key={run.id} className="settings-row">
              <div className="grow">
                <div>{run.fileName}</div>
                <div className="muted" style={{ fontSize: 12 }}>
                  {run.bankName} · {formatDate(run.periodFrom)} – {formatDate(run.periodTo)}
                </div>
              </div>
              <span className="muted amount" style={{ fontSize: 12.5 }}>
                +{run.created} · dup {run.duplicates} · review {run.needsReview}
              </span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
