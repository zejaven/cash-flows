import { useState, type FormEvent } from 'react';
import { apiErrorMessage, createCategory, updateCategory } from '../api';
import { useToast } from '../context/ToastContext';
import { Modal } from './Modal';
import type { Category, CategoryType } from '../types';

function hslToHex(h: number, s: number, l: number): string {
  s /= 100;
  l /= 100;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => {
    const k = (n + h / 30) % 12;
    const color = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(255 * color)
      .toString(16)
      .padStart(2, '0');
  };
  return `#${f(0)}${f(8)}${f(4)}`;
}

/** A fresh, pleasantly saturated random color so new categories aren't all the same blue. */
function randomColor(): string {
  return hslToHex(Math.floor(Math.random() * 360), 60 + Math.random() * 25, 55 + Math.random() * 15);
}

/**
 * Create/edit a category (FR-1.x). Reused by Settings and the inline "+ New" flow
 * inside the transaction form. New categories start with a random color; `onSaved`
 * hands back the saved category so callers can immediately select it.
 */
export function CategoryForm({
  existing,
  defaultType,
  onClose,
  onSaved,
}: {
  existing: Category | null;
  /** Preselected type for a brand-new category (matches the transaction's type). */
  defaultType?: CategoryType;
  onClose: () => void;
  onSaved: (category: Category) => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [type, setType] = useState<CategoryType>(existing?.type ?? defaultType ?? 'EXPENSE');
  const [color, setColor] = useState(() => existing?.color ?? randomColor());
  const [busy, setBusy] = useState(false);
  const { toast } = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const body = { name, type, color, archived: existing?.archived ?? false };
      const saved = existing
        ? await updateCategory(existing.id, body as Partial<Category>)
        : await createCategory(body as Partial<Category>);
      onSaved(saved);
      onClose();
    } catch (err) {
      toast(apiErrorMessage(err), true);
      setBusy(false);
    }
  }

  return (
    <Modal title={existing ? 'Edit category' : 'New category'} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="form-row">
          <label htmlFor="cat-name">Name</label>
          <input
            id="cat-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
            required
          />
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
          <button className="btn" disabled={busy}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}
