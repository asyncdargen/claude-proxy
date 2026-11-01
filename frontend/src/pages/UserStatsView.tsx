// The full per-user statistics view: headline cards, daily/token/window charts, per-inbound-token
// breakdowns for both datapaths, per-model table and recent requests — all filterable by source
// (proxy | routing | both). Rendered as "My Stats" (userId=null) and as the admin per-user view.
import { useEffect, useMemo, useState } from 'react';
import {
  api, DailyStats, fmtTokens, fmtUntilUtcMidnight, fmtUsd, McpUsage, MyStats as MyStatsDto,
  StatsSource, TokenKindSeries, TokenStats, TokenUsage, WindowDaily, WindowStats,
} from '../api';
import { LineChart, SERIES_COLORS, StackedChart } from '../Chart';
import {
  CacheWriteCell, fmtEventTs, Legend, PremiumMarks, RangeControls, sumKinds, todayLocal, tokenSeries,
  TOKEN_KINDS, W5H, WindowBurnCharts, WWK,
} from './statsShared';
import { Segmented, Select, useChartMode } from '../ui';
import { TokenUsageChartsRow, usageItems } from './tokenUsage';
import { ChartMode } from '../Chart';
import { SkeletonChartRow, SkeletonControls, SkeletonStatsPage } from '../Skeleton';

type SourceSel = 'all' | 'proxy' | 'routing' | 'chat';
const SOURCE_OPTIONS: { value: SourceSel; label: string }[] = [
  { value: 'all', label: 'All sources' }, { value: 'proxy', label: 'Proxy' },
  { value: 'routing', label: 'Routing' }, { value: 'chat', label: 'Chat' },
];

export function sourceBadge(source: string) {
  const tone = source === 'routing' ? 'accent' : source === 'chat' ? 'active' : 'muted';
  return <span className={`badge ${tone}`}>{source}</span>;
}

export function UserStatsView({ userId, canReset, onResetDone }: {
  userId: number | null;               // null = the caller's own stats
  canReset: boolean;
  onResetDone?: () => void;
}) {
  const [s, setS] = useState<MyStatsDto | null>(null);
  const [period, setPeriod] = useState<'today' | 'all'>('all');
  const [err, setErr] = useState<string | null>(null);
  const [sourceSel, setSourceSel] = useState<SourceSel>('all');
  const source: StatsSource = sourceSel === 'all' ? undefined : sourceSel;

  // time-series charts (the user's usage + their personal accounts)
  const [daily, setDaily] = useState<DailyStats | null>(null);
  const [tokens, setTokens] = useState<TokenStats | null>(null);
  const [windows, setWindows] = useState<WindowStats | null>(null);
  const [winDaily, setWinDaily] = useState<WindowDaily | null>(null);
  const [days, setDays] = useState(7);
  const [endDate, setEndDate] = useState(todayLocal());
  const [mode, setMode] = useChartMode();
  const [modelFilter, setModelFilter] = useState('');
  const [hiddenKinds, setHiddenKinds] = useState<Set<string>>(new Set());
  const [selAcct, setSelAcct] = useState<number | null>(null);

  async function load() {
    try { setS(await api.userStats(userId, source)); } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); const t = setInterval(load, 8000); return () => clearInterval(t); /* eslint-disable-line */ }, [userId, sourceSel]);

  async function loadCharts() {
    try {
      const [d, t, w, wd] = await Promise.all([
        api.userStatsDaily(userId, days, endDate, source),
        api.userStatsTokens(userId, days, endDate, source),
        api.userStatsWindows(userId, days, endDate),
        api.userStatsWindowDaily(userId, days, endDate),
      ]);
      setDaily(d); setTokens(t); setWindows(w); setWinDaily(wd);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { loadCharts(); /* eslint-disable-next-line */ }, [userId, days, endDate, sourceSel]);
  useEffect(() => { const t = setInterval(loadCharts, 10000); return () => clearInterval(t); /* eslint-disable-line */ }, [userId, days, endDate, sourceSel]);

  // personal accounts seen across any dataset — drives the per-account picker + window section
  const accountList = useMemo(() => {
    const m = new Map<number, string | null>();
    daily?.perAccount.forEach((a) => m.set(a.accountId, a.accountName));
    tokens?.perAccount.forEach((a) => m.set(a.accountId, a.accountName));
    windows?.perAccount.forEach((a) => m.set(a.accountId, a.accountName));
    return [...m.entries()].map(([id, name]) => ({ id, name: name ?? `#${id}` }));
  }, [daily, tokens, windows]);
  const accountKey = accountList.map((a) => a.id).join(',');
  useEffect(() => {
    if (!accountList.length) { setSelAcct(null); return; }
    setSelAcct((prev) => (prev != null && accountList.some((a) => a.id === prev)) ? prev : accountList[0].id);
    /* eslint-disable-next-line */
  }, [accountKey]);

  if (err) return <div className="err">{err}</div>;
  // first load only — the polls below refresh in place rather than falling back to placeholders
  if (!s) return <SkeletonStatsPage />;

  function statusBadge(st: number) {
    const cls = st >= 200 && st < 300 ? 'ok' : st === 429 ? 'warn' : 'bad';
    return <span className={`badge ${cls}`}>{st}</span>;
  }

  async function reset() {
    if (!confirm(userId == null
      ? 'Reset your own statistics? This cannot be undone.'
      : 'Reset this user\'s statistics? This cannot be undone.')) return;
    if (userId == null) await api.resetMyStats(); else await api.resetUserStats(userId);
    load(); loadCharts(); onResetDone?.();
  }

  const zerosD = (daily?.days ?? []).map(() => 0);
  const emptyKinds: TokenKindSeries = { input: zerosD, output: zerosD, cacheRead: zerosD, cacheWrite: zerosD };

  const spendTotal = daily ? daily.totalCost.reduce((a, b) => a + b, 0) : 0;
  const tokenTopSrc: TokenKindSeries = tokens
    ? (modelFilter === '' ? tokens.total : (tokens.perModel.find((m) => m.model === modelFilter) ?? tokens.total))
    : emptyKinds;
  const tokenTotal = sumKinds(tokenTopSrc);
  const modelOptions = [{ value: '', label: 'All models' }, ...(tokens?.models ?? []).map((m) => ({ value: m, label: m }))];
  function toggleKind(k: string) {
    setHiddenKinds((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  }

  // per-account selection (personal accounts only)
  const acct = accountList.find((a) => a.id === selAcct) ?? null;
  const acctCost = daily?.perAccount.find((a) => a.accountId === selAcct)?.cost ?? zerosD;
  const acctTok = tokens?.perAccount.find((a) => a.accountId === selAcct) ?? emptyKinds;
  const wBuckets = windows?.buckets ?? [];
  const wNulls = wBuckets.map(() => null as number | null);
  const acctWin = windows?.perAccount.find((a) => a.accountId === selAcct);
  const hasAccounts = accountList.length > 0;
  const srcHint = sourceSel === 'all' ? '' : ` · ${sourceSel}`;

  return (
    <>
      <div className="section-head" style={{ marginTop: 0 }}>
        <Segmented<SourceSel> value={sourceSel} onChange={setSourceSel} options={SOURCE_OPTIONS} />
        {canReset && <button className="ghost" onClick={reset}>{userId == null ? 'Reset my stats' : 'Reset stats'}</button>}
      </div>

      <div className="cards" style={{ marginTop: 18 }}>
        <div className="card"><div className="label">Spent today{srcHint}</div><div className="value">{fmtUsd(s.todayCost)}</div><div className="hint">{fmtUsd(s.totalCost)} all-time</div></div>
        <div className="card" style={{ minWidth: 220 }}>
          <div className="label">Daily limits</div>
          <LimitRow label="proxy" used={s.proxyTodayCost} limit={s.dailyCostLimit} />
          <LimitRow label="routing" used={s.routingTodayCost} limit={s.dailyRoutingCostLimit} />
          <LimitRow label="chat" used={s.chatTodayCost} limit={s.dailyChatCostLimit} />
          {/* the limit is enforced on UTC days, so spell out when it actually rolls over —
              the charts above are on local days and the two boundaries rarely coincide */}
          <div className="hint" style={{ marginTop: 8 }}>resets in {fmtUntilUtcMidnight()} · 00:00 UTC</div>
        </div>
        <div className="card"><div className="label">Requests today{srcHint}</div><div className="value">{s.todayRequests.toLocaleString()}</div><div className="hint">{s.totalRequests.toLocaleString()} all-time</div></div>
        {/* in-flight right now, not a daily total — the source filter doesn't apply, both
            datapaths are always broken out and summed */}
        <div className="card">
          <div className="label">Active sessions</div>
          <div className="value">{s.activeProxySessions + s.activeRoutingSessions}</div>
          <div className="hint">{s.activeProxySessions} proxy · {s.activeRoutingSessions} routing</div>
          <div className="hint">{s.activeProxySessions + s.activeRoutingSessions === 0 ? 'nothing streaming' : 'streaming from Claude now'}</div>
        </div>
        <div className="card"><div className="label">Tokens today{srcHint}</div><div className="value" style={{ fontSize: 22 }}>{fmtTokens(s.todayClean)}</div><div className="hint">{fmtTokens(s.totalClean)} all-time</div></div>
      </div>

      {!daily && (<><SkeletonControls /><SkeletonChartRow /></>)}

      {daily && (
        <>
          {/* control bar — drives every chart at once */}
          <RangeControls days={days} onDays={setDays} endDate={endDate} onEndDate={setEndDate} mode={mode} onMode={setMode} />

          {/* row 1 — usage (Spend + Tokens); window is per personal account */}
          <div className="chart-row">
            <div className="panel">
              <div className="chart-card-head"><span className="t">Spend per day{srcHint}</span><span className="v">{fmtUsd(spendTotal)}</span></div>
              <StackedChart days={daily.days} height={190} fmt={fmtUsd} mode={mode}
                series={[{ name: 'Total', color: SERIES_COLORS[0], values: daily.totalCost }]} />
            </div>

            <div className="panel">
              <div className="chart-card-head">
                <span className="t">Tokens per day{srcHint}</span>
                <Select ariaLabel="Filter by model" value={modelFilter} onChange={setModelFilter} options={modelOptions} minWidth={120} />
              </div>
              <StackedChart days={daily.days} series={tokenSeries(tokenTopSrc, hiddenKinds)} height={190} fmt={fmtTokens} mode={mode} />
              <Legend items={TOKEN_KINDS.map((k) => ({ key: k.key, label: k.label, color: k.color }))} hidden={hiddenKinds} onToggle={toggleKind} />
              <div className="hint" style={{ marginTop: 6 }}>Total tokens: <b style={{ color: 'var(--text)' }}>{fmtTokens(tokenTotal)}</b></div>
            </div>

            {hasAccounts && windows && (
              <div className="panel">
                <div className="chart-card-head">
                  <span className="t">Window utilization</span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span className="hint">personal accounts{days > 30 ? ' · 30d max' : ''}</span>
                  </span>
                </div>
                <LineChart labels={windows.buckets} height={190} lines={[
                  { name: '5-hour', color: W5H, values: windows.totalFiveHour },
                  { name: 'weekly', color: WWK, values: windows.totalWeekly },
                ]} />
                <Legend items={[{ key: '5h', label: '5-hour', color: W5H }, { key: 'wk', label: 'Weekly', color: WWK }]} />
              </div>
            )}
          </div>

          {/* how much of each limit window the personal accounts burned per day (resets included) */}
          {hasAccounts && winDaily && (
            <>
              <h2>Window spend per day</h2>
              <WindowBurnCharts data={winDaily} mode={mode}
                hint="Personal accounts. Share of a limit window consumed per day; 100% = one full window, and the 5-hour bar passes 100% on days the window reset and was spent again." />
            </>
          )}

          {/* per-inbound-token breakdowns — one block per datapath, following the source filter */}
          {(sourceSel === 'all' || sourceSel === 'proxy') && (
            <PerTokenBlock userId={userId} source="proxy" title="By proxy token" days={days} endDate={endDate} mode={mode} />
          )}
          {(sourceSel === 'all' || sourceSel === 'routing') && (
            <PerTokenBlock userId={userId} source="routing" title="By routing token" days={days} endDate={endDate} mode={mode} />
          )}

          {/* MCP tool calls — Claude Code datapath only, so hidden under the other filters */}
          {(sourceSel === 'all' || sourceSel === 'proxy') && (
            <McpBlock userId={userId} days={days} endDate={endDate} mode={mode} />
          )}

          {/* per-account — pick one personal account, see its three charts */}
          {hasAccounts && (
            <>
              <div className="section-head">
                <h2 style={{ margin: 0 }}>Per account</h2>
                <Select ariaLabel="Select account" value={String(selAcct ?? '')} minWidth={160}
                  onChange={(v) => setSelAcct(Number(v))}
                  options={accountList.map((a) => ({ value: String(a.id), label: a.name }))} />
              </div>
              <div className="chart-row">
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(acctCost.reduce((a, v) => a + v, 0))}</span></div>
                  <StackedChart days={daily.days} height={175} fmt={fmtUsd} mode={mode}
                    series={[{ name: acct?.name ?? '', color: SERIES_COLORS[0], values: acctCost }]} />
                </div>
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Tokens per day</span><span className="v">{fmtTokens(sumKinds(acctTok))}</span></div>
                  <StackedChart days={daily.days} height={175} fmt={fmtTokens} mode={mode}
                    series={tokenSeries(acctTok, hiddenKinds)} />
                  <Legend items={TOKEN_KINDS.map((k) => ({ key: k.key, label: k.label, color: k.color }))} hidden={hiddenKinds} onToggle={toggleKind} />
                </div>
                <div className="panel">
                  <div className="chart-card-head">
                    <span className="t">Window utilization</span>
                    <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {days > 30 && <span className="hint">30d max</span>}
                    </span>
                  </div>
                  <LineChart labels={wBuckets} height={175} lines={[
                    { name: '5-hour', color: W5H, values: acctWin?.fiveHour ?? wNulls },
                    { name: 'weekly', color: WWK, values: acctWin?.weekly ?? wNulls },
                  ]} />
                  <Legend items={[{ key: '5h', label: '5-hour', color: W5H }, { key: 'wk', label: 'Weekly', color: WWK }]} />
                </div>
              </div>
            </>
          )}
        </>
      )}

      <div className="head-row">
        <h2>By model{srcHint}</h2>
        <Segmented<'today' | 'all'> value={period} onChange={setPeriod} options={[
          { value: 'today', label: 'Today' }, { value: 'all', label: 'All time' },
        ]} />
      </div>
      {(() => {
        const rows = period === 'today' ? s.perModelToday : s.perModel;
        return (
          <div className="tablewrap">
            <table>
              <thead><tr><th>Model</th><th className="num">Requests</th><th className="num">Tokens</th><th className="num">Cost</th></tr></thead>
              <tbody>
                {rows.map((m, i) => (
                  <tr key={i}>
                    <td className="mono">{m.model ?? '—'}</td>
                    <td className="num">{m.requests.toLocaleString()}</td>
                    <td className="num">{m.cleanTokens.toLocaleString()}</td>
                    <td className="num">{fmtUsd(m.cost)}</td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={4} className="hint">No usage {period === 'today' ? 'today' : 'yet'}.</td></tr>}
              </tbody>
            </table>
          </div>
        );
      })()}

      <h2>Recent requests{srcHint}</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Time</th><th>Source</th><th>Account</th><th>Model</th><th>In</th><th>Out</th><th>Cache R</th><th>Cache W</th><th>Cost</th><th>Status</th></tr></thead>
          <tbody>
            {s.recent.map((e) => (
              <tr key={e.id}>
                <td className="hint">{fmtEventTs(e.ts)}</td>
                <td>{sourceBadge(e.source)}</td>
                <td>{e.accountName ?? '—'}</td>
                <td className="hint">
                  <span className="row" style={{ gap: 4, alignItems: 'center', flexWrap: 'wrap' }}>
                    {e.model ?? '—'}<PremiumMarks e={e} />
                  </span>
                </td>
                <td className="num">{e.inputTokens}</td>
                <td className="num">{e.outputTokens}</td>
                <td className="num">{e.cacheReadTokens}</td>
                <td className="num"><CacheWriteCell e={e} /></td>
                <td className="num">{fmtUsd(e.cost)}</td>
                <td>{statusBadge(e.httpStatus)}</td>
              </tr>
            ))}
            {s.recent.length === 0 && <tr><td colSpan={10} className="hint">No requests yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}

/**
 * MCP tool-call accounting (Claude Code datapath): top-tools table + daily stacked chart.
 * Renders nothing until the user has MCP calls in the selected range.
 */
function McpBlock({ userId, days, endDate, mode }: {
  userId: number | null; days: number; endDate: string; mode: ChartMode;
}) {
  const [data, setData] = useState<McpUsage | null>(null);
  useEffect(() => {
    let gone = false;
    const load = () => api.userMcpUsage(userId, days, endDate).then((d) => { if (!gone) setData(d); }).catch(() => {});
    load();
    const t = setInterval(load, 10000);
    return () => { gone = true; clearInterval(t); };
  }, [userId, days, endDate]);

  if (!data || data.tools.length === 0) return null;
  const total = data.tools.reduce((a, t) => a + t.totalCalls, 0);
  const totalPerDay = data.days.map((_, i) => data.tools.reduce((a, t) => a + t.calls[i], 0));
  const maxCalls = data.tools[0]?.totalCalls ?? 0; // sorted desc server-side
  const servers = new Set(data.tools.map((t) => splitMcpName(t.name)[0])).size;

  // Per-tool chart: top tools as their own series, everything else folded into "other".
  const TOP = 6;
  const top = data.tools.slice(0, TOP);
  const rest = data.tools.slice(TOP);
  const series = top.map((t, i) => ({
    name: splitMcpName(t.name)[1], color: SERIES_COLORS[i % SERIES_COLORS.length], values: t.calls,
  }));
  if (rest.length > 0) {
    const other = data.days.map((_, i) => rest.reduce((a, t) => a + t.calls[i], 0));
    series.push({ name: `other (${rest.length})`, color: SERIES_COLORS[TOP % SERIES_COLORS.length], values: other });
  }
  const fmtCalls = (n: number) => n.toLocaleString();

  return (
    <>
      <h2>MCP tools</h2>
      <div className="chart-row">
        <div className="panel">
          <div className="chart-card-head">
            <span className="t">Total MCP calls</span>
            <span className="v">{fmtCalls(total)}</span>
          </div>
          <StackedChart days={data.days} height={190} fmt={fmtCalls} mode={mode}
            series={[{ name: 'MCP calls', color: SERIES_COLORS[0], values: totalPerDay }]} />
          <div className="hint" style={{ marginTop: 6 }}>
            {data.tools.length} tool{data.tools.length === 1 ? '' : 's'} across {servers} server{servers === 1 ? '' : 's'} in this range
          </div>
        </div>
        <div className="panel">
          <div className="chart-card-head"><span className="t">Calls per day by tool</span></div>
          <StackedChart days={data.days} series={series} height={190} fmt={fmtCalls} mode={mode} />
          <Legend items={series.map((s) => ({ key: s.name, label: s.name, color: s.color }))} />
        </div>
      </div>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Server</th><th>Tool</th><th className="num">Calls</th><th style={{ width: '30%' }}>Share</th></tr></thead>
          <tbody>
            {data.tools.map((t, i) => {
              const [server, tool] = splitMcpName(t.name);
              const share = total > 0 ? (t.totalCalls / total) * 100 : 0;
              return (
                <tr key={t.name}>
                  <td>
                    <span className="lg">
                      <span className="sw" style={{ background: i < TOP ? SERIES_COLORS[i % SERIES_COLORS.length] : SERIES_COLORS[TOP % SERIES_COLORS.length] }} />
                      <span className="badge muted">{server || 'mcp'}</span>
                    </span>
                  </td>
                  <td className="mono" title={t.name}>{tool}</td>
                  <td className="num">{fmtCalls(t.totalCalls)}</td>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <div className="bar" style={{ flex: 1 }}>
                        <span style={{ width: `${maxCalls > 0 ? Math.max(2, Math.round((t.totalCalls / maxCalls) * 100)) : 0}%` }} />
                      </div>
                      <span className="hint" style={{ minWidth: 38, textAlign: 'right' }}>{share < 1 ? '<1' : Math.round(share)}%</span>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

/** "mcp__server__tool" → [server, tool] (tool names may themselves contain "__"). */
function splitMcpName(name: string): [string, string] {
  const m = /^mcp__(.+?)__(.+)$/.exec(name);
  return m ? [m[1], m[2]] : ['', name];
}

/** One datapath's per-inbound-token breakdown: totals table + stacked per-token charts. */
function PerTokenBlock({ userId, source, title, days, endDate, mode }: {
  userId: number | null; source: 'proxy' | 'routing'; title: string;
  days: number; endDate: string; mode: ChartMode;
}) {
  const [data, setData] = useState<TokenUsage | null>(null);
  useEffect(() => {
    let gone = false;
    api.userTokenUsage(userId, source, days, endDate).then((d) => { if (!gone) setData(d); }).catch(() => {});
    return () => { gone = true; };
  }, [userId, source, days, endDate]);

  const items = useMemo(() => usageItems(data), [data]);
  if (!data || items.length === 0) return null;

  return (
    <>
      <h2>{title}</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Token</th><th className="num">Requests</th><th className="num">Tokens</th><th className="num">Cost</th></tr></thead>
          <tbody>
            {items.map((it) => (
              <tr key={it.key}>
                <td><span className="lg"><span className="sw" style={{ background: it.color }} />{it.label}</span></td>
                <td className="num">{it.s.totalRequests.toLocaleString()}</td>
                <td className="num">{fmtTokens(it.s.totalTokens)}</td>
                <td className="num">{fmtUsd(it.s.totalCost)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <TokenUsageChartsRow data={data} mode={mode} />
    </>
  );
}

/** Compact "used / limit" gauge line for the Daily limits card. */
function LimitRow({ label, used, limit }: { label: string; used: number; limit: number | null }) {
  return (
    <div style={{ marginTop: 8 }}>
      <div className="hint" style={{ display: 'flex', justifyContent: 'space-between', gap: 10 }}>
        <span>{label}</span>
        <span><b style={{ color: 'var(--text)' }}>{fmtUsd(used)}</b>{limit != null ? ` / ${fmtUsd(limit)}` : ' · unlimited'}</span>
      </div>
      {limit != null && (
        <div className="bar" style={{ marginTop: 4 }}>
          <span style={{ width: `${Math.min(100, Math.round((used / limit) * 100))}%` }} />
        </div>
      )}
    </div>
  );
}
