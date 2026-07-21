import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  apiErrorMessage,
  createCategoryRule,
  deleteCategoryRule,
  getCategories,
  getCategoryRules,
  previewCategoryRule,
  updateCategoryRule,
} from '../api';
import { useToast } from '../context/ToastContext';
import { Modal } from './Modal';
import { Spinner } from './Spinner';
import type { Category, CategoryRule } from '../types';

/**
 * Category rules directory (mockup 3b): pattern -> category, applied on import.
 * Deterministic substring matching — no guessing (FR-4.7).
 */
export function CategoryRulesCard() {
  const [rules, setRules] = useState<CategoryRule[] | null>(null);
  const [modal, setModal] = useState<CategoryRule | 'new' | null>(null);
  const { toast } = useToast();

  const reload = useCallback(() => {
    getCategoryRules()
      .then(setRules)
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  useEffect(reload, [reload]);

  const remove = async (rule: CategoryRule) => {
    if (!confirm(`Delete rule "${rule.pattern}"?`)) return;
    try {
      await deleteCategoryRule(rule.id);
      reload();
    } catch (e) {
      toast(apiErrorMessage(e), true);
    }
  };

  return (
    <div className="card">
      <h2>
        Category rules · applied on import{' '}
        <button className="row-btn" style={{ float: 'right' }} onClick={() => setModal('new')}>
          + Add rule
        </button>
      </h2>
      {rules == null ? (
        <Spinner />
      ) : rules.length === 0 ? (
        <div className="placeholder">
          No rules yet. Unmatched imported transactions get «needs review».
        </div>
      ) : (
        rules.map((rule) => (
          <div key={rule.id} className="settings-row">
            <div className="grow">
              <span className="mono">{rule.pattern}</span>
              <span className="muted"> → </span>
              <span style={{ color: rule.categoryColor }}>{rule.categoryName}</span>
              {rule.archived && <span className="badge badge-review"> archived</span>}
            </div>
            <div className="row-actions">
              <button className="row-btn" onClick={() => setModal(rule)}>
                Edit
              </button>
              <button className="row-btn" onClick={() => remove(rule)}>
                Delete
              </button>
            </div>
          </div>
        ))
      )}
      {modal && (
        <RuleModal
          existing={modal === 'new' ? null : modal}
          onClose={() => setModal(null)}
          onSaved={reload}
        />
      )}
    </div>
  );
}

/**
 * Rule editor with a live "N transactions would match" counter and an
 * apply-to-existing toggle. Also used as the post-categorization suggestion
 * dialog on the Transactions page (prefilled pattern).
 */
export function RuleModal({
  existing,
  prefillPattern,
  prefillCategoryId,
  onClose,
  onSaved,
}: {
  existing: CategoryRule | null;
  prefillPattern?: string;
  prefillCategoryId?: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [categories, setCategories] = useState<Category[] | null>(null);
  const [pattern, setPattern] = useState(existing?.pattern ?? prefillPattern ?? '');
  const [categoryId, setCategoryId] = useState<number | null>(
    existing?.categoryId ?? prefillCategoryId ?? null,
  );
  const [applyToExisting, setApplyToExisting] = useState(true);
  const [matchCount, setMatchCount] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  useEffect(() => {
    getCategories()
      .then((list) => setCategories(list.filter((c) => !c.system)))
      .catch((e) => toast(apiErrorMessage(e), true));
  }, [toast]);

  // live match counter over current needs-review transactions
  useEffect(() => {
    if (!pattern.trim() || !categoryId) {
      setMatchCount(null);
      return;
    }
    const timer = setTimeout(() => {
      previewCategoryRule(pattern, categoryId)
        .then((result) => setMatchCount(result.matchCount))
        .catch(() => setMatchCount(null));
    }, 300);
    return () => clearTimeout(timer);
  }, [pattern, categoryId]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!pattern.trim() || !categoryId) return;
    setBusy(true);
    try {
      const body = { pattern: pattern.trim(), categoryId, applyToExisting };
      const result = existing
        ? await updateCategoryRule(existing.id, { ...body, archived: existing.archived })
        : await createCategoryRule(body);
      if (result.appliedCount > 0) {
        toast(`Rule saved · ${result.appliedCount} existing transactions categorized`);
      } else {
        toast('Rule saved');
      }
      onSaved();
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
    } finally {
      setBusy(false);
    }
  }

  if (categories == null) {
    return (
      <Modal title="Category rule" onClose={onClose}>
        <Spinner />
      </Modal>
    );
  }

  return (
    <Modal title={existing ? 'Edit rule' : 'Create rule'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="rule-pattern">Description pattern · use | for alternatives</label>
          <input
            id="rule-pattern"
            className="mono"
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            placeholder="OPENAI|CURSOR|ANTHROPIC"
            autoFocus={!existing && !prefillPattern}
            required
          />
        </div>
        <div className="form-row">
          <label htmlFor="rule-category">Category</label>
          <select
            id="rule-category"
            value={categoryId ?? ''}
            onChange={(e) => setCategoryId(Number(e.target.value))}
            required
          >
            <option value="" disabled>
              Choose…
            </option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.type.toLowerCase()})
              </option>
            ))}
          </select>
        </div>
        <div className="form-row muted" style={{ fontSize: 13 }}>
          {matchCount == null
            ? 'Case-insensitive substring match against transaction descriptions.'
            : `${matchCount} uncategorized transaction${matchCount === 1 ? '' : 's'} would match now.`}
        </div>
        <div className="form-row">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input
              type="checkbox"
              style={{ width: 'auto' }}
              checked={applyToExisting}
              onChange={(e) => setApplyToExisting(e.target.checked)}
            />
            Apply to existing uncategorized transactions
          </label>
        </div>
        <div className="form-actions">
          <button type="button" className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={busy || !pattern.trim() || !categoryId}>
            Save rule
          </button>
        </div>
      </form>
    </Modal>
  );
}
