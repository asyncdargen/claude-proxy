import { useEffect, useState } from 'react';
import { api, CatalogModel, CatalogStatus, ModelPrice, PriceSyncStatus } from '../api';
import { Modal, NumberInput, Switch } from '../ui';
import { SkeletonTable } from '../Skeleton';

type Field = { key: keyof Omit<ModelPrice, 'pattern' | 'origin' | 'hasBase'>; label: string; hint: string; unit: string; step: number };

// Per-1M-token prices. `cacheWrite1hPrice` is its own tier, not a variant of the 5m one:
// Anthropic charges 2× input for a 1-hour cache write against 1.25× for the default 5 minutes,
// and Claude Code writes its main-loop prefix with ttl:"1h" — so most cache-write spend lands
// in that column, not the one next to it.
const TOKEN_FIELDS: Field[] = [
  { key: 'inputPrice', label: 'Input', hint: 'prompt tokens', unit: '$ / 1M', step: 0.5 },
  { key: 'outputPrice', label: 'Output', hint: 'completion tokens', unit: '$ / 1M', step: 0.5 },
  { key: 'cacheReadPrice', label: 'Cache read', hint: 'cache hits', unit: '$ / 1M', step: 0.1 },
  { key: 'cacheWritePrice', label: 'Cache write 5m', hint: 'default TTL · ≈1.25× input', unit: '$ / 1M', step: 0.5 },
  { key: 'cacheWrite1hPrice', label: 'Cache write 1h', hint: 'extended TTL · ≈2× input', unit: '$ / 1M', step: 0.5 },
];

// Charges that aren't per token.
const EXTRA_FIELDS: Field[] = [
  { key: 'fastMultiplier', label: 'Fast mode', hint: 'premium tier on the same model', unit: '× all tokens', step: 0.25 },
  { key: 'webSearchPrice', label: 'Web search', hint: 'server-side search calls', unit: '$ / request', step: 0.01 },
];

const ALL_FIELDS = [...TOKEN_FIELDS, ...EXTRA_FIELDS];

const BLANK: Record<string, number> = {
  inputPrice: 0, outputPrice: 0, cacheReadPrice: 0, cacheWritePrice: 0,
  cacheWrite1hPrice: 0, fastMultiplier: 2, webSearchPrice: 0.01,
};

const ORIGIN_BADGE: Record<string, { cls: string; label: string; title: string }> = {
  builtin: { cls: 'muted', label: 'built-in', title: 'Curated default shipped with the proxy' },
  synced: { cls: 'active', label: 'synced', title: 'Pulled automatically from the public price list' },
  override: { cls: 'accent', label: 'override', title: 'Set by an admin — wins over the base price and survives every sync' },
  live: { cls: 'ok', label: 'live', title: 'Listed by Anthropic’s /v1/models' },
  manual: { cls: 'accent', label: 'manual', title: 'Added by an admin; Anthropic does not list it' },
};

function OriginBadge({ origin }: { origin?: string | null }) {
  const b = ORIGIN_BADGE[origin ?? 'override'] ?? ORIGIN_BADGE.override;
  return <span className={`badge ${b.cls}`} title={b.title}>{b.label}</span>;
}

/** "ok: 22 models, 3 updated" + when, as one muted line. */
function SyncLine({ at, result }: { at: string | null; result: string | null }) {
  if (!at) return <span className="hint">never yet</span>;
  const failed = result?.startsWith('error');
  return (
    <span className="hint">
      {new Date(at).toLocaleString()} · <span style={failed ? { color: 'var(--bad)' } : undefined}>{result}</span>
    </span>
  );
}

export function ModelPricing() {
  const [prices, setPrices] = useState<ModelPrice[] | null>(null);
  const [sync, setSync] = useState<PriceSyncStatus | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [np, setNp] = useState('');
  const [nv, setNv] = useState<Record<string, number>>({ ...BLANK });

  async function load() {
    try {
      const [p, st] = await Promise.all([api.modelPrices(), api.priceSyncStatus()]);
      setPrices(p); setSync(st);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  async function syncNow() {
    setSyncing(true);
    try { setSync(await api.syncPrices()); setPrices(await api.modelPrices()); }
    catch (e: any) { setErr(e.message); }
    finally { setSyncing(false); }
  }

  async function add() {
    if (!np.trim()) return;
    try {
      setPrices(await api.setModelPrice({ pattern: np.trim().toLowerCase(), ...(nv as any) }));
      setNp(''); setNv({ ...BLANK });
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="main-inner">
      <h1>Models &amp; pricing</h1>
      <p className="sub">Which models the proxy offers, and what each response costs.</p>
      {err && <div className="err">{err}</div>}

      <ModelCatalogPanel onError={setErr} />

      <div className="panel">
        <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
          <h2 style={{ margin: 0 }}>Pricing</h2>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            {sync && (sync.enabled ? <>Last sync: <SyncLine at={sync.at} result={sync.result} /></> : <span className="hint">Auto-sync is off (PRICING_SYNC_URL=off)</span>)}
            {sync?.enabled && <button className="sm" onClick={syncNow} disabled={syncing}>{syncing ? 'Syncing…' : 'Sync now'}</button>}
          </div>
        </div>
        <p className="hint">
          Base prices are <OriginBadge origin="builtin" /> defaults, kept current by a daily <OriginBadge origin="synced" /> pull
          from the public price list. Editing a row saves an <OriginBadge origin="override" />: it wins over the base price
          and no sync touches it — <b>Reset</b> drops it back to the base.
        </p>
        <p className="hint" style={{ marginTop: 0 }}>
          Matched by substring of the model id, longest match wins — <span className="mono">opus</span> matches{' '}
          <span className="mono">claude-opus-5</span>. Unknown models cost $0.
        </p>
        <p className="hint" style={{ marginTop: 6 }}>
          <b>cost</b> = (input×in + output×out + cache_read×cr + cache_write_5m×cw + cache_write_1h×cw1h) ÷ 1M,
          × the fast-mode multiplier when the response was served in fast mode, + web searches × their per-request price.
        </p>
        <p className="hint" style={{ marginTop: 6 }}>
          Claude Code caches its main-loop prefix at the <b>1h</b> TTL, so that column — not “Cache write 5m” —
          carries most of the cache spend. Leaving it at 0 would price those writes as free.
        </p>
        {!prices ? <SkeletonTable rows={4} cols={ALL_FIELDS.length + 3} /> : (
          <div className="tablewrap">
            <table>
              <thead>
                <tr>
                  <th>Model</th>
                  <th>Source</th>
                  {ALL_FIELDS.map((f) => (
                    <th key={f.key} className="num" title={`${f.hint} · ${f.unit}`}>
                      {f.label}
                      <div className="hint" style={{ fontWeight: 400 }}>{f.unit}</div>
                    </th>
                  ))}
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {prices.map((p) => (
                  <PriceRow key={`${p.pattern}:${p.origin}:${p.inputPrice}:${p.outputPrice}`} p={p}
                    onSave={async (np2) => setPrices(await api.setModelPrice(np2))}
                    onDelete={async (pat) => setPrices(await api.deleteModelPrice(pat))} />
                ))}
                {prices.length === 0 && <tr><td colSpan={ALL_FIELDS.length + 3} className="hint">No pricing rules — everything costs $0.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="panel narrow">
        <h2 style={{ marginTop: 0 }}>Add / override a price</h2>
        <label className="field"><span>Model pattern (substring of the model id)</span>
          <input placeholder="e.g. opus" value={np} onChange={(e) => setNp(e.target.value)} />
        </label>
        <div className="grid2">
          {ALL_FIELDS.map((f) => (
            <label key={f.key} className="field">
              <span>{f.label} <span className="hint">· {f.unit} · {f.hint}</span></span>
              <NumberInput value={nv[f.key]} onChange={(v) => setNv((cur) => ({ ...cur, [f.key]: v }))} min={0} step={f.step} />
            </label>
          ))}
        </div>
        <p className="hint">
          Leave <b>Cache write 1h</b> at 0 to derive it as 2× input; a <b>Fast mode</b> of 0 is read as ×1 (no premium).
        </p>
        <button onClick={add} disabled={!np.trim()}>{prices?.some((p) => p.pattern === np.trim().toLowerCase()) ? 'Update price' : 'Add price'}</button>
      </div>
    </div>
  );
}

function PriceRow({ p, onSave, onDelete }: { p: ModelPrice; onSave: (p: ModelPrice) => void; onDelete: (pat: string) => void }) {
  const [v, setV] = useState<Record<string, number>>(() =>
    Object.fromEntries(ALL_FIELDS.map((f) => [f.key, (p as any)[f.key] ?? BLANK[f.key]])));
  const [dirty, setDirty] = useState(false);
  const set = (key: string, x: number) => { setV((cur) => ({ ...cur, [key]: x })); setDirty(true); };
  const isOverride = (p.origin ?? 'override') === 'override';
  return (
    <tr>
      <td><span className="mono">{p.pattern}</span></td>
      <td><OriginBadge origin={p.origin} /></td>
      {ALL_FIELDS.map((f) => (
        <td key={f.key} style={{ width: 104 }}><NumberInput value={v[f.key]} onChange={(x) => set(f.key, x)} min={0} step={f.step} /></td>
      ))}
      <td>
        <div className="row">
          {dirty && <button className="sm" onClick={() => { onSave({ pattern: p.pattern, ...(v as any) }); setDirty(false); }}>
            {isOverride ? 'Save' : 'Override'}
          </button>}
          {isOverride && (p.hasBase
            ? <button className="sm" title="Drop the override and go back to the base price" onClick={() => onDelete(p.pattern)}>Reset</button>
            : <button className="sm danger" onClick={() => onDelete(p.pattern)}>Delete</button>)}
        </div>
      </td>
    </tr>
  );
}

/**
 * The model catalogue: Anthropic's live listing under admin overrides. It is what the routing
 * gateways advertise on /v1/models and what the chat picker offers.
 */
function ModelCatalogPanel({ onError }: { onError: (e: string) => void }) {
  const [cat, setCat] = useState<CatalogStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<CatalogModel | null>(null);
  const [newId, setNewId] = useState('');
  const [newName, setNewName] = useState('');

  const run = async (f: () => Promise<CatalogStatus>) => {
    try { setCat(await f()); } catch (e: any) { onError(e.message); }
  };
  useEffect(() => { run(api.modelCatalog); }, []);

  async function refresh() {
    setBusy(true);
    await run(api.refreshModels);
    setBusy(false);
  }

  const save = (m: CatalogModel, patch: { displayName?: string | null; description?: string | null; hidden?: boolean }) =>
    run(() => api.setModelOverride({
      id: m.id,
      displayName: patch.displayName !== undefined ? patch.displayName : m.customName,
      description: patch.description !== undefined ? patch.description : m.customDescription,
      hidden: patch.hidden ?? m.hidden,
    }));

  async function add() {
    const id = newId.trim();
    if (!id) return;
    await run(() => api.setModelOverride({ id, displayName: newName.trim() || null, hidden: false }));
    setNewId(''); setNewName('');
  }

  const models = cat?.models ?? null;
  return (
    <div className="panel">
      <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
        <h2 style={{ margin: 0 }}>Models</h2>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          {cat && <>Last listing: <SyncLine at={cat.refreshedAt} result={cat.refreshResult} /></>}
          <button className="sm" onClick={refresh} disabled={busy}>{busy ? 'Refreshing…' : 'Refresh from Anthropic'}</button>
        </div>
      </div>
      <p className="hint">
        Pulled from Anthropic’s <span className="mono">/v1/models</span> every few hours. This is what the API routing
        gateways list on their <span className="mono">/v1/models</span> and what the chat offers. Rename or hide any model,
        or add one Anthropic doesn’t list yet — any id is forwarded verbatim either way.
      </p>
      {!models ? <SkeletonTable rows={4} cols={5} /> : (
        <div className="tablewrap">
          <table>
            <thead>
              <tr><th>Model</th><th>Source</th><th>Priced by</th><th>Offered</th><th></th></tr>
            </thead>
            <tbody>
              {models.map((m) => (
                <tr key={m.id} style={m.hidden ? { opacity: 0.55 } : undefined}>
                  <td>
                    <div>{m.displayName}</div>
                    <div className="hint mono">{m.id}</div>
                    {m.description && <div className="hint">{m.description}</div>}
                  </td>
                  <td><OriginBadge origin={m.origin} /></td>
                  <td>
                    {m.pricePattern
                      ? <><span className="mono">{m.pricePattern}</span> <OriginBadge origin={m.priceOrigin} /></>
                      : <span className="badge bad" title="No pricing pattern matches — this model costs $0">unpriced</span>}
                  </td>
                  <td><Switch checked={!m.hidden} onChange={(on) => save(m, { hidden: !on })} /></td>
                  <td>
                    <div className="row">
                      <button className="sm" onClick={() => setEditing(m)}>Edit</button>
                      {m.overridden && (m.origin === 'manual'
                        ? <button className="sm danger" onClick={() => run(() => api.deleteModelOverride(m.id))}>Remove</button>
                        : <button className="sm" title="Drop the rename/hide and follow the listing again" onClick={() => run(() => api.deleteModelOverride(m.id))}>Reset</button>)}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="row" style={{ gap: 8, marginTop: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label className="field" style={{ margin: 0 }}><span>Add a model id</span>
          <input className="mono" placeholder="e.g. claude-opus-5-5" value={newId} onChange={(e) => setNewId(e.target.value)} />
        </label>
        <label className="field" style={{ margin: 0 }}><span>Display name (optional)</span>
          <input placeholder="Claude Opus 5.5" value={newName} onChange={(e) => setNewName(e.target.value)} />
        </label>
        <button onClick={add} disabled={!newId.trim()}>Add model</button>
      </div>
      {editing && <EditModel m={editing} onClose={() => setEditing(null)}
        onSave={async (name, desc) => { await save(editing, { displayName: name, description: desc }); setEditing(null); }} />}
    </div>
  );
}

function EditModel({ m, onClose, onSave }: { m: CatalogModel; onClose: () => void; onSave: (name: string | null, desc: string | null) => void }) {
  const [name, setName] = useState(m.customName ?? '');
  const [desc, setDesc] = useState(m.customDescription ?? '');
  return (
    <Modal title={`Edit ${m.id}`} onClose={onClose}
      footer={<><button className="ghost" onClick={onClose}>Cancel</button>
        <button onClick={() => onSave(name.trim() || null, desc.trim() || null)}>Save</button></>}>
      <label className="field"><span>Display name</span>
        <input placeholder={m.customName ? '' : m.displayName} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className="field"><span>Description</span>
        <input placeholder={m.customDescription ? '' : (m.description ?? '')} value={desc} onChange={(e) => setDesc(e.target.value)} />
      </label>
      <p className="hint">Leave a field empty to follow Anthropic’s listing.</p>
    </Modal>
  );
}
