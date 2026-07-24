import { useEffect, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { getSession, onUnauthorized } from './api';
import { useTheme } from './context/ThemeContext';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { ImportPage } from './pages/ImportPage';
import { RecurringPage } from './pages/RecurringPage';
import { DebtsPage } from './pages/DebtsPage';
import { ForecastPage } from './pages/ForecastPage';
import { StatisticsPage } from './pages/StatisticsPage';
import { ConvertPage } from './pages/ConvertPage';
import { ReconcilePage } from './pages/ReconcilePage';
import { SettingsPage } from './pages/SettingsPage';

const TITLES: Record<string, string> = {
  '': 'Dashboard',
  transactions: 'Transactions',
  import: 'Import',
  recurring: 'Recurring & planned',
  debts: 'Debts & loans',
  forecast: 'Forecast',
  statistics: 'Statistics',
  convert: 'Convert',
  reconcile: 'Reconcile',
  settings: 'Settings',
};

function ThemeSwitcher() {
  const { theme, toggle } = useTheme();
  return (
    <button className="btn-ghost" onClick={toggle} title="Toggle theme" aria-label="Toggle theme">
      {theme === 'dark' ? '☀️' : '🌙'}
    </button>
  );
}

export default function App() {
  const [user, setUser] = useState<string | null>(null);
  const [checked, setChecked] = useState(false);
  const location = useLocation();

  useEffect(() => {
    onUnauthorized(() => setUser(null));
    getSession()
      .then((session) => setUser(session.username))
      .catch(() => setUser(null))
      .finally(() => setChecked(true));
  }, []);

  if (!checked) return null;
  if (!user) return <LoginPage onLogin={setUser} />;

  const section = location.pathname.split('/')[1] ?? '';
  const title = TITLES[section] ?? 'Cash Flows';

  const nav = (
    <>
      <NavLink to="/" end className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Dashboard
      </NavLink>
      <NavLink
        to="/transactions"
        className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
      >
        Transactions
      </NavLink>
      <NavLink to="/import" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Import
      </NavLink>
      <NavLink to="/recurring" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Recurring
      </NavLink>
      <NavLink to="/debts" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Debts
      </NavLink>
      <NavLink to="/forecast" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Forecast
      </NavLink>
      <NavLink to="/statistics" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Statistics
      </NavLink>
      <NavLink to="/convert" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Convert
      </NavLink>
      <NavLink to="/reconcile" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Reconcile
      </NavLink>
      <NavLink to="/settings" className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
        Settings
      </NavLink>
    </>
  );

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">Cash Flows</div>
        {nav}
      </aside>
      <div className="main">
        <header className="topbar">
          <h1>{title}</h1>
          <div className="topbar-actions">
            <ThemeSwitcher />
          </div>
        </header>
        <div className="content">
          <Routes>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/transactions" element={<TransactionsPage />} />
            <Route path="/import" element={<ImportPage />} />
            <Route path="/recurring" element={<RecurringPage />} />
            <Route path="/debts" element={<DebtsPage />} />
            <Route path="/forecast" element={<ForecastPage />} />
            <Route path="/statistics" element={<StatisticsPage />} />
            <Route path="/convert" element={<ConvertPage />} />
            <Route path="/reconcile" element={<ReconcilePage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </div>
      <nav className="tabbar">
        <NavLink to="/" end className={({ isActive }) => (isActive ? 'active' : '')}>
          <span className="tab-icon">⌂</span>Home
        </NavLink>
        <NavLink to="/transactions" className={({ isActive }) => (isActive ? 'active' : '')}>
          <span className="tab-icon">≣</span>Txns
        </NavLink>
        <NavLink to="/recurring" className={({ isActive }) => (isActive ? 'active' : '')}>
          <span className="tab-icon">↻</span>Recur
        </NavLink>
        <NavLink to="/forecast" className={({ isActive }) => (isActive ? 'active' : '')}>
          <span className="tab-icon">📈</span>Forecast
        </NavLink>
        <NavLink to="/settings" className={({ isActive }) => (isActive ? 'active' : '')}>
          <span className="tab-icon">⚙</span>Settings
        </NavLink>
      </nav>
    </div>
  );
}
