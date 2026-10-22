import { useEffect, useState } from 'react';
import { AccountDto, api, GroupDto, has, PoolStats, UserDto } from '../api';
import { AccountEditModal, AccountsTable, AddAccountModal, globalAccountApi, GroupsModal, PoolCards } from '../accounts';
import { SkeletonCards, SkeletonTable } from '../Skeleton';
import { WindowAttributionBlock } from './WindowAttribution';

export function Dashboard({ user }: { user: UserDto }) {
  const [stats, setStats] = useState<PoolStats | null>(null);
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [editing, setEditing] = useState<AccountDto | null>(null);
  const [adding, setAdding] = useState(false);
  const [groupsOpen, setGroupsOpen] = useState(false);
  const canViewAccounts = has(user, 'ACCOUNTS_VIEW');
  const canManage = canViewAccounts && has(user, 'ACCOUNTS_MANAGE');

  async function load() {
    try {
      setStats(await api.accounts());
      setGroups(await api.groups().catch(() => []));
      setErr(null);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => {
    if (!canViewAccounts) return;
    load(); const t = setInterval(load, 5000); return () => clearInterval(t);
  }, [canViewAccounts]);

  async function refreshAll() {
    setRefreshing(true);
    try { setStats(await api.refreshAll()); } catch (e: any) { setErr(e.message); } finally { setRefreshing(false); }
  }
  async function toggle(a: AccountDto, v: boolean) { setStats(await api.updateAccount(a.id, { enabled: v })); }
  async function del(a: AccountDto) { if (confirm(`Delete account "${a.name}"?`)) { await api.deleteAccount(a.id); setStats(await api.accounts()); } }
  async function refreshOne(a: AccountDto) { setStats(await api.refreshOne(a.id)); }

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div>
          <h1>Dashboard</h1>
          <p className="sub" style={{ margin: 0 }}>Live account pool.</p>
        </div>
        <div className="row">
          {canManage && <button onClick={() => setAdding(true)}>+ Add account</button>}
          {canManage && <button className="ghost" onClick={() => setGroupsOpen(true)}>Edit groups</button>}
          {canViewAccounts && <button className="ghost" disabled={refreshing} onClick={refreshAll}>{refreshing ? 'Refreshing…' : '↻ Refresh limits'}</button>}
        </div>
      </div>

      {canViewAccounts && err && <div className="err" role="alert">Account pool: {err}</div>}
      {canViewAccounts && (!stats ? (!err && <SkeletonCards n={4} />) : <PoolCards stats={stats} scope="global" />)}
      {has(user, 'STATS_VIEW_WINDOW_ATTRIBUTION') && <WindowAttributionBlock user={user} />}
      {canViewAccounts && (!stats ? (!err && <><h2>Accounts by priority</h2><SkeletonTable rows={5} cols={6} /></>) : (
        <>
          <h2>Accounts by priority</h2>
          <AccountsTable stats={stats} groups={groups} showGroup canManage={canManage}
            onEdit={setEditing} onToggle={toggle} onDelete={del} onRefreshOne={refreshOne} />
        </>
      ))}

      {canManage && editing && (
        <AccountEditModal a={editing} groups={groups} scope="global" update={api.updateAccount}
          onClose={() => setEditing(null)} onSaved={(s) => { setStats(s); setEditing(null); }} />
      )}
      {canManage && adding && (
        <AddAccountModal scope="global" groups={groups} accountApi={globalAccountApi}
          onClose={() => setAdding(false)} onDone={setStats} />
      )}
      {canManage && groupsOpen && (
        <GroupsModal groups={groups} onChange={setGroups} onAccountsChange={setStats} onClose={() => setGroupsOpen(false)} />
      )}
    </div>
  );
}
