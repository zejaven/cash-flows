import { useState, type FormEvent } from 'react';
import { getSession, login } from '../api';

export function LoginPage({ onLogin }: { onLogin: (username: string) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      const session = await getSession();
      onLogin(session.username);
    } catch {
      setError('Wrong login or password');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="brand">⇄ Cash Flows</div>
        <div className="sub">self-hosted · single user</div>
        <div className="form-row">
          <label htmlFor="login-username">Login</label>
          <input
            id="login-username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
        </div>
        <div className="form-row">
          <label htmlFor="login-password">Password</label>
          <input
            id="login-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </div>
        {error && <div className="expense" style={{ marginBottom: 10 }}>{error}</div>}
        <button className="btn" style={{ width: '100%' }} disabled={busy}>
          Sign in
        </button>
        <div className="note">
          Your data stays on your server. Only outbound call: open.er-api.com (rates).
        </div>
      </form>
    </div>
  );
}
