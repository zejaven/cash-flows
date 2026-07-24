import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  calculateRoute,
  compareRoutes,
  createConversionRoute,
  deleteConversionRoute,
  updateConversionRoute,
  getConversionRoutes,
} from '../api';
import { useSettings } from '../context/SettingsContext';
import { useToast } from '../context/ToastContext';
import { Spinner } from '../components/Spinner';
import { Modal } from '../components/Modal';
import { TransferModal } from '../components/TransferForm';
import { formatMoney, KNOWN_CURRENCIES } from '../utils/money';
import type { CalcResult, CompareRow, ConversionRoute, ConversionStepDef } from '../types';

export function ConvertPage() {
  const [routes, setRoutes] = useState<ConversionRoute[] | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [direction, setDirection] = useState<'FORWARD' | 'REVERSE'>('FORWARD');
  const [amount, setAmount] = useState('');
  const [calc, setCalc] = useState<CalcResult | null>(null);
  const [calcError, setCalcError] = useState<string | null>(null);
  const [comparison, setComparison] = useState<CompareRow[]>([]);
  const [editor, setEditor] = useState<ConversionRoute | 'new' | null>(null);
  const [transfer, setTransfer] = useState<{
    amountSent: number;
    amountReceived: number;
    description: string;
  } | null>(null);
  const { fxRates } = useSettings();
  const { toast } = useToast();

  const reload = useCallback(() => {
    getConversionRoutes()
      .then((list) => {
        setRoutes(list);
        setSelectedId((current) => current ?? list.find((r) => !r.archived)?.id ?? null);
      })
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  useEffect(reload, [reload]);

  const selected = routes?.find((route) => route.id === selectedId) ?? null;

  // live recalculation, debounced (FR-5.3)
  useEffect(() => {
    setCalc(null);
    setCalcError(null);
    if (!selected || !amount || Number(amount) <= 0) return;
    const timer = setTimeout(() => {
      calculateRoute(selected.id, direction, Number(amount))
        .then((result) => {
          setCalc(result);
          if (selected.fromCurrency && selected.toCurrency) {
            compareRoutes(selected.fromCurrency, selected.toCurrency, result.amountIn)
              .then(setComparison)
              .catch(() => setComparison([]));
          }
        })
        .catch((e) => setCalcError(apiErrorMessage(e)));
    }, 250);
    return () => clearTimeout(timer);
  }, [selected, direction, amount]);

  const marketHeader = (() => {
    if (!fxRates || !selected?.fromCurrency || !selected.toCurrency) return null;
    const from = fxRates.rates[selected.fromCurrency === 'USDT' ? 'USD' : selected.fromCurrency];
    const to = fxRates.rates[selected.toCurrency === 'USDT' ? 'USD' : selected.toCurrency];
    if (!from || !to) return null;
    return `market: 1 ${selected.toCurrency} = ${(from / to).toFixed(4)} ${selected.fromCurrency} · open.er-api.com`;
  })();

  const remove = async (route: ConversionRoute) => {
    if (!confirm(`Delete route "${route.name}"?`)) return;
    try {
      await deleteConversionRoute(route.id);
      setSelectedId(null);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  if (routes == null) return <Spinner />;

  return (
    <>
      <div className="section-head">
        <span className="overline">Conversion routes</span>
        {marketHeader && (
          <span className="note amount" style={{ textAlign: 'right' }}>
            {marketHeader}
          </span>
        )}
      </div>

      {routes.length === 0 ? (
        <>
          <div className="placeholder">
            No routes yet — save the chains you actually use (e.g. «THB via ByBit»)
          </div>
          <div style={{ textAlign: 'center' }}>
            <button className="btn" onClick={() => setEditor('new')}>
              + New route
            </button>
          </div>
        </>
      ) : (
        <div className="convert-grid">
          <div>
            {selected ? (
              <div className="card-deep">
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 10,
                    flexWrap: 'wrap',
                  }}
                >
                  <b style={{ fontSize: 13 }}>{selected.name}</b>
                  <div className="pill-switch">
                    <button
                      className={direction === 'FORWARD' ? 'active' : ''}
                      onClick={() => setDirection('FORWARD')}
                    >
                      From input
                    </button>
                    <button
                      className={direction === 'REVERSE' ? 'active' : ''}
                      onClick={() => setDirection('REVERSE')}
                    >
                      To target ⇄
                    </button>
                  </div>
                </div>
                <div className="form-row" style={{ marginTop: 12, marginBottom: 0 }}>
                  <input
                    type="number"
                    step="any"
                    min="0"
                    inputMode="decimal"
                    placeholder={
                      direction === 'FORWARD'
                        ? `amount you send, ${selected.fromCurrency}`
                        : `amount you need, ${selected.toCurrency}`
                    }
                    value={amount}
                    onChange={(e) => setAmount(e.target.value)}
                    className="amount"
                  />
                </div>
                {calcError && <div className="expense" style={{ marginTop: 10 }}>{calcError}</div>}
                {calc && (
                  <>
                    <div className="io-row">
                      <div className="io-box">
                        <div className="lbl">You send</div>
                        <div className="val">
                          {formatMoney(calc.amountIn, selected.fromCurrency ?? '')}
                        </div>
                      </div>
                      <div className="arrow">→</div>
                      <div className="io-box get">
                        <div className="lbl">You get</div>
                        <div className="val">
                          {formatMoney(calc.amountOut, selected.toCurrency ?? '')}
                        </div>
                      </div>
                    </div>
                    {calc.steps.map((step) => (
                      <div key={step.position} className="settings-row amount" style={{ fontSize: 12.5 }}>
                        <span className="mono" style={{ width: 18, color: 'var(--dim)' }}>
                          {step.position}
                        </span>
                        <div className="grow">
                          {step.fromCurrency} → {step.toCurrency}
                          <span className="muted">
                            {' '}
                            · {step.rate}
                            {step.rateInverted
                              ? ` ${step.fromCurrency}/${step.toCurrency}`
                              : ` ${step.toCurrency}/${step.fromCurrency}`}
                            {step.feePct > 0 && ` · fee ${step.feePct}%`}
                            {step.feeFixed > 0 &&
                              ` · fee ${formatMoney(step.feeFixed, step.toCurrency)} fix`}
                          </span>
                        </div>
                        <span>
                          {formatMoney(step.amountIn, step.fromCurrency)} →{' '}
                          <b>{formatMoney(step.amountOut, step.toCurrency)}</b>
                        </span>
                      </div>
                    ))}
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 10,
                        flexWrap: 'wrap',
                        marginTop: 10,
                        paddingTop: 10,
                        borderTop: '1px solid var(--border-soft)',
                      }}
                    >
                      <span className="muted amount" style={{ fontSize: 11.5 }}>
                        effective{' '}
                        <b style={{ color: 'var(--text)' }}>
                          {calc.effectiveRate?.toFixed(4)} {selected.fromCurrency}/{selected.toCurrency}
                        </b>
                        {calc.marketRate != null && calc.lossPct != null && (
                          <>
                            {' '}
                            · market {calc.marketRate.toFixed(4)} · loss{' '}
                            <b className={calc.lossPct < 0 ? 'review-text' : 'income'}>
                              {calc.lossPct > 0 ? '+' : ''}
                              {calc.lossPct.toFixed(1)}%
                            </b>
                          </>
                        )}
                      </span>
                      <button
                        className="btn btn-sm"
                        onClick={() =>
                          setTransfer({
                            amountSent: calc.amountIn,
                            amountReceived: calc.amountOut,
                            description: selected.name,
                          })
                        }
                      >
                        Create transfer →
                      </button>
                    </div>
                  </>
                )}
              </div>
            ) : (
              <div className="placeholder">Select a route</div>
            )}
          </div>

          <div>
            <div className="section-head" style={{ marginTop: 0 }}>
              <span className="overline">Saved routes</span>
              <button className="btn btn-sm" onClick={() => setEditor('new')}>
                + New route
              </button>
            </div>
            <div className="list-table">
              {routes.map((route) => (
                <div key={route.id} className="settings-row">
                  <button
                    className="grow"
                    style={{
                      background: 'none',
                      border: 'none',
                      color: route.id === selectedId ? 'var(--cta)' : 'var(--text)',
                      textAlign: 'left',
                      fontWeight: route.id === selectedId ? 700 : 600,
                      padding: 0,
                      fontSize: 12.5,
                    }}
                    onClick={() => setSelectedId(route.id)}
                  >
                    {route.name}{' '}
                    <span className="muted mono" style={{ fontSize: 11, fontWeight: 400 }}>
                      {route.fromCurrency} → {route.toCurrency} · {route.steps.length} steps
                    </span>
                    {route.archived && <span className="badge badge-review"> archived</span>}
                  </button>
                  <div className="row-actions">
                    <button className="row-btn" onClick={() => setEditor(route)}>
                      Edit steps
                    </button>
                    <button className="row-btn" onClick={() => remove(route)}>
                      Delete
                    </button>
                  </div>
                </div>
              ))}
            </div>

            {comparison.length > 1 && selected && (
              <>
                <div className="section-head">
                  <span className="overline">
                    Compare · {selected.fromCurrency} → {selected.toCurrency}
                  </span>
                </div>
                <div className="list-table">
                  {comparison.map((row) => (
                    <div
                      key={row.routeId}
                      className={`settings-row amount${row.best ? ' best-row' : ''}`}
                      style={{ fontSize: 12.5 }}
                    >
                      <div className="grow" style={{ fontWeight: 600 }}>
                        {row.name} {row.best && <span className="badge badge-income">BEST</span>}
                      </div>
                      <span style={{ marginRight: 14 }}>{row.effectiveRate.toFixed(4)}</span>
                      <span className={row.lossPct != null && row.lossPct < 0 ? 'expense' : 'income'}>
                        {row.lossPct != null
                          ? `${row.lossPct > 0 ? '+' : ''}${row.lossPct.toFixed(1)}%`
                          : '—'}
                      </span>
                    </div>
                  ))}
                </div>
              </>
            )}

            <div className="card-deep" style={{ fontSize: 11, color: 'var(--muted)', lineHeight: 1.6 }}>
              Saved routes are editable chains of steps (rate + fixed fee + % fee). The calculator
              runs both directions; «Create transfer» pre-fills a transfer with the calculated
              amounts — rates stay editable.
            </div>
          </div>
        </div>
      )}

      {editor && (
        <RouteEditor
          existing={editor === 'new' ? null : editor}
          onClose={() => setEditor(null)}
          onSaved={reload}
        />
      )}
      {transfer && (
        <TransferModal
          prefill={transfer}
          onClose={() => setTransfer(null)}
          onSaved={() => toast('Transfer saved')}
        />
      )}
    </>
  );
}

function RouteEditor({
  existing,
  onClose,
  onSaved,
}: {
  existing: ConversionRoute | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [steps, setSteps] = useState<ConversionStepDef[]>(
    existing?.steps.map((step) => ({ ...step })) ?? [
      { fromCurrency: 'KZT', toCurrency: 'USDT', rate: 0, rateInverted: true, feeFixed: 0, feePct: 0 },
    ],
  );
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  const patchStep = (index: number, patch: Partial<ConversionStepDef>) =>
    setSteps((current) => current.map((step, i) => (i === index ? { ...step, ...patch } : step)));

  const addStep = () =>
    setSteps((current) => [
      ...current,
      {
        fromCurrency: current[current.length - 1]?.toCurrency ?? '',
        toCurrency: '',
        rate: 0,
        rateInverted: false,
        feeFixed: 0,
        feePct: 0,
      },
    ]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const body = { name, steps, archived: existing?.archived ?? false };
      if (existing) await updateConversionRoute(existing.id, body);
      else await createConversionRoute(body);
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={existing ? 'Edit route' : 'New conversion route'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="route-name">Name</label>
          <input
            id="route-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="THB via ByBit"
            required
          />
        </div>
        {steps.map((step, index) => (
          <div
            key={index}
            className="card"
            style={{ padding: '10px 12px', marginBottom: 8, background: 'var(--panel-2)' }}
          >
            <div className="form-grid">
              <div className="form-row" style={{ marginBottom: 6 }}>
                <label>From</label>
                <input
                  value={step.fromCurrency}
                  onChange={(e) => patchStep(index, { fromCurrency: e.target.value.toUpperCase() })}
                  list="route-currencies"
                  maxLength={8}
                  required
                />
              </div>
              <div className="form-row" style={{ marginBottom: 6 }}>
                <label>To</label>
                <input
                  value={step.toCurrency}
                  onChange={(e) => patchStep(index, { toCurrency: e.target.value.toUpperCase() })}
                  list="route-currencies"
                  maxLength={8}
                  required
                />
              </div>
            </div>
            <div className="form-grid">
              <div className="form-row" style={{ marginBottom: 6 }}>
                <label>Rate</label>
                <input
                  type="number"
                  step="any"
                  min="0"
                  value={step.rate || ''}
                  onChange={(e) => patchStep(index, { rate: Number(e.target.value) })}
                  required
                />
              </div>
              <div className="form-row" style={{ marginBottom: 6 }}>
                <label>Quoted as</label>
                <select
                  value={step.rateInverted ? 'inv' : 'dir'}
                  onChange={(e) => patchStep(index, { rateInverted: e.target.value === 'inv' })}
                >
                  <option value="dir">
                    {step.toCurrency || 'TO'} per 1 {step.fromCurrency || 'FROM'}
                  </option>
                  <option value="inv">
                    {step.fromCurrency || 'FROM'} per 1 {step.toCurrency || 'TO'}
                  </option>
                </select>
              </div>
            </div>
            <div className="form-grid">
              <div className="form-row" style={{ marginBottom: 0 }}>
                <label>Fee fixed ({step.toCurrency || 'TO'})</label>
                <input
                  type="number"
                  step="any"
                  min="0"
                  value={step.feeFixed || ''}
                  onChange={(e) => patchStep(index, { feeFixed: Number(e.target.value) || 0 })}
                />
              </div>
              <div className="form-row" style={{ marginBottom: 0 }}>
                <label>Fee %</label>
                <input
                  type="number"
                  step="any"
                  min="0"
                  value={step.feePct || ''}
                  onChange={(e) => patchStep(index, { feePct: Number(e.target.value) || 0 })}
                />
              </div>
            </div>
            {steps.length > 1 && (
              <button
                type="button"
                className="row-btn"
                style={{ marginTop: 6 }}
                onClick={() => setSteps((current) => current.filter((_, i) => i !== index))}
              >
                Remove step
              </button>
            )}
          </div>
        ))}
        <datalist id="route-currencies">
          {KNOWN_CURRENCIES.map((c) => (
            <option key={c} value={c} />
          ))}
        </datalist>
        <button type="button" className="btn-ghost" onClick={addStep}>
          + Add step
        </button>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy}>
            Save route
          </button>
        </div>
      </form>
    </Modal>
  );
}
