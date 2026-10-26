'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const enc = new TextEncoder();
const hasNFC = 'NDEFReader' in window;

/* ---------- 小工具 ---------- */

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function toBytes(data) {
  if (data == null) return new Uint8Array(0);
  if (typeof data === 'string') return enc.encode(data);
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
}
function hex(bytes) {
  return [...bytes].map(b => b.toString(16).padStart(2, '0')).join(' ');
}
function fromHex(s) {
  const clean = s.replace(/[^0-9a-f]/gi, '');
  const out = new Uint8Array(clean.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(i * 2, 2), 16);
  return out;
}
function decodeText(data, encoding = 'utf-8') {
  try { return new TextDecoder(encoding).decode(data); } catch { return new TextDecoder().decode(data); }
}
function timeStr(t) {
  const d = new Date(t);
  const p = n => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
function store(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* 無痕模式等 */ }
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

let toastTimer;
function toast(msg, isErr = false) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.toggle('err', isErr);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, isErr ? 5000 : 2600);
}
function buzz(pattern = 80) { try { navigator.vibrate?.(pattern); } catch { /* ignore */ } }

/* ---------- 錯誤訊息 ---------- */

function errMsg(e, ctx = '') {
  const name = e?.name || '';
  if (name === 'AbortError') return '已取消';
  if (name === 'NotAllowedError') {
    return ctx === 'protect'
      ? '標籤已有資料（已勾選不覆寫），或沒有允許 NFC 權限'
      : '沒有 NFC 權限，或標籤已鎖定無法寫入';
  }
  if (name === 'NotSupportedError') return '這張標籤不支援 NDEF，或手機不支援這個操作';
  if (name === 'NotReadableError') return '讀不到 NFC：請確認手機的 NFC 已開啟';
  if (name === 'NetworkError') return '傳輸中斷：標籤移開太快，請貼穩再試一次';
  if (name === 'InvalidStateError') return 'NFC 正在進行其他操作，請稍後再試';
  if (name === 'TypeError' || name === 'SyntaxError') return '內容格式錯誤：' + (e.message || '');
  return (e?.message || String(e)) || '發生未知錯誤';
}

/* ---------- 記錄類型（寫入用） ---------- */

const URI_PREFIXES = ['', 'http://www.', 'https://www.', 'http://', 'https://', 'tel:', 'mailto:',
  'ftp://anonymous:anonymous@', 'ftp://ftp.', 'ftps://', 'sftp://', 'smb://', 'nfs://', 'ftp://', 'dav://',
  'news:', 'telnet://', 'imap:', 'rtsp://', 'urn:', 'pop:', 'sip:', 'sips:', 'tftp:', 'btspp://',
  'btl2cap://', 'btgoep://', 'tcpobex://', 'irdaobex://', 'file://', 'urn:epc:id:', 'urn:epc:tag:',
  'urn:epc:pat:', 'urn:epc:raw:', 'urn:epc:', 'urn:nfc:'];

function normalizeUrl(u) {
  u = u.trim();
  if (!u) return '';
  if (!/^[a-z][a-z0-9+.-]*:/i.test(u)) u = 'https://' + u;
  return u;
}
function igHandle(v) {
  v = v.trim();
  const m = v.match(/instagram\.com\/([^/?#]+)/i);
  if (m) v = m[1];
  return v.replace(/^@/, '').replace(/\/+$/, '');
}
function vEsc(s) { return s.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/([,;])/g, '\\$1'); }

// Wi-Fi Simple Config（Android 碰一下就連線）
const WIFI_AUTH = { wpa2: 0x0020, mixed: 0x0022, wpa: 0x0002, open: 0x0001 };
function wifiPayload(ssid, auth, key) {
  const parts = [];
  const tlv = (type, bytes) => {
    const out = new Uint8Array(4 + bytes.length);
    out[0] = type >> 8; out[1] = type & 255; out[2] = bytes.length >> 8; out[3] = bytes.length & 255;
    out.set(bytes, 4);
    parts.push(out);
  };
  const u16 = n => new Uint8Array([n >> 8, n & 255]);
  const open = auth === 'open';
  tlv(0x1026, new Uint8Array([1]));                    // Network Index
  tlv(0x1045, enc.encode(ssid));                       // SSID
  tlv(0x1003, u16(WIFI_AUTH[auth] || 0x0020));         // Authentication Type
  tlv(0x100F, u16(open ? 0x0001 : 0x0008));            // Encryption Type (None / AES)
  tlv(0x1027, enc.encode(open ? '' : key));            // Network Key
  tlv(0x1020, new Uint8Array([255, 255, 255, 255, 255, 255])); // MAC（廣播）
  const inner = concat(parts);
  const cred = new Uint8Array(4 + inner.length);
  cred[0] = 0x10; cred[1] = 0x0E; cred[2] = inner.length >> 8; cred[3] = inner.length & 255;
  cred.set(inner, 4);
  return cred;
}
function concat(arrs) {
  const out = new Uint8Array(arrs.reduce((n, a) => n + a.length, 0));
  let o = 0;
  for (const a of arrs) { out.set(a, o); o += a.length; }
  return out;
}
function parseWifi(bytes) {
  const res = {};
  const walk = (b) => {
    let i = 0;
    while (i + 4 <= b.length) {
      const type = (b[i] << 8) | b[i + 1];
      const len = (b[i + 2] << 8) | b[i + 3];
      const v = b.subarray(i + 4, i + 4 + len);
      if (type === 0x100E) walk(v);
      else if (type === 0x1045) res.ssid = decodeText(v);
      else if (type === 0x1027) res.key = decodeText(v);
      else if (type === 0x1003) {
        const a = (v[0] << 8) | v[1];
        res.auth = Object.keys(WIFI_AUTH).find(k => WIFI_AUTH[k] === a) || 'wpa2';
      }
      i += 4 + len;
    }
  };
  walk(bytes);
  return res;
}

const TYPES = {
  url: {
    label: '網址',
    fields: [{ k: 'url', label: '網址', type: 'url', ph: 'https://example.com' }],
    build: v => ({ recordType: 'url', data: normalizeUrl(v.url) }),
    check: v => v.url.trim() ? '' : '請輸入網址',
  },
  ig: {
    label: 'Instagram 帳號',
    fields: [{ k: 'handle', label: 'IG 帳號（可貼整個網址）', type: 'text', ph: '@your_club' }],
    build: v => ({ recordType: 'url', data: `https://www.instagram.com/${igHandle(v.handle)}/` }),
    check: v => igHandle(v.handle) ? '' : '請輸入 IG 帳號',
  },
  text: {
    label: '文字',
    fields: [
      { k: 'text', label: '文字內容', type: 'textarea', ph: '想放的文字' },
      { k: 'lang', label: '語言', type: 'select', options: [['zh-TW', '中文'], ['en', 'English'], ['ja', '日本語']], def: 'zh-TW' },
    ],
    build: v => ({ recordType: 'text', data: v.text, lang: v.lang || 'zh-TW', encoding: 'utf-8' }),
    check: v => v.text ? '' : '請輸入文字',
  },
  tel: {
    label: '電話',
    fields: [{ k: 'tel', label: '電話號碼', type: 'tel', ph: '0912345678 或 +886912345678' }],
    build: v => ({ recordType: 'url', data: 'tel:' + v.tel.replace(/[\s-]/g, '') }),
    check: v => v.tel.trim() ? '' : '請輸入電話',
  },
  sms: {
    label: '簡訊',
    fields: [
      { k: 'to', label: '收件號碼', type: 'tel', ph: '0912345678' },
      { k: 'body', label: '預設內容（可空白）', type: 'textarea', ph: '' },
    ],
    build: v => ({ recordType: 'url', data: 'sms:' + v.to.replace(/[\s-]/g, '') + (v.body ? '?body=' + encodeURIComponent(v.body) : '') }),
    check: v => v.to.trim() ? '' : '請輸入號碼',
  },
  email: {
    label: 'Email',
    fields: [
      { k: 'to', label: '收件人', type: 'email', ph: 'name@example.com' },
      { k: 'subject', label: '主旨（可空白）', type: 'text' },
      { k: 'body', label: '內容（可空白）', type: 'textarea' },
    ],
    build: v => {
      const q = [];
      if (v.subject) q.push('subject=' + encodeURIComponent(v.subject));
      if (v.body) q.push('body=' + encodeURIComponent(v.body));
      return { recordType: 'url', data: 'mailto:' + v.to.trim() + (q.length ? '?' + q.join('&') : '') };
    },
    check: v => v.to.trim() ? '' : '請輸入收件人',
  },
  wifi: {
    label: 'Wi‑Fi 連線',
    fields: [
      { k: 'ssid', label: '網路名稱（SSID）', type: 'text' },
      { k: 'auth', label: '加密方式', type: 'select', options: [['wpa2', 'WPA2'], ['mixed', 'WPA/WPA2'], ['wpa', 'WPA'], ['open', '不加密（開放）']], def: 'wpa2' },
      { k: 'key', label: '密碼', type: 'text' },
    ],
    build: v => ({ recordType: 'mime', mediaType: 'application/vnd.wfa.wsc', data: wifiPayload(v.ssid, v.auth || 'wpa2', v.key || '') }),
    check: v => !v.ssid ? '請輸入網路名稱' : (v.auth !== 'open' && (v.key || '').length < 8 ? '密碼至少 8 個字元' : ''),
    note: 'Android 碰一下就會詢問是否連線；iPhone 不支援 Wi‑Fi 標籤。',
  },
  vcard: {
    label: '名片（vCard）',
    fields: [
      { k: 'name', label: '姓名', type: 'text' },
      { k: 'tel', label: '電話', type: 'tel' },
      { k: 'email', label: 'Email', type: 'email' },
      { k: 'org', label: '公司／社團', type: 'text' },
      { k: 'url', label: '網址', type: 'url' },
    ],
    build: v => {
      const L = ['BEGIN:VCARD', 'VERSION:3.0', 'N:' + vEsc(v.name || '') + ';;;;', 'FN:' + vEsc(v.name || '')];
      if (v.tel) L.push('TEL;TYPE=CELL:' + v.tel.trim());
      if (v.email) L.push('EMAIL:' + v.email.trim());
      if (v.org) L.push('ORG:' + vEsc(v.org));
      if (v.url) L.push('URL:' + normalizeUrl(v.url));
      L.push('END:VCARD');
      return { recordType: 'mime', mediaType: 'text/vcard', data: enc.encode(L.join('\r\n')) };
    },
    check: v => v.name ? '' : '請輸入姓名',
    note: 'Android 會詢問是否加入聯絡人。名片內容較多，小容量標籤（NTAG213）可能放不下。',
  },
  geo: {
    label: '地圖位置',
    fields: [
      { k: 'lat', label: '緯度', type: 'text', ph: '24.1790', half: true },
      { k: 'lng', label: '經度', type: 'text', ph: '120.6487', half: true },
      { k: 'name', label: '地點名稱（可空白）', type: 'text', ph: '逢甲大學' },
    ],
    build: v => {
      const ll = `${v.lat.trim()},${v.lng.trim()}`;
      return { recordType: 'url', data: `geo:${ll}` + (v.name ? `?q=${ll}(${encodeURIComponent(v.name)})` : '') };
    },
    check: v => (isFinite(parseFloat(v.lat)) && isFinite(parseFloat(v.lng))) ? '' : '請輸入正確的經緯度',
    extra: 'geo',
  },
  app: {
    label: '開啟 Android App',
    fields: [{ k: 'pkg', label: 'App 套件名稱', type: 'text', ph: 'com.instagram.android' }],
    build: v => ({ recordType: 'android.com:pkg', data: enc.encode(v.pkg.trim()) }),
    check: v => /^[\w]+(\.[\w]+)+$/.test(v.pkg.trim()) ? '' : '套件名稱格式像 com.example.app',
    note: '放在網址記錄<b>後面</b>可以強制用指定 App 開啟（Android Application Record）。',
  },
  mime: {
    label: '自訂 MIME 資料',
    fields: [
      { k: 'type', label: 'MIME 類型', type: 'text', ph: 'application/json', def: 'application/json' },
      { k: 'data', label: '內容', type: 'textarea', ph: '{"id": 1}' },
    ],
    build: v => ({ recordType: 'mime', mediaType: v.type.trim(), data: enc.encode(v.data) }),
    check: v => /^[\w.+-]+\/[\w.+-]+$/.test(v.type.trim()) ? '' : 'MIME 類型格式像 text/plain',
  },
  empty: {
    label: '空記錄',
    fields: [],
    build: () => ({ recordType: 'empty' }),
    check: () => '',
  },
};

/* ---------- NDEF 大小估算 ---------- */

function payloadOf(init) {
  const t = init.recordType;
  if (t === 'empty') return { type: 0, payload: 0 };
  if (t === 'url') {
    const u = String(init.data);
    let best = 0;
    URI_PREFIXES.forEach((p, i) => { if (p && u.startsWith(p) && p.length > URI_PREFIXES[best].length) best = i; });
    return { type: 1, payload: 1 + enc.encode(u.slice(URI_PREFIXES[best].length)).length };
  }
  if (t === 'absolute-url') return { type: enc.encode(String(init.data)).length, payload: 0 };
  if (t === 'text') {
    const lang = init.lang || 'en';
    return { type: 1, payload: 1 + lang.length + toBytes(init.data).length };
  }
  if (t === 'smart-poster') return { type: 2, payload: messageSize(init.data.records) };
  if (t === 'mime') return { type: enc.encode(init.mediaType || '').length, payload: toBytes(init.data).length };
  // 外部類型 / 本地類型 / unknown
  return { type: enc.encode(t.startsWith(':') ? t.slice(1) : t).length, payload: toBytes(init.data).length };
}
function recordSize(init) {
  const { type, payload } = payloadOf(init);
  const idLen = init.id ? enc.encode(init.id).length : 0;
  return 1 + 1 + (payload < 256 ? 1 : 4) + (idLen ? 1 + idLen : 0) + type + payload;
}
function messageSize(inits) { return inits.reduce((n, r) => n + recordSize(r), 0); }
function tlvSize(msg) { return 1 + (msg < 255 ? 1 : 3) + msg + 1; }

/* ---------- 讀出的記錄 → 可重寫的格式 ---------- */

function recordToInit(r) {
  const init = { recordType: r.recordType };
  if (r.id) init.id = r.id;
  if (r.mediaType) init.mediaType = r.mediaType;
  const bytes = r.data ? toBytes(r.data).slice() : null;
  if (r.recordType === 'url' || r.recordType === 'absolute-url') init.data = decodeText(bytes);
  else if (r.recordType === 'text') { init.data = decodeText(bytes, r.encoding || 'utf-8'); init.lang = r.lang || 'en'; init.encoding = 'utf-8'; }
  else if (r.recordType === 'smart-poster') init.data = { records: (r.toRecords() || []).map(recordToInit) };
  else if (r.recordType !== 'empty') init.data = bytes;
  return init;
}
// 存到 localStorage 時把位元組轉成 hex
function initToJSON(init) {
  const o = { ...init };
  if (init.data instanceof Uint8Array) o.data = { hex: hex(init.data) };
  else if (init.data && init.data.records) o.data = { records: init.data.records.map(initToJSON) };
  return o;
}
function initFromJSON(o) {
  const init = { ...o };
  if (o.data && o.data.hex !== undefined) init.data = fromHex(o.data.hex);
  else if (o.data && o.data.records) init.data = { records: o.data.records.map(initFromJSON) };
  return init;
}

// 讀出的記錄盡量轉成可編輯的表單
function initToEditable(init) {
  const t = init.recordType;
  if (t === 'url') {
    const u = init.data;
    let m;
    if ((m = u.match(/^https?:\/\/(www\.)?instagram\.com\/([^/?#]+)\/?$/i))) return { type: 'ig', values: { handle: m[2] } };
    if (u.startsWith('tel:')) return { type: 'tel', values: { tel: u.slice(4) } };
    if (u.startsWith('sms:')) {
      const [to, q = ''] = u.slice(4).split('?');
      return { type: 'sms', values: { to, body: new URLSearchParams(q).get('body') || '' } };
    }
    if (u.startsWith('mailto:')) {
      const [to, q = ''] = u.slice(7).split('?');
      const p = new URLSearchParams(q);
      return { type: 'email', values: { to: decodeURIComponent(to), subject: p.get('subject') || '', body: p.get('body') || '' } };
    }
    if ((m = u.match(/^geo:(-?[\d.]+),(-?[\d.]+)(?:\?q=[^()]*\((.*)\))?/))) {
      return { type: 'geo', values: { lat: m[1], lng: m[2], name: m[3] ? decodeURIComponent(m[3]) : '' } };
    }
    return { type: 'url', values: { url: u } };
  }
  if (t === 'text') return { type: 'text', values: { text: init.data, lang: ['zh-TW', 'en', 'ja'].includes(init.lang) ? init.lang : 'zh-TW' } };
  if (t === 'empty') return { type: 'empty', values: {} };
  if (t === 'android.com:pkg') return { type: 'app', values: { pkg: decodeText(init.data) } };
  if (t === 'mime' && init.mediaType === 'application/vnd.wfa.wsc') {
    const w = parseWifi(init.data);
    return { type: 'wifi', values: { ssid: w.ssid || '', auth: w.auth || 'wpa2', key: w.key || '' } };
  }
  return { type: 'raw', init };
}

/* ---------- 讀出的記錄 → 顯示 ---------- */

const TYPE_NAMES = {
  url: '網址', 'absolute-url': '絕對網址', text: '文字', mime: 'MIME 資料', 'smart-poster': 'Smart Poster',
  empty: '空記錄', unknown: '未知格式', 'android.com:pkg': 'Android App',
};
function describe(init) {
  const t = init.recordType;
  if (t === 'url' || t === 'absolute-url') {
    const u = init.data;
    if (u.startsWith('tel:')) return { label: '電話', text: u.slice(4) };
    if (u.startsWith('mailto:')) return { label: 'Email', text: u, link: u };
    if (u.startsWith('sms:')) return { label: '簡訊', text: u };
    if (u.startsWith('geo:')) return { label: '地圖位置', text: u };
    return { label: TYPE_NAMES[t], text: u, link: /^https?:/i.test(u) ? u : null };
  }
  if (t === 'text') return { label: `文字（${init.lang || '?'}）`, text: init.data };
  if (t === 'empty') return { label: '空記錄', text: '（沒有內容）' };
  if (t === 'smart-poster') return { label: 'Smart Poster', nested: init.data.records };
  if (t === 'android.com:pkg') return { label: 'Android App', text: decodeText(init.data) };
  if (t === 'mime') {
    const mt = init.mediaType || '';
    if (mt === 'application/vnd.wfa.wsc') {
      const w = parseWifi(init.data);
      return { label: 'Wi‑Fi', text: `名稱：${w.ssid || '?'}\n密碼：${w.key || '（無）'}` };
    }
    if (/^text\/|json|xml|vcard/i.test(mt)) return { label: mt, text: decodeText(init.data) };
    return { label: mt || 'MIME 資料', text: `${init.data.length} bytes 二進位資料` };
  }
  const bytes = toBytes(init.data);
  const txt = decodeText(bytes);
  const printable = bytes.length && !/[\u0000-\u0008\u000e-\u001f\ufffd]/.test(txt);
  return { label: TYPE_NAMES[t] || `外部類型 ${t}`, text: printable ? txt : `${bytes.length} bytes 二進位資料` };
}
function rawBytes(init) {
  if (init.recordType === 'smart-poster' || init.recordType === 'empty') return null;
  return toBytes(init.data);
}
function renderRecordOut(init, i) {
  const d = describe(init);
  let html = `<div class="rec-out"><div class="label">#${i + 1}　${esc(d.label)}${init.id ? `　id: ${esc(init.id)}` : ''}</div>`;
  if (d.nested) {
    html += `<div class="nested">${d.nested.map(renderRecordOut).join('')}</div>`;
  } else {
    html += `<div class="value">${d.link ? `<a href="${esc(d.link)}" target="_blank" rel="noopener">${esc(d.text)}</a>` : esc(d.text)}</div>`;
  }
  const b = rawBytes(init);
  if (b && b.length) html += `<details class="hex"><summary>原始資料（${b.length} bytes）</summary><code>${hex(b)}</code></details>`;
  return html + '</div>';
}

/* ---------- 分頁 ---------- */

function showTab(name) {
  $$('.tabs button').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === name)));
  $$('.panel').forEach(p => { p.hidden = p.id !== 'tab-' + name; });
  if (name === 'history') renderHistory();
  try { sessionStorage.setItem('nfc.tab', name); } catch { /* ignore */ }
}
$$('.tabs button').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));

/* ---------- 支援檢查 ---------- */

function checkSupport() {
  const pill = $('#support-pill');
  const banner = $('#support-banner');
  const ua = navigator.userAgent;
  const isIOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const isAndroid = /Android/.test(ua);
  if (hasNFC && window.isSecureContext) {
    pill.textContent = '可使用 NFC';
    pill.className = 'pill ok';
    return;
  }
  pill.textContent = '此瀏覽器不支援';
  pill.className = 'pill bad';
  let msg;
  if (isIOS) {
    msg = '<b>iPhone／iPad 不支援網頁 NFC</b>（Safari、Chrome 都一樣），請改用 Android 手機的 Chrome 開啟這個網址。iPhone 想寫入可以用 App，例如 NFC Tools；iPhone 讀取寫好的標籤則不需要任何 App。';
  } else if (isAndroid) {
    msg = '<b>請用 Android 版 Chrome 開啟</b>（版本 89 以上）。Firefox、LINE／IG 內建瀏覽器都不支援網頁 NFC。從 LINE 點開的話，請按右上角「以預設瀏覽器開啟」。';
  } else {
    msg = '<b>電腦瀏覽器不支援網頁 NFC。</b>請用 Android 手機的 Chrome 開啟這個網址。你仍然可以在這裡先編輯內容、存成常用，之後在手機上使用（常用內容只存在各自的瀏覽器）。';
  }
  banner.innerHTML = `<div>${msg}</div>`;
  banner.hidden = false;
  $$('#btn-scan, #btn-write, #btn-clone, #btn-erase, #btn-lock').forEach(b => { b.disabled = true; });
}

/* ---------- 等待面板 ---------- */

let currentCtrl = null;
function openOverlay(title, text = '') {
  $('#overlay-title').textContent = title;
  $('#overlay-text').innerHTML = text;
  $('#overlay-count').hidden = true;
  $('#overlay').classList.remove('success');
  $('#btn-cancel').textContent = '取消';
  $('#overlay').hidden = false;
}
function closeOverlay() { $('#overlay').hidden = true; }
function overlaySuccess(title, text = '') {
  $('#overlay').classList.add('success');
  $('#overlay-title').textContent = title;
  $('#overlay-text').innerHTML = text;
}
function cancelOverlay() {
  if (currentCtrl) currentCtrl.abort();
  currentCtrl = null;
  closeOverlay();
}
$('#btn-cancel').addEventListener('click', cancelOverlay);
$('#overlay').addEventListener('click', (e) => { if (e.target.id === 'overlay') cancelOverlay(); });
function newCtrl() {
  stopScan();
  if (currentCtrl) currentCtrl.abort();
  currentCtrl = new AbortController();
  return currentCtrl;
}

/* ---------- 紀錄 ---------- */

let history = store('nfc.history', []);
function logEvent(kind, data) {
  history.unshift({ t: Date.now(), kind, ...data });
  history = history.slice(0, 300);
  save('nfc.history', history);
}
const KIND_NAMES = { read: '讀取', write: '寫入', erase: '清除', lock: '鎖定', clone: '複製' };
function summarize(inits) {
  if (!inits.length) return '（空白標籤）';
  return inits.map(r => { const d = describe(r); return d.nested ? 'Smart Poster' : (d.text || '').split('\n')[0]; }).join('；');
}
function renderHistory() {
  const el = $('#history-list');
  if (!history.length) { el.innerHTML = '<p class="empty">還沒有紀錄</p>'; }
  else {
    el.innerHTML = history.slice(0, 100).map(h => `
      <div class="list-item">
        <div class="main">
          <span class="badge${h.ok === false ? ' err' : ''}">${KIND_NAMES[h.kind] || h.kind}${h.ok === false ? ' 失敗' : ''}</span>
          ${esc(h.summary || h.error || '')}
          <div class="meta">${timeStr(h.t)}${h.serial ? '　序號 ' + esc(h.serial) : ''}</div>
        </div>
      </div>`).join('');
  }
  renderTemplates();
}
$('#btn-clear-history').addEventListener('click', () => {
  if (!history.length || !confirm('確定清空全部紀錄？')) return;
  history = [];
  save('nfc.history', history);
  renderHistory();
});
$('#btn-export').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify({ history, templates }, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `nfc-紀錄-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
});

/* ---------- 讀取 ---------- */

let scanCtrl = null;
let tagCount = 0;
function stopScan() {
  if (scanCtrl) { scanCtrl.abort(); scanCtrl = null; }
  const btn = $('#btn-scan');
  btn.textContent = '開始讀取';
  btn.classList.add('primary');
}
$('#btn-scan').addEventListener('click', async () => {
  if (scanCtrl) { stopScan(); return; }
  const ctrl = new AbortController();
  const reader = new NDEFReader();
  try {
    await reader.scan({ signal: ctrl.signal });
  } catch (e) {
    toast(errMsg(e), true);
    return;
  }
  scanCtrl = ctrl;
  const btn = $('#btn-scan');
  btn.textContent = '讀取中…按此停止';
  btn.classList.remove('primary');
  toast('請把標籤貼近手機背面');
  reader.onreading = (ev) => {
    if (scanCtrl !== ctrl) return;
    buzz();
    const inits = ev.message.records.map(recordToInit);
    showReadResult(ev.serialNumber, inits);
    logEvent('read', { serial: ev.serialNumber, summary: summarize(inits) });
  };
  reader.onreadingerror = () => toast('讀不到內容：可能是空白／未格式化的標籤，或不是 NDEF 標籤', true);
});

function showReadResult(serial, inits) {
  tagCount++;
  const size = messageSize(inits);
  const card = document.createElement('div');
  card.className = 'tag';
  card.innerHTML = `
    <div class="tag-head"><b>標籤 #${tagCount}</b><span class="time">${timeStr(Date.now())}</span></div>
    <dl class="kv">
      <dt>序號（UID）</dt><dd>${esc(serial || '（無法取得）')}</dd>
      <dt>記錄數</dt><dd>${inits.length}</dd>
      <dt>資料大小</dt><dd>約 ${size} bytes</dd>
    </dl>
    ${inits.length ? inits.map(renderRecordOut).join('') : '<div class="rec-out"><div class="value">這張標籤是空白的（沒有任何記錄），可以直接寫入。</div></div>'}
    <div class="tag-actions">
      ${inits.length ? '<button class="btn small" data-act="edit">載入到寫入頁</button><button class="btn small" data-act="clone">複製到另一張</button>' : ''}
      ${serial ? '<button class="btn ghost small" data-act="copy">複製序號</button>' : ''}
    </div>`;
  card.querySelector('[data-act="edit"]')?.addEventListener('click', () => {
    records = inits.map(initToEditable);
    renderRecords();
    showTab('write');
    toast('已載入，可修改後寫入');
  });
  card.querySelector('[data-act="clone"]')?.addEventListener('click', () => cloneWrite(inits));
  card.querySelector('[data-act="copy"]')?.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(serial); toast('已複製序號'); } catch { toast('無法複製', true); }
  });
  const list = $('#read-results');
  list.prepend(card);
  while (list.children.length > 30) list.lastChild.remove();
}

/* ---------- 寫入表單 ---------- */

let records = [{ type: 'url', values: {} }];
const typeSelectHTML = Object.entries(TYPES).map(([k, t]) => `<option value="${k}">${t.label}</option>`).join('');

function renderRecords() {
  const box = $('#records');
  box.innerHTML = '';
  records.forEach((rec, i) => box.appendChild(renderRecord(rec, i)));
  updateSize();
}
function renderRecord(rec, i) {
  const node = $('#tpl-record').content.firstElementChild.cloneNode(true);
  node.querySelector('.rec-no').textContent = i + 1;
  const sel = node.querySelector('.rec-type');
  const fieldsBox = node.querySelector('.rec-fields');
  node.querySelector('.rec-up').hidden = i === 0;
  node.querySelector('.rec-del').hidden = records.length === 1;

  if (rec.type === 'raw') {
    sel.innerHTML = `<option>複製來的資料（${esc(describe(rec.init).label)}）</option>`;
    sel.disabled = true;
    const d = describe(rec.init);
    fieldsBox.innerHTML = `<p class="hint">${esc(d.nested ? 'Smart Poster（含多筆內容）' : d.text || '')}</p><p class="hint">這筆無法編輯，會原封不動寫入。</p>`;
  } else {
    sel.innerHTML = typeSelectHTML;
    sel.value = rec.type;
    const T = TYPES[rec.type];
    let html = '';
    let half = [];
    const flushHalf = () => { if (half.length) { html += `<div class="field-row">${half.join('')}</div>`; half = []; } };
    T.fields.forEach(f => {
      if (rec.values[f.k] === undefined) rec.values[f.k] = f.def || '';
      const v = esc(rec.values[f.k]);
      let input;
      if (f.type === 'textarea') input = `<textarea data-k="${f.k}" placeholder="${esc(f.ph || '')}">${v}</textarea>`;
      else if (f.type === 'select') input = `<select data-k="${f.k}">${f.options.map(([val, lab]) => `<option value="${val}"${rec.values[f.k] === val ? ' selected' : ''}>${lab}</option>`).join('')}</select>`;
      else input = `<input type="${f.type}" data-k="${f.k}" value="${v}" placeholder="${esc(f.ph || '')}" autocomplete="off" autocapitalize="off" spellcheck="false"${f.type === 'tel' ? ' inputmode="tel"' : ''}>`;
      const block = `<label class="field"><span>${f.label}</span>${input}</label>`;
      if (f.half) half.push(block); else { flushHalf(); html += block; }
    });
    flushHalf();
    if (T.extra === 'geo') html += '<button class="btn ghost small full" data-act="here">使用目前位置</button>';
    if (T.note) html += `<p class="hint">${T.note}</p>`;
    fieldsBox.innerHTML = html;
    fieldsBox.addEventListener('input', (e) => {
      const k = e.target.dataset.k;
      if (!k) return;
      rec.values[k] = e.target.value;
      updateSize();
    });
    fieldsBox.querySelector('[data-act="here"]')?.addEventListener('click', () => {
      if (!navigator.geolocation) { toast('無法取得位置', true); return; }
      navigator.geolocation.getCurrentPosition(p => {
        rec.values.lat = p.coords.latitude.toFixed(6);
        rec.values.lng = p.coords.longitude.toFixed(6);
        renderRecords();
      }, () => toast('無法取得位置（可能沒有允許定位）', true), { enableHighAccuracy: true, timeout: 10000 });
    });
  }
  sel.addEventListener('change', () => {
    records[i] = { type: sel.value, values: {} };
    renderRecords();
  });
  node.querySelector('.rec-up').addEventListener('click', () => {
    [records[i - 1], records[i]] = [records[i], records[i - 1]];
    renderRecords();
  });
  node.querySelector('.rec-del').addEventListener('click', () => {
    records.splice(i, 1);
    renderRecords();
  });
  return node;
}
$('#btn-add-record').addEventListener('click', () => {
  records.push({ type: 'text', values: {} });
  renderRecords();
});

function buildInits(strict) {
  const out = [];
  for (let i = 0; i < records.length; i++) {
    const rec = records[i];
    if (rec.type === 'raw') { out.push(rec.init); continue; }
    const T = TYPES[rec.type];
    const err = T.check(rec.values);
    if (err) {
      if (strict) throw new Error(`第 ${i + 1} 筆：${err}`);
      continue;
    }
    out.push(T.build(rec.values));
  }
  return out;
}

function updateSize() {
  const inits = buildInits(false);
  const msg = messageSize(inits);
  const total = tlvSize(msg);
  const cap = +$('#chip-select').value;
  const pct = Math.min(100, total / cap * 100);
  const bar = $('#size-bar');
  bar.style.width = pct + '%';
  bar.classList.toggle('over', total > cap);
  $('#size-text').innerHTML = total > cap
    ? `<span class="danger-text">約 ${total} / ${cap} bytes，超出容量，請減少內容或換大容量標籤。</span>`
    : `約 ${total} / ${cap} bytes（${Math.round(pct)}%），還剩約 ${cap - total} bytes。`;
  $$('#records .record').forEach((node, i) => {
    const rec = records[i];
    let txt = '';
    try {
      const init = rec.type === 'raw' ? rec.init : TYPES[rec.type].build(rec.values);
      if (rec.type === 'raw' || !TYPES[rec.type].check(rec.values)) {
        txt = `約 ${recordSize(init)} bytes`;
        if (init.recordType === 'url') txt = `${esc(init.data)}　·　${txt}`;
      }
    } catch { /* 未填完 */ }
    node.querySelector('.rec-size').innerHTML = txt;
  });
}
$('#chip-select').addEventListener('change', () => { updateSize(); save('nfc.chip', $('#chip-select').value); });

/* ---------- 常用內容 ---------- */

let templates = store('nfc.templates', []);
function recordsToJSON(recs) {
  return recs.map(r => r.type === 'raw' ? { type: 'raw', init: initToJSON(r.init) } : { type: r.type, values: { ...r.values } });
}
function recordsFromJSON(recs) {
  return recs.map(r => r.type === 'raw' ? { type: 'raw', init: initFromJSON(r.init) } : { type: r.type, values: { ...r.values } });
}
function renderTemplates() {
  const sel = $('#template-select');
  sel.innerHTML = '<option value="">載入常用內容…</option>' + templates.map((t, i) => `<option value="${i}">${esc(t.name)}</option>`).join('');
  sel.hidden = !templates.length;
  const list = $('#template-list');
  if (!list) return;
  list.innerHTML = templates.length ? templates.map((t, i) => `
    <div class="list-item">
      <div class="main">${esc(t.name)}<div class="meta">${t.records.length} 筆記錄</div></div>
      <button class="btn small" data-load="${i}">載入</button>
      <button class="btn ghost small" data-del="${i}" aria-label="刪除">✕</button>
    </div>`).join('') : '<p class="empty">在寫入頁按「存成常用」，之後就能一鍵載入。</p>';
}
function loadTemplate(i) {
  const t = templates[i];
  if (!t) return;
  records = recordsFromJSON(t.records);
  renderRecords();
  showTab('write');
  toast(`已載入「${t.name}」`);
}
$('#template-select').addEventListener('change', (e) => {
  if (e.target.value !== '') loadTemplate(+e.target.value);
  e.target.value = '';
});
$('#template-list').addEventListener('click', (e) => {
  const load = e.target.dataset.load;
  const del = e.target.dataset.del;
  if (load !== undefined) loadTemplate(+load);
  if (del !== undefined && confirm(`刪除「${templates[+del].name}」？`)) {
    templates.splice(+del, 1);
    save('nfc.templates', templates);
    renderTemplates();
  }
});
$('#btn-save-template').addEventListener('click', () => {
  let inits;
  try { inits = buildInits(true); } catch (e) { toast(e.message, true); return; }
  const name = prompt('幫這組內容取個名字', summarize(inits).slice(0, 30));
  if (!name) return;
  templates.unshift({ name, records: recordsToJSON(records) });
  save('nfc.templates', templates);
  renderTemplates();
  toast('已存成常用內容');
});

/* ---------- 寫入 ---------- */

$('#opt-lock').addEventListener('change', (e) => {
  if (e.target.checked && !confirm('寫入後會永久鎖定標籤，之後再也不能修改或清除。確定要開啟嗎？')) e.target.checked = false;
});

$('#btn-write').addEventListener('click', async () => {
  let inits;
  try { inits = buildInits(true); } catch (e) { toast(e.message, true); return; }
  if (!inits.length) { toast('沒有可寫入的內容', true); return; }
  const cap = +$('#chip-select').value;
  if (tlvSize(messageSize(inits)) > cap && !confirm('內容可能超過所選標籤的容量，仍要嘗試寫入嗎？')) return;
  const opts = { protect: $('#opt-protect').checked, lock: $('#opt-lock').checked };
  if ($('#opt-batch').checked) batchWrite(inits, opts);
  else singleWrite(inits, opts);
});

async function singleWrite(inits, { protect, lock }, kind = 'write') {
  const ctrl = newCtrl();
  const summary = summarize(inits);
  openOverlay('請把標籤靠近手機', `將寫入：${esc(summary)}${lock ? '<br><b class="danger-text">寫入後會永久鎖定</b>' : ''}`);
  const writer = new NDEFReader();
  try {
    await writer.write({ records: inits }, { overwrite: !protect, signal: ctrl.signal });
    if (lock) await writer.makeReadOnly({ signal: ctrl.signal });
    buzz([60, 60, 60]);
    overlaySuccess(lock ? '寫入並鎖定完成' : '寫入完成', esc(summary));
    $('#btn-cancel').textContent = '完成';
    logEvent(kind, { summary: summary + (lock ? '（已鎖定）' : '') });
    currentCtrl = null;
  } catch (e) {
    if (e.name === 'AbortError') return;
    closeOverlay();
    const msg = errMsg(e, protect ? 'protect' : '');
    toast('寫入失敗：' + msg, true);
    logEvent(kind, { ok: false, error: msg });
  }
}

// 連續寫入：用掃描取得每張標籤的序號，同一張不會重複寫
async function batchWrite(inits, { protect, lock }) {
  const ctrl = newCtrl();
  const summary = summarize(inits);
  const done = new Set();
  let ok = 0, fail = 0, busy = false, lastTime = 0;
  const reader = new NDEFReader();
  const status = (title, text) => {
    $('#overlay-title').textContent = title;
    $('#overlay-text').innerHTML = text;
    const c = $('#overlay-count');
    c.hidden = false;
    c.textContent = `成功 ${ok} 張${fail ? `　失敗 ${fail}` : ''}`;
  };
  openOverlay('連續寫入');
  $('#btn-cancel').textContent = '結束';
  status('請把第 1 張標籤靠近手機', `內容：${esc(summary)}${lock ? '<br><b class="danger-text">每張都會永久鎖定</b>' : ''}`);
  try {
    await reader.scan({ signal: ctrl.signal });
  } catch (e) {
    closeOverlay();
    toast(errMsg(e), true);
    return;
  }
  reader.onreading = async (ev) => {
    if (ctrl.signal.aborted || busy) return;
    const id = ev.serialNumber;
    // 拿不到序號的標籤：用時間間隔避免同一張重複寫
    if (id ? done.has(id) : Date.now() - lastTime < 3000) {
      status('這張已經寫好了', '請換下一張標籤');
      return;
    }
    busy = true;
    try {
      await reader.write({ records: inits }, { overwrite: !protect, signal: ctrl.signal });
      if (lock) await reader.makeReadOnly({ signal: ctrl.signal });
      if (id) done.add(id);
      ok++;
      lastTime = Date.now();
      buzz([60, 60, 60]);
      logEvent('write', { serial: id, summary: summary + (lock ? '（已鎖定）' : '') });
      $('#overlay').classList.add('success');
      status(`第 ${ok} 張完成`, '請拿開，換下一張標籤');
      setTimeout(() => $('#overlay').classList.remove('success'), 900);
    } catch (e) {
      if (ctrl.signal.aborted) return;
      fail++;
      const msg = errMsg(e, protect ? 'protect' : '');
      logEvent('write', { serial: id, ok: false, error: msg });
      buzz(300);
      status('這張寫入失敗', esc(msg) + '<br>可以再貼一次重試，或換下一張');
    } finally {
      busy = false;
    }
  };
}

/* ---------- 複製 ---------- */

function cloneWrite(inits) {
  const ctrl = newCtrl();
  openOverlay('準備複製', `已讀取 ${inits.length} 筆記錄：${esc(summarize(inits))}<br>請<b>先拿開來源標籤</b>，換上要寫入的標籤後按下方按鈕。`);
  const btn = $('#btn-cancel');
  btn.textContent = '寫入目標標籤';
  btn.classList.add('primary');
  const go = async (e) => {
    e.stopImmediatePropagation();
    btn.removeEventListener('click', go, true);
    btn.classList.remove('primary');
    if (ctrl.signal.aborted) return;
    currentCtrl = null;
    await singleWrite(inits, { protect: false, lock: false }, 'clone');
  };
  btn.addEventListener('click', go, true);
  ctrl.signal.addEventListener('abort', () => {
    btn.removeEventListener('click', go, true);
    btn.classList.remove('primary');
  });
}

$('#btn-clone').addEventListener('click', async () => {
  const ctrl = newCtrl();
  openOverlay('請靠近「來源」標籤', '先讀取要被複製的那張');
  const reader = new NDEFReader();
  try {
    await reader.scan({ signal: ctrl.signal });
  } catch (e) {
    closeOverlay();
    toast(errMsg(e), true);
    return;
  }
  reader.onreading = (ev) => {
    if (ctrl.signal.aborted) return;
    const inits = ev.message.records.map(recordToInit);
    ctrl.abort();
    buzz();
    if (!inits.length) { closeOverlay(); toast('來源標籤是空白的，沒有東西可以複製', true); return; }
    showReadResult(ev.serialNumber, inits);
    cloneWrite(inits);
  };
});

/* ---------- 清除／鎖定 ---------- */

$('#btn-erase').addEventListener('click', async () => {
  if (!confirm('清除後標籤上的內容會消失，確定要清除嗎？')) return;
  const ctrl = newCtrl();
  openOverlay('請把要清除的標籤靠近手機');
  try {
    await new NDEFReader().write({ records: [{ recordType: 'empty' }] }, { signal: ctrl.signal });
    buzz([60, 60, 60]);
    overlaySuccess('已清除', '標籤現在是空白的，可以重新寫入');
    $('#btn-cancel').textContent = '完成';
    logEvent('erase', { summary: '清除為空白' });
    currentCtrl = null;
  } catch (e) {
    if (e.name === 'AbortError') return;
    closeOverlay();
    toast('清除失敗：' + errMsg(e), true);
    logEvent('erase', { ok: false, error: errMsg(e) });
  }
});

$('#btn-lock').addEventListener('click', async () => {
  const ans = prompt('鎖定後這張標籤永遠不能再修改或清除。\n確定的話請輸入「鎖定」兩個字：');
  if (ans === null) return;
  if (ans.trim() !== '鎖定') { toast('沒有輸入「鎖定」，已取消'); return; }
  const ctrl = newCtrl();
  openOverlay('請把要鎖定的標籤靠近手機', '<b class="danger-text">碰到就會立刻永久鎖定</b>');
  try {
    await new NDEFReader().makeReadOnly({ signal: ctrl.signal });
    buzz([60, 60, 60]);
    overlaySuccess('已永久鎖定', '這張標籤現在只能讀取');
    $('#btn-cancel').textContent = '完成';
    logEvent('lock', { summary: '永久鎖定' });
    currentCtrl = null;
  } catch (e) {
    if (e.name === 'AbortError') return;
    closeOverlay();
    const msg = e.name === 'NotSupportedError' ? '手機或標籤不支援鎖定（需要 Chrome 100 以上）' : errMsg(e);
    toast('鎖定失敗：' + msg, true);
    logEvent('lock', { ok: false, error: msg });
  }
});

/* ---------- 啟動 ---------- */

const chip = store('nfc.chip', null);
if (chip && $(`#chip-select option[value="${chip}"]`)) $('#chip-select').value = chip;
checkSupport();
renderRecords();
renderTemplates();
try { const t = sessionStorage.getItem('nfc.tab'); if (t) showTab(t); } catch { /* ignore */ }
document.addEventListener('visibilitychange', () => { if (document.hidden) stopScan(); });
