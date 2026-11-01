// Shared chart helpers for the Statistics + My Stats pages: token-kind palette, series
// builders, date math and the interactive legend chip row.
import { ReactNode, useState } from 'react';
import { ChartMode, Series, SERIES_COLORS, StackedChart } from '../Chart';
import { fmtWindowPct, TokenKindSeries, UsageEvent, WindowDaily } from '../api';
import { Icon, NumberInput, Segmented } from '../ui';

export const W5H = '#5a7fb0';
export const WWK = '#c96442';

export const TOKEN_KINDS: { key: keyof TokenKindSeries; label: string; color: string }[] = [
  { key: 'input', label: 'Input', color: '#c96442' },
  { key: 'output', label: 'Output', color: '#5a7fb0' },
  { key: 'cacheRead', label: 'Cache read', color: '#4f9d69' },
  { key: 'cacheWrite', label: 'Cache write', color: '#c08a2e' },
];

/** Today on the viewer's clock (not UTC) — every chart range is expressed in their local days. */
export function todayLocal(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/**
 * Timestamp for the "recent requests" lists, rendered on the viewer's clock.
 *
 * Bare time-of-day is only unambiguous while the event is recent: on a quiet instance the list
 * still holds yesterday's (or last week's) requests, and "09:14" then reads as if it just
 * happened. Past 23h — just under a full day, so a same-hour event from yesterday can't
 * masquerade as today's — the date is shown alongside.
 */
export function fmtEventTs(ts: string): string {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return ts;
  const olderThan23h = Date.now() - d.getTime() > 23 * 3600 * 1000;
  return olderThan23h
    ? d.toLocaleString([], { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
    : d.toLocaleTimeString();
}

/**
 * Cache-write cell for the recent-requests tables. Anthropic prices the 1-hour TTL at ~1.6× the
 * default 5-minute one, and Claude Code writes its main-loop prefix at 1h — so a bare total hides
 * which rate the row was actually charged at. The split rides along as a hint + tooltip.
 */
export function CacheWriteCell({ e }: { e: UsageEvent }) {
  const total = e.cacheWriteTokens;
  const oneHour = e.cacheWrite1hTokens ?? 0;
  if (!oneHour) return <>{total}</>;
  return (
    <span title={`1h TTL: ${oneHour.toLocaleString()} · 5m TTL: ${(total - oneHour).toLocaleString()}\nThe 1h tier costs ~1.6× the 5m one.`}>
      {total} <span className="hint">·1h {oneHour}</span>
    </span>
  );
}

/**
 * The premium charges that don't show up in any token column: fast mode (a higher price tier on
 * the same model) and server-side web searches (billed per call). Rendered next to the model so
 * an unusually expensive row explains itself.
 */
export function PremiumMarks({ e }: { e: UsageEvent }) {
  if (!e.fast && !e.webSearchRequests) return null;
  return (
    <>
      {e.fast && <span className="badge warn" title="Served in fast mode — a premium price tier on the same model.">fast</span>}
      {e.webSearchRequests > 0 && (
        <span className="badge muted" title={`${e.webSearchRequests} server-side web search${e.webSearchRequests === 1 ? '' : 'es'}, billed per call on top of the tokens.`}>
          🔍{e.webSearchRequests}
        </span>
      )}
    </>
  );
}

/** Calendar-date arithmetic on a YYYY-MM-DD label; zone-independent by construction. */
export function shiftDate(d: string, days: number): string {
  const dt = new Date(d + 'T00:00:00Z'); dt.setUTCDate(dt.getUTCDate() + days); return dt.toISOString().slice(0, 10);
}

export function sumKinds(s: TokenKindSeries): number {
  return (['input', 'output', 'cacheRead', 'cacheWrite'] as const)
    .reduce((t, k) => t + (s[k] || []).reduce((a, b) => a + b, 0), 0);
}

export function tokenSeries(src: TokenKindSeries, hidden: Set<string>): Series[] {
  return TOKEN_KINDS.filter((k) => !hidden.has(k.key)).map((k) => ({ name: k.label, color: k.color, values: src[k.key] || [] }));
}

/** Shared time-series control bar for the Statistics + My Stats pages: period presets
    (7/30/90 + a custom day count), the bars/area chart-mode toggle, and prev/next date
    navigation. Drives every chart on the page at once. `maxDays` mirrors the backend cap. */
export function RangeControls({ days, onDays, endDate, onEndDate, mode, onMode, maxDays = 90 }: {
  days: number; onDays: (d: number) => void;
  endDate: string; onEndDate: (d: string) => void;
  mode: ChartMode; onMode: (m: ChartMode) => void;
  maxDays?: number;
}) {
  const PRESETS = [7, 30, 90];
  const [showCustom, setShowCustom] = useState(!PRESETS.includes(days));
  const atToday = endDate >= todayLocal();
  const rangeStart = shiftDate(endDate, -(days - 1));
  return (
    <div className="controlbar">
      <div className="segmented" role="group">
        {PRESETS.map((p) => (
          <button key={p} type="button" aria-pressed={!showCustom && days === p}
            className={'seg' + (!showCustom && days === p ? ' active' : '')}
            onClick={() => { setShowCustom(false); onDays(p); }}>{p}d</button>
        ))}
        <button type="button" aria-pressed={showCustom}
          className={'seg' + (showCustom ? ' active' : '')}
          onClick={() => setShowCustom(true)}>Custom</button>
      </div>
      {showCustom && (
        <span className="custom-days">
          <NumberInput value={days} min={1} max={maxDays} onChange={onDays} className="days-field" />
          <span className="hint">days</span>
        </span>
      )}
      <Segmented<ChartMode> value={mode} onChange={onMode} options={[
        { value: 'bars', label: <><Icon name="bars" size={15} /><span>Bars</span></> },
        { value: 'area', label: <><Icon name="area" size={15} /><span>Area</span></> },
      ]} />
      <div className="daterange">
        <button className="ghost sm" onClick={() => onEndDate(shiftDate(endDate, -days))}>← prev</button>
        <span className="hint mono">{rangeStart} … {endDate}</span>
        <button className="ghost sm" disabled={atToday} onClick={() => onEndDate(shiftDate(endDate, days))}>next →</button>
      </div>
    </div>
  );
}

/**
 * "Window burn per day": how much of the 5-hour and the weekly limit each account actually spent
 * on each day, stacked per account so the stack height is the pool total.
 *
 * Read in window-fractions — 100% is one whole window. The 5h chart routinely passes 100% because
 * the window resets up to ~5 times a day and each fresh window is spent again; that's the number
 * the plain utilization gauge structurally cannot show, since it drops back to 0 on every reset.
 */
export function WindowBurnCharts({ data, mode, hint, height = 190 }: {
  data: WindowDaily; mode: ChartMode; hint?: string; height?: number;
}) {
  const perAcct = data.canViewAccounts && data.perAccount.length > 0;
  const items = perAcct
    ? data.perAccount.map((a, i) => ({
        key: String(a.accountId),
        label: a.accountName ?? `#${a.accountId}`,
        color: SERIES_COLORS[i % SERIES_COLORS.length],
        a,
      }))
    : [];
  const series = (pick: (a: WindowDaily['perAccount'][number]) => number[], total: number[]): Series[] =>
    perAcct
      ? items.map((it) => ({ name: it.label, color: it.color, values: pick(it.a) }))
      : [{ name: 'Total', color: SERIES_COLORS[0], values: total }];

  const sum = (v: number[]) => v.reduce((a, b) => a + b, 0);
  const legend = items.map((it) => ({ key: it.key, label: it.label, color: it.color }));

  const panels = [
    { title: '5-hour window burned', total: data.totalFiveHour, pick: (a: WindowDailySrc) => a.fiveHour },
    { title: 'Weekly window burned', total: data.totalWeekly, pick: (a: WindowDailySrc) => a.weekly },
  ];

  return (
    <div className="chart-row two">
      {panels.map((c) => (
        <div className="panel" key={c.title}>
          <div className="chart-card-head">
            <span className="t">{c.title}</span>
            <span className="v">{fmtWindowPct(sum(c.total))}</span>
          </div>
          <StackedChart days={data.days} series={series(c.pick, c.total)} height={height} fmt={fmtWindowPct} mode={mode} />
          {legend.length > 1 && <Legend items={legend} />}
        </div>
      ))}
      {hint && <p className="hint" style={{ gridColumn: '1 / -1', margin: 0 }}>{hint}</p>}
    </div>
  );
}

type WindowDailySrc = WindowDaily['perAccount'][number];

/** Legend chips; interactive (toggles series) when `onToggle` is supplied. */
export function Legend({ items, hidden, onToggle }: {
  items: { key: string; label: string; color: string }[];
  hidden?: Set<string>; onToggle?: (k: string) => void;
}) {
  return (
    <div className="legend">
      {items.map((it) => {
        const off = !!hidden?.has(it.key);
        const inner: ReactNode = (<><span className="sw" style={{ background: it.color }} />{it.label}</>);
        return onToggle
          ? <button key={it.key} type="button" className={'lg' + (off ? ' off' : '')} onClick={() => onToggle(it.key)}>{inner}</button>
          : <span key={it.key} className="lg">{inner}</span>;
      })}
    </div>
  );
}
