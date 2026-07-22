import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  apiErrorMessage,
  cancelGeneration,
  confirmImport,
  getAccounts,
  getBanks,
  getCurrentGeneration,
  getGeneratorOptions,
  getGeneratorSettings,
  getImportRuns,
  openGenerationEvents,
  previewImport,
  startParserGeneration,
  updateBank,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { formatMoney } from '../utils/money';
import { formatDate } from '../utils/dates';
import type {
  Account,
  AccountDecision,
  Bank,
  CashDecision,
  GeneratorLogEvent,
  GeneratorOptions,
  GeneratorSettings,
  GeneratorTask,
  ImportPreview,
  ImportReport,
  ImportRun,
  MappingAction,
} from '../types';

type AccountChoice = { action: MappingAction; accountId: number | null; newName: string };
type CashChoice = { accountId: number | null; createNew: boolean };

/**
 * Three-step statement import (mockups 1f/7a): upload & parse -> map accounts -> report.
 * Step 1 has a second mode — generate a new parser with an AI agent (FR-4.10).
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
  const [mode, setMode] = useState<'parse' | 'generate'>('parse');
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
    // a generation may be running from before a page reload — reattach to it
    getCurrentGeneration()
      .then((task) => {
        if (task?.status === 'RUNNING') setMode('generate');
      })
      .catch(() => {});
  }, [toast]);

  const parserBanks = useMemo(() => (banks ?? []).filter((b) => b.parserId), [banks]);

  async function runPreview(idArg?: number, fileArg?: File) {
    const targetBankId = idArg ?? bankId;
    const targetFile = fileArg ?? file;
    if (!targetBankId || !targetFile) return;
    setBusy(true);
    setError(null);
    try {
      const result = await previewImport(targetBankId, targetFile);
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
          const bankName = (banks ?? []).find((b) => b.id === targetBankId)?.name ?? 'Account';
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

  /** Success follow-up of a generation: bind the new parser and parse the same PDF. */
  async function bindAndParse(bank: Bank, parserId: string, statement: File) {
    try {
      await updateBank(bank.id, { name: bank.name, parserId });
      const list = await getBanks();
      setBanks(list);
      setBankId(bank.id);
      setFile(statement);
      setMode('parse');
      await runPreview(bank.id, statement);
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  }

  if (banks == null) return <Spinner />;

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

  // step 1: upload (parse mode) or generate a parser (AI mode)
  return (
    <>
      <div className="card">
        <h2>Import bank statement</h2>
        <div className="form-actions" style={{ justifyContent: 'flex-start', marginBottom: 12 }}>
          <button
            className={mode === 'parse' ? 'btn' : 'btn-ghost'}
            onClick={() => setMode('parse')}
          >
            Parse statement
          </button>
          <button
            className={mode === 'generate' ? 'btn' : 'btn-ghost'}
            onClick={() => setMode('generate')}
          >
            Generate parser (AI)
          </button>
        </div>

        {mode === 'generate' ? (
          <GenerateParserPanel banks={banks} onBindAndParse={bindAndParse} />
        ) : parserBanks.length === 0 ? (
          <div className="placeholder">
            No bank has a statement parser bound. Bind one in Settings — or switch to
            «Generate parser (AI)» to create a parser from a sample statement.
          </div>
        ) : (
          <>
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
              <label htmlFor="import-file">Statement file (PDF, CSV or ZIP)</label>
              <input
                id="import-file"
                type="file"
                accept=".pdf,.csv,.zip"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </div>
            {error && (
              <div className="form-row expense" style={{ whiteSpace: 'pre-wrap' }}>
                Import rejected: {error}
              </div>
            )}
            <div className="form-actions">
              <button className="btn" onClick={() => runPreview()} disabled={!bankId || !file || busy}>
                {busy ? 'Parsing…' : 'Upload & parse'}
              </button>
            </div>
          </>
        )}
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

/**
 * AI parser generation (FR-4.10): pick a bank + sample PDF, watch the agent's
 * live log over SSE, then bind the new parser to the bank and parse right away.
 * Reattaches to a run that survived a page reload.
 */
function GenerateParserPanel({
  banks,
  onBindAndParse,
}: {
  banks: Bank[];
  onBindAndParse: (bank: Bank, parserId: string, file: File) => void;
}) {
  const [options, setOptions] = useState<GeneratorOptions | null>(null);
  const [genSettings, setGenSettings] = useState<GeneratorSettings | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [bankId, setBankId] = useState<number | null>(null);
  const [scriptName, setScriptName] = useState('');
  const [scriptTouched, setScriptTouched] = useState(false);
  const [task, setTask] = useState<GeneratorTask | null>(null);
  const [log, setLog] = useState<GeneratorLogEvent[]>([]);
  const [busy, setBusy] = useState(false);
  const closeRef = useRef<(() => void) | null>(null);
  const seenSeq = useRef<Set<number>>(new Set());
  const logRef = useRef<HTMLDivElement>(null);
  // stick to the bottom only while the user is already there — scrolling up to
  // read history must not be undone by new log lines
  const atBottomRef = useRef(true);
  const { toast } = useToast();

  useEffect(() => {
    getGeneratorOptions()
      .then(setOptions)
      .catch(() => setOptions({ enabled: false, providers: [] }));
    getGeneratorSettings().then(setGenSettings).catch(() => {});
    getCurrentGeneration()
      .then((current) => {
        if (current) {
          setTask(current);
          if (current.status === 'RUNNING') subscribe(current.taskId);
        }
      })
      .catch(() => {});
    return () => closeRef.current?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (atBottomRef.current) logRef.current?.scrollTo({ top: logRef.current.scrollHeight });
  }, [log]);

  function subscribe(taskId: string) {
    closeRef.current?.();
    seenSeq.current = new Set();
    setLog([]);
    closeRef.current = openGenerationEvents(
      taskId,
      (event) => {
        // reconnects replay the buffer — dedupe by seq (truncation marker is -1)
        if (event.seq >= 0) {
          if (seenSeq.current.has(event.seq)) return;
          seenSeq.current.add(event.seq);
        }
        setLog((current) => [...current, event]);
      },
      setTask,
    );
  }

  // fold streamed text deltas into paragraphs for display
  const blocks = useMemo(() => {
    const out: { kind: string; text: string }[] = [];
    for (const event of log) {
      const last = out[out.length - 1];
      if (event.kind === 'text' && last?.kind === 'text') last.text += event.text;
      else out.push({ kind: event.kind, text: event.text });
    }
    return out;
  }, [log]);

  const bank = banks.find((b) => b.id === bankId) ?? null;
  const slugOf = (name: string) =>
    name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '');
  const scriptValid = /^[a-z][a-z0-9_-]{1,40}$/.test(scriptName);
  const provider = genSettings
    ? options?.providers.find((p) => p.id === genSettings.provider)
    : null;
  const model = genSettings
    ? (genSettings.provider === 'CLAUDE' ? genSettings.claudeModel : genSettings.codexModel)
    : null;

  async function start() {
    if (files.length === 0 || !bank || !scriptValid) return;
    setBusy(true);
    try {
      const { taskId } = await startParserGeneration(files, bank.name, scriptName);
      setTask({
        taskId,
        status: 'RUNNING',
        parserId: `${scriptName}-pdf`,
        scriptFile: `${scriptName}.py`,
        error: null,
        warnings: [],
        startedAt: new Date().toISOString(),
        finishedAt: null,
      });
      subscribe(taskId);
    } catch (e) {
      toast(apiErrorMessage(e), true);
    } finally {
      setBusy(false);
    }
  }

  if (options == null) return <Spinner />;

  if (!options.enabled) {
    return (
      <div className="placeholder">
        Parser generation is only available when the app runs locally (it needs the
        claude/codex CLI on this machine).
      </div>
    );
  }

  // a finished or running task takes over the panel
  if (task) {
    return (
      <>
        <div className="muted" style={{ marginBottom: 8, fontSize: 13 }}>
          {task.status === 'RUNNING' && <>Generating <span className="mono">parsers/{task.scriptFile}</span>…</>}
          {task.status === 'SUCCEEDED' && <>✓ Parser <span className="mono">{task.parserId}</span> generated and verified</>}
          {task.status === 'FAILED' && <>✗ Generation failed</>}
          {task.status === 'CANCELLED' && <>Generation cancelled</>}
        </div>
        {task.error && (
          <div className="form-row expense" style={{ whiteSpace: 'pre-wrap' }}>
            {task.error}
          </div>
        )}
        {task.warnings.map((warning, index) => (
          <div key={index} className="badge badge-review" style={{ display: 'block', marginBottom: 4 }}>
            {warning}
          </div>
        ))}
        <div
          ref={logRef}
          onScroll={(e) => {
            const el = e.currentTarget;
            atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
          }}
          className="mono"
          style={{
            maxHeight: 320,
            overflowY: 'auto',
            fontSize: 12,
            whiteSpace: 'pre-wrap',
            background: 'var(--bg)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            padding: 10,
            marginBottom: 10,
          }}
        >
          {blocks.map((block, index) => (
            <div
              key={index}
              className={block.kind === 'activity' ? 'muted' : block.kind === 'error' ? 'expense' : ''}
              style={block.kind === 'info' ? { fontStyle: 'italic' } : undefined}
            >
              {block.text}
            </div>
          ))}
          {task.status === 'RUNNING' && <Spinner />}
        </div>
        <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
          The agent reads your statement — this log may echo its contents. It stays on this
          machine only.
        </div>
        <div className="form-actions">
          {task.status === 'RUNNING' && (
            <button
              className="btn-ghost"
              onClick={() => cancelGeneration(task.taskId).catch((e) => toast(apiErrorMessage(e), true))}
            >
              Cancel
            </button>
          )}
          {task.status !== 'RUNNING' && (
            <button className="btn-ghost" onClick={() => setTask(null)}>
              {task.status === 'SUCCEEDED' ? 'Done' : 'New generation'}
            </button>
          )}
          {task.status === 'SUCCEEDED' && task.parserId && bank && files.length > 0 && (
            <button className="btn" onClick={() => onBindAndParse(bank, task.parserId!, files[0])}>
              Bind to {bank.name} &amp; parse{files.length > 1 ? ' 1st statement' : ''} now →
            </button>
          )}
          {task.status === 'SUCCEEDED' && task.parserId && (!bank || files.length === 0) && (
            <span className="muted" style={{ fontSize: 12.5 }}>
              Bind parser <span className="mono">{task.parserId}</span> to a bank in Settings.
            </span>
          )}
        </div>
      </>
    );
  }

  // form
  return (
    <>
      <div className="form-row">
        <label htmlFor="gen-bank">Bank</label>
        <select
          id="gen-bank"
          value={bankId ?? ''}
          onChange={(e) => {
            const id = Number(e.target.value);
            setBankId(id);
            if (!scriptTouched) {
              const selected = banks.find((b) => b.id === id);
              setScriptName(selected ? slugOf(selected.name) : '');
            }
          }}
        >
          <option value="" disabled>
            Choose bank…
          </option>
          {banks.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}
              {b.parserId ? ` · has parser ${b.parserId}` : ''}
            </option>
          ))}
        </select>
      </div>
      <div className="form-row">
        <label htmlFor="gen-file">Sample statements (PDF, CSV or ZIP)</label>
        <input
          id="gen-file"
          type="file"
          accept=".pdf,.csv,.zip"
          multiple
          onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
        />
        <div className="muted" style={{ fontSize: 12 }}>
          {files.length > 1
            ? `${files.length} statements attached — more samples expose more layout variants; the parser must handle all of them`
            : 'attach several statements if you have them — more samples expose more layout variants'}
        </div>
      </div>
      <div className="form-row">
        <label htmlFor="gen-script">Script name</label>
        <input
          id="gen-script"
          className="mono"
          value={scriptName}
          onChange={(e) => {
            setScriptTouched(true);
            setScriptName(e.target.value);
          }}
          pattern="[a-z][a-z0-9_-]*"
          placeholder="tbank"
        />
        <div className="muted" style={{ fontSize: 12 }}>
          {scriptValid ? (
            <>
              creates <span className="mono">parsers/{scriptName}.py</span> · parser id{' '}
              <span className="mono">{scriptName}</span>
            </>
          ) : (
            'lowercase letters, digits, "-" or "_"'
          )}
        </div>
      </div>
      <div className="form-row muted" style={{ fontSize: 12.5 }}>
        Agent: {provider ? provider.label : '…'} · model {model || 'CLI default'}
        {provider && !provider.available && (
          <span className="expense"> — CLI not available: {provider.error}</span>
        )}{' '}
        · <a href="/settings">change in Settings</a>
      </div>
      <div className="form-actions">
        <button
          className="btn"
          onClick={start}
          disabled={
            files.length === 0 || !bank || !scriptValid || busy || (provider != null && !provider.available)
          }
        >
          {busy ? 'Starting…' : 'Generate parser'}
        </button>
      </div>
    </>
  );
}
