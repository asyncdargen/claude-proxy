import { useEffect, useState } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { api, has, UserDto } from './api';
import { Icon, IconButton, Modal, ThemeToggle } from './ui';
import { SkeletonLine } from './Skeleton';
import { Login } from './pages/Login';
import { Chat } from './pages/Chat';
import { Dashboard } from './pages/Dashboard';
import { MyAccounts } from './pages/MyAccounts';
import { Users } from './pages/Users';
import { Tokens } from './pages/Tokens';
import { RoutingTokens } from './pages/RoutingTokens';
import { ModelPricing } from './pages/ModelPricing';
import { Stats } from './pages/Stats';
import { MyStats } from './pages/MyStats';
import { UserStats } from './pages/UserStats';

interface NavDef {
  path: string;
  label: string;
  icon: string;
  perm?: string;
  anyPerm?: string[];
}

const NAV: NavDef[] = [
  { path: '/chat', label: 'Chat', icon: 'chat', perm: 'CHAT_USE' },
  { path: '/dashboard', label: 'Dashboard', icon: 'dashboard', anyPerm: ['ACCOUNTS_VIEW', 'STATS_VIEW_WINDOW_ATTRIBUTION'] },
  { path: '/my/accounts', label: 'My Accounts', icon: 'accounts', perm: 'ACCOUNTS_OWN_MANAGE' },
  { path: '/my/stats', label: 'My Stats', icon: 'mystats', perm: 'STATS_VIEW_OWN' },
  { path: '/stats', label: 'Statistics', icon: 'stats', anyPerm: ['STATS_VIEW', 'STATS_VIEW_RECENT', 'STATS_VIEW_ACCOUNTS'] },
  { path: '/tokens', label: 'Proxy Tokens', icon: 'tokens', perm: 'PROXY_USE' },
  { path: '/routing', label: 'API Routing', icon: 'routing', perm: 'ROUTING_USE' },
  { path: '/pricing', label: 'Model Pricing', icon: 'pricing', perm: 'ADMIN' },
  { path: '/users', label: 'Users & Roles', icon: 'users', perm: 'USERS_MANAGE' },
];

const navAllowed = (u: UserDto, n: NavDef) =>
  (!n.perm || has(u, n.perm)) && (!n.anyPerm || n.anyPerm.some((p) => has(u, p)));

/** First section the user is allowed to see — the landing target for `/` and unknown paths. */
function firstAllowedPath(u: UserDto): string {
  return NAV.find((n) => navAllowed(u, n))?.path ?? '/tokens';
}

export function App() {
  const [user, setUser] = useState<UserDto | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.me().then((u) => {
      // An SSO error from an abandoned attempt is moot once a session exists: drop it from the URL.
      const params = new URLSearchParams(window.location.search);
      if (params.has('sso_error')) {
        params.delete('sso_error');
        const rest = params.toString();
        window.history.replaceState(null, '', window.location.pathname + (rest ? `?${rest}` : ''));
      }
      setUser(u);
    }).catch(() => setUser(null)).finally(() => setLoading(false));
  }, []);

  // session probe: neutral card placeholder — we don't yet know whether this lands on the
  // login form or the app shell, so don't pre-draw either one
  if (loading) return (
    <div className="login-wrap">
      <div className="panel login-card" style={{ display: 'grid', gap: 14 }}>
        <SkeletonLine width={132} height={18} />
        <SkeletonLine height={34} />
        <SkeletonLine height={34} />
        <SkeletonLine width={104} height={34} />
      </div>
    </div>
  );
  if (!user) return <Login onLogin={setUser} />;

  return (
    <BrowserRouter>
      <Shell user={user} setUser={setUser} />
    </BrowserRouter>
  );
}

/** The authenticated app shell: sidebar, topbar, and the routed section content. */
function Shell({ user, setUser }: { user: UserDto; setUser: (u: UserDto | null) => void }) {
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('cp-sidebar-collapsed') === '1'; } catch { return false; }
  });
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

  // Close the mobile drawer whenever the route changes.
  useEffect(() => { setDrawerOpen(false); }, [location.pathname]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDrawerOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  function toggleCollapsed() {
    setCollapsed((c) => {
      const n = !c;
      try { localStorage.setItem('cp-sidebar-collapsed', n ? '1' : '0'); } catch { /* ignore */ }
      return n;
    });
  }

  async function logout() {
    await api.logout().catch(() => {});
    setUser(null);
  }

  const allowed = NAV.filter((n) => navAllowed(user, n));
  // prefix match so nested paths keep their section's label; the per-user stats view
  // (/user-stats/5, opened from the Users list) belongs to the Users section
  const active = NAV.find((n) => location.pathname === n.path || location.pathname.startsWith(n.path + '/'))
    ?? (location.pathname.startsWith('/user-stats') ? NAV.find((n) => n.path === '/users') : undefined);
  const activeLabel = active?.label ?? 'claude-proxy';
  const landing = firstAllowedPath(user);
  // The chat owns its own scrolling and fills the viewport; the shell must not pad or scroll it.
  const isChat = location.pathname.startsWith('/chat');

  return (
    <div className={'app' + (collapsed ? ' collapsed' : '') + (drawerOpen ? ' drawer-open' : '') + (isChat ? ' chat-route' : '')}>
      <div className="scrim" onClick={() => setDrawerOpen(false)} />

      <aside className="sidebar">
        <div className="brand">
          <span className="dot" style={{ color: '#fdf3ea' }}><Icon name="sparkle" size={13} /></span>
          <span className="name">claude-proxy</span>
        </div>
        <nav>
          {allowed.map((n) => (
            <NavLink
              key={n.path}
              to={n.path}
              className={({ isActive }) => 'navitem' + (isActive ? ' active' : '')}
              title={collapsed ? n.label : undefined}
            >
              <Icon name={n.icon} />
              <span className="label">{n.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="spacer" />
        <div className="sidebar-foot">
          <ThemeToggle />
          <div className="userbox">
            Signed in as <b>{user.username}</b>
            <br />
            <span>{user.roles.join(', ') || 'no roles'}</span>
          </div>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <button className="ghost sm settings-btn" onClick={() => setProfileOpen(true)} title="Settings">
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                <Icon name="settings" size={15} /><span className="settings-label">Settings</span>
              </span>
            </button>
            <IconButton className="collapse-btn" icon={collapsed ? 'expand' : 'collapse'}
              onClick={toggleCollapsed} label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} />
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <IconButton icon="menu" onClick={() => setDrawerOpen(true)} label="Open menu" />
          <span className="tb-brand">{activeLabel}</span>
          <div className="right"><ThemeToggle /></div>
        </header>
        <div className={'main-scroll' + (isChat ? ' full' : '')}>
          <Routes>
            <Route path="/chat" element={<RequirePerm user={user} perm="CHAT_USE"><Chat onOpenNav={() => setDrawerOpen(true)} /></RequirePerm>} />
            <Route path="/chat/:id" element={<RequirePerm user={user} perm="CHAT_USE"><Chat onOpenNav={() => setDrawerOpen(true)} /></RequirePerm>} />
            <Route path="/dashboard" element={<RequirePerm user={user} anyPerm={['ACCOUNTS_VIEW', 'STATS_VIEW_WINDOW_ATTRIBUTION']}><Dashboard user={user} /></RequirePerm>} />
            <Route path="/my/accounts" element={<RequirePerm user={user} perm="ACCOUNTS_OWN_MANAGE"><MyAccounts user={user} onUserChange={setUser} /></RequirePerm>} />
            <Route path="/my/stats" element={<RequirePerm user={user} perm="STATS_VIEW_OWN"><MyStats canReset={has(user, 'STATS_RESET_OWN')} /></RequirePerm>} />
            <Route path="/stats" element={<RequirePerm user={user} anyPerm={['STATS_VIEW', 'STATS_VIEW_RECENT', 'STATS_VIEW_ACCOUNTS']}><Stats user={user} /></RequirePerm>} />
            <Route path="/tokens" element={<RequirePerm user={user} perm="PROXY_USE"><Tokens /></RequirePerm>} />
            <Route path="/routing" element={<RequirePerm user={user} perm="ROUTING_USE"><RoutingTokens /></RequirePerm>} />
            <Route path="/pricing" element={<RequirePerm user={user} perm="ADMIN"><ModelPricing /></RequirePerm>} />
            <Route path="/user-stats/:id" element={<RequirePerm user={user} perm="USERS_MANAGE"><UserStats /></RequirePerm>} />
            <Route path="/users" element={<RequirePerm user={user} perm="USERS_MANAGE"><Users isAdmin={has(user, 'ADMIN')} meId={user.id} /></RequirePerm>} />
            <Route path="*" element={<Navigate to={landing} replace />} />
          </Routes>
        </div>
      </div>

      {profileOpen && <ProfileModal user={user} onClose={() => setProfileOpen(false)} onSaved={setUser} onLogout={logout} />}
    </div>
  );
}

/** Redirects to the user's first allowed section when they lack permission for a route. */
function RequirePerm({ user, perm, anyPerm, children }: { user: UserDto; perm?: string; anyPerm?: string[]; children: JSX.Element }) {
  const ok = (!perm || has(user, perm)) && (!anyPerm || anyPerm.some((p) => has(user, p)));
  return ok ? children : <Navigate to={firstAllowedPath(user)} replace />;
}

/** Self-service settings: change your own username/password, and log out. */
function ProfileModal({ user, onClose, onSaved, onLogout }: { user: UserDto; onClose: () => void; onSaved: (u: UserDto) => void; onLogout: () => void }) {
  const [username, setUsername] = useState(user.username);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [current, setCurrent] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [ok, setOk] = useState(false);

  const nameChanged = username.trim() !== '' && username.trim() !== user.username;
  const wantsPassword = password !== '';
  const canSave = current !== '' && (nameChanged || wantsPassword) && !busy;

  async function save() {
    setErr(null); setOk(false);
    if (wantsPassword && password !== confirm) { setErr('New passwords do not match'); return; }
    setBusy(true);
    try {
      const updated = await api.updateProfile({
        currentPassword: current,
        username: nameChanged ? username.trim() : undefined,
        password: wantsPassword ? password : undefined,
      });
      onSaved(updated);
      setOk(true); setPassword(''); setConfirm(''); setCurrent('');
    } catch (e: any) { setErr(e.message); } finally { setBusy(false); }
  }

  return (
    <Modal title="Settings" onClose={onClose}
      footer={<>
        <button className="danger" onClick={onLogout}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}><Icon name="logout" size={15} />Log out</span>
        </button>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 10 }}>
          <button className="ghost" onClick={onClose}>Close</button>
          <button disabled={!canSave} onClick={save}>{busy ? '…' : 'Save changes'}</button>
        </div>
      </>}>
      <label className="field"><span>Username</span>
        <input value={username} onChange={(e) => { setUsername(e.target.value); setOk(false); }} autoComplete="username" />
      </label>
      <label className="field"><span>New password <span className="hint">· leave blank to keep</span></span>
        <input type="password" value={password} onChange={(e) => { setPassword(e.target.value); setOk(false); }} autoComplete="new-password" />
      </label>
      {wantsPassword && (
        <label className="field"><span>Confirm new password</span>
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" />
        </label>
      )}
      <label className="field" style={{ marginBottom: 0 }}><span>Current password <span className="hint">· required to save</span></span>
        <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" />
      </label>
      {err && <div className="err">{err}</div>}
      {ok && <div className="hint" style={{ color: 'var(--ok)', marginTop: 8 }}>Saved.</div>}
    </Modal>
  );
}
