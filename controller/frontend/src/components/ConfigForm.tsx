import { useMemo, useState } from 'preact/hooks';
import { SetConfig, type PhoneApiConfig } from '../api';

interface Props {
  phoneId: string;
  config: PhoneApiConfig | undefined;
  configError?: string;
  onSaved?: (cfg: PhoneApiConfig) => void;
}

const GB = 1_000_000_000;
const DAY_MS = 86_400_000;
const SENSITIVITIES = ['low', 'medium', 'high'];

interface Draft {
  deviceName: string;
  motionSensitivity: string;
  storageCapGb: string;
  ringBufferDays: string;
}

function toDraft(c: PhoneApiConfig): Draft {
  return {
    deviceName: c.deviceName ?? '',
    motionSensitivity: c.motionSensitivity ?? 'medium',
    storageCapGb: c.storageCapBytes ? String(+(c.storageCapBytes / GB).toFixed(1)) : '',
    ringBufferDays: c.ringBufferMaxAgeMs ? String(+(c.ringBufferMaxAgeMs / DAY_MS).toFixed(2)) : '',
  };
}

/** Editable GET/POST /api/config. rotationDegrees is deliberately not here — it
 * lives with the camera controls even though the phone persists it via config. */
export function ConfigForm({ phoneId, config, configError, onSaved }: Props) {
  const original = useMemo(() => (config ? toDraft(config) : null), [config]);
  const [draft, setDraft] = useState<Draft | null>(original);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  // Re-seed if the parent hands us a fresh config object (device switch / reload).
  const seedKey = config ? `${config.deviceName}|${config.motionSensitivity}|${config.storageCapBytes}|${config.ringBufferMaxAgeMs}` : '';
  const [lastSeed, setLastSeed] = useState(seedKey);
  if (seedKey !== lastSeed) {
    setLastSeed(seedKey);
    setDraft(original);
  }

  if (configError) {
    return (
      <div className="card">
        <div className="field-row">
          <span className="k">Error</span>
          <span className="v">{configError}</span>
        </div>
      </div>
    );
  }
  if (!config || !draft || !original) {
    return <div className="card"><div className="calib-sub">No config available.</div></div>;
  }

  const d = draft;
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setDraft({ ...d, [k]: v });
  const dirty = (Object.keys(d) as (keyof Draft)[]).some((k) => d[k] !== original[k]);

  const save = async () => {
    setBusy(true);
    setMsg(null);
    const patch: Record<string, unknown> = {};
    if (d.deviceName !== original.deviceName) patch.deviceName = d.deviceName.trim();
    if (d.motionSensitivity !== original.motionSensitivity) patch.motionSensitivity = d.motionSensitivity;
    if (d.storageCapGb !== original.storageCapGb) {
      const gb = parseFloat(d.storageCapGb);
      if (!isNaN(gb) && gb > 0) patch.storageCapBytes = Math.round(gb * GB);
    }
    if (d.ringBufferDays !== original.ringBufferDays) {
      const days = parseFloat(d.ringBufferDays);
      if (!isNaN(days) && days > 0) patch.ringBufferMaxAgeMs = Math.round(days * DAY_MS);
    }
    try {
      const r = await SetConfig(phoneId, patch as Parameters<typeof SetConfig>[1]);
      if (!r.ok || !r.config) {
        setMsg(r.error || 'The phone rejected the config change.');
      } else {
        onSaved?.(r.config);
        setMsg('Saved.');
      }
    } catch (err) {
      setMsg(String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card cfg-form">
      <div className="cfg-row">
        <label>Device name</label>
        <input
          type="text"
          value={d.deviceName}
          onInput={(e) => set('deviceName', (e.target as HTMLInputElement).value)}
        />
      </div>
      <div className="cfg-row">
        <label>Motion sensitivity</label>
        <select
          value={d.motionSensitivity}
          onChange={(e) => set('motionSensitivity', (e.target as HTMLSelectElement).value)}
        >
          {SENSITIVITIES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
      <div className="cfg-row">
        <label>Storage cap (GB)</label>
        <input
          type="number"
          min="1"
          step="1"
          value={d.storageCapGb}
          onInput={(e) => set('storageCapGb', (e.target as HTMLInputElement).value)}
        />
      </div>
      <div className="cfg-row">
        <label>Ring buffer (days)</label>
        <input
          type="number"
          min="0.5"
          step="0.5"
          value={d.ringBufferDays}
          onInput={(e) => set('ringBufferDays', (e.target as HTMLInputElement).value)}
        />
      </div>

      <div className="cfg-actions">
        <button className="btn primary" disabled={!dirty || busy} onClick={save}>
          {busy ? 'Saving…' : 'Save'}
        </button>
        <button
          className="btn"
          disabled={!dirty || busy}
          onClick={() => {
            setDraft(original);
            setMsg(null);
          }}
        >
          Reset
        </button>
        {msg && <span className="calib-sub" style={{ marginLeft: '4px' }}>{msg}</span>}
      </div>
    </div>
  );
}
