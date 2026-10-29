'use strict';

/* ============================================================================
   FileFlow — Gitly's design system + a smart file engine.
   Views: Home (storage, recent, activity), Files (search across storage *and*
   the recycle bin, in-app viewer), Smart (duplicates, same-name, large, stale
   screenshots, leftover APKs, empty folders, fuzzy clean), Bin.
   ========================================================================== */

const N = window.FileFlow || {};
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.prototype.slice.call((r || document).querySelectorAll(s));

/* ------------------------------------------------------------- bridge ---- */
let JOB = 0;
const PENDING = {};
window.__ff = function (job, ok, json) {
  const p = PENDING[job]; if (!p) return; delete PENDING[job];
  let v = json; try { v = JSON.parse(json); } catch (e) { }
  if (ok) p.res(v); else p.rej(v);
};
function call(fn) {
  const args = Array.prototype.slice.call(arguments, 1);
  return new Promise((res, rej) => {
    const job = ++JOB; PENDING[job] = { res, rej };
    try { N[fn].apply(N, [job].concat(args)); }
    catch (e) { delete PENDING[job]; rej(e); }
  });
}
function sync(fn) {
  const args = Array.prototype.slice.call(arguments, 1);
  try { return N[fn].apply(N, args); } catch (e) { return null; }
}

/* --------------------------------------------------------------- state --- */
const S = {
  tab: 'home',
  prefs: { rules: [], autoOrganise: false, autoGroup: false, autoDelete: false, autoQuery: '',
           autoThreshold: 75, notify: true, theme: 'light', accent: 'blue', glow: true, navglow: false,
           compact: false, watchNew: true, autoDeletePermanent: false, autoEmptyFolders: false,
           customAccent: '', timerPresets: '', defaultPresetMs: 0 },
  stats: null, counts: {}, files: [], bin: [], binMap: {}, recent: [], sugg: null,
  cat: 'All', q: '', inclBin: true, sort: 'date',
  cleanQ: '', cleanT: 75, matches: [], sel: {}, searched: false,
  access: false, busy: false, lastRun: null, filesLoaded: false, activity: [],
  selectMode: false, picked: {}, scheduled: [], notifOn: true, canOverlay: true,
  cust: { d: 0, h: 0, m: 0 }, custPath: '',
  folders: [], showFolders: false, folderFilter: '',
  presets: [], defaultPresetMs: 0, newPreset: { label: '', d: 0, h: 0, m: 0 }, usageAccess: false,
  setTab: 'general',
};

const CAT_ICON = {
  Images: ['push', 'image'], Videos: ['fork', 'video'], Audio: ['star', 'music'],
  Documents: ['pr', 'file'], Archives: ['create', 'archive'], APKs: ['tag', 'package'],
  Code: ['code', 'code'], Fonts: ['user', 'type'], Other: ['other', 'file'],
};
const CAT_ORDER = ['Images', 'Videos', 'Audio', 'Documents', 'Archives', 'APKs', 'Code', 'Fonts', 'Other'];

/* --------------------------------------------------------------- icons --- */
const S2 = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
const IC = {
  image: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>`,
  video: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><rect x="2" y="4" width="20" height="16" rx="2"/><polygon points="10 9 15 12 10 15 10 9"/></svg>`,
  music: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`,
  file: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>`,
  archive: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/></svg>`,
  package: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M16 16l-4-4-4 4"/><path d="M12 12v9"/><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"/></svg>`,
  code: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`,
  type: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/></svg>`,
  search: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>`,
  more: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><circle cx="12" cy="12" r="1"/><circle cx="12" cy="5" r="1"/><circle cx="12" cy="19" r="1"/></svg>`,
  chev: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="9 18 15 12 9 6"/></svg>`,
  check: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="20 6 9 17 4 12"/></svg>`,
  close: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`,
  plus: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>`,
  minus: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><line x1="5" y1="12" x2="19" y2="12"/></svg>`,
  move: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="5 9 2 12 5 15"/><polyline points="9 5 12 2 15 5"/><polyline points="15 19 12 22 9 19"/><polyline points="19 9 22 12 19 15"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="12" y1="2" x2="12" y2="22"/></svg>`,
  restore: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>`,
  trash: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
  alert: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,
  info: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`,
  zap: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`,
  tag: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>`,
  sliders: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></svg>`,
  folder: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>`,
  scan: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>`,
  copy: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`,
  eye: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`,
  clock: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`,
  layers: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>`,
  telegram: `<svg viewBox="0 0 24 24" fill="#229ED9" width="20" height="20"><path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z"/></svg>`,
  github: `<svg viewBox="0 0 24 24" fill="currentColor" width="20" height="20"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>`,
  harddrive: `<svg viewBox="0 0 24 24" width="18" height="18" ${S2}><line x1="22" y1="12" x2="2" y2="12"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/><line x1="6" y1="16" x2="6.01" y2="16"/><line x1="10" y1="16" x2="10.01" y2="16"/></svg>`,
};

/* ------------------------------------------------------------ helpers ---- */
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function fmtBytes(b) {
  b = Number(b) || 0;
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
  if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB';
  return (b / 1073741824).toFixed(2) + ' GB';
}
function fmtDate(t) {
  const d = new Date(Number(t) || 0); if (!t) return '';
  const diff = Date.now() - d.getTime();
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
  if (diff < 7 * 86400000) return Math.floor(diff / 86400000) + 'd ago';
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}
/* Toast pop-ups are switched off. The function stays so callers do not need to
   change, but nothing is drawn — confirmations live in the UI instead. */
function toast() { }
function spinner(sm) { return '<div class="spinwrap' + (sm ? ' sm' : '') + '"><div class="spinner"></div></div>'; }
function openSheet(inner) { const s = $('#sheet'); s.innerHTML = '<div class="sheetcard">' + inner + '</div>'; s.hidden = false; document.body.classList.add('sheetopen'); }
function closeSheet() { const s = $('#sheet'); if (s) { s.hidden = true; s.innerHTML = ''; } document.body.classList.remove('sheetopen'); }
function contentUri(p) { return 'content://com.fileflow.app.files' + encodeURIComponent(p); }

/* timer presets */
function builtinPresets() { return DELAYS.map(d => ({ label: d[0], ms: d[1] })); }
function presetList() { return (S.presets && S.presets.length) ? S.presets : builtinPresets(); }
function presetLabel(ms) {
  const p = presetList().find(x => x.ms === ms);
  if (p) return p.label;
  return fmtDur(ms);
}
function fmtDur(ms) {
  const d = Math.floor(ms / 86400000), h = Math.floor((ms % 86400000) / 3600000), m = Math.floor((ms % 3600000) / 60000);
  const bits = [];
  if (d) bits.push(d + ' d');
  if (h) bits.push(h + ' h');
  if (m || !bits.length) bits.push(m + ' m');
  return bits.join(' ');
}

/* auto-delete timers */
const DELAYS = [['1 hour', 3600000], ['1 day', 86400000], ['1 week', 604800000], ['1 month', 2592000000]];
function fmtLeft(at) {
  const ms = Number(at) - Date.now();
  if (ms <= 0) return 'due now';
  const m = Math.round(ms / 60000);
  if (m < 60) return 'in ' + m + ' min';
  const h = Math.round(m / 60);
  if (h < 48) return 'in ' + h + ' h';
  return 'in ' + Math.round(h / 24) + ' days';
}
function autoDeletePrompt(path) {
  if (!path) return;
  const name = String(path).split('/').pop();
  const def = Number(S.defaultPresetMs) || 0;
  const list = presetList();
  openSheet(`
    <div class="sheethead" style="margin-bottom:6px"><b style="font-size:18px;font-weight:700">Auto-delete</b></div>
    <div class="lsub" style="white-space:normal;line-height:1.4;margin-bottom:12px">${esc(name)} · permanently deleted when the timer ends</div>
    ${def >= 60000 ? `<button class="btn primary btnblock" data-act="pickDelay" data-path="${esc(path)}" data-ms="${def}">${IC.zap}&nbsp; Use default · ${esc(fmtDur(def))}</button>` : ''}
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px">
      ${list.map(d => `<button class="btn ghost" data-act="pickDelay" data-path="${esc(path)}" data-ms="${d.ms}">${esc(d.label)}</button>`).join('')}
    </div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px">
      <button class="btn ghost" data-act="customDelay" data-path="${esc(path)}">${IC.sliders}&nbsp; Custom</button>
      <button class="btn ghost" data-act="closeSheet">Keep it</button>
    </div>`);
}
window.ffAutoDeletePrompt = autoDeletePrompt;

function presetMs(n) { return (n.d * 86400000) + (n.h * 3600000) + (n.m * 60000); }
function presetSheet() {
  const n = S.newPreset;
  const row = (label, unit) => `
    <div class="setrow" style="margin-top:8px">
      <div class="lmain"><div class="ltitle">${label}</div></div>
      <button class="iconbtn" data-act="presetStep" data-u="${unit}" data-dir="-1" aria-label="Less">${IC.minus}</button>
      <span style="min-width:40px;text-align:center;font-weight:700;font-size:16px">${n[unit]}</span>
      <button class="iconbtn" data-act="presetStep" data-u="${unit}" data-dir="1" aria-label="More">${IC.plus}</button>
    </div>`;
  openSheet(`
    <div class="sheethead" style="margin-bottom:6px"><b style="font-size:18px;font-weight:700">New preset</b></div>
    <label class="fldlabel" for="presetLabel">Name</label>
    <input id="presetLabel" class="fld" placeholder="e.g. Two hours" value="${esc(n.label || '')}" autocomplete="off">
    ${row('Days', 'd')}${row('Hours', 'h')}${row('Minutes', 'm')}
    <div style="text-align:center;margin:12px 0 4px"><span class="chip" style="background:var(--accent);color:#fff">${esc(fmtDur(presetMs(n)))}</span></div>
    <button class="btn primary btnblock" data-act="presetSave" style="margin-top:8px" ${presetMs(n) < 60000 ? 'disabled style="margin-top:8px;opacity:.45"' : ''}>Save preset</button>
    <button class="btn text btnblock" data-act="closeSheet" style="margin-top:8px">Cancel</button>`);
}

function custTotalMs() { return (S.cust.d * 86400000) + (S.cust.h * 3600000) + (S.cust.m * 60000); }
function custLabel() {
  const p = [];
  if (S.cust.d) p.push(S.cust.d + ' d');
  if (S.cust.h) p.push(S.cust.h + ' h');
  p.push(S.cust.m + ' m');
  return p.join(' ');
}
function customDelaySheet(path) {
  S.custPath = path;
  const row = (label, unit) => `
    <div class="setrow" style="margin-top:12px">
      <div class="lmain"><div class="ltitle">${label}</div></div>
      <button class="iconbtn" data-act="custStep" data-u="${unit}" data-dir="-1" aria-label="Less">${IC.minus}</button>
      <span style="min-width:46px;text-align:center;font-weight:700;font-size:18px">${S.cust[unit]}</span>
      <button class="iconbtn" data-act="custStep" data-u="${unit}" data-dir="1" aria-label="More">${IC.plus}</button>
    </div>`;
  openSheet(`
    <div class="sheethead" style="margin-bottom:8px"><b style="font-size:20px;font-weight:700">Custom timer</b></div>
    <div class="lsub" style="white-space:normal;margin-bottom:6px">${esc(String(path).split('/').pop())} — it will be permanently deleted when the timer ends.</div>
    ${row('Days', 'd')}
    ${row('Hours', 'h')}
    ${row('Minutes', 'm')}
    <div style="text-align:center;margin:18px 0 6px">
      <span class="chip" style="background:var(--accent);color:#fff">Permanently deletes in ${custLabel()}</span>
    </div>
    <button class="btn primary btnblock" data-act="custSave" ${custTotalMs() < 60000 ? 'disabled style="margin-top:12px;opacity:.45"' : 'style="margin-top:12px"'}>Set timer</button>
    <button class="btn text btnblock" data-act="closeSheet" style="margin-top:10px">Cancel</button>`);
}

/* activity log (kept on the device) */
function loadActivity() { try { S.activity = JSON.parse(localStorage.getItem('ff.activity') || '[]'); } catch (e) { S.activity = []; } }
function logAct(text) {
  S.activity.unshift({ t: Date.now(), text });
  S.activity = S.activity.slice(0, 25);
  try { localStorage.setItem('ff.activity', JSON.stringify(S.activity)); } catch (e) { }
}

/* ------------------------------------------------------ colour utils ---- */
function hexToRgb(h) {
  const m = /^#([0-9a-fA-F]{6})$/.exec(h || '');
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}
function shadeHex(h, amt) {
  const c = hexToRgb(h); if (!c) return h;
  const f = v => Math.max(0, Math.min(255, Math.round(v + 255 * amt)));
  return '#' + [f(c.r), f(c.g), f(c.b)].map(v => v.toString(16).padStart(2, '0')).join('');
}
function rgbaOf(h, a) {
  const c = hexToRgb(h); if (!c) return 'rgba(27,110,243,' + a + ')';
  return 'rgba(' + c.r + ',' + c.g + ',' + c.b + ',' + a + ')';
}

/* ------------------------------------------------------ Gitly motion ----- */
let ENTER_T = null, REFRESHING = false;
function enterView() {
  document.body.classList.add('entering');
  clearTimeout(ENTER_T);
  ENTER_T = setTimeout(() => document.body.classList.remove('entering'), 1100);
}
function beginRefresh() { REFRESHING = true; document.body.classList.add('refreshing'); }
function settleView() {
  const v = $('#view'); if (!v) return;
  $$(':scope > *', v).forEach(n => n.classList.add('settled'));
  $$('.lrow', v).forEach(n => { if (!n.classList.contains('new-in')) n.classList.add('settled'); });
}
function endRefresh() {
  REFRESHING = false;
  setTimeout(() => { if (REFRESHING) return; settleView(); document.body.classList.remove('refreshing'); }, 900);
}

/* ------------------------------------------------- Gitly theme system ---- */
function applyTheme() {
  const t = S.prefs.theme || 'light';
  document.body.classList.remove('dark', 'pitch');
  if (t === 'dark') document.body.classList.add('dark');
  else if (t === 'pitch') document.body.classList.add('pitch');
  document.body.classList.toggle('navglow', !!S.prefs.navglow);
  document.body.classList.toggle('compact', !!S.prefs.compact);
  const rt = document.documentElement;
  const hex = (S.prefs.customAccent || '').trim();
  if (/^#[0-9a-fA-F]{6}$/.test(hex)) {
    rt.dataset.accent = 'custom';
    rt.style.setProperty('--accent', hex);
    rt.style.setProperty('--accentD', shadeHex(hex, -0.16));
    rt.style.setProperty('--glow', rgbaOf(hex, 0.42));
    rt.style.setProperty('--glow2', rgbaOf(hex, 0.14));
  } else {
    rt.dataset.accent = S.prefs.accent || 'blue';
    ['--accent', '--accentD', '--glow', '--glow2'].forEach(v => rt.style.removeProperty(v));
  }
  rt.dataset.glow = S.prefs.glow === false ? 'off' : 'on';
  let bg = '#F2F2F2';
  try { bg = getComputedStyle(document.body).getPropertyValue('--bg').trim() || bg; } catch (e) { }
  sync('theme', bg, t === 'light');
}

/* ---------------------------------------------------- nav auto-hide ------ */
let lastY = 0, idleT = null;
function isWide() { try { return window.innerWidth >= 1080; } catch (e) { return false; } }
function navShow() { const nb = $('#navbar'); if (nb) nb.classList.remove('hide'); resetIdle(); }
function navHide() { if (isWide()) return; const nb = $('#navbar'); if (nb) nb.classList.add('hide'); clearTimeout(idleT); }
function resetIdle() {
  clearTimeout(idleT);
  idleT = setTimeout(() => { if (isWide()) return; const sc = $('#scroller'); if (sc && sc.scrollTop > 150) navHide(); }, 4000);
}
function wireScroll() {
  $('#scroller').addEventListener('scroll', () => {
    const sc = $('#scroller'), y = sc.scrollTop;
    $('#appbar').classList.toggle('on', y > 110);
    if (!isWide()) { if (y > lastY + 10 && y > 80) navHide(); else if (y < lastY - 10) navShow(); }
    lastY = y; resetIdle();
  });
  document.addEventListener('pointerdown', () => navShow(), true);
  window.addEventListener('keydown', () => navShow());
}

/* ------------------------------------------------------------ render ----- */
const TITLES = {
  home: ['FileFlow', 'Your storage, in order'],
  files: ['Files', 'Everything FileFlow can see'],
  search: ['Search', 'Find any file by name'],
  smart: ['Smart', 'Suggestions, duplicates and clean-up'],
  bin: ['Recycle bin', 'Restore anything you deleted'],
  settings: ['Settings', 'Automation, rules and appearance'],
};
function render() {
  const v = $('#view'); if (!v) return;
  let t = TITLES[S.tab] || TITLES.home;
  if (S.tab === 'settings' && S.setTab === 'credits') t = ['Credits', 'Version, developer and links'];
  $('#bigTitle').textContent = t[0];
  $('#bigSub').textContent = t[1];
  $('#appbarTitle').textContent = t[0];
  $$('#navbar .navbtn').forEach(b => b.classList.toggle('active', b.dataset.go === S.tab));
  const n = Object.keys(S.picked).length;
  if (S.selectMode) { $('#bigTitle').textContent = n + ' selected'; $('#appbarTitle').textContent = n + ' selected'; }
  $('#refreshBig').hidden = !(S.tab === 'home' || S.tab === 'files' || S.tab === 'smart');
  if (S.tab === 'home') v.innerHTML = viewHome();
  else if (S.tab === 'files') v.innerHTML = viewFiles();
  else if (S.tab === 'search') v.innerHTML = viewSearch();
  else if (S.tab === 'smart') v.innerHTML = viewSmart();
  else if (S.tab === 'bin') v.innerHTML = viewBin();
  else v.innerHTML = viewSettings();
  wireView();
  renderSelBar();
}

function renderNoAnim() {
  document.body.classList.remove('entering');
  render();
  settleView();
}

function renderSelBar() {
  let bar = $('#selbar');
  if (!S.selectMode) { if (bar) bar.remove(); return; }
  if (!bar) { bar = document.createElement('div'); bar.id = 'selbar'; document.body.appendChild(bar); }
  const n = Object.keys(S.picked).length;
  const rows = $$('#view .lrow[data-path]');
  const allPicked = rows.length > 0 && rows.every(r => S.picked[r.dataset.path]);
  bar.innerHTML = `
    <button class="sbtn" data-act="selClose" aria-label="Cancel">${IC.close}</button>
    <span class="cnt">${n} selected</span>
    <button class="sbtn" data-act="selAll">${allPicked ? 'None' : 'All'}</button>
    <button class="sbtn" data-act="selView" ${n === 1 ? '' : 'disabled'} aria-label="View">${IC.eye}</button>
    <button class="sbtn danger" data-act="selDelete" ${n ? '' : 'disabled'}>${IC.trash}&nbsp;Bin</button>`;
}

/* -------------------------------------------------------------- home ----- */
function viewHome() {
  const st = S.stats || {};
  const pct = st.percent || 0;
  const access = S.access ? '' : `
    <div class="card">
      <div class="lrow" style="padding:0;border:none;cursor:default">
        <div class="eicon tag">${IC.alert}</div>
        <div class="lmain">
          <div class="ltitle">Allow file access</div>
          <div class="lsub" style="white-space:normal">FileFlow needs “All files access” to organise, clean and restore your files.</div>
        </div>
      </div>
      <button class="btn primary btnblock" data-act="access" style="margin-top:14px">Open settings</button>
    </div>`;

  const total = Object.keys(S.counts).reduce((a, k) => a + S.counts[k], 0);
  const cats = CAT_ORDER.filter(k => S.counts[k] > 0).map(k => {
    const [cls, ic] = CAT_ICON[k];
    return `<div class="lrow" data-act="catgo" data-cat="${esc(k)}">
      <div class="eicon ${cls}">${IC[ic]}</div>
      <div class="lmain"><div class="ltitle">${esc(k)}</div><div class="lsub">${S.counts[k]} files</div></div>
      <span class="chip">${S.counts[k]}</span>
    </div>`;
  }).join('');

  const recent = (S.recent || []).slice(0, 6).map(f => fileRow(f)).join('');

  const sug = S.sugg;
  let sugLine = 'Scanning…', sugValue = '';
  if (sug) {
    const dup = (sug.duplicates || []).length;
    const same = (sug.sameName || []).length;
    const junk = (sug.apks || []).length + (sug.screenshots || []).length + (sug.emptyFolders || []).length;
    const bits = [];
    if (dup) bits.push(dup + ' duplicate sets');
    if (same) bits.push(same + ' shared names');
    if (junk) bits.push(junk + ' clean-up items');
    sugLine = bits.length ? bits.join(' · ') : 'Nothing needs attention';
    sugValue = bits.length ? (sug.duplicateText || '') : '';
  }

  const autoOn = [S.prefs.autoOrganise && 'organise', S.prefs.autoGroup && 'group', S.prefs.autoDelete && 'clean'].filter(Boolean);

  const activity = (S.activity || []).slice(0, 5).map(a => `<div class="lrow" style="cursor:default">
      <div class="eicon user">${IC.clock}</div>
      <div class="lmain"><div class="ltitle" style="white-space:normal;font-weight:500">${esc(a.text)}</div><div class="lsub">${esc(fmtDate(a.t))}</div></div>
    </div>`).join('');

  return `
  ${access}
  <div class="card">
    <div class="ltitle" style="font-size:13px;color:var(--text2);font-weight:600">Internal storage</div>
    <div style="font-size:38px;font-weight:700;letter-spacing:-1.2px;margin:6px 0 2px">${esc(st.freeText || '—')}</div>
    <div class="lsub">free of ${esc(st.totalText || '—')} · ${total} files found</div>
    <div class="bar" style="margin-top:14px"><span style="display:block;height:100%;width:${pct}%;background:var(--accent);border-radius:999px;box-shadow:0 0 12px var(--glow)"></span></div>
    <div class="bigstats">
      <div class="bstat"><b>${pct}%</b><span class="lsub">used</span></div>
      <div class="bstat"><b>${esc(st.usedText || '—')}</b><span class="lsub">used</span></div>
      <div class="bstat"><b>${S.bin.length}</b><span class="lsub">in bin</span></div>
    </div>
  </div>

  <button class="btn primary btnblock" data-act="organise" ${S.access ? '' : 'disabled'} style="margin-bottom:10px">
    ${IC.move}&nbsp; Organise now
  </button>
  <div class="btnrow" style="margin-top:0">
    <button class="btn ghost" data-act="goSmart">${IC.zap}&nbsp; Smart</button>
    <button class="btn ghost" data-act="goBin">${IC.trash}&nbsp; Bin${S.bin.length ? ' · ' + S.bin.length : ''}</button>
  </div>

  <div class="sect">Smart suggestions</div>
  <div class="card list">
    <div class="lrow" data-act="goSmart">
      <div class="eicon star">${IC.layers}</div>
      <div class="lmain"><div class="ltitle">${esc(sugLine)}</div>
        <div class="lsub">${sugValue ? 'About ' + esc(sugValue) + ' could be freed' : 'Tap to review suggestions'}</div></div>
      ${IC.chev}
    </div>
  </div>

  ${recent ? `<div class="sect">Recent</div><div class="card list">${recent}</div>` : ''}

  ${(S.scheduled || []).length ? `<div class="sect">Auto-delete timers</div><div class="card list">${S.scheduled.map(t => `
      <div class="lrow" style="cursor:default">
        <div class="eicon star">${IC.clock}</div>
        <div class="lmain"><div class="ltitle">${esc(t.name)}</div>
          <div class="lsub">${esc(t.sizeText || '')} · deletes ${esc(fmtLeft(t.at))}</div></div>
        <button class="iconbtn" data-act="cancelTimer" data-path="${esc(t.path)}" aria-label="Cancel">${IC.close}</button>
      </div>`).join('')}</div>` : ''}

  ${cats ? `<div class="sect">Your files</div><div class="card list">${cats}</div>` : ''}

  ${activity ? `<div class="sect">Your activity</div><div class="card list">${activity}</div>` : ''}

  <div class="sect">Automatic</div>
  <div class="card list">
    <div class="lrow" data-act="settings">
      <div class="eicon star">${IC.zap}</div>
      <div class="lmain"><div class="ltitle">Automatic tidy-up</div><div class="lsub">${autoOn.length ? autoOn.join(', ') + ' on' : 'All automatic actions are off'}</div></div>
      ${IC.chev}
    </div>
  </div>`;
}

/* -------------------------------------------------------------- rows ----- */
function fileRow(f) {
  const [cls, ic] = CAT_ICON[f.cat] || CAT_ICON.Other;
  const binned = f.inBin || !!S.binMap[f.path];
  const picked = !!S.picked[f.path];
  const badge = binned ? `<span class="chip" style="background:var(--danger);color:#fff;margin-right:6px">Bin</span>` : '';
  const meta = `<div class="lmain">
      <div class="ltitle">${esc(f.name)}</div>
      <div class="lsub">${esc(f.sizeText || fmtBytes(f.size))} · ${esc(f.dir || '')}${f.mtime ? ' · ' + esc(fmtDate(f.mtime)) : ''}</div>
    </div>`;
  if (S.selectMode) {
    return `<div class="lrow ${picked ? 'picked' : ''}" data-act="pick" data-path="${esc(f.path)}">
      <span class="pickbox">${picked ? IC.check : ''}</span>
      <div class="eicon ${cls}">${IC[ic]}</div>
      ${meta}${badge}
    </div>`;
  }
  return `<div class="lrow" data-act="filemenu" data-path="${esc(f.path)}">
    <div class="eicon ${cls}">${IC[ic]}</div>
    ${meta}${badge}
    <span style="color:var(--text2)">${IC.more}</span>
  </div>`;
}

/* ------------------------------------------------------------- files ----- */
function viewFiles() {
  if (S.showFolders) return viewFolders();
  let list = S.files.filter(f => {
    if (S.cat !== 'All' && f.cat !== S.cat) return false;
    if (S.folderFilter && (f.path || '').indexOf(S.folderFilter + '/') !== 0) return false;
    if (S.q && !(f.name || '').toLowerCase().includes(S.q.toLowerCase())) return false;
    return true;
  });
  if (S.sort === 'size') list = list.slice().sort((a, b) => (b.size || 0) - (a.size || 0));
  else if (S.sort === 'name') list = list.slice().sort((a, b) => (a.name || '').localeCompare(b.name || ''));
  const bytes = list.reduce((a, f) => a + (Number(f.size) || 0), 0);
  const cats = ['All'].concat(CAT_ORDER.filter(k => (S.counts[k] || 0) > 0));
  const chips = `<div style="display:flex;gap:8px;flex-wrap:wrap;margin:0 0 12px">` +
    `<button class="chip" data-act="toggleFolders">Folders</button>` +
    (S.folderFilter ? `<button class="chip" data-act="clearFolderFilter" style="background:var(--accent);color:#fff">${esc(S.folderFilter.split('/').pop())} ✕</button>` : '') +
    cats.map(k => `<button class="chip" data-act="cat" data-cat="${esc(k)}" style="${S.cat === k ? 'background:var(--accent);color:#fff' : ''}">${esc(k)}</button>`).join('') + `</div>`;
  const rows = list.slice(0, 600).map(fileRow).join('');
  const showFoldersHere = S.cat === 'All' && !S.q && !S.folderFilter;
  const folderRows = showFoldersHere
    ? (S.folders || []).slice(0, 8).map(d => `
      <div class="lrow" data-act="folderMenu" data-path="${esc(d.path)}">
        <div class="eicon fork">${IC.folder}</div>
        <div class="lmain">
          <div class="ltitle">${esc(d.name)}</div>
          <div class="lsub">${d.count} file${d.count === 1 ? '' : 's'} · ${esc(d.sizeText)}</div>
        </div>
        <span style="color:var(--text2)">${IC.more}</span>
      </div>`).join('')
    : '';

  return `
  <div class="searchbar">
    ${IC.search}
    <input id="fileQ" placeholder="Search files and the bin" value="${esc(S.q)}" autocomplete="off">
  </div>
  ${chips}
  <div style="display:flex;align-items:center;justify-content:space-between;margin:0 6px 10px;gap:8px">
    <span class="lsub">${list.length} files · ${fmtBytes(bytes)}</span>
    <span style="display:flex;gap:8px;align-items:center">
      <button class="chip" data-act="sort" data-sort="date" style="${S.sort === 'date' ? 'background:var(--accent);color:#fff' : ''}">Date</button>
      <button class="chip" data-act="sort" data-sort="size" style="${S.sort === 'size' ? 'background:var(--accent);color:#fff' : ''}">Size</button>
      <button class="chip" data-act="sort" data-sort="name" style="${S.sort === 'name' ? 'background:var(--accent);color:#fff' : ''}">Name</button>
    </span>
  </div>
  ${S.busy && !S.files.length ? `<div class="card">${spinner()}</div>` : ''}
  ${folderRows ? `<div class="sect" style="margin-top:6px">Folders</div><div class="card list">${folderRows}</div>` : ''}
  ${rows ? `<div class="sect" style="margin-top:${folderRows ? '22' : '6'}px">Files</div><div class="card list">${rows}</div>`
    : `<div class="card empty">No files here.<br>Try another category, or tap refresh.</div>`}`;
}

/* ------------------------------------------------------------ search ----- */
function viewSearch() {
  const sel = Object.keys(S.sel).filter(k => S.sel[k]).length;
  const allOn = S.matches.length > 0 && sel === S.matches.length;

  const matchRows = S.matches.map(m => {
    const [cls, ic] = CAT_ICON[m.cat] || CAT_ICON.Other;
    const on = !!S.sel[m.path];
    return `<div class="lrow" data-act="cleanToggle" data-path="${esc(m.path)}">
      <span style="width:24px;height:24px;border-radius:8px;display:flex;align-items:center;justify-content:center;flex-shrink:0;
        border:2px solid ${on ? 'var(--accent)' : 'var(--divider)'};background:${on ? 'var(--accent)' : 'transparent'};
        color:#fff;box-shadow:${on ? '0 0 12px var(--glow)' : 'none'}">${on ? IC.check : ''}</span>
      <div class="eicon ${cls}">${IC[ic]}</div>
      <div class="lmain">
        <div class="ltitle">${esc(m.name)}</div>
        <div class="lsub">${esc(m.sizeText)} · ${esc(m.dir || '')}${m.inBin ? ' · in bin' : ''}</div>
      </div>
      <span style="font-size:12px;font-weight:700;color:var(--accent);flex-shrink:0">${m.score}%</span>
    </div>`;
  }).join('');

  return `
  <div class="card">
    <div class="lrow" style="padding:0;border:none;cursor:default">
      <div class="eicon pr">${IC.search}</div>
      <div class="lmain"><div class="ltitle">Find a file by name</div>
        <div class="lsub" style="white-space:normal;line-height:1.45">Type a name or a few words. “gitly apk” also finds “Gitly account sync.apk”. Files already in the bin are searched too.</div></div>
    </div>
    <label class="fldlabel" for="cleanQ" style="margin-top:16px">Name or words</label>
    <input id="cleanQ" class="fld" placeholder="e.g. gitly apk" value="${esc(S.cleanQ)}" autocomplete="off">
    <div style="display:flex;align-items:center;justify-content:space-between;margin:16px 0 6px">
      <span class="fldlabel" style="margin:0">Match strength</span><span class="chip" style="background:var(--accent);color:#fff">${S.cleanT}%</span>
    </div>
    <input id="cleanT" type="range" min="40" max="100" step="5" value="${S.cleanT}" style="width:100%;accent-color:var(--accent)">
    <button class="btn primary btnblock" data-act="cleanFind" style="margin-top:16px" ${S.access ? '' : 'disabled'}>${IC.search}&nbsp; Find matches</button>
  </div>

  ${S.matches.length ? `
    <div style="display:flex;align-items:center;justify-content:space-between;margin:20px 6px 10px">
      <div class="sect" style="margin:0">${S.matches.length} matches</div>
      <button class="btn ghost sm" data-act="cleanAll" style="min-height:34px;padding:0 12px;font-size:13px">${allOn ? 'Deselect all' : 'Select all'}</button>
    </div>
    <div class="card list">${matchRows}</div>
    <button class="btn danger btnblock" data-act="cleanDelete" ${sel ? '' : 'disabled'}>${IC.trash}&nbsp; Move ${sel || ''} to bin</button>
    <div class="lsub" style="text-align:center;margin-top:12px">Deleted files go to the recycle bin and can be restored.</div>`
  : (S.searched ? `<div class="card empty">No matches.<br>Try fewer words or lower the match strength.</div>`
     : `<div class="card empty">${IC.search}<div class="ltitle" style="margin-top:10px">Search across storage and the bin</div><div class="lsub" style="white-space:normal">Matches are ranked by how closely the name fits, so different wordings of the same file all show up.</div></div>`)}`;
}

/* ----------------------------------------------------------- folders ----- */
function viewFolders() {
  const rows = (S.folders || []).slice(0, 300).map(d => `
    <div class="lrow" data-act="folderMenu" data-path="${esc(d.path)}">
      <div class="eicon fork">${IC.folder}</div>
      <div class="lmain">
        <div class="ltitle">${esc(d.name)}</div>
        <div class="lsub">${d.count} file${d.count === 1 ? '' : 's'} · ${esc(d.sizeText)} · ${esc(d.dir)}</div>
      </div>
      ${IC.chev}
    </div>`).join('');
  return `
  <div style="display:flex;gap:8px;flex-wrap:wrap;margin:0 0 12px">
    <button class="chip" data-act="toggleFolders" style="background:var(--accent);color:#fff">Folders</button>
  </div>
  <div style="display:flex;align-items:center;justify-content:space-between;margin:0 6px 10px">
    <span class="lsub">${(S.folders || []).length} folders</span>
    <button class="btn ghost sm" data-act="emptyFoldersAll" style="min-height:34px;padding:0 12px;font-size:13px">Remove empty</button>
  </div>
  ${rows ? `<div class="card list">${rows}</div>` : `<div class="card empty">No folders found.</div>`}`;
}

/* ------------------------------------------------------------- smart ----- */
function viewSmart() {
  const sug = S.sugg;

  const dupList = sug ? (sug.duplicates || []).map((g, gi) => `
      <div class="lrow" data-act="dupGroup" data-i="${gi}">
        <div class="eicon pr">${IC.copy}</div>
        <div class="lmain"><div class="ltitle">${g.files.length} identical files</div>
          <div class="lsub">${esc(g.sizeText)} each · ${esc(g.files[0].name)}</div></div>
        <span class="chip">${esc(g.sizeText)}</span>
      </div>`).join('') : '';

  const sameList = sug ? (sug.sameName || []).slice(0, 8).map((g, gi) => `
      <div class="lrow" data-act="sameGroup" data-i="${gi}">
        <div class="eicon fork">${IC.layers}</div>
        <div class="lmain"><div class="ltitle">${esc(g.name)}</div><div class="lsub">${g.files.length} copies in different folders</div></div>
        ${IC.chev}
      </div>`).join('') : '';

  return `
  <div class="card">
    <div class="lrow" style="padding:0;border:none;cursor:default">
      <div class="eicon star">${IC.layers}</div>
      <div class="lmain"><div class="ltitle">What FileFlow found</div>
        <div class="lsub" style="white-space:normal">Duplicates, shared names, big files, stale screenshots, leftover APKs and empty folders.</div></div>
      <button class="btn ghost sm" data-act="suggReload" style="min-height:34px;padding:0 12px;font-size:13px">${IC.scan}</button>
    </div>
    ${sug ? `<div class="bigstats">
      <div class="bstat"><b>${(sug.duplicates || []).length}</b><span class="lsub">duplicate sets</span></div>
      <div class="bstat"><b>${esc(sug.duplicateText || '0 B')}</b><span class="lsub">reclaimable</span></div>
      <div class="bstat"><b>${(sug.sameName || []).length}</b><span class="lsub">shared names</span></div>
    </div>` : `<div style="margin-top:16px">${spinner()}</div>`}
  </div>

  ${dupList ? `<div class="sect">Duplicates</div><div class="card list">${dupList}</div>` : ''}
  ${sameList ? `<div class="sect">Same name, different folder</div><div class="card list">${sameList}</div>` : ''}

  ${sug && (sug.large || []).length ? `<div class="sect">Biggest files</div><div class="card list">${sug.large.map(f => fileRow(f)).join('')}</div>` : ''}
  ${sug && (sug.screenshots || []).length ? `<div class="sect">Old screenshots</div><div class="card list">${sug.screenshots.slice(0, 8).map(f => fileRow(f)).join('')}</div>` : ''}
  ${sug && (sug.apks || []).length ? `<div class="sect">Leftover APKs</div><div class="card list">${sug.apks.slice(0, 8).map(f => fileRow(f)).join('')}</div>` : ''}
  ${sug && (sug.emptyFolders || []).length ? `<div style="display:flex;align-items:center;justify-content:space-between;margin:26px 6px 8px">
      <div class="sect" style="margin:0">Empty folders</div>
      <button class="btn ghost sm" data-act="emptyFoldersAll" style="min-height:34px;padding:0 12px;font-size:13px">Remove all</button>
    </div>
    <div class="card list">${sug.emptyFolders.slice(0, 12).map(d => `
      <div class="lrow" data-act="emptyFolderDel" data-path="${esc(d.path)}" data-name="${esc(d.name)}"><div class="eicon other">${IC.folder}</div>
      <div class="lmain"><div class="ltitle">${esc(d.name)}</div><div class="lsub" style="white-space:normal;word-break:break-all">${esc(d.path)}</div></div>
      <span style="color:var(--danger)">${IC.trash}</span></div>`).join('')}</div>` : ''}

  ${sug && !dupList && !sameList && !(sug.large || []).length && !(sug.apks || []).length
      ? `<div class="card empty">${IC.check}<div class="ltitle" style="margin-top:10px">Nothing needs attention</div><div class="lsub" style="white-space:normal">No duplicates, shared names or junk found.</div></div>` : ''}`;
}

/* --------------------------------------------------------------- bin ----- */
function viewBin() {
  if (!S.bin.length) return `<div class="card empty">${IC.trash}<div class="ltitle" style="margin-top:10px">Recycle bin is empty</div><div class="lsub" style="white-space:normal">Files you delete land here first, so nothing is lost by accident.</div></div>`;
  const total = S.bin.reduce((a, b) => a + (Number(b.size) || 0), 0);
  const rows = S.bin.map(b => {
    const [cls, ic] = CAT_ICON[b.cat] || CAT_ICON.Other;
    return `<div class="lrow" data-act="binMenu" data-id="${esc(b.id)}">
      <div class="eicon ${cls}">${IC[ic]}</div>
      <div class="lmain">
        <div class="ltitle">${esc(b.name)}</div>
        <div class="lsub">${esc(b.sizeText || fmtBytes(b.size))} · deleted ${esc(fmtDate(b.deletedAt))}</div>
      </div>
      <button class="iconbtn" data-act="binRestore" data-id="${esc(b.id)}" aria-label="Restore">${IC.restore}</button>
    </div>`;
  }).join('');
  return `
  <div style="display:flex;align-items:center;justify-content:space-between;margin:0 6px 10px">
    <span class="lsub">${S.bin.length} items · ${fmtBytes(total)}</span>
    <button class="btn ghost sm" data-act="binEmpty" style="min-height:34px;padding:0 12px;font-size:13px">Empty</button>
  </div>
  <div class="card list">${rows}</div>
  <button class="btn ghost btnblock" data-act="binRestoreAll">${IC.restore}&nbsp; Restore all</button>
  <div class="lsub" style="text-align:center;margin-top:12px">Tap an item to view it without restoring.</div>`;
}

/* ---------------------------------------------------------- settings ----- */
function viewSettings() {
  const seg = `<div class="seg" id="setSeg" style="margin-bottom:16px">
      <button class="segb ${S.setTab === 'credits' ? '' : 'on'}" data-act="setTab" data-st="general">Settings</button>
      <button class="segb ${S.setTab === 'credits' ? 'on' : ''}" data-act="setTab" data-st="credits">Credits</button>
    </div>`;
  if (S.setTab === 'credits') return seg + viewCredits();
  const sw = (on, id) => `<button class="switch ${on ? 'on' : ''}" data-act="tg" data-id="${id}"></button>`;
  const rules = S.prefs.rules || [];
  const accents = [['blue', '#1B6EF3'], ['purple', '#8B5CF6'], ['green', '#12B76A'], ['pink', '#EC4899'], ['amber', '#F59E0B'], ['teal', '#14B8A6'], ['red', '#EF4444'], ['indigo', '#6366F1']];
  const dots = accents.map(([a, c]) => `<button data-act="accent" data-a="${a}" aria-label="${a}"
    style="width:36px;height:36px;border-radius:50%;background:${c};border:3px solid ${S.prefs.accent === a ? 'var(--text)' : 'transparent'};box-shadow:${S.prefs.accent === a ? '0 0 16px ' + c + '6B' : 'none'}"></button>`).join('');
  const ruleRows = rules.length ? rules.map((r, i) => `<div class="lrow">
      <div class="eicon tag">${IC.tag}</div>
      <div class="lmain"><div class="ltitle">${esc(r.type === 'ext' ? '.' + r.match : r.match)}</div><div class="lsub">→ ${esc(r.folder)}</div></div>
      <button class="iconbtn" data-act="delRule" data-i="${i}" aria-label="Remove">${IC.close}</button>
    </div>`).join('') : `<div class="lrow" style="cursor:default"><div class="lmain"><div class="lsub">No rules yet — files sort by type.</div></div></div>`;

  return `
  ${seg}
  ${S.notifOn === false ? `<div class="card" style="margin-top:6px">
    <div class="lrow" style="padding:0;border:none;cursor:default">
      <div class="eicon tag">${IC.alert}</div>
      <div class="lmain"><div class="ltitle">Notifications are blocked</div>
        <div class="lsub" style="white-space:normal">FileFlow cannot show new-file alerts until you allow notifications for the app.</div></div>
    </div>
    <button class="btn primary btnblock" data-act="enableNotif" style="margin-top:14px">Allow notifications</button>
  </div>` : ''}

  <div class="sect" style="margin-top:6px">Automatic</div>
  <div class="card">
    <div class="setrow"><div class="eicon pr">${IC.move}</div><div class="lmain"><div class="ltitle">Auto organise</div><div class="lsub">Sort new files into folders on its own</div></div>${sw(S.prefs.autoOrganise, 'autoOrganise')}</div>
    <div class="setrow" style="margin-top:16px"><div class="eicon fork">${IC.folder}</div><div class="lmain"><div class="ltitle">Auto group</div><div class="lsub">Keep every category folder in sync</div></div>${sw(S.prefs.autoGroup, 'autoGroup')}</div>
    <div class="setrow" style="margin-top:16px"><div class="eicon star">${IC.zap}</div><div class="lmain"><div class="ltitle">Auto clean</div><div class="lsub">Delete fuzzy matches automatically</div></div>${sw(S.prefs.autoDelete, 'autoDelete')}</div>
    <div class="setrow" style="margin-top:16px"><div class="eicon other">${IC.folder}</div><div class="lmain"><div class="ltitle">Auto-remove empty folders</div><div class="lsub">Clear out folders that hold nothing</div></div>${sw(!!S.prefs.autoEmptyFolders, 'autoEmptyFolders')}</div>
  </div>

  <div class="card">
    <label class="fldlabel" for="autoQ">Auto clean words</label>
    <input id="autoQ" class="fld" placeholder="e.g. gitly apk" value="${esc(S.prefs.autoQuery || '')}" autocomplete="off">
    <div style="display:flex;align-items:center;justify-content:space-between;margin:16px 0 6px">
      <span class="fldlabel" style="margin:0">Match strength</span><span class="chip">${S.prefs.autoThreshold}%</span>
    </div>
    <input id="autoT" type="range" min="40" max="100" step="5" value="${S.prefs.autoThreshold}" style="width:100%;accent-color:var(--accent)">
    <div class="lsub" style="margin-top:10px;white-space:normal">Matches go to the recycle bin, never straight to deletion.</div>
  </div>

  <div class="sect">Timer presets</div>
  <div class="card list">${presetList().map((pr, i) => {
      const isDef = Number(S.defaultPresetMs) === pr.ms;
      return `<div class="lrow" style="cursor:default">
        <div class="eicon star">${IC.clock}</div>
        <div class="lmain"><div class="ltitle">${esc(pr.label)}</div><div class="lsub">${esc(fmtDur(pr.ms))}</div></div>
        ${isDef ? '<span class="chip" style="background:var(--accent);color:#fff">default</span>'
                : `<button class="chip" data-act="setDefault" data-ms="${pr.ms}">Default</button>`}
        <button class="iconbtn" data-act="delPreset" data-i="${i}" aria-label="Remove">${IC.close}</button>
      </div>`;
    }).join('')}</div>
  <button class="btn ghost btnblock" data-act="addPreset">${IC.plus}&nbsp; Add preset</button>
  <div class="lsub" style="margin-top:10px;white-space:normal">Presets appear in the timer sheet and the panel. With a default set, the notification offers a one-tap “Use default”.</div>

  <div class="sect">Folder rules</div>
  <div class="card list">${ruleRows}</div>
  <button class="btn ghost btnblock" data-act="addRule">${IC.plus}&nbsp; Add a rule</button>

  <div class="sect">Organise into</div>
  <div class="card list">
    <div class="lrow" style="cursor:default">
      <div class="eicon create">${IC.folder}</div>
      <div class="lmain"><div class="ltitle" style="white-space:normal;word-break:break-all">${esc((S.stats && S.stats.base) || 'FileFlow')}</div><div class="lsub">Every sorted file lands here</div></div>
    </div>
  </div>

  <div class="sect">Appearance</div>
  <div class="card">
    <div class="seg">
      <button class="segb ${S.prefs.theme === 'light' ? 'on' : ''}" data-act="theme" data-t="light">Light</button>
      <button class="segb ${S.prefs.theme === 'dark' ? 'on' : ''}" data-act="theme" data-t="dark">Dark</button>
      <button class="segb ${S.prefs.theme === 'pitch' ? 'on' : ''}" data-act="theme" data-t="pitch">Black</button>
    </div>
    <div class="fldlabel">Accent</div>
    <div style="display:flex;gap:12px;flex-wrap:wrap">${dots}</div>
    <div class="fldlabel" style="margin-top:18px">Custom accent</div>
    <div style="display:flex;gap:10px;align-items:center">
      <input id="hexInput" class="fld" placeholder="#1B6EF3" maxlength="7" value="${esc(S.prefs.customAccent || '')}" style="flex:1" autocomplete="off">
      <span class="swatch" style="background:${/^#[0-9a-fA-F]{6}$/.test(S.prefs.customAccent || '') ? esc(S.prefs.customAccent) : 'var(--chip)'}"></span>
    </div>
    <div class="btnrow" style="margin-top:10px">
      <button class="btn ghost" data-act="applyHex">Apply hex</button>
      <button class="btn ghost" data-act="resetHex">Reset</button>
    </div>
    <div class="setrow" style="margin-top:18px"><div class="eicon star">${IC.zap}</div><div class="lmain"><div class="ltitle">Glow</div><div class="lsub">Accent glows on buttons, nav and charts</div></div>${sw(S.prefs.glow !== false, 'glow')}</div>
    <div class="setrow" style="margin-top:14px"><div class="eicon other">${IC.sliders}</div><div class="lmain"><div class="ltitle">Nav glow</div><div class="lsub">Halo behind the floating nav bar</div></div>${sw(S.prefs.navglow, 'navglow')}</div>
    <div class="setrow" style="margin-top:14px"><div class="eicon user">${IC.info}</div><div class="lmain"><div class="ltitle">Notify me</div><div class="lsub">Tell me when automatic tidy-up runs</div></div>${sw(S.prefs.notify, 'notify')}</div>
    <div class="setrow" style="margin-top:14px"><div class="eicon pr">${IC.clock}</div><div class="lmain"><div class="ltitle">New file alerts</div><div class="lsub">${S.prefs.watchNew !== false ? 'Watching storage now — alerts arrive as files land' : 'Off — no new-file alerts'}</div></div>${sw(S.prefs.watchNew !== false, 'watchNew')}</div>
    <div class="setrow" style="margin-top:14px"><div class="eicon pr">${IC.eye}</div><div class="lmain"><div class="ltitle">Close the panel when I leave an app</div><div class="lsub">${S.usageAccess ? 'On — the panel closes as soon as you switch apps' : 'Needs Usage access — stops the panel hanging over your home screen'}</div></div>${S.usageAccess ? '<span class="chip">On</span>' : '<button class="btn ghost sm" data-act="allowUsage" style="min-height:34px;padding:0 12px;font-size:13px">Allow</button>'}</div>
    <div class="setrow" style="margin-top:14px"><div class="eicon fork">${IC.layers}</div><div class="lmain"><div class="ltitle">Timer panel over other apps</div><div class="lsub">${S.canOverlay === false ? 'Not allowed yet — tap Allow to enable' : 'The delete panel can slide up in any app'}</div></div>${S.canOverlay === false
        ? '<button class="btn ghost sm" data-act="allowOverlay" style="min-height:34px;padding:0 12px;font-size:13px">Allow</button>'
        : '<button class="btn ghost sm" data-act="testOverlay" style="min-height:34px;padding:0 12px;font-size:13px">Test</button>'}</div>
  </div>

  <div class="sect">Maintenance</div>
  <button class="btn ghost btnblock" data-act="checkNew">${IC.clock}&nbsp; Check for new files now</button>
  <button class="btn ghost btnblock" data-act="runAuto" style="margin-top:10px">${IC.zap}&nbsp; Run automatic now</button>
  <button class="btn ghost btnblock" data-act="clearActivity" style="margin-top:10px">Clear activity history</button>
  <div class="lsub" style="text-align:center;margin:18px 0 4px">FileFlow v${esc(sync('appVersion') || '1.0')} · everything stays on this device</div>`;
}

/* ------------------------------------------------------------ credits ---- */
function viewCredits() {
  const li = (txt, last) => `<li class="lsub" style="padding:7px 0 7px 18px;position:relative${last ? '' : ';border-bottom:1px solid var(--divider)'}">${esc(txt)}</li>`;
  return `
  <div class="card" style="text-align:center;padding:28px 20px">
    <div style="font-size:34px;font-weight:800;letter-spacing:-0.5px">FileFlow</div>
    <ul class="creditslist" style="list-style:none;text-align:left;margin:16px auto 0;padding:0;max-width:290px">
      ${li('Version ' + (sync('appVersion') || '1.0'))}
      ${li('Works entirely on your device')}
      ${li('Not affiliated with Google or Android', true)}
    </ul>
  </div>

  <h2 class="sect">Developer</h2>
  <div class="card" style="text-align:center">
    <img src="logo.jpg" alt="BonkerUnkil (Bonki)" style="width:96px;height:96px;border-radius:32px;margin:0 auto 16px;box-shadow:0 8px 28px var(--glow)">
    <div style="padding:8px 0;border-bottom:1px solid var(--divider)"><div class="ltitle">Name</div><div class="lsub" style="margin-top:2px">BonkerUnkil (Bonki)</div></div>
    <div style="padding:8px 0;border-bottom:1px solid var(--divider)"><div class="ltitle">Age</div><div class="lsub" style="margin-top:2px">20</div></div>
    <div style="padding:8px 0"><div class="ltitle">Profession</div><div class="lsub" style="margin-top:2px">Coding, problem solving, and more</div></div>
  </div>

  <h2 class="sect">Connect</h2>
  <div class="card">
    <button class="btn ghost btnblock" data-act="ext" data-url="https://t.me/BonkerUnkilBonki" style="display:flex;align-items:center;justify-content:flex-start;gap:12px">${IC.telegram}<span>Telegram · @BonkerUnkilBonki</span></button>
    <button class="btn ghost btnblock" data-act="ext" data-url="https://github.com/BonkerUnkilBonki" style="display:flex;align-items:center;justify-content:flex-start;gap:12px;margin-top:8px">${IC.github}<span>GitHub · @BonkerUnkilBonki</span></button>
  </div>

  <h2 class="sect">Languages used</h2>
  <div class="card"><div class="chips"><span class="chip">HTML</span><span class="chip">CSS</span><span class="chip">JavaScript</span><span class="chip">Java</span></div></div>`;
}

/* ------------------------------------------------------------- viewer ---- */
let VIEWER = null;
function openViewer(f) {
  VIEWER = f;
  const host = document.createElement('div');
  host.id = 'viewer';
  host.style.cssText = 'position:fixed;inset:0;z-index:90;background:var(--bg);display:flex;flex-direction:column';
  host.innerHTML = `
    <div style="display:flex;align-items:center;gap:10px;padding:14px 16px;background:var(--card);border-bottom:1px solid var(--divider)">
      <button class="iconbtn" data-act="closeViewer" aria-label="Close">${IC.close}</button>
      <div class="lmain"><div class="ltitle">${esc(f.name)}</div><div class="lsub">${esc(f.sizeText || '')}</div></div>
    </div>
    <div id="viewerBody" style="flex:1;overflow:auto;display:flex;align-items:center;justify-content:center;padding:12px">
      ${spinner()}
    </div>`;
  document.body.appendChild(host);
  document.body.style.overflow = 'hidden';
  renderViewer();
}
function closeViewer() {
  const h = $('#viewer'); if (h) h.remove();
  VIEWER = null; document.body.style.overflow = '';
}
async function renderViewer() {
  const f = VIEWER; if (!f) return;
  const body = $('#viewerBody'); if (!body) return;
  let info = null;
  try { info = await call('fileInfo', f.path); } catch (e) { }
  if (!VIEWER || VIEWER.path !== f.path) return;
  const kind = info ? info.kind : 'other';
  const uri = contentUri(f.path);
  let html = '';
  if (kind === 'image') html = `<img src="${uri}" alt="" style="max-width:100%;max-height:100%;border-radius:12px">`;
  else if (kind === 'video') html = `<video src="${uri}" controls autoplay playsinline style="max-width:100%;max-height:100%;border-radius:12px;background:#000"></video>`;
  else if (kind === 'audio') html = `<div style="text-align:center;width:100%"><div class="eicon star" style="width:96px;height:96px;margin:0 auto 20px">${IC.music}</div><audio src="${uri}" controls autoplay style="width:100%"></audio></div>`;
  else if (kind === 'text') {
    let txt = '';
    try { const r = await call('readText', f.path, 200000); txt = (r && r.text) || ''; } catch (e) { }
    if (!VIEWER || VIEWER.path !== f.path) return;
    html = `<pre style="width:100%;height:100%;margin:0;padding:16px;overflow:auto;background:var(--card);border-radius:16px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;line-height:1.55;white-space:pre-wrap;word-break:break-word">${esc(txt || 'Empty file')}</pre>`;
  } else {
    html = `<div style="text-align:center;padding:24px">
      <div class="eicon other" style="width:96px;height:96px;margin:0 auto 18px">${IC.file}</div>
      <div class="ltitle" style="white-space:normal">No built-in preview for this type</div>
      <div class="lsub" style="white-space:normal;margin:8px 0 18px">${esc(info ? info.mime : '')}</div>
      <button class="btn primary" data-act="openFile" data-path="${esc(f.path)}">Open with another app</button>
    </div>`;
  }
  body.innerHTML = html;
}

/* --------------------------------------------------------------- wire ---- */
function wireView() {
  const fq = $('#fileQ');
  if (fq) fq.addEventListener('input', () => {
    S.q = fq.value; const p = fq.selectionStart; render();
    const e = $('#fileQ'); if (e) { e.focus(); try { e.setSelectionRange(p, p); } catch (x) { } }
  });
  const cq = $('#cleanQ'); if (cq) cq.addEventListener('input', () => { S.cleanQ = cq.value; });
  const ct = $('#cleanT'); if (ct) ct.addEventListener('input', () => {
    S.cleanT = Number(ct.value);
    const b = ct.parentElement.querySelector('.chip'); if (b) b.textContent = S.cleanT + '%';
  });
  const aq = $('#autoQ'); if (aq) aq.addEventListener('change', () => { S.prefs.autoQuery = aq.value; savePrefs(); });
  const at = $('#autoT'); if (at) {
    at.addEventListener('input', () => { S.prefs.autoThreshold = Number(at.value); const b = at.parentElement.querySelector('.chip'); if (b) b.textContent = S.prefs.autoThreshold + '%'; });
    at.addEventListener('change', () => savePrefs());
  }
}

/* ------------------------------------------------------------ actions ---- */
const ACT = {
  settings: () => go('settings'),
  goSmart: () => go('smart'),
  goSearch: () => go('search'),
  goBin: () => go('bin'),
  access: () => sync('requestAccess'),
  closeSheet: () => closeSheet(),
  closeViewer: () => closeViewer(),

  applyHex() {
    const el = $('#hexInput');
    let v = (el ? el.value : '').trim();
    if (v && v[0] !== '#') v = '#' + v;
    if (!/^#[0-9a-fA-F]{6}$/.test(v)) return toast('Use a hex code like #1B6EF3');
    S.prefs.customAccent = v.toUpperCase();
    savePrefs(); applyTheme(); render();
    toast('Accent set to ' + S.prefs.customAccent);
  },
  resetHex() {
    S.prefs.customAccent = '';
    savePrefs(); applyTheme(); render();
    toast('Back to the preset accents');
  },

  addPreset() {
    S.newPreset = { label: '', d: 0, h: 1, m: 0 };
    presetSheet();
  },
  presetStep(el) {
    const u = el.dataset.u, dir = Number(el.dataset.dir);
    const n = S.newPreset;
    if (u === 'd') n.d = Math.max(0, Math.min(365, n.d + dir));
    else if (u === 'h') n.h = (n.h + dir + 24) % 24;
    else n.m = (n.m + dir + 60) % 60;
    const lab = $('#presetLabel');
    if (lab) n.label = lab.value;
    presetSheet();
  },
  presetSave() {
    const lab = $('#presetLabel');
    const label = (lab ? lab.value : '').trim() || fmtDur(presetMs(S.newPreset));
    const ms = presetMs(S.newPreset);
    if (ms < 60000) return toast('Pick at least one minute');
    S.presets = (S.presets || []).concat([{ label, ms }]).slice(0, 4);
    if (!S.defaultPresetMs) S.defaultPresetMs = ms;
    savePrefs(); closeSheet(); render();
    toast('Preset saved');
  },
  delPreset(el) {
    const i = Number(el.dataset.i);
    const list = presetList();
    if (list.length <= 1) return toast('Keep at least one preset');
    const gone = list[i];
    S.presets = list.filter((_, k) => k !== i);
    if (Number(S.defaultPresetMs) === gone.ms) S.defaultPresetMs = S.presets.length ? S.presets[0].ms : 0;
    savePrefs(); render();
  },
  setDefault(el) {
    S.defaultPresetMs = Number(el.dataset.ms);
    savePrefs(); render();
    toast('Default timer set');
  },

  customDelay(el) {
    S.cust = { d: 0, h: 0, m: 0 };
    customDelaySheet(el.dataset.path);
  },
  custStep(el) {
    const u = el.dataset.u, dir = Number(el.dataset.dir);
    if (u === 'd') S.cust.d = Math.max(0, Math.min(365, S.cust.d + dir));
    else if (u === 'h') S.cust.h = (S.cust.h + dir + 24) % 24;
    else S.cust.m = (S.cust.m + dir + 60) % 60;
    customDelaySheet(S.custPath);
  },
  async custSave() {
    const ms = custTotalMs();
    if (ms < 60000) return toast('Pick at least one minute');
    const path = S.custPath;
    closeSheet();
    try {
      await call('scheduleDelete', path, ms);
      toast('Permanently deletes in ' + custLabel());
      logAct('Auto-delete set for ' + String(path).split('/').pop());
      await loadScheduled();
    } catch (e) { toast('Could not set the timer'); }
    render();
  },
  async pickDelay(el) {
    const path = el.dataset.path, ms = Number(el.dataset.ms);
    closeSheet();
    try {
      await call('scheduleDelete', path, ms);
      const label = (DELAYS.filter(d => d[1] === ms)[0] || ['', ''])[0];
      toast('Permanently deletes in ' + label);
      logAct('Auto-delete set for ' + path.split('/').pop());
      await loadScheduled();
    } catch (e) { toast('Could not set the timer'); }
    render();
  },
  async cancelTimer(el) {
    const path = el.dataset.path;
    try { await call('cancelScheduled', path); toast('Timer cancelled'); } catch (e) { }
    await loadScheduled(); render();
  },
  async allowUsage() {
    try { sync('openUsageAccess'); } catch (e) { }
    setTimeout(() => {
      try { S.usageAccess = sync('hasUsageAccess') === true; } catch (e) { }
      render();
    }, 1500);
  },
  testOverlay() {
    try { sync('testOverlay'); toast('Panel should slide up over this app'); }
    catch (e) { toast('Could not open the panel'); }
  },
  async allowOverlay() {
    try { sync('requestOverlay'); } catch (e) { }
    setTimeout(() => {
      const co = sync('canOverlay');
      S.canOverlay = (co === false) ? false : true;
      if (S.canOverlay) toast('Panel can now appear over other apps');
      render();
    }, 1200);
  },
  async enableNotif() {
    try { sync('requestNotif'); } catch (e) { }
    setTimeout(() => {
      const nf = sync('notifEnabled');
      S.notifOn = (nf === false) ? false : true;
      if (S.notifOn) toast('Notifications allowed');
      render();
    }, 900);
  },
  async checkNew(el) {
    try { const r = await call('checkNewFiles'); toast(r.count ? r.count + ' new files found' : 'No new files'); }
    catch (e) { toast('Check failed'); }
    render();
  },

  pick(el) {
    const p = el.dataset.path;
    if (S.picked[p]) delete S.picked[p]; else S.picked[p] = true;
    if (!Object.keys(S.picked).length) { S.selectMode = false; renderNoAnim(); return; }
    /* repaint just this row and the bar: a full re-render of the list on every
       tap is what made the page look like it was refreshing */
    el.classList.toggle('picked', !!S.picked[p]);
    const box = el.querySelector('.pickbox');
    if (box) box.innerHTML = S.picked[p] ? IC.check : '';
    const n = Object.keys(S.picked).length;
    const t = $('#bigTitle'); if (t) t.textContent = n + ' selected';
    const a = $('#appbarTitle'); if (a) a.textContent = n + ' selected';
    renderSelBar();
  },
  selAll() {
    const rows = $$('#view .lrow[data-path]');
    const all = rows.length && rows.every(r => S.picked[r.dataset.path]);
    if (all) S.picked = {};
    else rows.forEach(r => { S.picked[r.dataset.path] = true; });
    if (!Object.keys(S.picked).length) S.selectMode = false;
    renderNoAnim();
  },
  selClose() { S.selectMode = false; S.picked = {}; renderNoAnim(); },
  selCopy() {
    const paths = Object.keys(S.picked);
    if (!paths.length) return;
    try { navigator.clipboard.writeText(paths.join('\n')); } catch (e) { }
    toast(paths.length + ' path' + (paths.length === 1 ? '' : 's') + ' copied');
    ACT.selClose();
  },
  selView() {
    const paths = Object.keys(S.picked);
    if (paths.length !== 1) return;
    const p = paths[0];
    const f = S.files.find(x => x.path === p) || (S.recent || []).find(x => x.path === p)
      || { name: p.split('/').pop(), path: p };
    ACT.selClose();
    openViewer(f);
  },
  async selDelete() {
    const paths = Object.keys(S.picked).filter(p => !S.binMap[p]);
    if (!paths.length) return toast('Nothing to delete');
    beginRefresh();
    try {
      const r = await call('toBin', JSON.stringify(paths));
      toast(`Moved ${r.ok} to bin · ${r.bytesText}`);
      logAct(`Moved ${r.ok} files to bin`);
      S.files = S.files.filter(f => paths.indexOf(f.path) === -1);
      S.recent = (S.recent || []).filter(f => paths.indexOf(f.path) === -1);
      S.selectMode = false; S.picked = {};
      await Promise.all([loadBin(true), refreshStats()]);
    } catch (e) { toast('Failed'); }
    endRefresh(); render();
  },

  async organise() {
    if (!S.access) return toast('Allow file access first');
    beginRefresh(); S.busy = true; toast('Organising…');
    try {
      const r = await call('organise', false);
      S.lastRun = { text: `Organised ${r.count} files into ${Object.keys(r.byFolder || {}).length} folders` };
      logAct(`Organised ${r.count} files`);
      toast(r.count ? `Organised ${r.count} files` : 'Everything is already tidy');
      await Promise.all([refreshStats(), scan(true), loadRecent()]);
    } catch (e) { toast('Could not organise'); }
    S.busy = false; endRefresh(); enterView(); render();
  },

  async rescan() { scan(); },
  cat(el) { S.cat = el.dataset.cat; S.folderFilter = ''; render(); },
  toggleFolders() {
    S.showFolders = !S.showFolders;
    if (S.showFolders && !(S.folders || []).length) { loadFolders().then(render); render(); return; }
    render();
  },
  clearFolderFilter() { S.folderFilter = ''; render(); },
  folderMenu(el) {
    const d = (S.folders || []).find(x => x.path === el.dataset.path) || { name: el.dataset.path.split('/').pop(), path: el.dataset.path, count: 0, sizeText: '' };
    openSheet(`
      <div class="sheethead" style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
        <div class="eicon fork">${IC.folder}</div>
        <div class="lmain"><div class="ltitle">${esc(d.name)}</div>
          <div class="lsub">${d.count} files · ${esc(d.sizeText)}</div></div>
      </div>
      <button class="btn primary btnblock" data-act="folderOpen" data-path="${esc(d.path)}">${IC.folder}&nbsp; Open folder</button>
      <button class="btn ghost btnblock" data-act="copyFolder" data-path="${esc(d.path)}" style="margin-top:8px">Copy path</button>
      <button class="btn danger btnblock" data-act="folderDelete" data-path="${esc(d.path)}" data-name="${esc(d.name)}" data-count="${d.count}" data-size="${esc(d.sizeText)}" style="margin-top:8px">${IC.trash}&nbsp; Delete folder</button>
      <div class="lsub" style="margin-top:16px;word-break:break-all;white-space:normal">${esc(d.path)}</div>`);
  },
  copyFolder(el) {
    try { navigator.clipboard.writeText(el.dataset.path); } catch (e) { }
    closeSheet();
  },
  folderOpen(el) {
    closeSheet();
    S.showFolders = false; S.folderFilter = el.dataset.path; S.cat = 'All'; S.q = '';
    render();
  },
  folderDelete(el) {
    const { path: p, name, count, size } = el.dataset;
    closeSheet();
    openSheet(`
      <div class="sheethead" style="margin-bottom:10px"><b style="font-size:20px;font-weight:700">Delete ${esc(name)}?</b></div>
      <div class="lsub" style="white-space:normal;line-height:1.5">This permanently removes the folder and the ${count || 0} file${String(count) === '1' ? '' : 's'} inside it${size ? ' (' + esc(size) + ')' : ''}. It does not go to the recycle bin.</div>
      <button class="btn ghost btnblock" data-act="closeSheet" style="margin-top:18px">Cancel</button>
      <button class="btn danger btnblock" id="confirmFolder" style="margin-top:10px">Delete folder</button>`);
    $('#confirmFolder').onclick = async () => {
      closeSheet(); beginRefresh();
      try {
        const r = await call('deleteFolder', p);
        toast(r.ok ? `Deleted ${r.files} files · ${r.bytesText}` : 'Could not delete');
        logAct('Deleted folder ' + name);
        await Promise.all([scan(true), loadFolders(), refreshStats()]);
      } catch (e) { toast('Could not delete'); }
      endRefresh(); render();
    };
  },
  emptyFoldersAll() {
    openSheet(`
      <div class="sheethead" style="margin-bottom:10px"><b style="font-size:20px;font-weight:700">Remove empty folders?</b></div>
      <div class="lsub" style="white-space:normal;line-height:1.5">Every folder with nothing inside it will be deleted. Folders that still hold files are left alone.</div>
      <button class="btn ghost btnblock" data-act="closeSheet" style="margin-top:18px">Cancel</button>
      <button class="btn danger btnblock" id="confirmEmpty" style="margin-top:10px">Remove all</button>`);
    $('#confirmEmpty').onclick = async () => {
      closeSheet(); beginRefresh();
      try { const r = await call('deleteEmptyFolders'); toast(`Removed ${r.ok} folders`); logAct(`Removed ${r.ok} empty folders`); }
      catch (e) { toast('Failed'); }
      await Promise.all([scan(true), loadFolders(), loadSuggestions()]);
      endRefresh(); render();
    };
  },
  emptyFolderDel(el) {
    const p = el.dataset.path, name = el.dataset.name || p.split('/').pop();
    openSheet(`
      <div class="sheethead" style="margin-bottom:10px"><b style="font-size:20px;font-weight:700">Delete ${esc(name)}?</b></div>
      <div class="lsub" style="white-space:normal;line-height:1.5">This empty folder will be removed.</div>
      <button class="btn ghost btnblock" data-act="closeSheet" style="margin-top:18px">Cancel</button>
      <button class="btn danger btnblock" id="confirmEF" style="margin-top:10px">Delete</button>`);
    $('#confirmEF').onclick = async () => {
      closeSheet();
      try { const r = await call('deleteFolder', p); toast(r.ok ? 'Folder removed' : 'Could not delete'); }
      catch (e) { toast('Failed'); }
      await Promise.all([loadSuggestions(), loadFolders()]);
      render();
    };
  },
  sort(el) { S.sort = el.dataset.sort; render(); },
  catgo(el) { S.cat = el.dataset.cat; go('files'); },

  filemenu(el) {
    const f = S.files.find(x => x.path === el.dataset.path) || (S.recent || []).find(x => x.path === el.dataset.path);
    if (!f) return;
    const [cls, ic] = CAT_ICON[f.cat] || CAT_ICON.Other;
    const binned = !!S.binMap[f.path];
    openSheet(`
      <div class="sheethead" style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
        <div class="eicon ${cls}">${IC[ic]}</div>
        <div class="lmain"><div class="ltitle">${esc(f.name)}</div><div class="lsub">${esc(f.sizeText)} · ${esc(f.dir || '')}</div></div>
      </div>
      ${binned ? `<div class="chip" style="background:var(--danger);color:#fff;margin-bottom:14px">This file is in the recycle bin</div>` : ''}
      <button class="btn primary btnblock" data-act="viewFile" data-path="${esc(f.path)}">${IC.eye}&nbsp; View in app</button>
      <button class="btn ghost btnblock" data-act="openFile" data-path="${esc(f.path)}" style="margin-top:10px">Open with another app</button>
      <button class="btn ghost btnblock" data-act="copyPath" data-path="${esc(f.path)}" style="margin-top:10px">Copy path</button>
      ${binned ? `<button class="btn ghost btnblock" data-act="restorePath" data-path="${esc(f.path)}" style="margin-top:10px">Restore from bin</button>`
        : `<button class="btn danger btnblock" data-act="delFile" data-path="${esc(f.path)}" style="margin-top:10px">Move to bin</button>`}
      <div class="lsub" style="margin-top:16px;word-break:break-all;white-space:normal">${esc(f.path)}</div>`);
  },
  viewFile(el) { const f = S.files.find(x => x.path === el.dataset.path) || (S.recent || []).find(x => x.path === el.dataset.path) || { name: el.dataset.path.split('/').pop(), path: el.dataset.path }; closeSheet(); openViewer(f); },
  openFile(el) { sync('openFile', el.dataset.path); closeSheet(); },
  copyPath(el) { try { navigator.clipboard.writeText(el.dataset.path); } catch (e) { } toast('Path copied'); closeSheet(); },
  async delFile(el) {
    const path = el.dataset.path; closeSheet(); beginRefresh();
    try {
      const r = await call('toBin', JSON.stringify([path]));
      toast(r.ok ? 'Moved to bin' : 'Could not move');
      logAct('Moved ' + path.split('/').pop() + ' to bin');
      S.files = S.files.filter(f => f.path !== path);
      await Promise.all([loadBin(true), loadRecent()]);
    } catch (e) { toast('Failed'); }
    endRefresh(); render();
  },
  async restorePath(el) {
    const id = S.binMap[el.dataset.path]; closeSheet();
    if (!id) return toast('Not in bin');
    try { const r = await call('restore', JSON.stringify([id])); toast(r.ok ? 'Restored' : 'Could not restore'); logAct('Restored a file'); }
    catch (e) { toast('Failed'); }
    loadBin(); refreshStats();
  },

  async suggReload() {
    beginRefresh(); S.sugg = null; render();
    try { S.sugg = await call('suggestions'); } catch (e) { toast('Could not scan'); }
    endRefresh(); enterView(); render();
  },
  dupGroup(el) {
    const g = (S.sugg && S.sugg.duplicates || [])[Number(el.dataset.i)];
    if (!g) return;
    openSheet(`
      <div class="sheethead" style="margin-bottom:12px"><b style="font-size:20px;font-weight:700">${g.files.length} identical files</b></div>
      <div class="lsub" style="white-space:normal;margin-bottom:14px">${esc(g.sizeText)} each. Keep the first and the rest can be removed — about ${esc(g.sizeText)} × ${g.files.length - 1} is reclaimable.</div>
      <div class="card list" style="margin:0">${g.files.map((f, i) => `
        <div class="lrow" style="cursor:default">
          <div class="eicon ${i === 0 ? 'create' : 'pr'}">${i === 0 ? IC.check : IC.copy}</div>
          <div class="lmain"><div class="ltitle">${esc(f.name)}</div><div class="lsub" style="white-space:normal;word-break:break-all">${esc(f.dir)}</div></div>
          ${i === 0 ? '<span class="chip">keep</span>' : ''}
        </div>`).join('')}</div>
      <button class="btn danger btnblock" data-act="dupDelete" data-i="${el.dataset.i}" style="margin-top:16px">Move the ${g.files.length - 1} copies to bin</button>`);
  },
  async dupDelete(el) {
    const g = (S.sugg && S.sugg.duplicates || [])[Number(el.dataset.i)];
    if (!g) return;
    const paths = g.files.slice(1).map(f => f.path);
    closeSheet(); beginRefresh();
    try {
      const r = await call('toBin', JSON.stringify(paths));
      toast(`Moved ${r.ok} duplicates to bin`);
      logAct(`Removed ${r.ok} duplicates`);
      S.sugg = await call('suggestions');
      await Promise.all([loadBin(true), refreshStats()]);
    } catch (e) { toast('Failed'); }
    endRefresh(); render();
  },
  sameGroup(el) {
    const g = (S.sugg && S.sugg.sameName || [])[Number(el.dataset.i)];
    if (!g) return;
    openSheet(`
      <div class="sheethead" style="margin-bottom:12px"><b style="font-size:20px;font-weight:700">${esc(g.name)}</b></div>
      <div class="lsub" style="white-space:normal;margin-bottom:14px">${g.files.length} files share this name. These are different files, so check before deleting.</div>
      <div class="card list" style="margin:0">${g.files.map(f => `
        <div class="lrow" data-act="viewFile" data-path="${esc(f.path)}">
          <div class="eicon other">${IC.file}</div>
          <div class="lmain"><div class="ltitle">${esc(f.sizeText)}</div><div class="lsub" style="white-space:normal;word-break:break-all">${esc(f.dir)}</div></div>
          ${IC.chev}
        </div>`).join('')}</div>`);
  },

  async cleanFind() {
    if (!S.cleanQ.trim()) return toast('Type a name or some words first');
    beginRefresh(); S.busy = true; S.searched = true; render();
    try {
      S.matches = await call('searchAll', S.cleanQ, S.cleanT, true);
      S.sel = {}; S.matches.forEach(m => { if (!m.inBin) S.sel[m.path] = true; });
      if (!S.matches.length) toast('No matches');
    } catch (e) { toast('Search failed'); }
    S.busy = false; endRefresh(); enterView(); render();
  },
  cleanToggle(el) { const p = el.dataset.path; S.sel[p] = !S.sel[p]; render(); },
  cleanAll() {
    const sel = Object.keys(S.sel).filter(k => S.sel[k]).length;
    if (sel === S.matches.length) S.sel = {}; else S.matches.forEach(m => { if (!m.inBin) S.sel[m.path] = true; });
    render();
  },
  async cleanDelete() {
    const paths = Object.keys(S.sel).filter(k => S.sel[k]);
    if (!paths.length) return;
    beginRefresh();
    try {
      const r = await call('toBin', JSON.stringify(paths));
      toast(`Moved ${r.ok} to bin · ${r.bytesText}`);
      logAct(`Cleaned ${r.ok} matching files`);
      S.matches = S.matches.filter(m => !S.sel[m.path]); S.sel = {};
      await Promise.all([loadBin(true), refreshStats()]);
    } catch (e) { toast('Failed'); }
    endRefresh(); render();
  },

  binMenu(el) {
    const b = S.bin.find(x => x.id === el.dataset.id); if (!b) return;
    const [cls, ic] = CAT_ICON[b.cat] || CAT_ICON.Other;
    openSheet(`
      <div class="sheethead" style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
        <div class="eicon ${cls}">${IC[ic]}</div>
        <div class="lmain"><div class="ltitle">${esc(b.name)}</div><div class="lsub">${esc(b.sizeText)} · deleted ${esc(fmtDate(b.deletedAt))}</div></div>
      </div>
      <button class="btn primary btnblock" data-act="binView" data-id="${esc(b.id)}">${IC.eye}&nbsp; View without restoring</button>
      <button class="btn ghost btnblock" data-act="binRestore" data-id="${esc(b.id)}" style="margin-top:10px">${IC.restore}&nbsp; Restore</button>
      <div class="lsub" style="margin-top:16px;word-break:break-all;white-space:normal">${esc(b.origPath)}</div>`);
  },
  binView(el) {
    const b = S.bin.find(x => x.id === el.dataset.id); if (!b) return;
    closeSheet();
    openViewer({ name: b.name, path: b.binPath, sizeText: b.sizeText, cat: b.cat });
  },
  async binRestore(el) {
    closeSheet();
    try { const r = await call('restore', JSON.stringify([el.dataset.id])); toast(r.ok ? 'Restored' : 'Could not restore'); logAct('Restored ' + (r.ok ? 'a file' : 'nothing')); } catch (e) { toast('Failed'); }
    loadBin(); refreshStats();
  },
  async binRestoreAll() {
    const ids = S.bin.map(b => b.id); if (!ids.length) return;
    try { const r = await call('restore', JSON.stringify(ids)); toast(`Restored ${r.ok} files`); logAct(`Restored ${r.ok} files`); } catch (e) { toast('Failed'); }
    loadBin(); refreshStats();
  },
  binEmpty() {
    openSheet(`<div class="sheethead" style="margin-bottom:12px"><b style="font-size:20px;font-weight:700">Empty the recycle bin?</b></div>
      <div class="lsub" style="white-space:normal;line-height:1.5">This permanently deletes everything in the bin. Files cannot be recovered afterwards.</div>
      <button class="btn ghost btnblock" data-act="closeSheet" style="margin-top:18px">Cancel</button>
      <button class="btn danger btnblock" id="confirmOk" style="margin-top:10px">Empty bin</button>`);
    $('#confirmOk').onclick = async () => {
      closeSheet(); beginRefresh();
      try { const r = await call('emptyBin'); toast(`Deleted ${r.ok} files`); logAct(`Emptied bin (${r.ok})`); } catch (e) { toast('Failed'); }
      loadBin(); refreshStats(); endRefresh();
    };
  },

  setTab(el) { S.setTab = el.dataset.st; try { $('#scroller').scrollTop = 0; } catch (e) { } render(); },
  ext(el) {
    const url = el.dataset.url;
    if (!url) return;
    try { location.href = url; } catch (e) { }
  },
  theme(el) { S.prefs.theme = el.dataset.t; savePrefs(); applyTheme(); render(); },
  accent(el) { S.prefs.accent = el.dataset.a; savePrefs(); applyTheme(); render(); },
  tg(el) {
    const k = el.dataset.id;
    if (k === 'glow') S.prefs.glow = S.prefs.glow === false;
    else S.prefs[k] = !S.prefs[k];
    savePrefs(); applyTheme(); render();
    if (k === 'autoOrganise' || k === 'autoGroup' || k === 'autoDelete') toast(S.prefs[k] ? 'Automatic action on' : 'Automatic action off');
  },
  async runAuto() {
    beginRefresh();
    try {
      const r = await call('runAuto');
      toast(`Organised ${r.organised || 0} · cleaned ${r.deleted || 0}`);
      logAct(`Automatic run: ${r.organised || 0} organised, ${r.deleted || 0} cleaned`);
      await Promise.all([refreshStats(), scan(true), loadBin(true), loadRecent()]);
    } catch (e) { toast('Failed'); }
    endRefresh(); enterView(); render();
  },
  clearActivity() { S.activity = []; try { localStorage.removeItem('ff.activity'); } catch (e) { } toast('Activity cleared'); render(); },

  addRule() { ruleSheet(); },
  delRule(el) { S.prefs.rules.splice(Number(el.dataset.i), 1); savePrefs(); render(); },
};

function ruleSheet() {
  openSheet(`
    <div class="sheethead" style="margin-bottom:16px"><b style="font-size:20px;font-weight:700">New folder rule</b></div>
    <div class="seg" id="ruleType">
      <button class="segb on" data-type="ext">By extension</button>
      <button class="segb" data-type="kw">By word</button>
    </div>
    <label class="fldlabel" id="ruleMatchLabel">Extension (e.g. pdf)</label>
    <input id="ruleMatch" class="fld" placeholder="pdf" autocomplete="off">
    <label class="fldlabel" style="margin-top:16px">Folder name</label>
    <input id="ruleFolder" class="fld" placeholder="PDFs" autocomplete="off">
    <div class="lsub" style="margin-top:12px;white-space:normal">Matching files go to this folder inside your organise location. Rules run before the built-in type folders.</div>
    <button class="btn primary btnblock" id="ruleSave" style="margin-top:18px">Save rule</button>`);
  let type = 'ext';
  $$('#ruleType .segb').forEach(b => b.onclick = () => {
    type = b.dataset.type;
    $$('#ruleType .segb').forEach(x => x.classList.toggle('on', x === b));
    $('#ruleMatchLabel').textContent = type === 'ext' ? 'Extension (e.g. pdf)' : 'Word in the name';
    $('#ruleMatch').placeholder = type === 'ext' ? 'pdf' : 'invoice';
  });
  $('#ruleSave').onclick = () => {
    const match = $('#ruleMatch').value.trim(), folder = $('#ruleFolder').value.trim();
    if (!match || !folder) return toast('Fill in both fields');
    S.prefs.rules = (S.prefs.rules || []).concat([{ type, match, folder }]);
    savePrefs(); closeSheet(); render(); toast('Rule added');
  };
}

/* --------------------------------------------------------------- data ---- */
function savePrefs() {
  sync('setPrefs', JSON.stringify({
    rules: S.prefs.rules, autoOrganise: S.prefs.autoOrganise, autoGroup: S.prefs.autoGroup,
    autoDelete: S.prefs.autoDelete, autoQuery: S.prefs.autoQuery, autoThreshold: S.prefs.autoThreshold,
    notify: S.prefs.notify, theme: S.prefs.theme, accent: S.prefs.accent,
    watchNew: S.prefs.watchNew !== false, autoDeletePermanent: !!S.prefs.autoDeletePermanent,
    autoEmptyFolders: !!S.prefs.autoEmptyFolders,
    customAccent: S.prefs.customAccent || '',
    timerPresets: JSON.stringify(S.presets || []),
    defaultPresetMs: Number(S.defaultPresetMs) || 0,
  }));
}
async function refreshStats() {
  try { S.stats = await call('stats'); S.counts = await call('counts', 4000); } catch (e) { }
}
async function loadRecent() {
  try { S.recent = await call('recent', 14, 12); } catch (e) { }
}
async function loadSuggestions() {
  try { S.sugg = await call('suggestions'); } catch (e) { }
}
async function scan(quiet) {
  if (!S.access) { S.filesLoaded = true; return render(); }
  if (!quiet) { beginRefresh(); S.busy = true; render(); }
  try {
    S.files = await call('scan', 4000);
    S.counts = await call('counts', 4000);
    S.filesLoaded = true;
  } catch (e) { toast('Scan failed'); }
  S.busy = false;
  if (!quiet) endRefresh();
  enterView(); render();
}
async function loadFolders() {
  try { S.folders = await call('folders'); } catch (e) { S.folders = []; }
}
async function loadScheduled() {
  try { S.scheduled = await call('listScheduled'); } catch (e) { S.scheduled = []; }
}
async function loadBin(quiet) {
  try {
    S.bin = await call('listBin');
    S.binMap = await call('binPaths');
  } catch (e) { }
  if (!quiet) render();
}

/* --------------------------------------------------------------- boot ---- */
function go(tab) {
  S.tab = tab;
  if (S.selectMode) { S.selectMode = false; S.picked = {}; try { const b = $('#selbar'); if (b) b.remove(); } catch (e) { } }
  try { const sc = $('#scroller'); if (sc) sc.scrollTop = 0; } catch (e) { }
  try { navShow(); } catch (e) { }
  if (tab === 'files') { loadFolders().then(render); if (!S.filesLoaded) return scan(); }
  if (tab === 'bin') return loadBin();
  if (tab === 'smart' && !S.sugg) { enterView(); render(); return loadSuggestions().then(() => render()); }
  enterView(); render();
}

/* ---- long-press to start selecting ---- */
let pressT = null, longAt = 0;
function cancelPress() { if (pressT) { clearTimeout(pressT); pressT = null; } }
document.addEventListener('pointerdown', (e) => {
  if (S.selectMode) return;
  const row = e.target.closest('.lrow[data-act="filemenu"]');
  if (!row) return;
  cancelPress();
  pressT = setTimeout(() => {
    pressT = null; longAt = Date.now();
    S.selectMode = true; S.picked = {}; S.picked[row.dataset.path] = true;
    try { if (navigator.vibrate) navigator.vibrate(12); } catch (x) { }
    renderNoAnim();
  }, 420);
}, true);
document.addEventListener('pointerup', cancelPress, true);
document.addEventListener('pointercancel', cancelPress, true);
document.addEventListener('pointermove', (e) => { if (pressT) cancelPress(); }, true);
document.addEventListener('contextmenu', (e) => { if (e.target.closest('.lrow')) e.preventDefault(); });

document.addEventListener('click', (e) => {
  if (Date.now() - longAt < 700) { e.preventDefault(); e.stopPropagation(); return; }
  const nav = e.target.closest('#navbar .navbtn');
  if (nav) { go(nav.dataset.go); return; }
  const g = e.target.closest('[data-go]');
  if (g && !e.target.closest('#navbar')) { go(g.dataset.go); return; }
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const lab = $('#presetLabel');
  if (lab && el.dataset.act === 'presetStep') S.newPreset.label = lab.value;
  const fn = ACT[el.dataset.act];
  if (fn) { e.preventDefault(); fn(el); }
});

$('#refreshBig').onclick = () => { scan(); loadSuggestions().then(render); loadRecent().then(render); };
$('#refreshApp').onclick = () => { scan(); loadSuggestions().then(render); };

window.ffBack = function () {
  if ($('#viewer')) { closeViewer(); return true; }
  if (!$('#sheet').hidden) { closeSheet(); return true; }
  if (S.tab !== 'home') { go('home'); return true; }
  return false;
};

async function boot() {
  const p = sync('getPrefs');
  if (p) { try { Object.assign(S.prefs, JSON.parse(p)); } catch (e) { } }
  loadActivity();
  try {
    const raw = S.prefs.timerPresets;
    S.presets = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(S.presets)) S.presets = [];
  } catch (e) { S.presets = []; }
  S.defaultPresetMs = Number(S.prefs.defaultPresetMs) || 0;
  S.cleanQ = S.prefs.autoQuery || '';
  S.cleanT = S.prefs.autoThreshold || 75;
  applyTheme();
  wireScroll();
  S.access = !!sync('hasAccess');
  try { const nf = sync('notifEnabled'); S.notifOn = (nf === false) ? false : true; } catch (e) { }
  try { const co = sync('canOverlay'); S.canOverlay = (co === false) ? false : true; } catch (e) { }
  try { S.usageAccess = sync('hasUsageAccess') === true; } catch (e) { }
  enterView();
  render();
  loadScheduled().then(() => render());
  try {
    const pend = sync('takePendingAutoDelete');
    if (pend) setTimeout(() => autoDeletePrompt(pend), 500);
  } catch (e) { }
  if (S.access) {
    await refreshStats(); render();
    await loadBin(true);
    await loadRecent();
    render();
    scan(true);
    loadSuggestions().then(() => render());
    if (S.prefs.autoOrganise || S.prefs.autoGroup || S.prefs.autoDelete) {
      try {
        const r = await call('runAuto');
        if (r.organised || r.deleted) {
          S.lastRun = { text: `Tidied ${r.organised || 0} organised · ${r.deleted || 0} cleaned` };
          logAct(`Automatic run: ${r.organised || 0} organised, ${r.deleted || 0} cleaned`);
          await Promise.all([refreshStats(), loadBin(true), loadRecent()]);
        }
      } catch (e) { }
      render();
    }
  }
}
window.ffAccessChanged = function () {
  const now = !!sync('hasAccess');
  if (now && !S.access) { S.access = true; boot(); }
};

document.addEventListener('DOMContentLoaded', boot);
if (document.readyState !== 'loading') boot();
