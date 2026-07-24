import { useCallback, useEffect, useRef, useState } from 'react';
import {
  apiErrorMessage,
  confirmLoanPayment,
  getDebtSchedule,
  getScheduleParsers,
  importDebtSchedule,
  skipScheduleItem,
  unskipScheduleItem,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Spinner } from './Spinner';
import { DebtLinkDialog } from './DebtLinkDialog';
import { formatMoney } from '../utils/money';
import { formatDate, today } from '../utils/dates';
import type { Debt, LoanScheduleItem, ParserInfo } from '../types';

/**
 * The imported amortization schedule of a LOAN debt (FR-13.5): row status is
 * derived from debt-entry linkage, unpaid due rows can be confirmed in one
 * click, linked to an already-imported bank transaction, or skipped. Re-import
 * replaces future unpaid rows and keeps paid history.
 */
export function LoanScheduleTable({ debt, onChanged }: { debt: Debt; onChanged: () => void }) {
  const [items, setItems] = useState<LoanScheduleItem[] | null>(null);
  const [parsers, setParsers] = useState<ParserInfo[]>([]);
  const [parserId, setParserId] = useState('');
  const [linking, setLinking] = useState<LoanScheduleItem | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const { toast } = useToast();

  const reload = useCallback(() => {
    getDebtSchedule(debt.id)
      .then(setItems)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [debt.id, toast]);

  useEffect(reload, [reload]);
  useEffect(() => {
    getScheduleParsers()
      .then((list) => {
        setParsers(list);
        if (list.length === 1) setParserId(list[0].id);
      })
      .catch(() => setParsers([]));
  }, []);

  const changed = () => {
    reload();
    onChanged();
  };

  const confirmRow = async (item: LoanScheduleItem) => {
    setBusy(true);
    try {
      await confirmLoanPayment({ scheduleItemId: item.id });
      changed();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    } finally {
      setBusy(false);
    }
  };

  const toggleSkip = async (item: LoanScheduleItem) => {
    try {
      if (item.skipped) await unskipScheduleItem(item.id);
      else await skipScheduleItem(item.id);
      changed();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  const importFile = async (file: File) => {
    if (!parserId) {
      toast('Choose a schedule parser first', true);
      return;
    }
    setBusy(true);
    try {
      const result = await importDebtSchedule(debt.id, parserId, file);
      toast(
        `Schedule imported: ${result.inserted} rows added, ${result.replaced} replaced, ${result.keptPaid} paid kept`,
      );
      changed();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  if (items == null) return <Spinner />;

  const day = today();
  // rows due on or before the anchor date are inside the opening balance —
  // imported history, never payable
  const isCovered = (item: LoanScheduleItem) => !item.paid && item.dueDate <= debt.anchorDate;
  // default view: history tail + everything due soon, not all 200+ rows
  const firstPendingIndex = items.findIndex(
    (item) => !item.paid && !item.skipped && !isCovered(item),
  );
  const visible = showAll
    ? items
    : items.slice(
        Math.max(0, (firstPendingIndex < 0 ? items.length : firstPendingIndex) - 3),
        (firstPendingIndex < 0 ? items.length : firstPendingIndex) + 12,
      );

  return (
    <div style={{ marginTop: 8 }}>
      <div className="filters" style={{ marginBottom: 8 }}>
        <select value={parserId} onChange={(e) => setParserId(e.target.value)}>
          <option value="" disabled>
            Schedule parser…
          </option>
          {parsers.map((parser) => (
            <option key={parser.id} value={parser.id}>
              {parser.id}
            </option>
          ))}
        </select>
        <button
          className="btn-ghost"
          disabled={busy || !parserId}
          onClick={() => fileInput.current?.click()}
        >
          Import schedule…
        </button>
        <input
          ref={fileInput}
          type="file"
          style={{ display: 'none' }}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) importFile(file);
          }}
        />
        {items.length > 0 && (
          <button className="btn-ghost" onClick={() => setShowAll(!showAll)}>
            {showAll ? 'Collapse' : `Show all ${items.length} rows`}
          </button>
        )}
      </div>
      {items.length === 0 ? (
        <div className="placeholder">No schedule yet — import the bank's payment schedule</div>
      ) : (
        visible.map((item) => {
          const covered = isCovered(item);
          const overdue = !item.paid && !item.skipped && !covered && item.dueDate <= day;
          return (
            <div key={item.id} className="settings-row">
              <div className="grow">
                <div>
                  #{item.position} · {formatDate(item.dueDate)}{' '}
                  {item.paid && <span className="badge badge-imported">paid</span>}
                  {covered && !item.skipped && (
                    <span className="badge badge-imported" title="Inside the opening balance">
                      covered
                    </span>
                  )}
                  {item.skipped && <span className="badge badge-review">skipped</span>}
                  {overdue && <span className="badge badge-review">due</span>}
                </div>
                <div className="muted amount" style={{ fontSize: 12.5 }}>
                  principal {formatMoney(item.principalAmount, debt.currency)} · interest{' '}
                  {formatMoney(item.interestAmount, debt.currency)}
                  {item.remainingAfter != null &&
                    ` · left ${formatMoney(item.remainingAfter, debt.currency)}`}
                </div>
              </div>
              <span className="amount">{formatMoney(item.totalAmount, debt.currency)}</span>
              {!item.paid && !item.skipped && !covered && (
                <div className="row-actions">
                  {debt.accountId != null && (
                    <button className="row-btn" disabled={busy} onClick={() => confirmRow(item)}>
                      Confirm
                    </button>
                  )}
                  <button className="row-btn" onClick={() => setLinking(item)}>
                    Link
                  </button>
                  <button className="row-btn" onClick={() => toggleSkip(item)}>
                    Skip
                  </button>
                </div>
              )}
              {item.skipped && (
                <div className="row-actions">
                  <button className="row-btn" onClick={() => toggleSkip(item)}>
                    Unskip
                  </button>
                </div>
              )}
            </div>
          );
        })
      )}
      {linking && (
        <DebtLinkDialog
          debt={debt}
          scheduleItem={linking}
          onClose={() => setLinking(null)}
          onSaved={changed}
        />
      )}
    </div>
  );
}
