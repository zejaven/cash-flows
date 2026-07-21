import { useEffect, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { getSession, onUnauthorized } from './api';
import { useTheme } from './context/ThemeContext';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { TransactionsPage } from './pages/TransactionsPage';
import { SettingsPage } from './pages/SettingsPage';

const TITLES: Record<string, string> = {
  '': 'Dashboard',
  transactions: 'Transactions',
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
        <NavLink to="/settings" className={({ isActive }) => (isActive ? 'active' : '')}>
          <span className="tab-icon">⚙</span>Settings
        </NavLink>
      </nav>
    </div>
  );
}
