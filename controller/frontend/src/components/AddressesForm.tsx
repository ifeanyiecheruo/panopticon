import { useEffect, useRef, useState } from 'preact/hooks';
import { GetPhoneAddresses, SetPhoneAddresses, type PhoneAddressView } from '../api';

interface Props {
  phoneId: string;
  /** The address in use as Phone detail last read it; a change (opening the
   * detail can fail over to another address) re-reads the list. */
  activeAddress: string;
  onSaved?: () => void;
}

const REACH_NOTE: Record<string, string> = {
  unreachable: 'not answering',
  wrong_phone: 'a different phone',
  error: 'not a phone',
};

// Bare addresses read better, and NormalizeAddress puts the http:// back.
const display = (a: string) => a.replace(/^http:\/\//, '');

/** Every address the controller knows this phone at. Edit one in place when the
 * phone's IP changes, or list several (wifi + VPN): the controller uses the one
 * that last answered and moves to another when it stops. */
export function AddressesForm({ phoneId, activeAddress, onSaved }: Props) {
  const [saved, setSaved] = useState<PhoneAddressView[] | null>(null);
  const [draft, setDraft] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  // Which phone's list, in use where, is showing - so a save that moved the
  // address in use doesn't re-read over its own result.
  const shown = useRef('');

  const seed = (addresses: PhoneAddressView[]) => {
    shown.current = `${phoneId}|${addresses.find((a) => a.active)?.address ?? ''}`;
    setSaved(addresses);
    setDraft(addresses.map((a) => display(a.address)));
  };

  useEffect(() => {
    if (shown.current === `${phoneId}|${activeAddress}`) return;
    if (!shown.current.startsWith(`${phoneId}|`)) setSaved(null); // another phone's list
    setMsg(null);
    GetPhoneAddresses(phoneId)
      .then((r) => (r.ok ? seed(r.addresses) : setMsg(r.error || 'Could not load addresses.')))
      .catch((err) => setMsg(String(err)));
  }, [phoneId, activeAddress]);

  if (!saved) {
    return <div className="card"><div className="calib-sub">{msg ?? 'Loading…'}</div></div>;
  }

  const original = saved.map((a) => display(a.address));
  const entered = draft.map((d) => d.trim()).filter((d) => d !== '');
  const dirty = draft.length !== original.length || draft.some((d, i) => d.trim() !== original[i]);

  const save = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await SetPhoneAddresses(phoneId, entered);
      if (!r.ok) {
        setMsg(r.error || 'Could not save the addresses.');
        return;
      }
      seed(r.addresses);
      const reached = r.addresses.some((a) => a.reach === 'ok');
      setMsg(reached ? 'Saved.' : 'Saved, but the phone did not answer at any of them.');
      onSaved?.();
    } catch (err) {
      setMsg(String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card cfg-form">
      {draft.map((d, i) => {
        const s = saved[i] && display(saved[i].address) === d.trim() ? saved[i] : null;
        return (
          <div className="addr-row" key={i}>
            <input
              type="text"
              value={d}
              placeholder="192.168.1.87"
              onInput={(e) => setDraft(draft.map((x, j) => (j === i ? (e.target as HTMLInputElement).value : x)))}
            />
            <span className="addr-note">
              {s?.active ? 'in use' : s?.reach ? REACH_NOTE[s.reach] ?? '' : ''}
            </span>
            <button
              className="btn small"
              disabled={busy || draft.length === 1}
              onClick={() => setDraft(draft.filter((_, j) => j !== i))}
            >
              Remove
            </button>
          </div>
        );
      })}

      <div className="cfg-actions">
        <button className="btn primary" disabled={!dirty || busy || entered.length === 0} onClick={save}>
          {busy ? 'Checking…' : 'Save'}
        </button>
        <button className="btn" disabled={busy} onClick={() => setDraft([...draft, ''])}>
          Add address
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
