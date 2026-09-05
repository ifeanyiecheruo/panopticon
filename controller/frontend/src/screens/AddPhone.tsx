import { useState } from 'preact/hooks';
import type { JSX } from 'preact/jsx-runtime';
import { AddPhone as addPhoneApi, ParseInviteURL, type PhoneView } from '../api';
import { CheckIcon } from '../lib/icons';

interface AddPhoneScreenState {
  address: string;
  code: string;
  connecting: boolean;
  error: string;
  pairedPhone: PhoneView | null;
}

function freshState(): AddPhoneScreenState {
  return { address: '', code: '', connecting: false, error: '', pairedPhone: null };
}

interface AddPhoneProps {
  onGoFleet: () => void;
}

export function AddPhone({ onGoFleet }: AddPhoneProps) {
  const [s, setS] = useState<AddPhoneScreenState>(freshState);

  if (s.pairedPhone) {
    return (
      <div className="add-phone-wrap">
        <div className="method-card success-card">
          <div className="avatar-circle">
            <CheckIcon />
          </div>
          <h3>{s.pairedPhone.name}</h3>
          <div className="sub">
            {s.pairedPhone.manufacturer} {s.pairedPhone.model}
          </div>
          <div className="success-actions">
            <button className="btn primary" onClick={onGoFleet}>
              Go to Fleet
            </button>
            <button className="btn" onClick={() => setS(freshState())}>
              Pair another
            </button>
          </div>
        </div>
      </div>
    );
  }

  const tryAutofill = async (raw: string): Promise<boolean> => {
    const parsed = await ParseInviteURL(raw);
    if (parsed.ok) {
      setS((prev) => ({ ...prev, address: parsed.address, code: parsed.code }));
      return true;
    }
    return false;
  };

  // Both inputs share identical paste-autofill behavior: pasting a full
  // invite URL into either field fills in both, same as the original.
  const handlePaste = async (e: JSX.TargetedClipboardEvent<HTMLInputElement>) => {
    const text = e.clipboardData?.getData('text') ?? '';
    if (await tryAutofill(text)) {
      e.preventDefault();
    }
  };

  const handleSubmit = async () => {
    const address = s.address.trim();
    const code = s.code.trim();
    if (!address) {
      setS((prev) => ({
        ...prev,
        error: "Enter the phone's IP address or hostname — the invite code alone isn't enough to find it on the network.",
      }));
      return;
    }
    if (!code) {
      setS((prev) => ({ ...prev, error: 'Enter the invite code shown on the phone.' }));
      return;
    }

    setS((prev) => ({ ...prev, address, code, error: '', connecting: true }));

    const result = await addPhoneApi(address, code);
    if (!result.ok) {
      setS((prev) => ({ ...prev, connecting: false, error: result.message }));
      return;
    }
    setS((prev) => ({ ...prev, connecting: false, pairedPhone: result.phone ?? null }));
  };

  return (
    <div className="add-phone-wrap">
      <div className="method-card">
        <label>Phone address</label>
        <input
          type="text"
          placeholder="192.168.1.87"
          value={s.address}
          onInput={(e) => setS((prev) => ({ ...prev, address: (e.target as HTMLInputElement).value }))}
          onPaste={handlePaste}
        />
        <label>Invite code</label>
        <input
          type="text"
          placeholder="XYZF-EBDO-ORMS"
          value={s.code}
          onInput={(e) => setS((prev) => ({ ...prev, code: (e.target as HTMLInputElement).value }))}
          onPaste={handlePaste}
        />
        <div className="field-hint">Pasting a full invite URL into either field fills in both automatically.</div>
        {s.connecting && (
          <div className="connecting-row">
            <span className="spin"></span> Connecting to phone…
          </div>
        )}
        {s.error && <div className="error-text">{s.error}</div>}
        <div style={{ marginTop: '16px', display: 'flex', gap: '8px' }}>
          <button className="btn primary" disabled={s.connecting} onClick={handleSubmit}>
            Pair phone
          </button>
        </div>
      </div>
    </div>
  );
}
