// Shared account UI: the rich pool table, summary cards, add/edit modals and group
// management. Used by the Dashboard (global pool), My Accounts (personal) and the
// admin oversight view on the Users page.
import { ReactNode, useState } from 'react';
import { AccountDto, api, fmtReset, fmtTokens, fmtUsd, GroupDto, OverageDto, PoolStats, WindowLimitDto } from './api';
import { Modal, NumberInput, Segmented, Switch } from './ui';

/** The scope-specific API calls a table/modal needs. Bound to the global or personal endpoints. */
export interface AccountApi {
  create: (b: unknown) => Promise<PoolStats>;
  update: (id: number, b: unknown) => Promise<PoolStats>;
  remove: (id: number) => Promise<unknown>;
  refreshOne: (id: number) => Promise<PoolStats>;
  refreshAll: () => Promise<PoolStats>;
  oauthStart: () => Promise<{ authorizeUrl: string; state: string }>;
  oauthComplete: (b: unknown) => Promise<PoolStats>;
  oauthReauth: (id: number, b: unknown) => Promise<PoolStats>;
}

export const globalAccountApi: AccountApi = {
  create: api.createAccount, update: api.updateAccount, remove: api.deleteAccount,
  refreshOne: api.refreshOne, refreshAll: api.refreshAll, oauthStart: api.oauthStart, oauthComplete: api.oauthComplete, oauthReauth: api.oauthReauth,
};

export const personalAccountApi: AccountApi = {
  create: api.createMyAccount, update: api.updateMyAccount, remove: api.deleteMyAccount,
  refreshOne: api.refreshMyOne, refreshAll: api.refreshMyAll, oauthStart: api.myOauthStart, oauthComplete: api.myOauthComplete, oauthReauth: api.myOauthReauth,
};

export type Scope = 'global' | 'personal';

/* ---------------------------------------------------------------- cells */

function WindowCell({ w, isApi, overage }: { w: WindowLimitDto | null; isApi: boolean; overage?: OverageDto | null }) {
  if (isApi) return <span className="hint">n/a</span>;
  if (!w || (w.usageFraction == null && w.resetAt == null && w.status !== 'REJECTED')) {
    return <span className="hint">—</span>;
  }
  const hasPct = w.usageFraction != null;
  const frac = w.usageFraction ?? (w.status === 'REJECTED' ? 1 : 0);
  // With paid credits enabled the subscription week can sit at 100% while the account keeps
  // serving. Anthropic reports the true figure separately (7d including credits) — show it, or
  // the row reads as "dead until the reset" when it is actually working and costing money.
  const withCredits = overage?.weeklyWithOverage;
  const showCredits = withCredits != null && frac >= 0.99;
  return (
    <div className="win">
      <div className="win-top">
        <span>{hasPct ? fmtPct(frac) : (w.status === 'REJECTED' ? 'limited' : '—')}</span>
        <span>reset <b>{fmtReset(w.resetAt)}</b></span>
      </div>
      <div className="bar"><span style={{ width: `${Math.round(frac * 100)}%` }} /></div>
      {showCredits && (
        <div className="win-top" style={{ marginTop: 2 }} title="Weekly usage including paid credits: the subscription window is full, credits are covering the rest.">
          <span className="hint">with credits</span>
          <b className="hint">{fmtPct(withCredits!)}</b>
        </div>
      )}
    </div>
  );
}

function healthBadge(a: AccountDto) {
  const cls = !a.enabled ? 'muted' : a.health === 'OK' ? 'ok' : a.health === 'REFRESH_FAILED' ? 'warn' : 'bad';
  const label = !a.enabled ? 'disabled' : a.rateLimitedUntil && new Date(a.rateLimitedUntil) > new Date() ? 'limited' : a.health.toLowerCase().replace('_', ' ');
  return <span className={`badge ${cls}`}>{label}</span>;
}

/** Why Anthropic says paid credits are unavailable, in words an operator can act on. */
const CREDIT_REASON: Record<string, string> = {
  free: 'plan has no usage credits',
  preference: 'turned off for this account',
  extra_usage_disabled: 'disabled by the organization',
  org_level_disabled: 'disabled at the organization level',
  network_error: 'Anthropic could not report credit state',
};

/**
 * Paid usage credits ("extra usage") on an account. Only shown once there is something to say:
 * credits actually serving traffic, or budget already spent. A plain subscription stays quiet.
 */
function creditsBadge(a: AccountDto) {
  const o = a.overage;
  if (!o) return null;
  // Anthropic reports the credit allowance either as its own window (`overage-utilization`,
  // what live traffic carries) or as a monthly budget; prefer the more specific one.
  const monthly = o.monthlyUtilization ?? o.utilization;
  const budget = monthly != null ? `\nCredit allowance spent: ${fmtPct(monthly)}` : '';
  const channel = o.channelUtilization != null ? `\nThis channel: ${fmtPct(o.channelUtilization)}` : '';
  const reset = o.resetAt ? `\nBudget resets ${fmtReset(o.resetAt)}` : '';
  // Credits keep the upstream serving, but the pool still drops an account past its threshold
  // unless it opted into fallback — so say so, or the account looks unusable for no reason.
  const skipped = !a.overThreshold && (a.usageFraction ?? 0) >= a.threshold
    ? '\n\nThe pool is skipping this account: it is over its threshold and not opted into fallback. Turn on "use past threshold" in Edit to keep routing to it (on credits).'
    : '';
  if (o.inUse) {
    return (
      <span className="badge warn" title={`Subscription window is spent — requests are being served from paid credits, at API rates.${budget}${channel}${reset}${skipped}`}>
        on credits{monthly != null ? ` ${fmtPct(monthly)}` : ''}
      </span>
    );
  }
  if (monthly != null && monthly > 0) {
    return (
      <span className="badge muted" title={`Paid credits available, partly spent this period.${budget}${channel}${reset}`}>
        credits {fmtPct(monthly)}
      </span>
    );
  }
  const reason = o.disabledReason ? CREDIT_REASON[o.disabledReason] ?? o.disabledReason : null;
  if (reason) {
    return <span className="badge muted" title={`No paid credits: ${reason}. The account stops at its subscription limit.`}>no credits</span>;
  }
  return null;
}

/** Free grace allowance on top of a saturated window — shown only while it is being drawn on. */
function graceBadge(a: AccountDto) {
  const g = a.grace;
  if (!g) return null;
  const active = (g.fiveHourUtilization ?? 0) > 0 || (g.weeklyUtilization ?? 0) > 0;
  if (!active) return null;
  const parts = [
    g.fiveHourUtilization != null ? `5h: ${fmtPct(g.fiveHourUtilization)}` : null,
    g.weeklyUtilization != null ? `7d: ${fmtPct(g.weeklyUtilization)}` : null,
  ].filter(Boolean).join(' · ');
  return <span className="badge muted" title={`Running on Anthropic's grace allowance past the window limit (free, unlike credits).\n${parts}`}>grace</span>;
}

/* ---------------------------------------------------------------- summary cards */

// Coefficient-weighted pool headroom, shown as a percent: each account's remaining fraction is
// multiplied by its coefficient and summed (Σ coefficient × (1 − usage)), so a ×20 account
// contributes up to 2000%. The figure can exceed 100% and is shown out of the pool's Σ coefficient
// capacity. The normalized "% of capacity left" (always 0..100%) stays on hover.
const fmtPct = (n: number) => `${Math.round(n * 100)}%`;

/** Headroom card: coefficient-weighted remaining as a percent, out of Σ-coefficient capacity. */
function HeadroomCard({ label, s }: { label: ReactNode; s: PoolStats }) {
  const capPct = s.totalEffectiveCapacity > 0 ? s.totalEffectiveRemaining / s.totalEffectiveCapacity : 0;
  const wkPct = s.totalWeeklyCapacity > 0 ? s.totalWeeklyRemaining / s.totalWeeklyCapacity : 0;
  const title = `5h: ${Math.round(capPct * 100)}% of capacity left\n`
    + (s.totalWeeklyCapacity > 0 ? `weekly: ${Math.round(wkPct * 100)}% of capacity left` : 'weekly: n/a');
  return (
    <div className="card" title={title}>
      <div className="label">{label}</div>
      <div className="value">{fmtPct(s.totalEffectiveRemaining)} <small className="hint" style={{ fontSize: 13 }}>/ {fmtPct(s.totalEffectiveCapacity)} (5h)</small></div>
      <div className="hint">{s.totalWeeklyCapacity > 0 ? `weekly: ${fmtPct(s.totalWeeklyRemaining)} / ${fmtPct(s.totalWeeklyCapacity)} left` : 'weekly: n/a'}</div>
    </div>
  );
}

/**
 * Requests streaming from Anthropic *right now*, split by datapath. Pool-wide on the Dashboard
 * (everyone), own on "My Accounts". Replaces the all-time request counter, which said nothing
 * about what the proxy is doing at this moment — and is still available per account in the table.
 */
function ActiveSessionsCard({ s, scope }: { s: PoolStats; scope: Scope }) {
  const total = s.activeProxySessions + s.activeRoutingSessions;
  return (
    <div className="card">
      <div className="label">{scope === 'personal' ? 'My active sessions' : 'Active sessions'}</div>
      <div className="value">{total}</div>
      <div className="hint">{s.activeProxySessions} proxy · {s.activeRoutingSessions} routing</div>
      <div className="hint">{total === 0 ? 'nothing streaming' : 'streaming from Anthropic now'}</div>
    </div>
  );
}

export function PoolCards({ stats, scope, shared }: { stats: PoolStats; scope: Scope; shared?: PoolStats | null }) {
  const activeName = stats.activeAccountId ? stats.accounts.find((a) => a.id === stats.activeAccountId)?.name ?? `#${stats.activeAccountId}` : '—';
  return (
    <div className="cards" style={{ marginTop: 18 }}>
      <div className="card"><div className="label">{scope === 'personal' ? 'My accounts healthy' : 'Accounts healthy'}</div><div className="value">{stats.healthyAccounts}/{stats.totalAccounts}</div></div>
      <div className="card"><div className="label">Active now</div><div className="value" style={{ fontSize: 20 }}>{activeName}</div></div>
      <HeadroomCard label="Pool headroom" s={stats} />
      <ActiveSessionsCard s={stats} scope={scope} />
      <div className="card">
        <div className="label">Total cost</div>
        <div className="value">{fmtUsd(stats.totalCost)}</div>
        <div className="hint">{fmtTokens(stats.totalInputTokens)} in / {fmtTokens(stats.totalOutputTokens)} out</div>
        <div className="hint">{fmtTokens(stats.totalCacheReadTokens)} cache-r / {fmtTokens(stats.totalCacheWriteTokens)} cache-w</div>
      </div>
      <div className="card">
        <div className="label">Next reset</div>
        <div className="value" style={{ fontSize: 18 }}>5h: {fmtReset(stats.nextFiveHourReset)}</div>
        <div className="hint">weekly: {fmtReset(stats.nextWeeklyReset)}</div>
      </div>
      {shared && (
        <>
          <HeadroomCard label={<>Shared pool <span className="badge muted">global</span></>} s={shared} />
          <div className="card">
            <div className="label">Shared reset <span className="badge muted">global</span></div>
            <div className="value" style={{ fontSize: 18 }}>5h: {fmtReset(shared.nextFiveHourReset)}</div>
            <div className="hint">weekly: {fmtReset(shared.nextWeeklyReset)}</div>
          </div>
        </>
      )}
    </div>
  );
}

/* ---------------------------------------------------------------- rich table */

interface HoverState { a: AccountDto; x: number; y: number; }

/** Cursor-following breakdown for the "Total" tokens column (in/out/cache + cost + requests). */
function TokenTooltip({ h }: { h: HoverState }) {
  const { a } = h;
  const total = a.totalInputTokens + a.totalOutputTokens + a.totalCacheReadTokens + a.totalCacheWriteTokens;
  // clamp within the viewport so it never spills off the edges
  const left = Math.min(h.x + 16, (typeof window !== 'undefined' ? window.innerWidth : 1200) - 210);
  const above = typeof window !== 'undefined' && h.y > window.innerHeight - 200;
  const top = above ? h.y - 200 : h.y + 16;
  const row = (label: string, val: string, cls = '') => (
    <div className={`tp-row ${cls}`.trim()}><span>{label}</span><b>{val}</b></div>
  );
  return (
    <div className="tokpop" style={{ left, top }}>
      {row('Input', a.totalInputTokens.toLocaleString())}
      {row('Output', a.totalOutputTokens.toLocaleString())}
      {row('Cache read', a.totalCacheReadTokens.toLocaleString())}
      {row('Cache write', a.totalCacheWriteTokens.toLocaleString())}
      <div className="tp-div" />
      {row('Total tokens', total.toLocaleString())}
      {row('Requests', a.totalRequests.toLocaleString())}
      {row('Cost', fmtUsd(a.totalCost), 'cost')}
    </div>
  );
}

export function AccountsTable({ stats, groups, showGroup, canManage, onEdit, onToggle, onDelete, onRefreshOne }: {
  stats: PoolStats;
  groups: GroupDto[];
  showGroup: boolean;
  canManage: boolean;
  onEdit: (a: AccountDto) => void;
  onToggle: (a: AccountDto, v: boolean) => void;
  onDelete: (a: AccountDto) => void;
  onRefreshOne: (a: AccountDto) => void;
}) {
  const groupName = (id: number | null) => groups.find((g) => g.id === id)?.name;
  const [hover, setHover] = useState<HoverState | null>(null);
  const cols = 8 + (showGroup ? 1 : 0) + (canManage ? 1 : 0);
  return (
    <div className="tablewrap">
      <table>
        <thead>
          <tr>
            <th>Prio</th><th>Name</th>{showGroup && <th>Group</th>}<th>Type</th>
            <th>5-hour</th><th>Weekly</th><th>Coef</th>
            <th className="num">Total</th><th>Status</th>{canManage && <th></th>}
          </tr>
        </thead>
        <tbody>
          {stats.accounts.map((a) => {
            const total = a.totalInputTokens + a.totalOutputTokens + a.totalCacheReadTokens + a.totalCacheWriteTokens;
            return (
            <tr key={a.id}>
              <td className="num">{a.priority}</td>
              <td>{a.name} {a.id === stats.activeAccountId && <span className="badge active">active</span>}</td>
              {showGroup && <td>{a.groupId ? <span className="grouptag">{groupName(a.groupId) ?? `#${a.groupId}`}</span> : <span className="hint">—</span>}</td>}
              <td><span className="badge muted">{a.type.toLowerCase()}</span></td>
              <td><WindowCell w={a.fiveHour} isApi={a.type === 'API_KEY'} /></td>
              <td><WindowCell w={a.weekly} isApi={a.type === 'API_KEY'} overage={a.overage} /></td>
              <td className="num">×{a.coefficient}</td>
              <td className="num totalcell"
                onMouseEnter={(e) => setHover({ a, x: e.clientX, y: e.clientY })}
                onMouseMove={(e) => setHover({ a, x: e.clientX, y: e.clientY })}
                onMouseLeave={() => setHover((h) => (h?.a.id === a.id ? null : h))}>
                {fmtTokens(total)}
              </td>
              <td>
                <div className="row" style={{ gap: 4, flexWrap: 'wrap' }}>
                  {healthBadge(a)}{creditsBadge(a)}{graceBadge(a)}
                </div>
              </td>
              {canManage && (
                <td>
                  <div className="row" style={{ alignItems: 'center' }}>
                    <Switch checked={a.enabled} onChange={(v) => onToggle(a, v)} />
                    <button className="sm ghost" onClick={() => onEdit(a)}>Edit</button>
                    <button className="sm ghost" onClick={() => onRefreshOne(a)} title="Refresh limits">↻</button>
                    <button className="sm danger" onClick={() => onDelete(a)}>Delete</button>
                  </div>
                </td>
              )}
            </tr>
          ); })}
          {stats.accounts.length === 0 && <tr><td colSpan={cols} className="hint">No accounts yet.</td></tr>}
        </tbody>
      </table>
      {hover && <TokenTooltip h={hover} />}
    </div>
  );
}

/* ---------------------------------------------------------------- groups (global only) */

export function GroupSelect({ value, groups, onChange }: { value: number | null; groups: GroupDto[]; onChange: (v: number | null) => void }) {
  return (
    <select value={value ?? ''} onChange={(e) => onChange(e.target.value ? +e.target.value : null)}>
      <option value="">— ungrouped —</option>
      {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
    </select>
  );
}

/** One editable group row inside the modal: inline rename + delete. */
function GroupRow({ g, onChange, onAccountsChange }: { g: GroupDto; onChange: (g: GroupDto[]) => void; onAccountsChange: (s: PoolStats) => void }) {
  const [name, setName] = useState(g.name);
  const dirty = name.trim() !== g.name && name.trim() !== '';
  async function save() { if (dirty) onChange(await api.renameGroup(g.id, name.trim())); }
  async function del() {
    if (!confirm(`Delete group "${g.name}"? Accounts in it become ungrouped.`)) return;
    await api.deleteGroup(g.id); onChange(await api.groups()); onAccountsChange(await api.accounts());
  }
  return (
    <div className="grouprow">
      <input value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && save()} />
      <span className="gr-count">{g.accountCount} acct{g.accountCount === 1 ? '' : 's'}</span>
      {dirty && <button className="sm" onClick={save}>Save</button>}
      <button className="sm danger" onClick={del}>Delete</button>
    </div>
  );
}

/** Modal to manage account groups: list current (rename/delete) + create new. */
export function GroupsModal({ groups, onChange, onAccountsChange, onClose }: {
  groups: GroupDto[]; onChange: (g: GroupDto[]) => void; onAccountsChange: (s: PoolStats) => void; onClose: () => void;
}) {
  const [name, setName] = useState('');
  async function create() { if (!name.trim()) return; onChange(await api.createGroup(name.trim())); setName(''); }
  return (
    <Modal title="Account groups" onClose={onClose} footer={<button className="ghost" onClick={onClose}>Close</button>}>
      <p className="hint" style={{ marginTop: 0 }}>Group accounts to grant users access to a subset. Ungrouped accounts are usable by everyone.</p>
      <div style={{ marginBottom: 16 }}>
        {groups.length === 0 && <p className="hint">No groups yet.</p>}
        {groups.map((g) => <GroupRow key={g.id} g={g} onChange={onChange} onAccountsChange={onAccountsChange} />)}
      </div>
      <label className="field" style={{ marginBottom: 0 }}><span>New group</span>
        <div className="row">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="group name" onKeyDown={(e) => e.key === 'Enter' && create()} />
          <button onClick={create}>Add</button>
        </div>
      </label>
    </Modal>
  );
}

/* ---------------------------------------------------------------- edit modal */

export function AccountEditModal({ a, groups, scope, update, accountApi, onClose, onSaved }: {
  a: AccountDto; groups: GroupDto[]; scope: Scope; update: AccountApi['update']; accountApi?: AccountApi;
  onClose: () => void; onSaved: (s: PoolStats) => void;
}) {
  const [name, setName] = useState(a.name);
  const [prio, setPrio] = useState(a.priority);
  const [thrPct, setThrPct] = useState(Math.round(a.threshold * 100));
  const [coef, setCoef] = useState(a.coefficient);
  const [overThreshold, setOverThreshold] = useState(a.overThreshold);
  const [group, setGroup] = useState<number | null>(a.groupId);
  const [deviceId, setDeviceId] = useState(a.deviceId ?? '');
  const [accountUuid, setAccountUuid] = useState(a.accountUuid ?? '');
  const isOAuth = a.type !== 'API_KEY';
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const isGlobal = scope === 'global';
  const [reauthState, setReauthState] = useState<string | null>(null);
  const [reauthUrl, setReauthUrl] = useState<string | null>(null);
  const [reauthCode, setReauthCode] = useState('');

  async function startReauth() {
    if (!accountApi) return; setErr(null);
    try { const r = await accountApi.oauthStart(); setReauthState(r.state); setReauthUrl(r.authorizeUrl); window.open(r.authorizeUrl, '_blank'); }
    catch (e: any) { setErr(e.message); }
  }
  async function completeReauth() {
    if (!accountApi || !reauthState) return; setBusy(true); setErr(null);
    try { onSaved(await accountApi.oauthReauth(a.id, { state: reauthState, code: reauthCode })); }
    catch (e: any) { setErr(e.message); setBusy(false); }
  }

  async function save() {
    setBusy(true); setErr(null);
    try {
      const body: any = { name, priority: prio, threshold: thrPct / 100, coefficient: coef, overThreshold, deviceId: deviceId.trim() || undefined };
      if (isGlobal) { body.groupId = group; body.clearGroup = group == null; }
      // "" clears it; the next token refresh looks it up again from the profile when it can.
      if (isOAuth && accountUuid.trim() !== (a.accountUuid ?? '')) body.accountUuid = accountUuid.trim();
      onSaved(await update(a.id, body));
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }
  // 64-hex, shaped like a genuine Claude Code device id.
  function regen() {
    const b = new Uint8Array(32); crypto.getRandomValues(b);
    setDeviceId(Array.from(b, (x) => x.toString(16).padStart(2, '0')).join(''));
  }

  return (
    <Modal title={`Edit ${a.name}`} onClose={onClose}
      footer={<><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={save}>{busy ? '…' : 'Save'}</button></>}>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="grid2">
        <label className="field"><span>Priority (lower = used first)</span><NumberInput value={prio} onChange={(v) => setPrio(Math.trunc(v))} allowNegative /></label>
        {isGlobal && <label className="field"><span>Group</span><GroupSelect value={group} groups={groups} onChange={setGroup} /></label>}
        <label className="field"><span>Threshold (%)</span><NumberInput value={thrPct} onChange={setThrPct} min={0} max={100} /></label>
        <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><NumberInput value={coef} onChange={setCoef} min={0} step={0.5} /></label>
      </div>
      <label className="field switch-field">
        <span>Over-threshold fallback<br /><small className="hint">Keep using this account past its threshold when every account is saturated. Off by default.</small></span>
        <Switch checked={overThreshold} onChange={setOverThreshold} />
      </label>
      <label className="field"><span>Device ID (per-account fingerprint sent in request body)</span>
        <div className="row"><input className="mono" value={deviceId} onChange={(e) => setDeviceId(e.target.value)} placeholder="64 hex chars" /><button className="ghost sm" onClick={regen} type="button">Regenerate</button></div>
      </label>
      {isOAuth && (
        <label className="field"><span>Account UUID (Anthropic account sent in request metadata)<br /><small className="hint">Filled in at login or from the profile; set by hand for tokens without the profile scope.</small></span>
          <input className="mono" value={accountUuid} onChange={(e) => setAccountUuid(e.target.value)} placeholder="not known yet" />
        </label>
      )}
      {isOAuth && accountApi && (
        <div className="field"><span>Login<br /><small className="hint">Redo <b>Login with Claude</b> for this account. Replaces its credentials and keeps its settings and usage history{a.health !== 'OK' ? ' — use this to recover a failed login' : ''}.</small></span>
          {!reauthState
            ? <div className="row"><button className="ghost sm" type="button" onClick={startReauth}>Re-authorize</button></div>
            : <>
                <p className="hint">If the tab didn't open: <a href={reauthUrl!} target="_blank" rel="noreferrer">open authorize URL</a>. After approving, paste the returned code.</p>
                <div className="row">
                  <input value={reauthCode} onChange={(e) => setReauthCode(e.target.value)} placeholder="paste code (or code#state)" />
                  <button className="sm" type="button" disabled={busy || !reauthCode.trim()} onClick={completeReauth}>{busy ? '…' : 'Complete'}</button>
                </div>
              </>}
        </div>
      )}
      <p className="hint">Type <b>{a.type.toLowerCase()}</b> · created {new Date(a.createdAt).toLocaleString()}</p>
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

/* ---------------------------------------------------------------- add modal (unified) */

export function AddAccountModal({ scope, groups, accountApi, onClose, onDone }: {
  scope: Scope; groups: GroupDto[]; accountApi: AccountApi; onClose: () => void; onDone: (s: PoolStats) => void;
}) {
  const isGlobal = scope === 'global';
  const [method, setMethod] = useState<'cred' | 'oauth'>('cred');
  // common config
  const [name, setName] = useState('');
  const [groupId, setGroupId] = useState<number | null>(null);
  const [priority, setPriority] = useState(100);
  const [thresholdPct, setThresholdPct] = useState(90);
  const [coefficient, setCoefficient] = useState(1);
  // credential fields
  const [type, setType] = useState('API_KEY');
  const [apiKey, setApiKey] = useState('');
  const [accessToken, setAccessToken] = useState('');
  const [refreshToken, setRefreshToken] = useState('');
  // oauth flow
  const [oauthState, setOauthState] = useState<string | null>(null);
  const [oauthUrl, setOauthUrl] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const groupBody = () => (isGlobal ? { groupId } : {});

  async function submitCred() {
    setBusy(true); setErr(null);
    try {
      const body: any = { name, type, priority, threshold: thresholdPct / 100, coefficient, ...groupBody() };
      if (type === 'API_KEY') body.apiKey = apiKey;
      else { body.accessToken = accessToken; if (refreshToken) body.refreshToken = refreshToken; }
      onDone(await accountApi.create(body));
      onClose();
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }
  async function startOauth() {
    setErr(null);
    try { const r = await accountApi.oauthStart(); setOauthState(r.state); setOauthUrl(r.authorizeUrl); window.open(r.authorizeUrl, '_blank'); }
    catch (e: any) { setErr(e.message); }
  }
  async function completeOauth() {
    if (!oauthState) return; setBusy(true); setErr(null);
    try {
      onDone(await accountApi.oauthComplete({ state: oauthState, code, name, priority, threshold: thresholdPct / 100, coefficient, ...groupBody() }));
      onClose();
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }

  const footer = method === 'cred'
    ? <><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={submitCred}>{busy ? '…' : 'Add account'}</button></>
    : !oauthState
      ? <><button className="ghost" onClick={onClose}>Cancel</button><button onClick={startOauth}>Start OAuth login</button></>
      : <><button className="ghost" onClick={onClose}>Cancel</button><button disabled={busy} onClick={completeOauth}>{busy ? '…' : 'Complete & add'}</button></>;

  return (
    <Modal title={isGlobal ? 'Add account' : 'Add my account'} onClose={onClose} footer={footer} width={560}>
      <div style={{ marginBottom: 14 }}>
        <Segmented<'cred' | 'oauth'> value={method} onChange={(m) => { setMethod(m); setErr(null); }} options={[
          { value: 'cred', label: 'By credential' },
          { value: 'oauth', label: 'Login with Claude' },
        ]} />
      </div>

      {method === 'cred' ? (
        <>
          <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-account-1" /></label>
          <div className="grid2">
            <label className="field"><span>Type</span>
              <select value={type} onChange={(e) => setType(e.target.value)}>
                <option value="API_KEY">API key (x-api-key)</option>
                <option value="OAUTH">OAuth (access + refresh)</option>
                <option value="OAUTH_STATIC">OAuth (access only)</option>
              </select>
            </label>
            {isGlobal && <label className="field"><span>Group</span><GroupSelect value={groupId} groups={groups} onChange={setGroupId} /></label>}
            <label className="field"><span>Priority (lower = first)</span><NumberInput value={priority} onChange={(v) => setPriority(Math.trunc(v))} allowNegative /></label>
            <label className="field"><span>Threshold (%)</span><NumberInput value={thresholdPct} onChange={setThresholdPct} min={0} max={100} /></label>
            <label className="field"><span>Coefficient (×1 / ×5 / ×20)</span><NumberInput value={coefficient} onChange={setCoefficient} min={0} step={0.5} /></label>
          </div>
          {type === 'API_KEY'
            ? <label className="field"><span>API key</span><input value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-ant-api03-…" /></label>
            : <>
                <label className="field"><span>Access token</span><input value={accessToken} onChange={(e) => setAccessToken(e.target.value)} placeholder="sk-ant-oat01-…" /></label>
                {type === 'OAUTH' && <label className="field"><span>Refresh token</span><input value={refreshToken} onChange={(e) => setRefreshToken(e.target.value)} placeholder="sk-ant-ort01-…" /></label>}
              </>}
        </>
      ) : (
        <>
          {!oauthState ? (
            <p className="hint">Starts the same OAuth flow the Claude Code client uses. A tab opens; authorize, then paste the code back here.</p>
          ) : (
            <p className="hint">If the tab didn't open: <a href={oauthUrl!} target="_blank" rel="noreferrer">open authorize URL</a>. After approving, paste the returned code.</p>
          )}
          <label className="field"><span>Account name</span><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. max-oauth-1" /></label>
          <div className="grid2">
            {isGlobal && <label className="field"><span>Group</span><GroupSelect value={groupId} groups={groups} onChange={setGroupId} /></label>}
            <label className="field"><span>Priority</span><NumberInput value={priority} onChange={(v) => setPriority(Math.trunc(v))} allowNegative /></label>
            <label className="field"><span>Coefficient</span><NumberInput value={coefficient} onChange={setCoefficient} min={0} step={0.5} /></label>
          </div>
          {oauthState && <label className="field"><span>Authorization code</span><input value={code} onChange={(e) => setCode(e.target.value)} placeholder="paste code (or code#state)" /></label>}
        </>
      )}
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}
