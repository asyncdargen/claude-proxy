import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AccountDto, api, fmtTokens, fmtUsd, GroupDto, PoolStats, RoleDto, UserDto } from '../api';
import { AccountEditModal, ShareControl } from '../accounts';
import { Check, Modal, NumberInput, Switch } from '../ui';
import { SkeletonLine, SkeletonTable } from '../Skeleton';

export function Users({ isAdmin, meId }: { isAdmin: boolean; meId?: number }) {
  const navigate = useNavigate();
  const [users, setUsers] = useState<UserDto[] | null>(null);
  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [allPerms, setAllPerms] = useState<string[]>([]);
  const [editing, setEditing] = useState<UserDto | 'new' | null>(null);
  const [accountsFor, setAccountsFor] = useState<UserDto | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setUsers(await api.users());
      const r = await api.roles(); setRoles(r.roles); setAllPerms(r.allPermissions);
      setGroups(await api.groups().catch(() => []));
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  if (err) return <div className="err">{err}</div>;

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>Users &amp; Roles</h1><p className="sub" style={{ margin: 0 }}>Access, permissions, group scope, and daily spend limits.</p></div>
        <div className="row">
          {isAdmin && <button className="ghost" onClick={async () => { if (confirm('Reset usage statistics for ALL users? This cannot be undone.')) { const r = await api.resetAllStats(); alert(r.message); load(); } }}>Reset all stats</button>}
          <button onClick={() => setEditing('new')}>+ New user</button>
        </div>
      </div>

      <h2>Users</h2>
      {!users ? <SkeletonTable rows={4} cols={6} /> : (
      <div className="tablewrap">
        <table>
          {/* "today" here is the UTC day the limits are enforced on, not the viewer's local day
              the charts use — the two only line up in UTC±0 */}
          <thead><tr><th>Username</th><th>Roles</th><th>Group access</th><th>Spent today <span className="hint">UTC</span></th><th>Daily limit</th><th>On</th><th></th></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td><b>{u.username}</b>{u.ssoLinked && <span className="badge accent" style={{ marginLeft: 6 }} title="Linked to an SSO identity">SSO</span>}</td>
                <td><div className="pillrow">{u.roles.length ? u.roles.map((r) => <span key={r} className="grouptag">{r}</span>) : <span className="hint">none</span>}</div></td>
                <td>
                  {u.allGroups ? <span className="badge accent">all groups</span>
                    : u.allowedGroups.length ? <div className="pillrow">{u.allowedGroups.map((gid) => <span key={gid} className="grouptag">{groups.find((g) => g.id === gid)?.name ?? `#${gid}`}</span>)}</div>
                    : <span className="hint">ungrouped only</span>}
                </td>
                <td className="num"><b>{fmtUsd(u.todayCost)}</b> <span className="hint">{fmtTokens(u.todayInputTokens + u.todayOutputTokens)} tok</span></td>
                <td>{u.dailyCostLimit == null ? <span className="hint">unlimited</span> : <BudgetCell used={u.todayCost} limit={u.dailyCostLimit} />}</td>
                <td><Switch checked={u.enabled} onChange={async (v) => { await api.updateUser(u.id, { enabled: v }); load(); }} /></td>
                <td>
                  <div className="row">
                    <button className="sm ghost" onClick={() => setEditing(u)}>Edit</button>
                    <button className="sm ghost" onClick={() => navigate(`/user-stats/${u.id}`)}>Stats</button>
                    <button className="sm ghost" onClick={() => setAccountsFor(u)}>Accounts</button>
                    <button className="sm ghost" onClick={async () => { if (confirm(`Reset ${u.username}'s statistics?`)) { const r = await api.resetUserStats(u.id); alert(r.message); load(); } }}>Reset stats</button>
                    {u.id !== meId && u.enabled && <button className="sm ghost" title="Disable the user, end all panel sessions and delete every proxy/routing token"
                      onClick={async () => { if (confirm(`Offboard ${u.username}? This disables the user, signs them out everywhere and permanently revokes all their proxy and routing tokens.`)) { try { const r = await api.offboardUser(u.id); alert(`Offboarded: ${r.proxyTokensRevoked} proxy and ${r.routingTokensRevoked} routing tokens revoked.`); } catch (e: any) { alert(e.message); } load(); } }}>Offboard</button>}
                    {u.ssoLinked && <button className="sm ghost" onClick={async () => { if (confirm(`Unlink ${u.username}'s SSO identity? Their next SSO sign-in goes through the linking rules again.`)) { try { await api.unlinkUserSso(u.id); } catch (e: any) { alert(e.message); } load(); } }}>Unlink SSO</button>}
                    <button className="sm danger" onClick={async () => { if (confirm(`Delete ${u.username}?`)) { await api.deleteUser(u.id); load(); } }}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      )}

      <h2>Roles</h2>
      {!users ? (
        <div className="panel" style={{ display: 'grid', gap: 10 }}>
          <SkeletonLine width={140} height={13} />
          <SkeletonLine width="60%" height={11} />
        </div>
      ) : (
        <>
          {roles.map((r) => <RoleRow key={r.id} r={r} allPerms={allPerms} onChange={load} />)}
          <CreateRole allPerms={allPerms} onDone={load} />
        </>
      )}

      {editing && (
        <UserModal user={editing === 'new' ? null : editing} roles={roles} groups={groups}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />
      )}
      {accountsFor && <UserAccountsModal user={accountsFor} onClose={() => setAccountsFor(null)} />}
    </div>
  );
}

/** Admin oversight of one user's personal accounts (view + edit + enable/disable + delete). */
function UserAccountsModal({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [editing, setEditing] = useState<AccountDto | null>(null);

  async function load() { try { setStats(await api.userAccounts(user.id)); } catch (e: any) { setErr(e.message); } }
  useEffect(() => { load(); }, []);

  async function toggle(a: AccountDto, v: boolean) { setStats(await api.updateUserAccount(user.id, a.id, { enabled: v })); }
  async function del(a: AccountDto) { if (confirm(`Delete ${user.username}'s account "${a.name}"?`)) setStats(await api.deleteUserAccount(user.id, a.id)); }
  async function share(a: AccountDto, until: string | null) { try { setErr(null); setStats(await api.shareUserAccount(user.id, a.id, until)); } catch (e: any) { setErr(e.message); } }
  const pct = (f: number | null | undefined) => (f != null ? `${Math.round(f * 100)}%` : '—');

  return (
    <Modal title={`${user.username} · personal accounts`} onClose={onClose} width={640}
      footer={<button className="ghost" onClick={onClose}>Close</button>}>
      {err && <div className="err">{err}</div>}
      {!stats ? <SkeletonTable rows={3} cols={5} />
        : stats.accounts.length === 0 ? <p className="hint">This user has no personal accounts.</p> : (
          <div className="tablewrap">
            <table>
              <thead><tr><th>Prio</th><th>Name</th><th>Type</th><th>5h</th><th>Weekly</th><th>Cost</th><th>On</th><th></th></tr></thead>
              <tbody>
                {stats.accounts.map((a) => (
                  <tr key={a.id}>
                    <td className="num">{a.priority}</td>
                    <td><b>{a.name}</b></td>
                    <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
                    <td className="num">{a.type === 'API_KEY' ? <span className="hint">n/a</span> : pct(a.fiveHour?.usageFraction)}</td>
                    <td className="num">{a.type === 'API_KEY' ? <span className="hint">n/a</span> : pct(a.weekly?.usageFraction)}</td>
                    <td className="num">{fmtUsd(a.totalCost)}</td>
                    <td><Switch checked={a.enabled} onChange={(v) => toggle(a, v)} /></td>
                    <td>
                      <div className="row">
                        <ShareControl a={a} onShare={(u) => share(a, u)} />
                        <button className="sm" onClick={() => setEditing(a)}>Edit</button>
                        <button className="sm danger" onClick={() => del(a)}>Delete</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      {editing && (
        <AccountEditModal a={editing} groups={[]} scope="personal"
          update={(id, b) => api.updateUserAccount(user.id, id, b)}
          onClose={() => setEditing(null)} onSaved={(s) => { setStats(s); setEditing(null); }} />
      )}
    </Modal>
  );
}

function BudgetCell({ used, limit }: { used: number; limit: number }) {
  const frac = limit > 0 ? Math.min(1, used / limit) : 0;
  return (
    <div className="win" style={{ minWidth: 130 }}>
      <div className="win-top"><span><b>{fmtUsd(used)}</b> / {fmtUsd(limit)}</span><span>{Math.round(frac * 100)}%</span></div>
      <div className="bar"><span style={{ width: `${Math.round(frac * 100)}%` }} /></div>
    </div>
  );
}

function UserModal({ user, roles, groups, onClose, onSaved }: {
  user: UserDto | null; roles: RoleDto[]; groups: GroupDto[]; onClose: () => void; onSaved: () => void;
}) {
  const isNew = user == null;
  const [username, setUsername] = useState(user?.username ?? '');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState(user?.email ?? '');
  const [selRoles, setSelRoles] = useState<string[]>(user?.roles ?? []);
  const [selGroups, setSelGroups] = useState<number[]>(user?.allowedGroups ?? []);
  const [limitOn, setLimitOn] = useState(user?.dailyCostLimit != null);
  const [limitVal, setLimitVal] = useState<number>(user?.dailyCostLimit ?? 5);
  const [rLimitOn, setRLimitOn] = useState(user?.dailyRoutingCostLimit != null);
  const [rLimitVal, setRLimitVal] = useState<number>(user?.dailyRoutingCostLimit ?? 5);
  const [cLimitOn, setCLimitOn] = useState(user?.dailyChatCostLimit != null);
  const [cLimitVal, setCLimitVal] = useState<number>(user?.dailyChatCostLimit ?? 5);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const toggleRole = (r: string) => setSelRoles((s) => s.includes(r) ? s.filter((x) => x !== r) : [...s, r]);
  const toggleGroup = (g: number) => setSelGroups((s) => s.includes(g) ? s.filter((x) => x !== g) : [...s, g]);
  const isAdmin = selRoles.some((rn) => roles.find((r) => r.name === rn)?.permissions.includes('ADMIN'));

  async function save() {
    setErr(null); setBusy(true);
    try {
      const common = { roles: selRoles, allowedGroups: selGroups };
      if (isNew) {
        await api.createUser({
          ...common, username, password, email: email.trim() || null,
          dailyCostLimit: limitOn ? limitVal : null,
          dailyRoutingCostLimit: rLimitOn ? rLimitVal : null,
          dailyChatCostLimit: cLimitOn ? cLimitVal : null,
        });
      } else {
        const body: any = { ...common };
        if (password) body.password = password;
        if (email.trim()) body.email = email.trim(); else body.clearEmail = true;
        if (limitOn) body.dailyCostLimit = limitVal; else body.clearDailyLimit = true;
        if (rLimitOn) body.dailyRoutingCostLimit = rLimitVal; else body.clearRoutingLimit = true;
        if (cLimitOn) body.dailyChatCostLimit = cLimitVal; else body.clearChatLimit = true;
        await api.updateUser(user!.id, body);
      }
      onSaved();
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }

  return (
    <Modal title={isNew ? 'Create user' : `Edit ${user!.username}`} onClose={onClose}
      footer={<><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={save}>{busy ? '…' : 'Save'}</button></>}>
      {isNew && <label className="field"><span>Username</span><input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus /></label>}
      <label className="field"><span>{isNew ? 'Password' : 'New password (leave blank to keep)'}</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      <label className="field"><span>Email <span className="hint">· optional; lets a first SSO sign-in with this verified email link to this user (if enabled)</span></span><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>

      <div className="field"><span>Roles</span>
        <div className="checks">{roles.map((r) => <Check key={r.id} checked={selRoles.includes(r.name)} onChange={() => toggleRole(r.name)} label={r.name} />)}</div>
      </div>

      <div className="field">
        <span>Account group access {isAdmin && <span className="badge accent" style={{ marginLeft: 6 }}>admin → all groups</span>}</span>
        {isAdmin ? <p className="hint">Admins can use every account regardless of group.</p> : (
          <div className="checks">
            {groups.length === 0 && <span className="hint">No groups defined. User can use ungrouped accounts.</span>}
            {groups.map((g) => <Check key={g.id} checked={selGroups.includes(g.id)} onChange={() => toggleGroup(g.id)} label={`${g.name} (${g.accountCount})`} />)}
          </div>
        )}
      </div>

      <div className="field">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <span style={{ margin: 0 }}>Daily spend limit <span className="hint">· proxy (Claude Code)</span></span>
          <Switch checked={limitOn} onChange={setLimitOn} />
        </div>
        {limitOn && (
          <div style={{ marginTop: 10 }}>
            <div className="row">
              <span className="hint">$</span>
              <NumberInput value={limitVal} onChange={setLimitVal} min={0} step={0.5} />
              <span className="hint">per day (UTC)</span>
            </div>
          </div>
        )}
      </div>

      <div className="field">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <span style={{ margin: 0 }}>Daily routing limit <span className="hint">· OpenAI/Anthropic API</span></span>
          <Switch checked={rLimitOn} onChange={setRLimitOn} />
        </div>
        {rLimitOn && (
          <div style={{ marginTop: 10 }}>
            <div className="row">
              <span className="hint">$</span>
              <NumberInput value={rLimitVal} onChange={setRLimitVal} min={0} step={0.5} />
              <span className="hint">per day (UTC)</span>
            </div>
          </div>
        )}
      </div>
      <div className="field">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <span style={{ margin: 0 }}>Daily chat limit <span className="hint">· built-in chat UI</span></span>
          <Switch checked={cLimitOn} onChange={setCLimitOn} />
        </div>
        {cLimitOn && (
          <div style={{ marginTop: 10 }}>
            <div className="row">
              <span className="hint">$</span>
              <NumberInput value={cLimitVal} onChange={setCLimitVal} min={0} step={0.5} />
              <span className="hint">per day (UTC)</span>
            </div>
          </div>
        )}
      </div>
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

function RoleRow({ r, allPerms, onChange }: { r: RoleDto; allPerms: string[]; onChange: () => void }) {
  const [selected, setSelected] = useState<string[]>(r.permissions);
  const [dirty, setDirty] = useState(false);
  const toggle = (p: string) => { setSelected((s) => s.includes(p) ? s.filter((x) => x !== p) : [...s, p]); setDirty(true); };
  return (
    <div className="panel narrow" style={{ maxWidth: 760 }}>
      <div className="section-head" style={{ margin: '0 0 12px' }}>
        <b>{r.name}</b>
        <div className="row">
          {dirty && <button className="sm" onClick={async () => { await api.updateRole(r.id, { permissions: selected }); setDirty(false); onChange(); }}>Save</button>}
          <button className="sm danger" onClick={async () => { if (confirm(`Delete role ${r.name}?`)) { await api.deleteRole(r.id); onChange(); } }}>Delete</button>
        </div>
      </div>
      <div className="checks">{allPerms.map((p) => <Check key={p} checked={selected.includes(p)} onChange={() => toggle(p)} label={p} />)}</div>
    </div>
  );
}

function CreateRole({ allPerms, onDone }: { allPerms: string[]; onDone: () => void }) {
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const toggle = (p: string) => setSelected((s) => s.includes(p) ? s.filter((x) => x !== p) : [...s, p]);
  return (
    <div className="panel narrow" style={{ maxWidth: 760 }}>
      <h2 style={{ marginTop: 0 }}>Create role</h2>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="checks">{allPerms.map((p) => <Check key={p} checked={selected.includes(p)} onChange={() => toggle(p)} label={p} />)}</div>
      <div style={{ marginTop: 14 }}><button onClick={async () => { if (name.trim()) { await api.createRole({ name, permissions: selected }); setName(''); setSelected([]); onDone(); } }}>Create role</button></div>
    </div>
  );
}
