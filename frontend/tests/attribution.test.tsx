import { afterEach, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { AttributionPeriod, UserDto, WindowAttribution } from '../src/api';
import { WindowAttributionBlock } from '../src/pages/WindowAttribution';

const user = { id: 42, username: 'alex' } as UserDto;
export const measuredPeriod = (overrides: Partial<AttributionPeriod> = {}): AttributionPeriod => ({
  id: 'current', startAt: '2026-10-15T10:00:00Z', endAt: '2026-10-15T15:00:00Z',
  current: true, estimatedBounds: false, partial: false, observedAt: new Date().toISOString(),
  total: 1.234567,
  points: [
    { ts: '2026-10-15T10:00:00Z', total: 0.1, values: { 'user:42': 0.1 } },
    { ts: '2026-10-15T10:05:00Z', total: 0.3, values: { 'user:42': 0.2, 'user:5': 0.1 } },
    { ts: '2026-10-15T10:50:00Z', total: 1.234567, values: { 'user:42': 0.6, 'user:5': 0.4, unattributed: 0.234567 } },
  ],
  contributors: [
    { key: 'user:42', userId: 42, username: 'alex', amount: 0.6, method: 'cost' },
    { key: 'user:5', userId: 5, username: 'sam', amount: 0.4, method: 'tokens' },
    { key: 'unattributed', userId: null, username: 'outside proxy', amount: 0.234567, method: 'unattributed' },
  ], ...overrides,
});
export function payload(): WindowAttribution {
  return { generatedAt: new Date().toISOString(), accounts: [
    { accountId: 1, accountName: 'Shared', provider: 'ANTHROPIC', windows: [
      { kind: '5h', periods: [measuredPeriod({ id: 'previous', current: false, total: 0.8, estimatedBounds: true, partial: true }), measuredPeriod()] },
      { kind: '7d', periods: [measuredPeriod({ id: 'weekly-previous', current: false, total: 0.8 })] },
    ] },
    { accountId: 2, accountName: 'Shared', provider: 'OPENAI', windows: [{ kind: '5h', periods: [] }, { kind: '7d', periods: [] }] },
  ] };
}
function serve(data = payload()) {
  const fetcher = vi.fn(() => Promise.resolve(new Response(JSON.stringify(data))));
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it('polls every 30 seconds, preserves manual periods, marks stale observations and retains data after failure', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-15T11:10:00Z'));
  const data = payload();
  data.accounts[0].windows[0].periods[1].observedAt = '2026-10-15T10:50:00Z';
  const fetcher = serve(data);
  await act(async () => { render(<WindowAttributionBlock user={user} />); });
  const short = screen.getByRole('region', { name: '5-hour window' });
  expect(within(short).getByText('Stale · over 10 min')).toBeTruthy();
  expect(screen.getByText(/Updated/)).toBeTruthy();
  fireEvent.change(screen.getByLabelText('5-hour period'), { target: { value: 'previous' } });
  fetcher.mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify({ message: 'temporarily unavailable' }), { status: 503 })));
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('alert').textContent).toContain('temporarily unavailable');
  expect(screen.getByRole('alert').textContent).toContain('Showing last successful data');
  expect((screen.getByLabelText('5-hour period') as HTMLSelectElement).value).toBe('previous');
  expect(within(short).getByText('80%')).toBeTruthy();
  await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
  expect(fetcher).toHaveBeenCalledTimes(3);
  expect(screen.queryByRole('alert')).toBeNull();
  expect((screen.getByLabelText('5-hour period') as HTMLSelectElement).value).toBe('previous');
});

it('cancels superseded requests and retries initial failures without losing a newer response', async () => {
  const pending: { resolve: (r: Response) => void; signal?: AbortSignal }[] = [];
  const fetcher = vi.fn((_url: string, options?: RequestInit) => new Promise<Response>((resolve) => {
    pending.push({ resolve, signal: options?.signal ?? undefined });
  }));
  vi.stubGlobal('fetch', fetcher);
  const view = render(<WindowAttributionBlock user={user} />);
  await act(async () => pending[0].resolve(new Response(JSON.stringify({ message: 'try again' }), { status: 503 })));
  expect(screen.getByRole('alert').textContent).toContain('try again');
  fireEvent.click(screen.getByRole('button', { name: 'Refresh attribution' }));
  expect(pending).toHaveLength(2);
  fireEvent.click(screen.getByRole('button', { name: 'Refresh attribution' }));
  expect(pending[1].signal?.aborted).toBe(true);
  await act(async () => pending[2].resolve(new Response(JSON.stringify(payload()))));
  expect(screen.getByRole('region', { name: '5-hour window' })).toBeTruthy();
  await act(async () => pending[1].resolve(new Response(JSON.stringify({ generatedAt: new Date().toISOString(), accounts: [] }))));
  expect(screen.queryByText('No shared accounts with quota windows.')).toBeNull();
  expect(screen.queryByRole('alert')).toBeNull();
  view.unmount();
  expect(pending[2].signal?.aborted).toBe(true);
});

it('labels reset times explicitly and does not turn missing samples into a fabricated zero timeline', async () => {
  const data = payload();
  data.accounts[0].windows[0].periods = [measuredPeriod({ points: [], endAt: null })];
  serve(data);
  render(<WindowAttributionBlock user={user} />);
  const short = await screen.findByRole('region', { name: '5-hour window' });
  expect(within(short).getByText('Reset unknown')).toBeTruthy();
  expect(within(short).getByText('No measured timeline samples.')).toBeTruthy();
  expect(within(short).queryByRole('img')).toBeNull();
  expect(within(short).getByText('123.4567%')).toBeTruthy();
  const weekly = screen.getByRole('region', { name: 'Weekly window' });
  expect(within(weekly).getByText(/Period ended/)).toBeTruthy();
});

it('starts measuring a responsive chart when delayed timeline samples arrive', async () => {
  vi.stubGlobal('ResizeObserver', class {
    constructor(private notify: (entries: unknown[]) => void) {}
    observe() { this.notify([{ contentRect: { width: 300 } }]); }
    disconnect() {}
  });
  const initial = payload();
  initial.accounts[0].windows[0].periods = [measuredPeriod({ points: [] })];
  const fetcher = serve(initial);
  render(<WindowAttributionBlock user={user} />);
  await screen.findByText('No measured timeline samples.');
  fetcher.mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify(payload()))));
  fireEvent.click(screen.getByRole('button', { name: 'Refresh attribution' }));
  const chart = await screen.findByRole('img', { name: '5-hour cumulative quota' });
  expect(chart.getAttribute('viewBox')).toBe('0 0 300 226');
});

it('draws linear stacked areas on actual timestamps with honest >100% scale and accessible point details', async () => {
  serve();
  render(<WindowAttributionBlock user={user} />);
  const chart = await screen.findByRole('img', { name: '5-hour cumulative quota' });
  const samples = Array.from(chart.querySelectorAll('circle[data-ts]'));
  const xs = samples.map((el) => Number(el.getAttribute('cx')));
  expect(xs).toHaveLength(3);
  expect((xs[1] - xs[0]) / (xs[2] - xs[0])).toBeCloseTo(0.1);
  const namedArea = chart.querySelector('path[data-series="user:42"]')!;
  expect(namedArea.getAttribute('d')).toContain('L');
  expect(namedArea.getAttribute('d')).not.toMatch(/[CQ]/);
  expect(chart.querySelector('path[data-series="unattributed"]')!.getAttribute('fill')).toBe('var(--muted)');
  const color = namedArea.getAttribute('fill');
  const legend = screen.getByRole('list', { name: '5-hour window estimated breakdown' });
  expect(within(legend).getByText('alex (you)').querySelector('i')?.style.background).toBeTruthy();
  fireEvent.change(screen.getByLabelText('5-hour observation'), { target: { value: '1' } });
  const tooltip = screen.getByRole('status', { name: '5-hour observation details' });
  expect(within(tooltip).getByText('30%')).toBeTruthy();
  expect(within(tooltip).getByText('≈ 20%')).toBeTruthy();
  expect(within(tooltip).getByText(/10\/15\/2026|15\/10\/2026/)).toBeTruthy();
  fireEvent.change(screen.getByLabelText('5-hour period'), { target: { value: 'previous' } });
  expect(screen.getByRole('img', { name: '5-hour cumulative quota' }).querySelector('path[data-series="user:42"]')!.getAttribute('fill')).toBe(color);
});

it('shows exact quota totals, visible named estimates, separate periods and provider-aware accounts', async () => {
  serve();
  render(<WindowAttributionBlock user={user} />);
  const short = await screen.findByRole('region', { name: '5-hour window' });
  const weekly = screen.getByRole('region', { name: 'Weekly window' });
  expect(within(short).getByText('123.4567%')).toBeTruthy();
  expect(within(short).getByText('alex (you)')).toBeTruthy();
  expect(within(short).getByText('sam')).toBeTruthy();
  expect(within(short).getByText('Unattributed')).toBeTruthy();
  expect(within(short).queryByText('outside proxy')).toBeNull();
  expect(within(short).getByText('≈ 60%')).toBeTruthy();
  expect(within(short).getByText('≈ 23.5%')).toBeTruthy(); // unattributed 0.234567 → one decimal, no false precision
  expect(within(weekly).getByText('80%')).toBeTruthy();
  expect((screen.getByLabelText('5-hour period') as HTMLSelectElement).value).toBe('current');
  expect((screen.getByLabelText('Weekly period') as HTMLSelectElement).value).toBe('weekly-previous');
  fireEvent.change(screen.getByLabelText('5-hour period'), { target: { value: 'previous' } });
  expect(within(short).getByText('Estimated bounds')).toBeTruthy();
  expect(within(short).getByText('Partial history')).toBeTruthy();
  expect((screen.getByLabelText('Weekly period') as HTMLSelectElement).value).toBe('weekly-previous');
  expect(screen.getByRole('option', { name: 'Shared · ANTHROPIC · #1' })).toBeTruthy();
  expect(screen.getByRole('option', { name: 'Shared · OPENAI · #2' })).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Attribution account'), { target: { value: '2' } });
  expect(screen.getAllByText('No measured data for this window.')).toHaveLength(2);
  expect(screen.queryByText('0%')).toBeNull();
});

it('defaults to the account with the most recent current period, not simply the first one', async () => {
  const data = payload();
  data.accounts.reverse(); // OpenAI (no data) first, like account id order on the live pool
  serve(data);
  render(<WindowAttributionBlock user={user} />);
  const select = await screen.findByLabelText('Attribution account') as HTMLSelectElement;
  expect(select.value).toBe('1');
});
