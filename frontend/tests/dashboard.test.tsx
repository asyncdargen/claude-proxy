import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { Dashboard } from '../src/pages/Dashboard';
import { App } from '../src/App';
import type { UserDto } from '../src/api';

export const viewer = (permissions: string[]): UserDto => ({
  id: 42, username: 'alex', permissions, roles: [], enabled: true,
  allowedGroups: [], allGroups: false, dailyCostLimit: null, dailyRoutingCostLimit: null,
  dailyChatCostLimit: null, preferGlobalPool: false, todayCost: 0, todayRoutingCost: 0,
  todayChatCost: 0, todayInputTokens: 0, todayOutputTokens: 0,
});
export const emptyPool = {
  accounts: [], totalAccounts: 0, healthyAccounts: 0, activeAccountId: null,
  totalEffectiveRemaining: 0, totalEffectiveCapacity: 0, totalWeeklyRemaining: 0,
  totalWeeklyCapacity: 0, totalInputTokens: 0, totalOutputTokens: 0,
  totalCacheReadTokens: 0, totalCacheWriteTokens: 0, totalCost: 0, totalRequests: 0,
  nextFiveHourReset: null, nextWeeklyReset: null, activeProxySessions: 0, activeRoutingSessions: 0,
};
export function respond(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify(data), { status: 200 }));
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('Dashboard permissions', () => {
  it.each(['/', '/dashboard', '/stats'])('opens Dashboard for only the attribution permission from %s', async (path) => {
    window.history.replaceState(null, '', path);
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: vi.fn() });
    vi.stubGlobal('fetch', vi.fn((url: string) => respond(url === '/api/auth/me'
      ? viewer(['STATS_VIEW_WINDOW_ATTRIBUTION'])
      : url.includes('window-attribution') ? { generatedAt: new Date().toISOString(), accounts: [] }
      : url.includes('token-usage') ? { days: [], perToken: [] } : [])));
    render(<App />);
    expect(await screen.findByRole('link', { name: 'Dashboard' })).toBeTruthy();
    expect(await screen.findByRole('heading', { name: 'Window attribution' })).toBeTruthy();
    expect(await screen.findByText('No shared accounts with quota windows.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Statistics' })).toBeNull();
    expect(window.location.pathname).toBe('/dashboard');
    expect(screen.queryByRole('heading', { name: 'Accounts by priority' })).toBeNull();
  });
  it('does not load or expose account resources to an attribution-only viewer', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn((url: string) => respond(url.includes('window-attribution')
      ? { generatedAt: new Date().toISOString(), accounts: [] } : url.includes('/groups') ? [] : emptyPool));
    vi.stubGlobal('fetch', fetcher);
    await act(async () => { render(<Dashboard user={viewer(['STATS_VIEW_WINDOW_ATTRIBUTION', 'ACCOUNTS_MANAGE'])} />); });
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    vi.useRealTimers();
    expect(fetcher.mock.calls.filter(([url]) => /^\/api\/(accounts|groups)/.test(url))).toEqual([]);
    expect(screen.queryByRole('heading', { name: 'Accounts by priority' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Refresh limits/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Add account|Edit groups/ })).toBeNull();
  });
  it('keeps the existing accounts-only dashboard without fetching attribution', async () => {
    const fetcher = vi.fn((url: string) => respond(url.includes('/groups') ? [] : emptyPool));
    vi.stubGlobal('fetch', fetcher);
    render(<Dashboard user={viewer(['ACCOUNTS_VIEW'])} />);
    await screen.findByText('Accounts healthy');
    expect(screen.getByRole('heading', { name: 'Accounts by priority' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Refresh limits/ })).toBeTruthy();
    expect(fetcher.mock.calls.map(([url]) => url).sort()).toEqual(['/api/accounts', '/api/groups']);
    expect(screen.queryByRole('region', { name: 'Window attribution' })).toBeNull();
  });
  it('keeps attribution available when the independent account-pool load fails', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => url === '/api/accounts'
      ? Promise.reject(new Error('pool unavailable'))
      : respond(url.includes('window-attribution') ? { generatedAt: new Date().toISOString(), accounts: [] } : [])));
    render(<Dashboard user={viewer(['ACCOUNTS_VIEW', 'STATS_VIEW_WINDOW_ATTRIBUTION'])} />);
    expect(await screen.findByText(/pool unavailable/)).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Window attribution' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeTruthy();
  });
  it('places attribution below pool cards and above the account list, isolating attribution failures', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('window-attribution')
      ? Promise.reject(new Error('attribution offline')) : respond(url.includes('/groups') ? [] : emptyPool)));
    render(<Dashboard user={viewer(['ACCOUNTS_VIEW', 'STATS_VIEW_WINDOW_ATTRIBUTION'])} />);
    const card = await screen.findByText('Accounts healthy');
    const attribution = screen.getByRole('region', { name: 'Window attribution' });
    const accounts = screen.getByRole('heading', { name: 'Accounts by priority' });
    expect(card.compareDocumentPosition(attribution) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(attribution.compareDocumentPosition(accounts) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect((await screen.findByRole('alert')).textContent).toContain('attribution offline');
    expect(screen.getByText('No accounts yet.')).toBeTruthy();
  });
});
