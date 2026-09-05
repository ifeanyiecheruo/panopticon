import './style.css';

import {
  ListPhones,
  ParseInviteURL,
  AddPhone,
  GetPhoneDetail,
  ListClips,
  ListTrash,
  TrashClip,
  RestoreClip,
  DeleteClipPermanently,
  EmptyTrash,
} from '../wailsjs/go/main/App';

// ---------------------------------------------------------------------
// Panopticon controller — vertical-slice UI.
//
// Screens implemented: Fleet, Phone detail (stub: raw status/config),
// Gallery, Trash, Add phone. Deferred (see README.md): live preview
// adjusters, calibration UI, per-clip Trash-vs-eviction-probe niceties.
// ---------------------------------------------------------------------

const state = {
  route: 'fleet', // 'fleet' | 'phone' | 'gallery' | 'trash' | 'add'
  phoneId: null,
  galleryFilter: 'all',
  gallerySelected: null, // filename
  trashSelected: null,
  trashConfirmingEmpty: false,
  addPhone: freshAddPhoneState(),
};

function freshAddPhoneState() {
  return { address: '', code: '', connecting: false, error: '', pairedPhone: null };
}

const app = document.querySelector('#app');

function navigate(route, extra = {}) {
  state.route = route;
  Object.assign(state, extra);
  render();
}

async function render() {
  app.innerHTML = shell();
  wireRail();

  const main = document.getElementById('main');
  try {
    switch (state.route) {
      case 'fleet':
        main.innerHTML = await renderFleetLoading();
        await renderFleet(main);
        break;
      case 'phone':
        main.innerHTML = '<div class="body-scroll">Loading…</div>';
        await renderPhoneDetail(main);
        break;
      case 'gallery':
        main.innerHTML = '<div class="body-scroll">Loading…</div>';
        await renderGallery(main);
        break;
      case 'trash':
        main.innerHTML = '<div class="body-scroll">Loading…</div>';
        await renderTrash(main);
        break;
      case 'add':
        renderAddPhone(main);
        break;
    }
  } catch (err) {
    main.innerHTML = `<div class="body-scroll"><div class="empty-note">Error: ${escapeHtml(String(err))}</div></div>`;
  }
}

function shell() {
  return `
    <div class="app-shell">
      <div class="rail">
        <div class="brand">${icon('logo')} Panopticon</div>
        <button class="rail-btn ${state.route === 'fleet' || state.route === 'phone' ? 'active' : ''}" data-nav="fleet">${icon('fleet')} Fleet</button>
        <button class="rail-btn ${state.route === 'gallery' ? 'active' : ''}" data-nav="gallery">${icon('gallery')} Gallery</button>
        <button class="rail-btn ${state.route === 'trash' ? 'active' : ''}" data-nav="trash">${icon('trash')} Trash</button>
        <button class="rail-btn ${state.route === 'add' ? 'active' : ''}" data-nav="add">${icon('add')} Add phone</button>
        <div class="rail-spacer"></div>
        <div class="rail-foot">v0.1 · tray running</div>
      </div>
      <div class="main" id="main"></div>
    </div>
  `;
}

function wireRail() {
  document.querySelectorAll('[data-nav]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const nav = btn.dataset.nav;
      if (nav === 'add') state.addPhone = freshAddPhoneState();
      navigate(nav);
    });
  });
}

// ---------------- Fleet ----------------

async function renderFleetLoading() {
  return '<div class="header"><h1>Fleet</h1></div><div class="body-scroll">Loading phones…</div>';
}

async function renderFleet(main) {
  const phones = await ListPhones();
  const totalDisk = phones.reduce((sum, p) => sum + (p.diskUsageBytes || 0), 0);

  main.innerHTML = `
    <div class="header">
      <div>
        <h1>Fleet</h1>
        <div class="sub">${phones.length} phone${phones.length === 1 ? '' : 's'} paired · <b>${fmtBytes(totalDisk)}</b> archived</div>
      </div>
      <div class="header-actions">
        <button class="btn" id="refreshFleet">${icon('refresh')} Refresh</button>
      </div>
    </div>
    <div class="body-scroll">
      ${phones.length === 0
        ? `<div class="empty-note">No phones paired yet.<br><br>Use <b>Add phone</b> in the left rail to pair one.</div>`
        : `<div class="grid">${phones.map(phoneCard).join('')}</div>`}
    </div>
  `;

  document.getElementById('refreshFleet').addEventListener('click', () => render());
  document.querySelectorAll('[data-phone]').forEach((card) => {
    card.addEventListener('click', () => navigate('phone', { phoneId: card.dataset.phone }));
  });
}

function phoneCard(p) {
  const statusClass = p.status === 'recording' ? 'recording' : p.status === 'unreachable' ? 'unreachable' : '';
  return `
    <div class="pcard ${p.status === 'unreachable' ? 'is-unreachable' : ''}" data-phone="${p.id}">
      <div class="top">
        <div class="name">${escapeHtml(p.name)}</div>
        ${p.hasBattery ? `<div class="batt ${p.batteryPercent <= 20 ? 'low' : ''}">${p.batteryPercent}%${p.charging ? ' ⚡' : ''}</div>` : ''}
      </div>
      <div class="status-pill ${statusClass}"><span class="dot"></span>${statusLabel(p.status)}</div>
    </div>
  `;
}

function statusLabel(s) {
  return { recording: 'Recording', standby: 'Standby', unreachable: 'Unreachable' }[s] || s;
}

// ---------------- Phone detail ----------------

async function renderPhoneDetail(main) {
  const detail = await GetPhoneDetail(state.phoneId);
  const p = detail.phone;

  main.innerHTML = `
    <div class="body-scroll">
      <button class="back-link" id="backToFleet">${icon('back')} Back to Fleet</button>
      <div class="detail-header">
        <div class="avatar-circle">${escapeHtml((p.name || '?').slice(0, 1).toUpperCase())}</div>
        <div>
          <h2>${escapeHtml(p.name)}</h2>
          <div class="sub">
            <span>${escapeHtml(p.manufacturer)} ${escapeHtml(p.model)}</span>
            ${p.hasBattery ? `<span>${p.batteryPercent}% battery${p.charging ? ' (charging)' : ''}</span>` : ''}
            <span class="status-pill ${p.status === 'recording' ? 'recording' : p.status === 'unreachable' ? 'unreachable' : ''}"><span class="dot"></span>${statusLabel(p.status)}</span>
          </div>
        </div>
      </div>

      <div class="deferred-note">
        Live preview / adjusters / calibration UI are out of scope for this vertical slice.
        Showing raw <span class="mono">GET /api/status</span> + <span class="mono">GET /api/config</span> below instead.
      </div>

      <div class="section-title">Sync</div>
      <div class="card">
        <div class="field-row"><span class="k">Address</span><span class="v">${escapeHtml(p.baseUrl)}</span></div>
        <div class="field-row"><span class="k">Last seen</span><span class="v">${p.lastSeenMs ? new Date(p.lastSeenMs).toLocaleString() : '—'}</span></div>
        <div class="field-row"><span class="k">Sync cursor</span><span class="v">${p.syncCursorMs ? new Date(p.syncCursorMs).toLocaleString() : '—'}</span></div>
        <div class="field-row"><span class="k">Archived on disk</span><span class="v">${fmtBytes(p.diskUsageBytes)}</span></div>
      </div>

      <div class="section-title">GET /api/status</div>
      <div class="card">
        ${detail.statusError
          ? `<div class="field-row"><span class="k">Error</span><span class="v">${escapeHtml(detail.statusError)}</span></div>`
          : `<div class="raw-json">${escapeHtml(JSON.stringify(detail.status, null, 2))}</div>`}
      </div>

      <div class="section-title">GET /api/config</div>
      <div class="card">
        ${detail.configError
          ? `<div class="field-row"><span class="k">Error</span><span class="v">${escapeHtml(detail.configError)}</span></div>`
          : `<div class="raw-json">${escapeHtml(JSON.stringify(detail.config, null, 2))}</div>`}
      </div>

      <div class="section-title">This phone's clips</div>
      <div class="card" style="padding:14px;">
        <button class="btn" id="viewPhoneGallery">${icon('gallery')} View in Gallery</button>
      </div>
    </div>
  `;

  document.getElementById('backToFleet').addEventListener('click', () => navigate('fleet'));
  document.getElementById('viewPhoneGallery').addEventListener('click', () =>
    navigate('gallery', { galleryFilter: state.phoneId })
  );
}

// ---------------- Gallery ----------------

async function renderGallery(main) {
  const [phones, clips] = await Promise.all([ListPhones(), ListClips(state.galleryFilter === 'all' ? '' : state.galleryFilter)]);

  if (!state.gallerySelected && clips.length > 0) state.gallerySelected = clips[0].filename + '|' + clips[0].phoneId;
  const selected = clips.find((c) => c.filename + '|' + c.phoneId === state.gallerySelected) || clips[0] || null;

  const days = groupByDay(clips);

  main.innerHTML = `
    <div class="header">
      <div>
        <h1>Gallery</h1>
        <div class="sub">${clips.length} clip${clips.length === 1 ? '' : 's'}</div>
      </div>
    </div>
    <div class="chip-row">
      <button class="chip ${state.galleryFilter === 'all' ? 'active' : ''}" data-filter="all">All</button>
      ${phones.map((p) => `<button class="chip ${state.galleryFilter === p.id ? 'active' : ''}" data-filter="${p.id}">${escapeHtml(p.name)}</button>`).join('')}
    </div>
    <div class="gallery-layout">
      <div class="viewer-pane">
        ${selected ? viewerHtml(selected, true) : `<div class="viewer-empty">No clip selected</div>`}
      </div>
      <div class="clip-list-pane">
        ${days.length === 0
          ? `<div class="empty-note">No synced clips yet. Pair a phone and wait for the background sync loop to pull its footage.</div>`
          : days.map((d) => dayGroupHtml(d, selected)).join('')}
      </div>
    </div>
  `;

  document.querySelectorAll('[data-filter]').forEach((chip) => {
    chip.addEventListener('click', () => navigate('gallery', { galleryFilter: chip.dataset.filter, gallerySelected: null }));
  });
  document.querySelectorAll('[data-clip]').forEach((tile) => {
    tile.addEventListener('click', () => navigate('gallery', { gallerySelected: tile.dataset.clip }));
  });
  const trashBtn = document.getElementById('trashSelected');
  if (trashBtn) {
    trashBtn.addEventListener('click', async () => {
      await TrashClip(selected.phoneId, selected.filename);
      navigate('gallery', { gallerySelected: null });
    });
  }
}

function viewerHtml(clip, withTrash) {
  return `
    ${clip.hasThumbnail || true
      ? `<video controls preload="metadata" poster="${clip.thumbnailUrl}" src="${clip.videoUrl}"></video>`
      : `<div class="viewer-empty">No preview</div>`}
    <div class="viewer-meta">
      <div>
        <div class="who">${escapeHtml(clip.phoneName)}</div>
        <div class="when">${new Date(clip.createdAtMs).toLocaleString()} · ${fmtDuration(clip.durationMs)}</div>
      </div>
    </div>
    ${withTrash ? `<div class="viewer-actions"><button class="btn danger" id="trashSelected">${icon('trash')} Trash</button></div>` : ''}
  `;
}

function dayGroupHtml(group, selected) {
  return `
    <div class="day-group">
      <div class="day-head">${escapeHtml(group.day)}</div>
      <div class="clip-grid">
        ${group.clips.map((c) => clipTileHtml(c, selected)).join('')}
      </div>
    </div>
  `;
}

function clipTileHtml(c, selected) {
  const isActive = selected && selected.filename === c.filename && selected.phoneId === c.phoneId;
  const key = c.filename + '|' + c.phoneId;
  const bg = c.hasThumbnail ? `style="background-image:url('${c.thumbnailUrl}')"` : '';
  return `
    <div class="ctile ${isActive ? 'active' : ''}" data-clip="${key}" ${bg}>
      ${!c.hasThumbnail ? `<div class="noimg">no thumb</div>` : ''}
      <div class="cbar">
        <span class="cphone">${escapeHtml(c.phoneName)}</span>
        <span class="clen">${fmtDuration(c.durationMs)}</span>
      </div>
    </div>
  `;
}

function groupByDay(clips) {
  const byDay = new Map();
  for (const c of clips) {
    const day = new Date(c.createdAtMs).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day).push(c);
  }
  return Array.from(byDay.entries()).map(([day, clips]) => ({ day, clips }));
}

// ---------------- Trash ----------------

async function renderTrash(main) {
  const clips = await ListTrash();
  if (!state.trashSelected && clips.length > 0) state.trashSelected = clips[0].filename + '|' + clips[0].phoneId;
  const selected = clips.find((c) => c.filename + '|' + c.phoneId === state.trashSelected) || clips[0] || null;
  const days = groupByDay(clips);

  main.innerHTML = `
    <div class="header">
      <div>
        <h1>Trash</h1>
        <div class="sub">${clips.length} clip${clips.length === 1 ? '' : 's'}</div>
      </div>
      <div class="header-actions">
        <button class="btn danger" id="emptyTrash" ${clips.length === 0 ? 'disabled' : ''}>${icon('trash')} Empty trash</button>
      </div>
    </div>
    ${state.trashConfirmingEmpty ? `
      <div class="body-scroll" style="padding-bottom:0;">
        <div class="confirm-box">
          <p>Permanently delete ${clips.length} clip${clips.length === 1 ? '' : 's'}? This can't be undone.</p>
          <div class="confirm-actions">
            <button class="btn danger" id="confirmEmpty">Delete all</button>
            <button class="btn" id="cancelEmpty">Cancel</button>
          </div>
        </div>
      </div>` : ''}
    <div class="gallery-layout">
      <div class="viewer-pane">
        ${selected ? viewerHtmlTrash(selected) : `<div class="viewer-empty">No clip selected</div>`}
      </div>
      <div class="clip-list-pane">
        ${days.length === 0
          ? `<div class="empty-note">Trash is empty.</div>`
          : days.map((d) => dayGroupHtml(d, selected)).join('')}
      </div>
    </div>
  `;

  document.querySelectorAll('[data-clip]').forEach((tile) => {
    tile.addEventListener('click', () => navigate('trash', { trashSelected: tile.dataset.clip }));
  });
  const emptyBtn = document.getElementById('emptyTrash');
  if (emptyBtn) emptyBtn.addEventListener('click', () => navigate('trash', { trashConfirmingEmpty: true }));
  const confirmBtn = document.getElementById('confirmEmpty');
  if (confirmBtn) confirmBtn.addEventListener('click', async () => {
    await EmptyTrash();
    navigate('trash', { trashConfirmingEmpty: false, trashSelected: null });
  });
  const cancelBtn = document.getElementById('cancelEmpty');
  if (cancelBtn) cancelBtn.addEventListener('click', () => navigate('trash', { trashConfirmingEmpty: false }));

  const restoreBtn = document.getElementById('restoreSelected');
  if (restoreBtn) restoreBtn.addEventListener('click', async () => {
    await RestoreClip(selected.phoneId, selected.filename);
    navigate('trash', { trashSelected: null });
  });
  const deleteBtn = document.getElementById('deleteSelected');
  if (deleteBtn) deleteBtn.addEventListener('click', async () => {
    await DeleteClipPermanently(selected.phoneId, selected.filename);
    navigate('trash', { trashSelected: null });
  });
}

function viewerHtmlTrash(clip) {
  return `
    <video preload="metadata" poster="${clip.thumbnailUrl}" src="${clip.videoUrl}"></video>
    <div class="viewer-meta">
      <div>
        <div class="who">${escapeHtml(clip.phoneName)}</div>
        <div class="when">${new Date(clip.createdAtMs).toLocaleString()} · ${fmtDuration(clip.durationMs)}</div>
      </div>
    </div>
    <div class="viewer-actions">
      <button class="btn" id="restoreSelected">${icon('restore')} Restore</button>
      <button class="btn danger" id="deleteSelected">${icon('trash')} Delete</button>
    </div>
  `;
}

// ---------------- Add phone ----------------

function renderAddPhone(main) {
  const s = state.addPhone;

  if (s.pairedPhone) {
    main.innerHTML = `
      <div class="add-phone-wrap">
        <div class="method-card success-card">
          <div class="avatar-circle">${icon('check')}</div>
          <h3>${escapeHtml(s.pairedPhone.name)}</h3>
          <div class="sub">${escapeHtml(s.pairedPhone.manufacturer)} ${escapeHtml(s.pairedPhone.model)}</div>
          <div class="success-actions">
            <button class="btn primary" id="goFleet">Go to Fleet</button>
            <button class="btn" id="pairAnother">Pair another</button>
          </div>
        </div>
      </div>
    `;
    document.getElementById('goFleet').addEventListener('click', () => { state.addPhone = freshAddPhoneState(); navigate('fleet'); });
    document.getElementById('pairAnother').addEventListener('click', () => { state.addPhone = freshAddPhoneState(); render(); });
    return;
  }

  main.innerHTML = `
    <div class="add-phone-wrap">
      <div class="method-card">
        <label>Phone address</label>
        <input id="addressInput" type="text" placeholder="192.168.1.87" value="${escapeHtml(s.address)}" />
        <label>Invite code</label>
        <input id="codeInput" type="text" placeholder="XYZF-EBDO-ORMS" value="${escapeHtml(s.code)}" />
        <div class="field-hint">Pasting a full invite URL into either field fills in both automatically.</div>
        ${s.connecting ? `<div class="connecting-row"><span class="spin"></span> Connecting to phone…</div>` : ''}
        ${s.error ? `<div class="error-text">${escapeHtml(s.error)}</div>` : ''}
        <div style="margin-top:16px; display:flex; gap:8px;">
          <button class="btn primary" id="submitPair" ${s.connecting ? 'disabled' : ''}>Pair phone</button>
        </div>
      </div>
    </div>
  `;

  const addressInput = document.getElementById('addressInput');
  const codeInput = document.getElementById('codeInput');

  const tryAutofill = async (raw) => {
    const parsed = await ParseInviteURL(raw);
    if (parsed.ok) {
      state.addPhone.address = parsed.address;
      state.addPhone.code = parsed.code;
      render();
      return true;
    }
    return false;
  };

  addressInput.addEventListener('paste', async (e) => {
    const text = (e.clipboardData || window.clipboardData).getData('text');
    if (await tryAutofill(text)) e.preventDefault();
  });
  codeInput.addEventListener('paste', async (e) => {
    const text = (e.clipboardData || window.clipboardData).getData('text');
    if (await tryAutofill(text)) e.preventDefault();
  });
  addressInput.addEventListener('input', () => { state.addPhone.address = addressInput.value; });
  codeInput.addEventListener('input', () => { state.addPhone.code = codeInput.value; });

  document.getElementById('submitPair').addEventListener('click', async () => {
    const address = addressInput.value.trim();
    const code = codeInput.value.trim();
    if (!address) { state.addPhone.error = "Enter the phone's IP address or hostname — the invite code alone isn't enough to find it on the network."; render(); return; }
    if (!code) { state.addPhone.error = 'Enter the invite code shown on the phone.'; render(); return; }

    state.addPhone.address = address;
    state.addPhone.code = code;
    state.addPhone.error = '';
    state.addPhone.connecting = true;
    render();

    const result = await AddPhone(address, code);
    state.addPhone.connecting = false;
    if (!result.ok) {
      state.addPhone.error = result.message;
      render();
      return;
    }
    state.addPhone.pairedPhone = result.phone;
    render();
  });
}

// ---------------- helpers ----------------

function fmtBytes(n) {
  if (!n) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

function fmtDuration(ms) {
  const sec = Math.round((ms || 0) / 1000);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function icon(name) {
  const map = {
    logo: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 11.5L12 4l9 7.5"/><path d="M5.5 10v9.5a1 1 0 001 1H9.5v-6.5h5V20.5H17.5a1 1 0 001-1V10"/></svg>',
    fleet: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="7" height="7" rx="1.5"/><rect x="14" y="4" width="7" height="7" rx="1.5"/><rect x="3" y="15" width="7" height="7" rx="1.5"/><rect x="14" y="15" width="7" height="7" rx="1.5"/></svg>',
    gallery: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M8 4v5M16 4v5"/></svg>',
    trash: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M8 6V4a1 1 0 011-1h6a1 1 0 011 1v2m2 0l-1 14a1 1 0 01-1 1H7a1 1 0 01-1-1L5 6"/></svg>',
    add: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"/></svg>',
    back: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 18l-6-6 6-6"/></svg>',
    refresh: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 2.5l4 4-4 4"/><path d="M3 12.5v-2a4 4 0 014-4h14"/><path d="M7 21.5l-4-4 4-4"/><path d="M21 11.5v2a4 4 0 01-4 4H3"/></svg>',
    restore: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 3v6h6"/><path d="M3.5 13a8.5 8.5 0 108-10.4"/></svg>',
    check: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>',
  };
  return map[name] || '';
}

render();
