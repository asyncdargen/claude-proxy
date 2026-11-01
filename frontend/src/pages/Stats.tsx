import { useEffect, useMemo, useState } from 'react';
import {
  api, DailyStats, fmtTokens, fmtUsd, has, ModelBreakdown, TokenKindSeries, TokenStats,
  UsageEvent, UsageSummary, UserDto, WindowDaily, WindowStats,
} from '../api';
import { LineChart, Series, SERIES_COLORS, StackedChart } from '../Chart';
import {
  CacheWriteCell, fmtEventTs, Legend, PremiumMarks, RangeControls, sumKinds, todayLocal, tokenSeries,
  TOKEN_KINDS, W5H, WindowBurnCharts, WWK,
} from './statsShared';
import { Segmented, Select, useChartMode } from '../ui';
import { SkeletonChartRow, SkeletonControls, SkeletonTable } from '../Skeleton';

export function Stats({ user }: { user: UserDto }) {
  const canStats = has(user, 'STATS_VIEW');
  const canRecent = has(user, 'STATS_VIEW_RECENT') || canStats;
  const canAccounts = has(user, 'STATS_VIEW_ACCOUNTS') || canStats;

  const [daily, setDaily] = useState<DailyStats | null>(null);
  const [windows, setWindows] = useState<WindowStats | null>(null);
  const [winDaily, setWinDaily] = useState<WindowDaily | null>(null);
  const [tokens, setTokens] = useState<TokenStats | null>(null);
  const [summary, setSummary] = useState<UsageSummary[]>([]);
  const [recent, setRecent] = useState<UsageEvent[] | null>(null);
  const [models, setModels] = useState<ModelBreakdown | null>(null);
  const [modelPeriod, setModelPeriod] = useState<'today' | 'all'>('all');
  const [endDate, setEndDate] = useState(todayLocal());
  const [days, setDays] = useState(7);
  const [mode, setMode] = useChartMode();
  const [err, setErr] = useState<string | null>(null);

  const [modelFilter, setModelFilter] = useState('');
  const [hiddenKinds, setHiddenKinds] = useState<Set<string>>(new Set());
  const [selAcct, setSelAcct] = useState<number | null>(null);

  async function load() {
    try {
      if (canStats) {
        const [d, w, wd, t, s, m] = await Promise.all([
          api.statsDaily(days, endDate), api.statsWindows(days, endDate),
          api.statsWindowDaily(days, endDate),
          api.statsTokens(days, endDate), api.statsSummary(), api.statsModels(),
        ]);
        setDaily(d); setWindows(w); setWinDaily(wd); setTokens(t); setSummary(s); setModels(m);
      }
      if (canRecent) setRecent(await api.statsRecent());
      setErr(null);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [endDate, days]);
  useEffect(() => { const t = setInterval(load, 10000); return () => clearInterval(t); /* eslint-disable-line */ }, [endDate, days]);

  // accounts present across any dataset, for the per-account picker
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

  if (err && !daily) return <div className="main-inner"><div className="err">{err}</div></div>;

  const dayLabels = daily?.days ?? [];
  const zerosD = dayLabels.map(() => 0);
  const emptyKinds: TokenKindSeries = { input: zerosD, output: zerosD, cacheRead: zerosD, cacheWrite: zerosD };

  // --- top row aggregates ---
  const spendByAccount = canAccounts && !!daily?.perAccount.length;
  const spendSeries: Series[] = daily
    ? (spendByAccount
        ? daily.perAccount.map((a, i) => ({ name: a.accountName ?? `#${a.accountId}`, color: SERIES_COLORS[i % SERIES_COLORS.length], values: a.cost }))
        : [{ name: 'Total', color: SERIES_COLORS[0], values: daily.totalCost }])
    : [];
  const spendLegend = spendByAccount
    ? daily!.perAccount.map((a, i) => ({ key: String(a.accountId), label: a.accountName ?? `#${a.accountId}`, color: SERIES_COLORS[i % SERIES_COLORS.length] }))
    : [];
  const weekTotal = daily ? daily.totalCost.reduce((s, v) => s + v, 0) : 0;

  const tokenTopSrc: TokenKindSeries = tokens
    ? (modelFilter === '' ? tokens.total : (tokens.perModel.find((m) => m.model === modelFilter) ?? tokens.total))
    : emptyKinds;
  const tokenTotal = sumKinds(tokenTopSrc);
  const modelOptions = [{ value: '', label: 'All models' }, ...(tokens?.models ?? []).map((m) => ({ value: m, label: m }))];

  function toggleKind(k: string) {
    setHiddenKinds((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  }

  // --- per-account selection ---
  const acct = accountList.find((a) => a.id === selAcct) ?? null;
  const acctCost = daily?.perAccount.find((a) => a.accountId === selAcct)?.cost ?? zerosD;
  const acctTok = tokens?.perAccount.find((a) => a.accountId === selAcct) ?? emptyKinds;
  const wBuckets = windows?.buckets ?? [];
  const wNulls = wBuckets.map(() => null as number | null);
  const acctWin = windows?.perAccount.find((a) => a.accountId === selAcct);

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>Statistics</h1><p className="sub" style={{ margin: 0 }}>Usage across the account pool.</p></div>
        {has(user, 'ADMIN') && <button className="ghost" onClick={async () => { if (confirm('Reset usage statistics for ALL users?')) { await api.resetAllStats(); load(); } }}>Reset all stats</button>}
      </div>

      {/* first load only — the 10s poll must never flip live numbers back to placeholders */}
      {canStats && !daily && (
        <>
          <SkeletonControls />
          <SkeletonChartRow />
          <SkeletonChartRow n={2} />
          <SkeletonTable rows={4} cols={4} />
        </>
      )}

      {canStats && daily && (
        <>
          {/* control bar — drives every chart at once */}
          <RangeControls days={days} onDays={setDays} endDate={endDate} onEndDate={setEndDate} mode={mode} onMode={setMode} />

          {/* row 1 — three chart types side by side */}
          <div className="chart-row">
            <div className="panel">
              <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(weekTotal)}</span></div>
              <StackedChart days={daily.days} series={spendSeries} height={190} fmt={fmtUsd} mode={mode} />
              {spendLegend.length > 1 && <Legend items={spendLegend} />}
            </div>

            <div className="panel">
              <div className="chart-card-head">
                <span className="t">Tokens per day</span>
                <Select ariaLabel="Filter by model" value={modelFilter} onChange={setModelFilter} options={modelOptions} minWidth={120} />
              </div>
              <StackedChart days={daily.days} series={tokenSeries(tokenTopSrc, hiddenKinds)} height={190} fmt={fmtTokens} mode={mode} />
              <Legend items={TOKEN_KINDS.map((k) => ({ key: k.key, label: k.label, color: k.color }))} hidden={hiddenKinds} onToggle={toggleKind} />
              <div className="hint" style={{ marginTop: 6 }}>Total tokens: <b style={{ color: 'var(--text)' }}>{fmtTokens(tokenTotal)}</b></div>
            </div>

            {windows && (
              <div className="panel">
                <div className="chart-card-head">
                  <span className="t">Window utilization</span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    {days > 30 && <span className="hint">30d max</span>}
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

          {/* how much of each limit window was actually spent per day (resets included) */}
          {winDaily && (
            <>
              <h2>Window spend per day</h2>
              <WindowBurnCharts data={winDaily} mode={mode}
                hint="Share of a limit window consumed per day, stacked per account. 100% = one full window; the 5-hour bar passes 100% on days the window reset and was spent again." />
            </>
          )}

          {/* per-account — pick one account, see its three charts */}
          {canAccounts && accountList.length > 0 && (
            <>
              <div className="section-head">
                <h2 style={{ margin: 0 }}>Per account</h2>
                <Select ariaLabel="Select account" value={String(selAcct ?? '')} minWidth={160}
                  onChange={(v) => setSelAcct(Number(v))}
                  options={accountList.map((a) => ({ value: String(a.id), label: a.name }))} />
              </div>
              <div className="chart-row">
                <div className="panel">
                  <div className="chart-card-head"><span className="t">Spend per day</span><span className="v">{fmtUsd(acctCost.reduce((s, v) => s + v, 0))}</span></div>
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

          {/* pool-wide per-model breakdown with a today / all-time toggle */}
          {(() => {
            const rows = models ? (modelPeriod === 'today' ? models.today : models.allTime) : [];
            return (
              <>
                <div className="head-row">
                  <h2>By model</h2>
                  <Segmented<'today' | 'all'> value={modelPeriod} onChange={setModelPeriod} options={[
                    { value: 'today', label: 'Today' }, { value: 'all', label: 'All time' },
                  ]} />
                </div>
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
                      {rows.length === 0 && <tr><td colSpan={4} className="empty">No usage {modelPeriod === 'today' ? 'today' : 'yet'}.</td></tr>}
                    </tbody>
                  </table>
                </div>
              </>
            );
          })()}

          <h2>Per account (24h)</h2>
          <div className="tablewrap">
            <table>
              <thead><tr>{canAccounts && <th>Account</th>}<th className="num">Requests</th><th className="num">Input</th><th className="num">Output</th><th className="num">Cost</th></tr></thead>
              <tbody>
                {summary.map((s) => (
                  <tr key={s.accountId}>
                    {canAccounts && <td>{s.accountName ?? `#${s.accountId}`}</td>}
                    <td className="num">{s.requests}</td>
                    <td className="num">{s.inputTokens.toLocaleString()}</td>
                    <td className="num">{s.outputTokens.toLocaleString()}</td>
                    <td className="num">{fmtUsd(s.cost)}</td>
                  </tr>
                ))}
                {summary.length === 0 && <tr><td colSpan={canAccounts ? 5 : 4} className="empty">No usage yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}

      {canRecent && !recent && (<><h2>Recent requests</h2><SkeletonTable rows={6} cols={6} /></>)}

      {canRecent && recent && (
        <>
          <h2>Recent requests</h2>
          <div className="tablewrap">
            <table>
              <thead><tr><th>Time</th>{canAccounts && <th>Account</th>}<th>Model</th><th className="num">In</th><th className="num">Out</th><th className="num">Cache R</th><th className="num">Cache W</th><th className="num">Cost</th><th>Status</th></tr></thead>
              <tbody>
                {recent.map((e) => (
                  <tr key={e.id}>
                    <td className="hint">{fmtEventTs(e.ts)}</td>
                    {canAccounts && <td>{e.accountName ?? '—'}</td>}
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
                    <td><span className={`badge ${e.httpStatus >= 200 && e.httpStatus < 300 ? 'ok' : e.httpStatus === 429 ? 'warn' : 'bad'}`}>{e.httpStatus}</span></td>
                  </tr>
                ))}
                {recent.length === 0 && <tr><td colSpan={canAccounts ? 9 : 8} className="empty">No requests yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
