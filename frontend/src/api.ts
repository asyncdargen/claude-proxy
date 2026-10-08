// Thin fetch wrapper for the management API. Cookies carry the session.

export interface UserDto {
  id: number;
  username: string;
  enabled: boolean;
  roles: string[];
  permissions: string[];
  allowedGroups: number[];
  allGroups: boolean;
  dailyCostLimit: number | null;
  dailyRoutingCostLimit: number | null;
  dailyChatCostLimit: number | null;
  preferGlobalPool: boolean;
  todayCost: number;
  todayRoutingCost: number;
  todayChatCost: number;
  todayInputTokens: number;
  todayOutputTokens: number;
}

export interface ModelPrice {
  pattern: string;
  inputPrice: number;
  outputPrice: number;
  cacheReadPrice: number;
  // cache writes at the default 5-minute TTL
  cacheWritePrice: number;
  // cache writes at the 1-hour TTL — a higher tier (2× input vs the 5m tier's 1.25×)
  cacheWrite1hPrice: number;
  // × applied to every token price when a response is served in fast mode
  fastMultiplier: number;
  // USD per server-side web search (billed per invocation, not per token)
  webSearchPrice: number;
}

export interface WindowLimitDto {
  usageFraction: number | null;
  remaining: number | null;
  limitTotal: number | null;
  resetAt: string | null;
  status: string | null;
  updatedAt: string | null;
}

/**
 * Paid usage credits ("extra usage"): once the subscription window is spent, requests keep
 * working and the overflow is charged against a monthly credit budget — real money.
 */
export interface OverageDto {
  // "allowed" when credits are usable, "rejected" when they are not
  status: string | null;
  inUse: boolean;
  // 0..1 of the credit allowance consumed — the figure live traffic carries
  utilization: number | null;
  // 0..1 of the monthly credit budget, reported only on some accounts
  monthlyUtilization: number | null;
  channelUtilization: number | null;
  resetAt: string | null;
  disabledReason: string | null;
  // weekly utilization *including* credits: what's really left when 7d already reads 100%
  weeklyWithOverage: number | null;
}

/** Free grace allowance on top of a saturated window. */
export interface GraceDto {
  status: string | null;
  fiveHourUtilization: number | null;
  weeklyUtilization: number | null;
}

export interface AccountDto {
  id: number;
  name: string;
  type: string;
  groupId: number | null;
  ownerId: number | null;
  priority: number;
  threshold: number;
  coefficient: number;
  enabled: boolean;
  overThreshold: boolean;
  health: string;
  fiveHour: WindowLimitDto | null;
  weekly: WindowLimitDto | null;
  overage: OverageDto | null;
  grace: GraceDto | null;
  usageFraction: number | null;
  rateLimitedUntil: string | null;
  effectiveRemaining: number | null;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCacheReadTokens: number;
  totalCacheWriteTokens: number;
  totalCost: number;
  totalRequests: number;
  deviceId: string | null;
  accountUuid?: string | null;
  createdAt: string;
}

export interface PoolStats {
  totalAccounts: number;
  healthyAccounts: number;
  activeAccountId: number | null;
  totalEffectiveRemaining: number;
  totalEffectiveCapacity: number;
  totalWeeklyRemaining: number;
  totalWeeklyCapacity: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCacheReadTokens: number;
  totalCacheWriteTokens: number;
  totalCost: number;
  totalRequests: number;
  nextFiveHourReset: string | null;
  nextWeeklyReset: string | null;
  // requests streaming from Anthropic right now — pool-wide on /api/accounts, own on /api/my/accounts
  activeProxySessions: number;
  activeRoutingSessions: number;
  accounts: AccountDto[];
}

export interface GroupDto {
  id: number;
  name: string;
  accountCount: number;
  createdAt: string;
}

export interface ProxyTokenDto {
  id: number;
  name: string;
  userId: number;
  createdAt: string;
  lastUsedAt: string | null;
  token?: string | null;
  // routing tokens only: static system prompt injected ahead of client system content
  systemPrompt?: string | null;
  // off = the token stops authenticating (clients get 401) without being deleted
  enabled: boolean;
  // proxy tokens only: model that replaces whatever the client asks for ("[1m]" = 1M context)
  defaultModel?: string | null;
}

export interface RoleDto {
  id: number;
  name: string;
  permissions: string[];
}

/** Datapath filter for the stats views: undefined = every source. */
export type StatsSource = 'proxy' | 'routing' | 'chat' | undefined;

/* ---------------------------------------------------------------- chat */

export interface ChatDto {
  id: number;
  title: string;
  model: string;
  pinned: boolean;
  archived: boolean;
  useMemory: boolean;
  systemPrompt: string | null;
  createdAt: string;
  updatedAt: string;
  preview: string | null;
  messageCount: number;
  cost: number;
  // set on search results: the matching text in context
  snippet: string | null;
}

export interface ChatAttachmentDto {
  id: number;
  name: string;
  mimeType: string;
  size: number;
  kind: 'image' | 'document' | 'text';
}

export interface ChatMessageDto {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  thinking: string | null;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  error: string | null;
  createdAt: string;
  attachments: ChatAttachmentDto[];
}

export interface ChatDetail { chat: ChatDto; messages: ChatMessageDto[]; }

export interface ChatModelDto { id: string; label: string; note: string | null; }

export interface ChatMemoryDto {
  id: number;
  content: string;
  source: 'auto' | 'manual';
  enabled: boolean;
  chatId: number | null;
  createdAt: string;
}

export interface ChatSettingsDto {
  memoryEnabled: boolean;
  defaultModel: string | null;
  aboutYou: string | null;
  responseStyle: string | null;
  dailyChatCostLimit: number | null;
  todayChatCost: number;
}

/** One turn of a temporary chat, replayed to the server (nothing about it is stored). */
export interface ChatTurn { role: 'user' | 'assistant'; content: string; attachmentIds?: number[]; }

export interface ChatStreamBody {
  chatId?: number | null;
  temporary?: boolean;
  message: string;
  attachmentIds?: number[];
  model?: string;
  webSearch?: boolean;
  thinking?: boolean;
  history?: ChatTurn[];
  fromMessageId?: number;
  /** viewer's IANA timezone — the server stamps each user turn with it for the model to read */
  tz?: string;
}

/** Events the chat datapath emits, in the order they arrive. */
export type ChatStreamEvent =
  | { type: 'start'; chatId?: number; title?: string; userMessageId?: number; model: string; temporary: boolean }
  | { type: 'delta'; t: string }
  | { type: 'thinking'; t: string }
  | { type: 'tool'; name: string; query?: string; results?: number }
  // an account died mid-answer and another took over: drop whatever it had already produced
  | { type: 'reset'; reason?: string }
  | { type: 'done'; messageId?: number; model: string; inputTokens: number; outputTokens: number; cost: number; warning?: string }
  | { type: 'error'; message: string; messageId?: number };

/**
 * POST a JSON body and read the SSE response. `fetch` rather than `EventSource`: these are POSTs
 * with bodies, and an abort signal is what powers Stop.
 *
 * A failed request may answer with HTML rather than JSON — a proxy's own 502/504 page, say — so
 * the error path never assumes the body parses.
 */
async function postSse(
  path: string,
  body: unknown,
  onEvent: (e: { type: string } & Record<string, unknown>) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '');
    let message = `HTTP ${res.status}`;
    try { message = JSON.parse(text)?.message || message; } catch { /* an HTML error page */ }
    throw new Error(message);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = '';
  let data = '';
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let nl: number;
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl).replace(/\r$/, '');
      buffer = buffer.slice(nl + 1);
      if (line === '') {
        if (event && data) {
          try { onEvent({ type: event, ...JSON.parse(data) }); } catch { /* skip a torn frame */ }
        }
        event = ''; data = '';
      } else if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += line.slice(5).trim();
      // ':' comments are keep-alives — ignored, which is the whole point of them
    }
  }
}

/** Read the chat message stream. */
export function streamChat(
  body: ChatStreamBody,
  onEvent: (e: ChatStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return postSse('/api/chat/stream', { tz: localTz(), ...body }, (e) => onEvent(e as ChatStreamEvent), signal);
}

/**
 * Compact a conversation into a new chat. Streamed rather than a plain POST: the compaction is a
 * model call over the whole transcript and outlives a proxy's read timeout, which surfaced as a
 * 504 (and an HTML body where JSON was expected).
 */
export function continueChatStream(id: number, signal?: AbortSignal): Promise<number> {
  let chatId: number | null = null;
  let failure: string | null = null;
  return postSse(`/api/chat/chats/${id}/continue`, {}, (e) => {
    if (e.type === 'done') chatId = e.chatId as number;
    else if (e.type === 'error') failure = String(e.message);
  }, signal).then(() => {
    if (failure) throw new Error(failure);
    if (chatId == null) throw new Error('The compaction ended without producing a chat.');
    return chatId;
  });
}

/** Upload one attachment; the bytes are the body, the name rides in the query. */
export async function uploadAttachment(file: File): Promise<ChatAttachmentDto> {
  const res = await fetch(`/api/chat/attachments?name=${encodeURIComponent(file.name)}`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    body: file,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
  return data as ChatAttachmentDto;
}

/** Own stats live under /api/stats/mine; the admin view of another user under /api/users/{id}/stats. */
function statsBase(uid: number | null): string {
  return uid == null ? '/api/stats/mine' : `/api/users/${uid}/stats`;
}

function qs(params: Record<string, string | number | undefined>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * The viewer's IANA timezone, sent with every time-series request so the server slices days on
 * their clock instead of UTC. Falls back to UTC when the browser won't say (the server does the
 * same for a missing/unparseable value, so behaviour matches).
 */
export function localTz(): string {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch { return 'UTC'; }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: 'include',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
  return data as T;
}

export const api = {
  config: () => req<{ publicBaseUrl: string }>('GET', '/api/config'),
  modelPrices: () => req<ModelPrice[]>('GET', '/api/model-prices'),
  setModelPrice: (b: ModelPrice) => req<ModelPrice[]>('POST', '/api/model-prices', b),
  deleteModelPrice: (pattern: string) => req<ModelPrice[]>('DELETE', `/api/model-prices/${encodeURIComponent(pattern)}`),

  login: (username: string, password: string) =>
    req<UserDto>('POST', '/api/auth/login', { username, password }),
  logout: () => req<unknown>('POST', '/api/auth/logout'),
  me: () => req<UserDto>('GET', '/api/auth/me'),
  updateProfile: (b: { currentPassword: string; username?: string; password?: string }) =>
    req<UserDto>('PATCH', '/api/account', b),

  accounts: () => req<PoolStats>('GET', '/api/accounts'),
  createAccount: (b: unknown) => req<PoolStats>('POST', '/api/accounts', b),
  updateAccount: (id: number, b: unknown) => req<PoolStats>('PATCH', `/api/accounts/${id}`, b),
  deleteAccount: (id: number) => req<unknown>('DELETE', `/api/accounts/${id}`),
  refreshOne: (id: number) => req<PoolStats>('POST', `/api/accounts/${id}/refresh-limits`),
  refreshAll: () => req<PoolStats>('POST', '/api/accounts/refresh-limits'),
  oauthStart: () => req<{ authorizeUrl: string; state: string }>('POST', '/api/accounts/oauth/start'),
  oauthComplete: (b: unknown) => req<PoolStats>('POST', '/api/accounts/oauth/complete', b),
  oauthReauth: (id: number, b: unknown) => req<PoolStats>('POST', `/api/accounts/${id}/oauth/complete`, b),

  // personal (per-user) accounts — tried before the global pool, excluded from global stats
  myAccounts: () => req<PoolStats>('GET', '/api/my/accounts'),
  globalPool: () => req<PoolStats>('GET', '/api/my/global-pool'),
  createMyAccount: (b: unknown) => req<PoolStats>('POST', '/api/my/accounts', b),
  updateMyAccount: (id: number, b: unknown) => req<PoolStats>('PATCH', `/api/my/accounts/${id}`, b),
  deleteMyAccount: (id: number) => req<unknown>('DELETE', `/api/my/accounts/${id}`),
  refreshMyOne: (id: number) => req<PoolStats>('POST', `/api/my/accounts/${id}/refresh-limits`),
  refreshMyAll: () => req<PoolStats>('POST', '/api/my/accounts/refresh-limits'),
  myOauthStart: () => req<{ authorizeUrl: string; state: string }>('POST', '/api/my/accounts/oauth/start'),
  myOauthComplete: (b: unknown) => req<PoolStats>('POST', '/api/my/accounts/oauth/complete', b),
  myOauthReauth: (id: number, b: unknown) => req<PoolStats>('POST', `/api/my/accounts/${id}/oauth/complete`, b),

  // admin oversight of a user's personal accounts
  userAccounts: (uid: number) => req<PoolStats>('GET', `/api/users/${uid}/accounts`),
  updateUserAccount: (uid: number, id: number, b: unknown) => req<PoolStats>('PATCH', `/api/users/${uid}/accounts/${id}`, b),
  deleteUserAccount: (uid: number, id: number) => req<PoolStats>('DELETE', `/api/users/${uid}/accounts/${id}`),

  groups: () => req<GroupDto[]>('GET', '/api/groups'),
  createGroup: (name: string) => req<GroupDto[]>('POST', '/api/groups', { name }),
  renameGroup: (id: number, name: string) => req<GroupDto[]>('PATCH', `/api/groups/${id}`, { name }),
  deleteGroup: (id: number) => req<unknown>('DELETE', `/api/groups/${id}`),

  users: () => req<UserDto[]>('GET', '/api/users'),
  createUser: (b: unknown) => req<UserDto>('POST', '/api/users', b),
  updateUser: (id: number, b: unknown) => req<UserDto>('PATCH', `/api/users/${id}`, b),
  deleteUser: (id: number) => req<unknown>('DELETE', `/api/users/${id}`),

  roles: () => req<{ roles: RoleDto[]; allPermissions: string[] }>('GET', '/api/roles'),
  createRole: (b: unknown) => req<unknown>('POST', '/api/roles', b),
  updateRole: (id: number, b: unknown) => req<unknown>('PATCH', `/api/roles/${id}`, b),
  deleteRole: (id: number) => req<unknown>('DELETE', `/api/roles/${id}`),

  tokens: () => req<ProxyTokenDto[]>('GET', '/api/proxy-tokens'),
  createToken: (name: string) => req<ProxyTokenDto>('POST', '/api/proxy-tokens', { name }),
  deleteToken: (id: number) => req<unknown>('DELETE', `/api/proxy-tokens/${id}`),
  // Disable/enable a token without revoking it; returns the refreshed list.
  setTokenEnabled: (id: number, enabled: boolean) =>
    req<ProxyTokenDto[]>('PATCH', `/api/proxy-tokens/${id}/enabled`, { enabled }),
  // Set (non-blank) or clear (null) the model forced onto every request of a proxy token.
  updateTokenModel: (id: number, defaultModel: string | null) =>
    req<ProxyTokenDto[]>('PATCH', `/api/proxy-tokens/${id}`, { defaultModel }),

  // Routing tokens (cxr_...) for the OpenAI/Anthropic API gateways. Same shape as proxy tokens.
  routingTokens: () => req<ProxyTokenDto[]>('GET', '/api/routing-tokens'),
  createRoutingToken: (name: string, systemPrompt?: string) =>
    req<ProxyTokenDto>('POST', '/api/routing-tokens', { name, systemPrompt: systemPrompt || null }),
  deleteRoutingToken: (id: number) => req<unknown>('DELETE', `/api/routing-tokens/${id}`),
  setRoutingTokenEnabled: (id: number, enabled: boolean) =>
    req<ProxyTokenDto[]>('PATCH', `/api/routing-tokens/${id}/enabled`, { enabled }),
  // Set (non-blank) or clear (null) a routing token's static system prompt.
  updateRoutingTokenPrompt: (id: number, systemPrompt: string | null) =>
    req<ProxyTokenDto[]>('PATCH', `/api/routing-tokens/${id}`, { systemPrompt }),

  statsSummary: () => req<UsageSummary[]>('GET', '/api/stats/summary'),
  statsRecent: () => req<UsageEvent[]>('GET', '/api/stats/recent'),
  // `tz` makes the "today" half of the breakdown the viewer's day, matching the charts.
  statsModels: () => req<ModelBreakdown>('GET', `/api/stats/models${qs({ tz: localTz() })}`),
  statsDaily: (days: number, end?: string) => req<DailyStats>('GET', `/api/stats/daily${qs({ days, end, tz: localTz() })}`),
  statsWindows: (days: number, end?: string) => req<WindowStats>('GET', `/api/stats/windows${qs({ days, end, tz: localTz() })}`),
  statsTokens: (days: number, end?: string) => req<TokenStats>('GET', `/api/stats/tokens${qs({ days, end, tz: localTz() })}`),
  // Per-day window burn (5h + weekly), reset-aware: how much of each window was spent per day.
  statsWindowDaily: (days: number, end?: string) =>
    req<WindowDaily>('GET', `/api/stats/window-daily${qs({ days, end, tz: localTz() })}`),
  // Per-user statistics: uid=null → the caller's own ("My Stats"), a number → admin view of that
  // user (USERS_MANAGE). `source` filters to one datapath ('proxy' | 'routing'); undefined = both.
  userStats: (uid: number | null, source?: StatsSource) =>
    req<MyStats>('GET', `${statsBase(uid)}${qs({ source, tz: localTz() })}`),
  userStatsDaily: (uid: number | null, days: number, end?: string, source?: StatsSource) =>
    req<DailyStats>('GET', `${statsBase(uid)}/daily${qs({ days, end, source, tz: localTz() })}`),
  userStatsWindows: (uid: number | null, days: number, end?: string) =>
    req<WindowStats>('GET', `${statsBase(uid)}/windows${qs({ days, end, tz: localTz() })}`),
  userStatsWindowDaily: (uid: number | null, days: number, end?: string) =>
    req<WindowDaily>('GET', `${statsBase(uid)}/window-daily${qs({ days, end, tz: localTz() })}`),
  userStatsTokens: (uid: number | null, days: number, end?: string, source?: StatsSource) =>
    req<TokenStats>('GET', `${statsBase(uid)}/tokens${qs({ days, end, source, tz: localTz() })}`),
  // Per-inbound-token usage (Tokens / API Routing pages + the stats views): all-time totals + daily series.
  userTokenUsage: (uid: number | null, source: 'proxy' | 'routing', days: number, end?: string) =>
    req<TokenUsage>('GET', `${statsBase(uid)}/token-usage${qs({ source, days, end, tz: localTz() })}`),
  // Per-MCP-tool call counts (Claude Code datapath): daily series + range totals per tool.
  userMcpUsage: (uid: number | null, days: number, end?: string) =>
    req<McpUsage>('GET', `${statsBase(uid)}/mcp${qs({ days, end, tz: localTz() })}`),
  usersOverview: () => req<UserStatsOverview[]>('GET', `/api/users/stats/overview${qs({ tz: localTz() })}`),
  myStats: () => req<MyStats>('GET', `/api/stats/mine${qs({ tz: localTz() })}`),
  tokenUsage: (source: 'proxy' | 'routing', days: number, end?: string) =>
    req<TokenUsage>('GET', `/api/stats/mine/token-usage${qs({ source, days, end, tz: localTz() })}`),
  setAccountOrder: (preferGlobalPool: boolean) => req<UserDto>('PATCH', '/api/my/account-order', { preferGlobalPool }),
  // ---- chat ----
  chats: (q?: string, archived?: boolean) =>
    req<ChatDto[]>('GET', `/api/chat/chats${qs({ q, archived: archived ? 'true' : undefined })}`),
  chat: (id: number) => req<ChatDetail>('GET', `/api/chat/chats/${id}`),
  createChat: (b: { title?: string; model?: string }) => req<ChatDto>('POST', '/api/chat/chats', b),
  updateChat: (id: number, b: Record<string, unknown>) => req<ChatDto>('PATCH', `/api/chat/chats/${id}`, b),
  deleteChat: (id: number) => req<unknown>('DELETE', `/api/chat/chats/${id}`),
  deleteAllChats: () => req<{ message: string }>('DELETE', '/api/chat/chats'),
  // Duplicate a conversation (transcript + files) into an independent copy.
  copyChat: (id: number) => req<ChatDetail>('POST', `/api/chat/chats/${id}/copy`),
  // drop a message and everything after it (the same cut a retry makes)
  truncateChat: (id: number, messageId: number) =>
    req<ChatDetail>('DELETE', `/api/chat/chats/${id}/messages/${messageId}`),
  chatModels: () => req<ChatModelDto[]>('GET', '/api/chat/models'),
  // Promote an already-answered side question into the conversation (no upstream call).
  appendExchange: (id: number, b: { question: string; answer: string; model?: string }) =>
    req<ChatDetail>('POST', `/api/chat/chats/${id}/messages`, b),
  deleteAttachment: (id: number) => req<unknown>('DELETE', `/api/chat/attachments/${id}`),
  memories: () => req<ChatMemoryDto[]>('GET', '/api/chat/memories'),
  addMemory: (content: string) => req<ChatMemoryDto[]>('POST', '/api/chat/memories', { content }),
  updateMemory: (id: number, b: { content?: string; enabled?: boolean }) =>
    req<ChatMemoryDto[]>('PATCH', `/api/chat/memories/${id}`, b),
  deleteMemory: (id: number) => req<ChatMemoryDto[]>('DELETE', `/api/chat/memories/${id}`),
  clearMemories: () => req<{ message: string }>('DELETE', '/api/chat/memories'),
  chatSettings: () => req<ChatSettingsDto>('GET', '/api/chat/settings'),
  saveChatSettings: (b: Partial<ChatSettingsDto>) => req<ChatSettingsDto>('PATCH', '/api/chat/settings', b),
  importChat: (url: string) => req<ChatDetail>('POST', '/api/chat/import', { url }),

  resetAllStats: () => req<{ message: string }>('POST', '/api/stats/reset'),
  resetUserStats: (id: number) => req<{ message: string }>('POST', `/api/users/${id}/stats/reset`),
  resetMyStats: () => req<{ message: string }>('POST', '/api/stats/mine/reset'),
};

export interface UsageSummary { accountId: number; accountName: string | null; requests: number; inputTokens: number; outputTokens: number; cost: number; }
export interface UsageEvent {
  id: number; accountId: number; accountName: string | null; ts: string;
  inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number;
  cost: number; httpStatus: number; model: string | null; source: string;
  // The parts of the bill the token columns don't show: the 1h slice of the cache writes
  // (priced ~1.6× the 5m rate), server-side web searches (billed per call), and fast mode.
  cacheWrite1hTokens: number; webSearchRequests: number; fast: boolean;
}
export interface ModelUsage { model: string | null; requests: number; cleanTokens: number; cost: number; }
export interface ModelBreakdown { today: ModelUsage[]; allTime: ModelUsage[]; }
export interface MyStats {
  todayCost: number; todayClean: number; todayRequests: number;
  totalCost: number; totalClean: number; totalRequests: number;
  dailyCostLimit: number | null;
  proxyTodayCost: number;             // spend counted against dailyCostLimit today
  dailyRoutingCostLimit: number | null;
  routingTodayCost: number;           // spend counted against dailyRoutingCostLimit today
  dailyChatCostLimit: number | null;
  chatTodayCost: number;              // spend counted against dailyChatCostLimit today
  activeProxySessions: number;        // this user's in-flight requests, by datapath
  activeRoutingSessions: number;
  perModel: ModelUsage[]; perModelToday: ModelUsage[]; recent: UsageEvent[];
}
export interface UserStatsOverview {
  userId: number | null;              // null = usage left by since-deleted users
  username: string | null;
  enabled: boolean;
  dailyCostLimit: number | null;
  dailyRoutingCostLimit: number | null;
  todayProxyCost: number; todayRoutingCost: number; todayCost: number;
  todayRequests: number; todayTokens: number;
  totalProxyCost: number; totalRoutingCost: number; totalCost: number;
  totalRequests: number; totalTokens: number;
  lastActivity: string | null;
}
export interface AccountSeries { accountId: number; accountName: string | null; cost: number[]; requests: number[]; }
export interface DailyStats {
  days: string[];
  totalCost: number[];
  totalRequests: number[];
  perAccount: AccountSeries[];
  canViewAccounts: boolean;
}
export interface WindowSeries {
  accountId: number; accountName: string | null;
  fiveHour: (number | null)[]; weekly: (number | null)[];
  // coefficient × utilization (may exceed 1) — the ×coef toggle switches to these
  fiveHourWeighted: (number | null)[]; weeklyWeighted: (number | null)[];
}
export interface WindowStats {
  buckets: string[];
  totalFiveHour: (number | null)[];          // Σ raw utilization across accounts (may exceed 1)
  totalWeekly: (number | null)[];
  totalFiveHourWeighted: (number | null)[];  // Σ coefficient × utilization across accounts
  totalWeeklyWeighted: (number | null)[];
  perAccount: WindowSeries[];
  canViewAccounts: boolean;
}

export interface WindowDailySeries {
  accountId: number; accountName: string | null;
  // window-fractions burned that day: 1.0 = one whole window. Several resets in a day stack past 1.
  fiveHour: number[]; weekly: number[];
  // the 5h burn restated in base-subscription windows (each step ×the account's coefficient)
  fiveHourWeighted: number[];
}
export interface WindowDaily {
  days: string[];
  totalFiveHour: number[];
  totalWeekly: number[];
  totalFiveHourWeighted: number[];
  perAccount: WindowDailySeries[];
  canViewAccounts: boolean;
}

export interface TokenKindSeries { input: number[]; output: number[]; cacheRead: number[]; cacheWrite: number[]; }
export interface ModelTokenSeries extends TokenKindSeries { model: string; }
export interface AccountTokenSeries extends TokenKindSeries { accountId: number; accountName: string | null; }
export interface TokenStats {
  days: string[];
  total: TokenKindSeries;
  perModel: ModelTokenSeries[];
  perAccount: AccountTokenSeries[];
  models: string[];
  canViewAccounts: boolean;
}

export interface TokenUsageSeries {
  tokenId: number | null;      // null = unattributed (pre-migration) rows
  name: string | null;         // null for deleted tokens
  cost: number[];              // per-day USD, aligned with `days`
  tokens: number[];            // per-day total tokens (all four kinds)
  requests: number[];
  totalCost: number;           // all-time totals (not range-scoped)
  totalTokens: number;
  totalRequests: number;
}
export interface TokenUsage { days: string[]; perToken: TokenUsageSeries[]; }

export interface McpToolSeries {
  name: string;                // full tool name, e.g. "mcp__github__get_issue"
  calls: number[];             // per-day call counts, aligned with `days`
  totalCalls: number;          // sum over the range
}
export interface McpUsage { days: string[]; tools: McpToolSeries[]; }

export function has(user: UserDto | null, perm: string): boolean {
  return !!user && user.permissions.includes(perm);
}

export function fmtUsd(n: number): string {
  if (n === 0) return '$0';
  if (n < 0.01) return `$${n.toFixed(4)}`;
  if (n < 1) return `$${n.toFixed(3)}`;
  return `$${n.toFixed(2)}`;
}

export function fmtTokens(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** Window burn as a percentage of one window: 2.78 → "278%". */
export function fmtWindowPct(n: number): string {
  if (n === 0) return '0%';
  if (n < 0.01) return `${(n * 100).toFixed(2)}%`;
  if (n < 0.1) return `${(n * 100).toFixed(1)}%`;
  return `${Math.round(n * 100)}%`;
}

/**
 * Time left until the daily-cost limit rolls over. The limit is enforced on UTC days (see
 * DatapathService), so this counts down to the next 00:00 UTC regardless of the viewer's zone —
 * which is the whole reason it's spelled out in the UI.
 */
export function fmtUntilUtcMidnight(now: Date = new Date()): string {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  const diff = next - now.getTime();
  const h = Math.floor(diff / 3_600_000);
  const m = Math.floor((diff % 3_600_000) / 60_000);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export function fmtReset(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const diff = d.getTime() - Date.now();
  if (diff <= 0) return 'now';
  const h = Math.floor(diff / 3_600_000);
  const m = Math.floor((diff % 3_600_000) / 60_000);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
