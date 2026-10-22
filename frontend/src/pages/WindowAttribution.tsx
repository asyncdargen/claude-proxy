import { useEffect, useRef, useState } from 'react';
import { SERIES_COLORS } from '../Chart';
import { api, AttributionPeriod, UserDto, WindowAttribution } from '../api';
import './window-attribution.css';

function percent(value: number): string {
  return `${Number((value * 100).toFixed(6))}%`;
}
/** Estimated shares: one decimal — extra digits would imply precision the estimate does not have. */
function approx(value: number): string {
  return `≈ ${Number((value * 100).toFixed(1))}%`;
}
function timestamp(value: string): string { return new Date(value).toLocaleString(); }
function contributorName(c: AttributionPeriod['contributors'][number], me: number): string {
  return c.userId == null || c.key === 'unattributed' ? 'Unattributed' : `${c.username}${c.userId === me ? ' (you)' : ''}`;
}

/** Default account: most windows with a current period, then most windows with any data, then freshest reading. */
function defaultAccount(accounts: WindowAttribution['accounts']): WindowAttribution['accounts'][number] | undefined {
  const score = (a: WindowAttribution['accounts'][number]) => [
    a.windows.filter((w) => w.periods.some((p) => p.current)).length,
    a.windows.filter((w) => w.periods.length > 0).length,
    Math.max(0, ...a.windows.flatMap((w) => w.periods).map((p) => Date.parse(p.observedAt) || 0)),
  ];
  return [...accounts].sort((a, b) => {
    const x = score(a), y = score(b);
    return (y[0] - x[0]) || (y[1] - x[1]) || (y[2] - x[2]);
  })[0];
}

function contributorColor(c: AttributionPeriod['contributors'][number]): string {
  return c.userId == null || c.key === 'unattributed' ? 'var(--muted)' : SERIES_COLORS[Math.abs(c.userId) % SERIES_COLORS.length];
}

/** Only connect observed samples, linearly, at their actual timestamps. No synthetic reset/baseline samples. */
function AttributionChart({ period, label, user }: { period: AttributionPeriod; label: string; user: UserDto }) {
  const container = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(520);
  const [active, setActive] = useState<number | null>(null);
  useEffect(() => {
    if (!container.current || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver((entries) => {
      const measured = entries[0]?.contentRect.width;
      if (measured > 0) setWidth(Math.max(180, measured));
    });
    observer.observe(container.current);
    return () => observer.disconnect();
  }, [period.points.length > 0]);
  const points = [...period.points].sort((a, b) => Date.parse(a.ts) - Date.parse(b.ts));
  if (!points.length) return <p className="hint attribution-empty">No measured timeline samples.</p>;
  const left = 48, right = width - 12, top = 12, bottom = 188, height = 226;
  const times = points.map((p) => Date.parse(p.ts));
  const first = times[0], last = times[times.length - 1];
  const x = (time: number) => first === last ? (left + right) / 2 : left + (time - first) / (last - first) * (right - left);
  let accumulated = points.map(() => 0);
  const layers = [...period.contributors].sort((a, b) => (a.userId ?? Infinity) - (b.userId ?? Infinity)).map((c) => {
    const lower = accumulated;
    const upper = points.map((p, i) => lower[i] + (p.values[c.key] ?? 0));
    accumulated = upper;
    return { c, lower, upper };
  });
  const maximum = Math.ceil(Math.max(1, ...points.map((p) => p.total), ...accumulated) * 4) / 4;
  const y = (v: number) => bottom - v / maximum * (bottom - top);
  const line = (values: number[]) => values.map((v, i) => `${i ? 'L' : 'M'}${x(times[i])} ${y(v)}`).join(' ');
  const selected = active == null ? null : points[Math.min(active, points.length - 1)];
  const timeLabel = (ms: number) => new Date(ms).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  return <div ref={container} className="attribution-chart">
    <svg role="img" aria-label={`${label} cumulative quota`} viewBox={`0 0 ${width} ${height}`} width="100%"
      onMouseMove={(e) => {
        const bounds = e.currentTarget.getBoundingClientRect();
        if (!bounds.width) return;
        const px = (e.clientX - bounds.left) / bounds.width * width;
        const nearest = times.reduce((best, t, i) => Math.abs(x(t) - px) < Math.abs(x(times[best]) - px) ? i : best, 0);
        setActive(nearest);
      }} onMouseLeave={() => setActive(null)}>
      <title>{label}: cumulative fractions of one account quota. Time between observations is drawn to scale.</title>
      {[0, maximum / 2, maximum].map((v) => <g key={v}>
        <line x1={left} x2={right} y1={y(v)} y2={y(v)} stroke="var(--border)" />
        <text x={left - 7} y={y(v) + 4} textAnchor="end" fill="var(--muted)" fontSize="10">{percent(v)}</text>
      </g>)}
      {layers.map(({ c, lower, upper }) => <path key={c.key} data-series={c.key}
        d={`${line(upper)} ${lower.map((_, j) => { const i = lower.length - 1 - j; return `L${x(times[i])} ${y(lower[i])}`; }).join(' ')} Z`}
        fill={contributorColor(c)} fillOpacity="0.38" stroke={contributorColor(c)} strokeWidth="1" />)}
      <path d={line(points.map((p) => p.total))} fill="none" stroke="var(--text)" strokeWidth="1.6" />
      {points.map((p, i) => <circle key={`${p.ts}:${i}`} data-ts={p.ts} cx={x(times[i])} cy={y(p.total)} r="2" fill="var(--text)" />)}
      {first === last ? <text x={x(first)} y={height - 12} textAnchor="middle" fill="var(--muted)" fontSize="10">{timeLabel(first)}</text>
        : [first, last].map((time, i) => <text key={time} x={x(time)} y={height - 12} textAnchor={i ? 'end' : 'start'} fill="var(--muted)" fontSize="10">{timeLabel(time)}</text>)}
      {selected && <line x1={x(Date.parse(selected.ts))} x2={x(Date.parse(selected.ts))} y1={top} y2={bottom} stroke="var(--text)" strokeDasharray="3 3" />}
    </svg>
    <label className="attribution-field attribution-scrubber"><span>{label} observation</span>
      <input type="range" min="0" max={points.length - 1} step="1" value={active ?? points.length - 1}
        aria-valuetext={timestamp(points[active ?? points.length - 1]?.ts ?? points[0].ts)}
        onFocus={() => setActive((a) => a ?? points.length - 1)} onChange={(e) => setActive(Number(e.target.value))} />
    </label>
    {selected && <div className="attribution-tooltip" role="status" aria-label={`${label} observation details`}>
      <div className="hint">{timestamp(selected.ts)}</div><strong>{percent(selected.total)}</strong> observed total
      {period.contributors.map((c) => <div className="attribution-tooltip-row" key={c.key}>
        <span>{contributorName(c, user.id)}</span><strong>{approx(selected.values[c.key] ?? 0)}</strong>
      </div>)}
    </div>}
  </div>;
}

function PeriodCard({ kind, periods, user, now }: { kind: '5h' | '7d'; periods: AttributionPeriod[]; user: UserDto; now: number }) {
  const [selected, setSelected] = useState<string | null>(null);
  const period = periods.find((p) => p.id === selected)
    ?? [...periods].reverse().find((p) => p.current) ?? periods[periods.length - 1];
  const title = kind === '5h' ? '5-hour window' : 'Weekly window';
  return <section className="panel attribution-card" aria-label={title}>
    <div className="attribution-card-head">
      <h3>{title}</h3>
      {period && <label className="attribution-field"><span>{kind === '5h' ? '5-hour period' : 'Weekly period'}</span>
        <select value={period.id} onChange={(e) => setSelected(e.target.value)}>
          {[...periods].reverse().map((p) => <option key={p.id} value={p.id}>
            {p.current ? 'Current' : 'Previous'} · {timestamp(p.startAt)}
          </option>)}
        </select>
      </label>}
    </div>
    {!period ? <p className="hint attribution-empty">No measured data for this window.</p> : <>
      <div className="attribution-total"><strong>{percent(period.total)}</strong><span>{period.current ? 'Current reported quota' : 'Observed total'}</span></div>
      <div className="attribution-badges">
        <span className={`badge ${period.current ? 'active' : 'muted'}`}>{period.current ? 'Current' : 'Previous period'}</span>
        {period.estimatedBounds && <span className="badge warn">Estimated bounds</span>}
        {period.partial && <span className="badge warn">Partial history</span>}
        {period.current && <span className={`badge ${now - Date.parse(period.observedAt) > 600_000 ? 'warn' : 'ok'}`}>
          {now - Date.parse(period.observedAt) > 600_000 ? 'Stale · over 10 min' : 'Observed recently'}
        </span>}
      </div>
      <p className="hint">Started {timestamp(period.startAt)}</p>
      <p className="hint">{period.endAt ? `${period.current ? 'Resets' : 'Period ended'} ${timestamp(period.endAt)}` : 'Reset unknown'}</p>
      <p className="hint">Observed {timestamp(period.observedAt)}</p>
      <AttributionChart key={period.id} period={period} label={kind === '5h' ? '5-hour' : 'Weekly'} user={user} />
      <ul className="attribution-contributors" aria-label={`${title} estimated breakdown`}>
        {period.contributors.map((c) => <li key={c.key}>
          <span><i aria-hidden="true" className="attribution-swatch" style={{ background: contributorColor(c) }} />{contributorName(c, user.id)}</span><strong>{approx(c.amount)}</strong>
        </li>)}
      </ul>
    </>}
  </section>;
}

export function WindowAttributionBlock({ user }: { user: UserDto }) {
  const [data, setData] = useState<WindowAttribution | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let controller: AbortController | null = null;
    let stopped = false;
    async function load() {
      controller?.abort();
      const request = new AbortController();
      controller = request;
      setNow(Date.now());
      try {
        const result = await api.windowAttribution(30, request.signal);
        if (stopped || request.signal.aborted) return;
        setData(result); setUpdatedAt(new Date().toISOString()); setError(null);
      } catch (e) {
        if (!stopped && !request.signal.aborted) setError(e instanceof Error ? e.message : 'Request failed');
      }
    }
    load();
    // Hidden tabs skip polling; a refresh runs as soon as the tab becomes visible again.
    const timer = setInterval(() => { if (document.visibilityState !== 'hidden') load(); }, 30_000);
    const onVisible = () => { if (document.visibilityState === 'visible') load(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => { stopped = true; controller?.abort(); clearInterval(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [refresh]);
  const account = data?.accounts.find((a) => a.accountId === accountId) ?? defaultAccount(data?.accounts ?? []);
  return <section className="attribution-block" aria-label="Window attribution">
    <div className="section-head">
      <h2>Window attribution</h2>
      <button className="ghost sm" onClick={() => setRefresh((r) => r + 1)}>Refresh attribution</button>
    </div>
    <p className="hint">Estimated user split · weighted by request cost/tokens; unallocated includes gaps and delayed measurements. Percentages are of one account quota, not shares of usage.</p>
    {!!data?.accounts.length && <label className="attribution-field attribution-account"><span>Attribution account</span>
      <select value={account?.accountId} onChange={(e) => setAccountId(Number(e.target.value))}>
        {data.accounts.map((a) => <option key={a.accountId} value={a.accountId}>{a.accountName} · {a.provider} · #{a.accountId}</option>)}
      </select>
    </label>}
    {updatedAt && <p className="hint">Updated {timestamp(updatedAt)} · Generated {timestamp(data!.generatedAt)} · Refreshes every 30s</p>}
    {error && <div className="err" role="alert">Could not refresh attribution: {error}.{data && ' Showing last successful data.'}</div>}
    {!data && !error && <p className="hint" role="status">Loading window attribution…</p>}
    {data?.accounts.length === 0 && <p className="hint">No shared accounts with quota windows.</p>}
    {account && <div className="attribution-grid">
      {(['5h', '7d'] as const).map((kind) => <PeriodCard key={`${account.accountId}:${kind}`} kind={kind}
        periods={account.windows.find((w) => w.kind === kind)?.periods ?? []} user={user} now={now} />)}
    </div>}
  </section>;
}
