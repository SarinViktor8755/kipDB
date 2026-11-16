/* =========================================================
 *  Состояние приложения
 * ========================================================= */
const DEVICE_CACHE_KEY = 'kip.devices.cache.v1';
let deviceCacheEtag = null;
let deviceCacheRestored = false;

function restoreDeviceCache() {
    if (deviceCacheRestored) return state.devices.length > 0;
    deviceCacheRestored = true;
    try {
        const raw = localStorage.getItem(DEVICE_CACHE_KEY);
        if (!raw) return false;
        const cached = JSON.parse(raw);
        if (!cached || typeof cached.etag !== 'string' || !Array.isArray(cached.data)) {
            localStorage.removeItem(DEVICE_CACHE_KEY);
            return false;
        }
        deviceCacheEtag = cached.etag;
        state.devices = cached.data;
        return true;
    } catch (e) {
        // Недоступное хранилище/повреждённый кеш не мешают обычной загрузке с сервера.
        deviceCacheEtag = null;
        state.devices = [];
        return false;
    }
}

function persistDeviceCache(etag, data) {
    if (!etag || !Array.isArray(data)) return;
    deviceCacheEtag = etag;
    try {
        localStorage.setItem(DEVICE_CACHE_KEY, JSON.stringify({ etag, data }));
    } catch (e) {
        // При переполнении localStorage приложение продолжает работать без постоянного кеша.
        try { localStorage.removeItem(DEVICE_CACHE_KEY); } catch (_) {}
    }
}

const state = {
    selectedDocumentDeviceId: null,
    devices: [],
    stats: { total: 0, overdue: 0, soon: 0, ok: 0 },
    currentTab: 'all',
    documents: [],
    currentDocument: null,
    users: [],
    user: null,
    columnFilters: {}, // текстовые фильтры: { col: 'строка' }
    // Сортировки по столбцам.
    // По умолчанию «Дата следующей поверки» — ASC: ближайшие поверки сверху.
    columnSorts: { nextVerification: 'asc' }
};

function $(sel) { return document.querySelector(sel); }
function $all(sel) { return document.querySelectorAll(sel); }

function today() { return new Date().toISOString().slice(0, 10); }

function formatDate(dateStr) {
    if (!dateStr || dateStr === '—' || dateStr === 'null') return '—';
    const parts = dateStr.split('-');
    if (parts.length === 3) {
        return parts[2] + '.' + parts[1] + '.' + parts[0].slice(2);
    }
    return dateStr;
}

function formatDateTime(dtStr) {
    if (!dtStr) return '';
    const parts = dtStr.split(' ');
    if (parts.length >= 2) {
        const timePart = parts[1].slice(0, 5);
        const datePart = formatDate(parts[0]);
        return timePart + '_' + datePart;
    }
    return dtStr;
}

function statusOf(d) {
    if (!d.nextVerification) return 'ok';
    const t = today();
    if (d.nextVerification < t) return 'overdue';
    const in30 = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10);
    if (d.nextVerification <= in30) return 'soon';
    return 'ok';
}
function statusLabel(s) { return s === 'overdue' ? 'Просрочено' : s === 'soon' ? 'Скоро' : 'В норме'; }
function statusBadge(s) { return `<span class="status status-${s}">${statusLabel(s)}</span>`; }

function escapeHtml(s) {
    return (s == null ? '' : String(s))
        .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}

function photoHtml(d) {
    const src = d.photoThumbPath || d.photoPath;
    if (src && src !== 'null' && src !== 'undefined' && String(src).trim() !== '') {
        return '<img src="/' + src + '" alt="" onerror="this.outerHTML=\'<div class=\\\'photo-placeholder\\\'>нет</div>\'">';
    }
    return '<div class="photo-placeholder">нет</div>';
}

function showToast(title, body, kind) {
    const box = document.getElementById('toasts');
    if (!box) return;
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' toast-' + kind : '');
    el.innerHTML = `
        <button class="toast-close" aria-label="Закрыть">×</button>
        <div class="toast-title">${escapeHtml(title)}</div>
        ${body ? `<div class="toast-body">${escapeHtml(body)}</div>` : ''}
    `;
    el.querySelector('.toast-close').addEventListener('click', () => el.remove());
    box.appendChild(el);
    setTimeout(() => el.remove(), 6000);
}

function humanizeChanges(raw) {
    const labels = {
        serial_number: 'Зав. номер',
        name: 'Наименование',
        location: 'Позиция',
        last_verification: 'Последняя поверка',
        next_verification: 'Следующая поверка'
    };
    return raw.split('; ').map(part => {
        const idx = part.indexOf(':');
        if (idx < 0) return part;
        const field = part.substring(0, idx).trim();
        const rest = part.substring(idx + 1).trim();
        return (labels[field] || field) + ': ' + rest.replace(' -> ', ' → ');
    }).join('\n');
}

function compressImage(file) {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
            const max = 1600;
            let w = img.width, h = img.height;
            if (w > max || h > max) {
                if (w >= h) { h = Math.round(h * max / w); w = max; }
                else        { w = Math.round(w * max / h); h = max; }
            }
            const canvas = document.createElement('canvas');
            canvas.width = w; canvas.height = h;
            canvas.getContext('2d').drawImage(img, 0, 0, w, h);
            canvas.toBlob(b => b ? resolve(b) : reject(new Error('toBlob failed')), 'image/jpeg', 0.85);
        };
        img.onerror = reject;
        img.src = URL.createObjectURL(file);
    });
}

/* =========================================================
 *  Индикаторы загрузки
 * ========================================================= */
const MIN_LOADING_MS = 280;

let __loadingDepth = 0;
let __loadingStart = 0;
let __loadingBarTimer = null;
let __loadingHideTimer = null;

function beginLoading() {
    __loadingDepth++;
    if (__loadingDepth === 1) {
        __loadingStart = performance.now();
        clearTimeout(__loadingHideTimer);
    }
    const bar = document.getElementById('loadingBar');
    if (!bar) return;
    bar.classList.add('active');
    bar.style.width = '15%';
    clearTimeout(__loadingBarTimer);
    __loadingBarTimer = setTimeout(() => { bar.style.width = '70%'; }, 200);
}

function finishLoading() {
    __loadingDepth = Math.max(0, __loadingDepth - 1);
    if (__loadingDepth > 0) return;
    const bar = document.getElementById('loadingBar');
    if (!bar) return;
    const elapsed = performance.now() - __loadingStart;
    const wait = Math.max(0, MIN_LOADING_MS - elapsed);
    clearTimeout(__loadingBarTimer);
    bar.style.width = '100%';
    __loadingHideTimer = setTimeout(() => {
        bar.classList.remove('active');
        bar.style.width = '0';
    }, 220 + wait);
}

async function apiFetch(url, opts) {
    beginLoading();
    try { return await fetch(url, opts); }
    finally { finishLoading(); }
}

function ensureLoadingOverlay(wrap) {
    if (!wrap) return null;
    if (!wrap.classList.contains('table-loading-wrap')) {
        wrap.classList.add('table-loading-wrap');
    }
    let overlay = wrap.querySelector('.table-loading-overlay');
    if (!overlay) {
        overlay = document.createElement('div');
        overlay.className = 'table-loading-overlay';
        overlay.innerHTML = '<div class="spinner"></div>';
        wrap.insertBefore(overlay, wrap.firstChild);
    }
    return overlay;
}

function setTableBusy(wrap, busy) {
    const overlay = ensureLoadingOverlay(wrap);
    if (!overlay) return;
    overlay.classList.toggle('active', !!busy);
}

function renderSkeleton(tbody, rows = 8, cols = 16) {
    if (!tbody) return;
    let html = '';
    for (let r = 0; r < rows; r++) {
        html += '<tr class="skeleton-row">';
        for (let c = 0; c < cols; c++) {
            const cls = c % 5 === 0 ? 'short' : (c % 3 === 0 ? 'tiny' : '');
            html += `<td><div class="skeleton-line ${cls}"></div></td>`;
        }
        html += '</tr>';
    }
    tbody.innerHTML = html;
}

function setButtonBusy(btn, busy, label) {
    if (!btn) return;
    if (busy) {
        btn.dataset.prevLabel = btn.innerHTML;
        btn.dataset.busy = '1';
        btn.innerHTML = '<span class="btn-spinner"></span>' + (label || 'Загрузка…');
    } else {
        btn.dataset.busy = '0';
        if (btn.dataset.prevLabel != null) {
            btn.innerHTML = btn.dataset.prevLabel;
            delete btn.dataset.prevLabel;
        }
    }
}

function debounce(fn, ms = 180) {
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

/* =========================================================
 *  Datalist для фильтров по столбцам
 * ========================================================= */
const COLUMN_FILTER_DATALIST_MAX = 500;

function cellValueForColumn(d, col) {
    switch (col) {
        case 'serialNumber':         return d.serialNumber || '';
        case 'location':             return d.location || '';
        case 'sensorType':           return d.sensorType || '';
        case 'scale':                return formatScale(d);
        case 'category':             return d.category || '';
        case 'mpi':                  return d.mpi || '';
        case 'accuracyClass':        return d.accuracyClass || '';
        case 'redLine':              return d.redLine || '';
        case 'deferredVerification': return d.deferredVerification || '';
        case 'movement':             return d.movement || '';
        case 'rowNumber':            return d.rowNumber || '';
        case 'groupName':            return d.groupName || '';
        case 'positionName':         return d.positionName || d.name || '';
        default:                     return '';
    }
}

function populateColumnDatalists() {
    const columns = [
        'serialNumber', 'location', 'sensorType', 'scale', 'category',
        'mpi', 'accuracyClass', 'redLine', 'deferredVerification', 'movement',
        'rowNumber', 'groupName', 'positionName'
    ];

    for (const col of columns) {
        const listId = 'col-dl-' + col;
        let dl = document.getElementById(listId);
        if (!dl) {
            dl = document.createElement('datalist');
            dl.id = listId;
            document.body.appendChild(dl);
        }
        const values = new Set();
        for (const d of state.devices) {
            const v = cellValueForColumn(d, col);
            if (v && v !== '—') values.add(v);
            if (values.size >= COLUMN_FILTER_DATALIST_MAX) break;
        }
        const sorted = [...values].sort((a, b) => a.localeCompare(b, 'ru'));
        dl.innerHTML = sorted.map(v => `<option value="${escapeHtml(v)}"></option>`).join('');
    }
}

function updateDatalists() {
    const names = [...new Set(state.devices.map(d => d.name).filter(Boolean))].sort();
    const locations = [...new Set(state.devices.map(d => d.location).filter(Boolean))].sort();

    const listNames = $('#listNames');
    const listLocations = $('#listLocations');
    if (listNames) listNames.innerHTML = names.map(n => `<option value="${escapeHtml(n)}">`).join('');
    if (listLocations) listLocations.innerHTML = locations.map(l => `<option value="${escapeHtml(l)}">`).join('');

    populateColumnDatalists();
}

/* =========================================================
 *  Загрузка данных
 * ========================================================= */
async function loadAll() {
    const wrap = document.getElementById('devicesTableWrap');
    const tbody = document.querySelector('#tableAll tbody');

    // Сначала мгновенно показываем последний сохранённый список, не ожидая сеть.
    const hadCache = restoreDeviceCache();
    if (hadCache) {
        render();
        updateDatalists();
    }

    beginLoading();
    setTableBusy(wrap, true);
    if (!state.devices.length) renderSkeleton(tbody, 8, 16);

    try {
        await Promise.all([loadDevices(), loadStats()]);
        render();
        updateDatalists();
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

async function loadDevices() {
    const headers = {};
    if (deviceCacheEtag) headers['If-None-Match'] = deviceCacheEtag;
    const r = await apiFetch('/api/devices', { headers });
    if (r.status === 304) return; // список не изменился — оставляем данные из localStorage
    if (!r.ok) throw new Error('devices: ' + r.status);
    const data = await r.json();
    state.devices = data;
    persistDeviceCache(r.headers.get('ETag'), data);
}

async function loadStats() {
    const r = await apiFetch('/api/stats');
    if (!r.ok) return;
    state.stats = await r.json();
}

async function loadAudit(limit = 200) {
    const r = await apiFetch('/api/audit?limit=' + limit);
    if (!r.ok) throw new Error('audit: ' + r.status);
    return r.json();
}

async function loadTrash() {
    const r = await apiFetch('/api/devices/deleted');
    if (!r.ok) throw new Error('trash: ' + r.status);
    return r.json();
}

function render() {
    renderAllTab();
}

function formatTableDate(value) {
    if (!value) return '—';
    const s = String(value);
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) { const [y,m,d] = s.split('-'); return `${d}.${m}.${y}`; }
    return s;
}

function formatScale(device) {
    const min = device.scaleMin == null ? '' : String(device.scaleMin).trim();
    const max = device.scaleMax == null ? '' : String(device.scaleMax).trim();
    const unit = device.unit == null ? '' : String(device.unit).trim();
    if (!min && !max && !unit) return '—';
    if (min && max) return `${min} ÷ ${max}${unit ? ' ' + unit : ''}`;
    return [min, max, unit].filter(Boolean).join(' ');
}

/* =========================================================
 *  Таблица «Все приборы»
 *
 *  Сортировка:
 *    1) Приоритет — выбранные пользователем сортировки (state.columnSorts).
 *    2) Если пользователь не задал сортировку — по умолчанию идёт
 *       ASC по nextVerification (ближайшие поверки сверху, пустые — в конце).
 *       Это совпадает с ORDER BY в SQL (Db.getAllDevices),
 *       поэтому порядок сохраняется даже после фильтрации на клиенте.
 * ========================================================= */
function renderAllTab() {
    const q = ($('#searchAll').value || '').toLowerCase().trim();
    const fEl = $('#statusFilter');
    const f = fEl ? fEl.value : '';

    // Активные текстовые фильтры
    const activeCols = Object.entries(state.columnFilters)
        .filter(([, v]) => v && v.trim() !== '');

    let rows = state.devices.filter(d => {
        if (q) {
            const haystack = [d.name,d.location,d.serialNumber,d.sensorType,d.unit,d.category,d.groupName,d.positionName,d.redLine].join(' ').toLowerCase();
            if (!haystack.includes(q)) return false;
        }
        if (f && statusOf(d) !== f) return false;
        for (const [col, needle] of activeCols) {
            const value = String(cellValueForColumn(d, col) || '').toLowerCase();
            if (!value.includes(needle)) return false;
        }
        return true;
    });

    // Сортировка.
    const hasExplicitSort = Object.values(state.columnSorts).some(v => v);
    rows.sort((a, b) => {
        if (hasExplicitSort) {
            for (const [col, dir] of Object.entries(state.columnSorts)) {
                if (!dir) continue;
                const av = a[col] || '';
                const bv = b[col] || '';
                if (av === bv) continue;
                // Пустые значения всегда в конце независимо от направления
                if (!av) return 1;
                if (!bv) return -1;
                return dir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av);
            }
        }
        // Fallback: ближайшие даты следующей поверки сверху, пустые — в конце.
        // ISO-даты (YYYY-MM-DD) корректно сравниваются как строки.
        const an = a.nextVerification || '';
        const bn = b.nextVerification || '';
        if (!an && !bn) return 0;
        if (!an) return 1;
        if (!bn) return -1;
        return an.localeCompare(bn);
    });

    $('#tableAll tbody').innerHTML = rows.map(d => `<tr data-id="${d.id}">
        <td>${escapeHtml(d.serialNumber || '—')}</td><td>${escapeHtml(d.location || '—')}</td>
        <td>${escapeHtml(d.sensorType || '—')}</td>
        <td>${escapeHtml(formatScale(d))}</td>
        <td>${escapeHtml(d.category || '—')}</td><td>${formatTableDate(d.lastVerification)}</td>
        <td>${escapeHtml(d.mpi || '—')}</td><td>${formatTableDate(d.nextVerification)}</td>
        <td>${escapeHtml(d.accuracyClass || '—')}</td><td>${escapeHtml(d.redLine || '—')}</td>
        <td>${formatTableDate(d.writeOff)}</td><td>${escapeHtml(d.deferredVerification || '—')}</td>
        <td>${escapeHtml(d.location || '—')}</td><td>${escapeHtml(d.rowNumber || '—')}</td>
        <td>${escapeHtml(d.groupName || '—')}</td><td>${escapeHtml(d.positionName || d.name || '—')}</td>
    </tr>`).join('');

    const emptyEl = $('#emptyAll');
    if (emptyEl) emptyEl.hidden = rows.length !== 0 || __loadingDepth > 0;
}

let movementsLoadAll = false;
let movementsSearchTimer = null;

function movementDateKey(value) {
    if (!value) return 'Без даты';
    const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})/);
    return m ? `${m[3]}.${m[2]}.${m[1]}` : String(value);
}

async function loadMovements(loadAll = movementsLoadAll) {
    if (!state.user) return;
    movementsLoadAll = !!loadAll;
    const wrap = $('#movementsTableWrap');
    const tbody = $('#tableMovements tbody');
    setTableBusy(wrap, true);
    if (!tbody.children.length) renderSkeleton(tbody, 8, 5);
    beginLoading();
    try {
        const q = ($('#searchMovements').value || '').trim();
        const params = new URLSearchParams();
        if (q) params.set('q', q);
        if (movementsLoadAll) params.set('all', 'true');
        const r = await apiFetch('/api/movements?' + params.toString());
        if (r.status === 401) return;
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const rows = await r.json();
        renderMovements(rows);
        $('#loadAllMovementsBtn').hidden = movementsLoadAll;
        $('#loadRecentMovementsBtn').hidden = !movementsLoadAll;
        $('#movementsCount').textContent = movementsLoadAll
            ? `Загружено: ${rows.length}`
            : `Последние ${rows.length} из журнала`;
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

function renderMovements(rows) {
    const tbody = $('#tableMovements tbody');
    const groups = new Map();
    rows.forEach(m => {
        const key = movementDateKey(m.eventDate);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(m);
    });
    let html = '';
    for (const [date, items] of groups) {
        html += `<tr class="movement-date-row"><td colspan="8">${escapeHtml(date)} <span>${items.length} ${items.length === 1 ? 'перемещение' : 'перемещений'}</span></td></tr>`;
        html += items.map(m => `<tr>
            <td>${escapeHtml(formatDateTime(m.createdAt || m.eventDate))}</td>
            <td><button type="button" class="btn-link" data-open-movement-device="${m.deviceId}">${escapeHtml(m.name || ('Прибор #' + m.deviceId))}</button></td>
            <td>${escapeHtml(m.serialNumber || '—')}</td>
            <td>${escapeHtml(m.eventType || '—')}</td>
            <td>${escapeHtml(m.fromPlace || '—')}</td>
            <td>${escapeHtml(m.toPlace || '—')}</td>
            <td>${escapeHtml(m.note || '—')}</td>
            <td>${escapeHtml(m.createdBy || '—')}</td>
        </tr>`).join('');
    }
    tbody.innerHTML = html;
    $('#emptyMovements').hidden = rows.length !== 0;
}

$('#loadAllMovementsBtn')?.addEventListener('click', () => loadMovements(true).catch(e => showToast('Ошибка загрузки перемещений', e.message, 'error')));
$('#loadRecentMovementsBtn')?.addEventListener('click', () => loadMovements(false).catch(e => showToast('Ошибка загрузки перемещений', e.message, 'error')));
$('#searchMovements')?.addEventListener('input', () => {
    clearTimeout(movementsSearchTimer);
    movementsSearchTimer = setTimeout(() => loadMovements(movementsLoadAll).catch(e => showToast('Ошибка поиска', e.message, 'error')), 250);
});
$('#tableMovements')?.addEventListener('click', e => {
    const btn = e.target.closest('[data-open-movement-device]');
    if (!btn) return;
    const id = Number(btn.dataset.openMovementDevice);
    const device = state.devices.find(d => Number(d.id) === id);
    if (device) openViewModal(device);
    else showToast('Прибор', 'Откройте вкладку «Все приборы» и найдите прибор по ID ' + id, 'info');
});

async function renderAuditTab(limit = 200) {
    if (!state.user) return;
    const wrap = document.getElementById('auditTableWrap');
    const tbody = $('#tableAudit tbody');
    setTableBusy(wrap, true);
    renderSkeleton(tbody, 6, 6);
    beginLoading();
    try {
        const r = await apiFetch('/api/audit?limit=' + limit);
        if (r.status === 401) return;
        if (!r.ok) throw new Error('audit: ' + r.status);
        const rows = await r.json();
        tbody.innerHTML = rows.map(r => `<tr>
            <td>${formatDateTime(r.when)}</td>
            <td>${escapeHtml(r.who)}</td>
            <td>${escapeHtml(r.action)}</td>
            <td>${escapeHtml(r.name)}</td>
            <td>${escapeHtml(r.serialNumber)}</td>
            <td>${escapeHtml(r.details)}</td>
        </tr>`).join('');
        $('#emptyAudit').hidden = rows.length !== 0;
    } catch (e) {
        showToast('Ошибка загрузки журнала', e.message, 'error');
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

async function renderTrashTab() {
    if (!state.user) return;
    const wrap = document.getElementById('trashTableWrap');
    const tbody = $('#tableTrash tbody');
    setTableBusy(wrap, true);
    renderSkeleton(tbody, 4, 6);
    beginLoading();
    try {
        const r = await apiFetch('/api/devices/deleted');
        if (r.status === 401) return;
        if (!r.ok) throw new Error('trash: ' + r.status);
        const rows = await r.json();
        tbody.innerHTML = rows.map(d => `<tr>
            <td>${d.id}</td>
            <td class="photo-cell">${photoHtml(d)}</td>
            <td>${escapeHtml(d.name)}</td>
            <td>${escapeHtml(d.serialNumber || '—')}</td>
            <td>${escapeHtml(d.location)}</td>
            <td class="actions">
                <button class="btn-link" data-act="restore" data-id="${d.id}">Восстановить</button>
            </td>
        </tr>`).join('');
        $('#emptyTrash').hidden = rows.length !== 0;
    } catch (e) {
        showToast('Ошибка загрузки корзины', e.message, 'error');
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

/* =========================================================
 *  РОУТИНГ
 * ========================================================= */
function switchTab(tabName) {
    $all('.tab').forEach(b => b.classList.remove('active'));
    $all('.tab-panel').forEach(p => p.classList.remove('active'));

    const btn = document.querySelector(`.tab[data-tab="${tabName}"]`);
    if (btn) btn.classList.add('active');

    const panel = $('#tab-' + tabName);
    if (panel) panel.classList.add('active');

    state.currentTab = tabName;

    if (!state.user) return;
    if (tabName === 'users' && state.user.role !== 'admin') { window.location.hash = 'all'; return; }
    if (tabName === 'audit') renderAuditTab(200);
    if (tabName === 'movements') loadMovements(false).catch(e => showToast('Ошибка загрузки перемещений', e.message, 'error'));
    if (tabName === 'trash') renderTrashTab();
    if (tabName === 'users') loadUsers().catch(e => showToast('Ошибка пользователей', e.message, 'error'));
    if (tabName === 'documents') {
        state.currentDocument = null;
        $('#documentWorkspace').hidden = true;
        $('#documentsListView').hidden = false;
        loadDocuments().catch(e => showToast('Ошибка загрузки документов', e.message, 'error'));
    }
}

async function handleAppRoute() {
    if (!state.user) return;

    const hash = decodeURIComponent(window.location.hash.replace(/^#/, '')) || 'all';
    const helpTarget = document.getElementById(hash);

    if (helpTarget && helpTarget.classList.contains('help-section')) {
        switchTab('help');
        requestAnimationFrame(() => helpTarget.scrollIntoView({ behavior: 'auto', block: 'start' }));
        return;
    }

    const allowedTabs = ['all', 'documents', 'movements', 'audit', 'trash', 'help', 'users'];
    switchTab(allowedTabs.includes(hash) ? hash : 'all');
}

window.addEventListener('hashchange', handleAppRoute);

document.addEventListener('click', (event) => {
    const link = event.target.closest('.help-toc a[href^="#help-"]');
    if (!link) return;

    event.preventDefault();
    const targetId = decodeURIComponent(link.getAttribute('href').slice(1));
    const target = document.getElementById(targetId);
    if (!target) return;

    if (state.currentTab !== 'help') {
        switchTab('help');
    }
    requestAnimationFrame(() => target.scrollIntoView({ behavior: 'smooth', block: 'start' }));
});

$all('.tab').forEach(btn => {
    btn.addEventListener('click', (e) => {
        e.preventDefault();
        const tabName = btn.dataset.tab;
        window.location.hash = tabName;
        if (window.location.hash.replace('#', '') === tabName) {
            switchTab(tabName);
        }
    });
});

/* =========================================================
 *  АВТОРИЗАЦИЯ И РОЛИ
 * ========================================================= */
function roleTitle(role) { return role === 'admin' ? 'Администратор' : role === 'guest' ? 'Гость · просмотр' : 'Пользователь'; }

function fillUserSelect() {
    const select = $('#userSelect');
    if (!select) return;
    const current = state.user ? state.user.login : 'guest';
    // Гость уже приходит из /api/users как системная учётная запись.
    // Не добавляем отдельный option вручную, иначе в списке будут два гостя.
    select.innerHTML = state.users.map(u => {
        const label = u.role === 'guest' ? 'Гость (только просмотр)' : u.name;
        return `<option value="${escapeHtml(u.login).replaceAll('"', '&quot;')}">${escapeHtml(label)}</option>`;
    }).join('');
    select.value = current;
}

function applyRoleUI() {
    const isAdmin = state.user && state.user.role === 'admin';
    const isGuest = state.user && state.user.role === 'guest';
    document.body.classList.toggle('read-only', !!isGuest);
    document.body.classList.toggle('auth-locked', !state.user);
    $all('.admin-only').forEach(el => { el.hidden = !isAdmin; });
    const cur = $('#currentUser');
    if (cur) cur.textContent = state.user ? `${state.user.name} · ${roleTitle(state.user.role)}` : 'Не выполнен вход';
    const logout = $('#logoutBtn');
    if (logout) logout.hidden = !state.user;
    if (state.user) $('#userSelect').value = state.user.login;
}

async function initializeAuth() {
    applyRoleUI();
    beginLoading();

    const usersPromise = fetch('/api/auth/users')
        .then(r => r.ok ? r.json() : [])
        .catch(() => []);
    const mePromise = fetch('/api/auth/me')
        .then(r => r.ok ? r.json() : null)
        .catch(() => null);
    const dataPromise = loadAll().catch(() => {});

    try {
        const [users, me] = await Promise.all([usersPromise, mePromise]);
        state.users = Array.isArray(users) ? users : [];
        fillUserSelect();

        if (me && me.authenticated) {
            state.user = me.user;
            applyRoleUI();
            await handleAppRoute();
            await dataPromise;
            if (!state.devices.length) {
                await loadAll().catch(() => {});
            }
        } else {
            $('#userSelect').value = 'guest';
            await loginSelectedUser('').catch(() => {});
            await handleAppRoute();
            await dataPromise;
        }
    } catch (e) {
        showToast('Ошибка авторизации', e.message, 'error');
    } finally {
        finishLoading();
    }
}

async function afterLogin() {
    applyRoleUI();
    loadAll().catch(e => showToast('Ошибка загрузки данных', e.message, 'error'));
}

async function loginSelectedUser(password = '') {
    const login = $('#userSelect').value;
    if (!login) return false;
    const r = await apiFetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ login, password })
    });
    if (!r.ok) {
        const err = await r.json().catch(() => ({}));
        const errEl = $('#loginError');
        if (errEl) { errEl.textContent = err.error || 'Ошибка входа'; errEl.hidden = false; }
        return false;
    }
    const result = await r.json();
    state.user = result.user;
    $('#passwordModal').hidden = true;
    $('#loginPassword').value = '';
    $('#loginError').hidden = true;

    await afterLogin();
    await handleAppRoute();
    showToast('Вход выполнен', `${state.user.name}: ${roleTitle(state.user.role)}`, 'info');
    return true;
}

$('#userSelect').addEventListener('change', async () => {
    const login = $('#userSelect').value;
    if (!login) return;
    if (login.toLowerCase() === 'guest') {
        try { await loginSelectedUser(''); } catch (e) { showToast('Ошибка входа', e.message, 'error'); }
        return;
    }
    const selected = state.users.find(u => u.login === login);
    $('#passwordUserLabel').textContent = selected ? `Пользователь: ${selected.name}` : `Пользователь: ${login}`;
    $('#loginError').hidden = true; $('#loginPassword').value = '';
    $('#passwordModal').hidden = false; $('#loginPassword').focus();
});

$('#passwordForm').addEventListener('submit', async e => {
    e.preventDefault();
    const submitBtn = e.target.querySelector('button[type="submit"]');
    setButtonBusy(submitBtn, true, 'Вход…');
    try { await loginSelectedUser($('#loginPassword').value); }
    catch (err) { $('#loginError').textContent = err.message; $('#loginError').hidden = false; }
    finally { setButtonBusy(submitBtn, false); }
});
$('#cancelLoginBtn').addEventListener('click', () => {
    $('#passwordModal').hidden = true; $('#loginPassword').value = ''; $('#userSelect').value = state.user ? state.user.login : '';
});
$('#logoutBtn').addEventListener('click', async () => {
    await apiFetch('/api/auth/logout', {method:'POST'});
    state.user = null;
    $('#userSelect').value = 'guest';
    await loginSelectedUser('');
    if (state.currentTab === 'users') { window.location.hash = 'all'; }
    showToast('Выполнен выход', 'Открыт режим гостя', 'info');
});

async function loadUsers() {
    if (!state.user) return;
    const wrap = document.getElementById('usersTableWrap');
    const tbody = $('#tableUsers tbody');
    setTableBusy(wrap, true);
    renderSkeleton(tbody, 4, 3);
    beginLoading();
    try {
        const r = await apiFetch('/api/users');
        if (r.status === 401) return;
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const users = await r.json();
        tbody.innerHTML = users.map(u => `<tr><td>${escapeHtml(u.name)}</td><td>${u.role === 'admin' ? 'Администратор' : u.role === 'guest' ? 'Гость · просмотр' : 'Пользователь'}</td><td>${u.role === 'admin' || u.role === 'guest' ? '<span class="muted">Системная учётная запись</span>' : `<button class="btn-danger btn-small" data-delete-user="${u.id}" data-user-name="${escapeHtml(u.name).replaceAll('"','&quot;')}">Удалить</button>`}</td></tr>`).join('');
        $('#emptyUsers').hidden = users.length > 0;
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

$('#createUserForm').addEventListener('submit', async e => {
    e.preventDefault();
    const submitBtn = e.target.querySelector('button[type="submit"]');
    setButtonBusy(submitBtn, true, 'Создание…');
    try {
        const name = $('#newUserName').value.trim(), password = $('#newUserPassword').value;
        const r = await apiFetch('/api/users', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({name,password})});
        if (!r.ok) { const err = await r.json().catch(()=>({})); showToast('Не удалось создать пользователя', err.error || `HTTP ${r.status}`, 'error'); return; }
        $('#newUserName').value = ''; $('#newUserPassword').value = '';
        const usersResponse = await apiFetch('/api/auth/users'); state.users = await usersResponse.json(); fillUserSelect();
        await loadUsers(); showToast('Пользователь создан', name, 'ok');
    } finally {
        setButtonBusy(submitBtn, false);
    }
});

$('#tableUsers').addEventListener('click', async e => {
    const btn = e.target.closest('button[data-delete-user]');
    if (!btn) return;
    const id = Number(btn.dataset.deleteUser);
    const name = btn.dataset.userName || 'пользователя';
    if (!confirm(`Удалить пользователя «${name}»? Его активные сессии будут завершены.`)) return;
    const r = await apiFetch('/api/users/' + id, {method:'DELETE'});
    if (!r.ok) {
        const err = await r.json().catch(()=>({}));
        showToast('Не удалось удалить пользователя', err.error || `HTTP ${r.status}`, 'error');
        return;
    }
    const usersResponse = await apiFetch('/api/auth/users');
    state.users = await usersResponse.json();
    fillUserSelect();
    await loadUsers();
    showToast('Пользователь удалён', name, 'ok');
});

/* =========================================================
 *  ДОКУМЕНТЫ
 * ========================================================= */
async function loadDocuments() {
    if (!state.user) return;
    const wrap = document.getElementById('documentsTableWrap');
    const tbody = document.querySelector('#tableDocuments tbody');
    setTableBusy(wrap, true);
    renderSkeleton(tbody, 5, 5);
    beginLoading();
    try {
        const r = await apiFetch('/api/documents');
        if (r.status === 401) return;
        if (!r.ok) throw new Error('HTTP ' + r.status);
        state.documents = await r.json();
        renderDocuments();
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

function renderDocuments() {
    const q = ($('#searchDocuments').value || '').toLowerCase().trim();
    const rows = state.documents.filter(d => !q || (d.title || '').toLowerCase().includes(q) || (d.documentNumber || '').toLowerCase().includes(q));
    $('#tableDocuments tbody').innerHTML = rows.map(d => `
        <tr data-document-id="${d.id}">
            <td><strong>${escapeHtml(d.title)}</strong></td>
            <td>${escapeHtml(d.documentNumber || '—')}</td>
            <td>${formatDateTime(d.createdAt)}</td>
            <td>${d.deviceCount || 0}</td>
            <td>${escapeHtml(d.description || '—')}</td>
        </tr>`).join('');
    $('#emptyDocuments').hidden = rows.length > 0;
}

function openDocumentForm(doc = null) {
    $('#documentModalTitle').textContent = doc ? 'Изменить документ' : 'Новый документ';
    $('#documentId').value = doc ? doc.id : '';
    $('#documentTitle').value = doc ? doc.title : '';
    $('#documentNumber').value = doc ? (doc.documentNumber || '') : '';
    $('#documentDescription').value = doc ? (doc.description || '') : '';
    $('#documentModal').hidden = false;
    $('#documentTitle').focus();
}
function closeDocumentForm() { $('#documentModal').hidden = true; }

async function openDocument(id) {
    const wrap = document.getElementById('documentDevicesWrap');
    setTableBusy(wrap, true);
    beginLoading();
    try {
        const r = await apiFetch('/api/documents/' + id);
        if (r.status === 401) return;
        if (!r.ok) throw new Error('Не удалось открыть документ: HTTP ' + r.status);
        state.currentDocument = await r.json();
        renderDocumentWorkspace();
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

function renderDocumentWorkspace() {
    const d = state.currentDocument;
    if (!d) return;
    $('#documentsListView').hidden = true;
    $('#documentWorkspace').hidden = false;
    $('#documentWorkspaceTitle').textContent = d.title || 'Документ';
    $('#documentWorkspaceMeta').textContent = 'Документ № ' + (d.documentNumber || '—') + ' · ID ' + d.id;
    $('#documentNumberView').textContent = d.documentNumber || '—';
    $('#documentCreatedView').textContent = formatDateTime(d.createdAt);
    $('#documentDescriptionView').textContent = d.description || '—';
    const devices = d.devices || [];
    $('#documentDeviceCount').textContent = devices.length + ' приборов';
    $('#tableDocumentDevices tbody').innerHTML = devices.map(v => `
        <tr>
            <td class="photo-cell">${photoHtml(v)}</td>
            <td>${escapeHtml(v.name)}</td><td>${escapeHtml(v.serialNumber || '—')}</td>
            <td>${escapeHtml(v.location || '—')}</td><td>${formatDate(v.nextVerification)}</td>
            <td><button class="btn-link" data-unlink-device="${v.id}">Убрать из документа</button></td>
        </tr>`).join('');
    $('#emptyDocumentDevices').hidden = devices.length > 0;
}

function closeDocumentWorkspace() {
    state.currentDocument = null;
    $('#documentWorkspace').hidden = true;
    $('#documentsListView').hidden = false;
    loadDocuments().catch(e => showToast('Ошибка загрузки документов', e.message, 'error'));
}

function renderDocumentDeviceSearch() {
    const input = $('#documentDeviceSearch');
    const results = $('#documentDeviceSearchResults');
    const q = (input.value || '').trim().toLowerCase();
    const linked = new Set((state.currentDocument?.devices || []).map(d => d.id));
    const available = state.devices.filter(d => !linked.has(d.id));
    const matches = q ? available.filter(d =>
        (d.name || '').toLowerCase().includes(q) ||
        (d.location || '').toLowerCase().includes(q) ||
        (d.serialNumber || '').toLowerCase().includes(q)
    ).slice(0, 10) : available.slice(0, 10);
    results.innerHTML = matches.map(d => `<button type="button" class="device-search-result" role="option" data-select-document-device="${d.id}">
        <strong>${escapeHtml(d.name)}</strong><span>${escapeHtml(d.serialNumber || 'Без зав. номера')} · ${escapeHtml(d.location || 'Позиция не указана')}</span>
    </button>`).join('');
    if (!matches.length) results.innerHTML = '<div class="device-search-empty">Ничего не найдено</div>';
}

async function showAddDeviceToDocument() {
    if (!state.currentDocument) return;
    const linked = new Set((state.currentDocument.devices || []).map(d => d.id));
    const available = state.devices.filter(d => !linked.has(d.id));
    if (!available.length) {
        showToast('Нет доступных приборов', 'Все активные приборы уже добавлены в этот документ', 'info');
        return;
    }
    state.selectedDocumentDeviceId = null;
    $('#documentDeviceSearch').value = '';
    $('#documentDeviceSelection').textContent = 'Прибор не выбран';
    $('#confirmAddDeviceDocument').disabled = true;
    renderDocumentDeviceSearch();
    $('#addDeviceDocumentModal').hidden = false;
    $('#documentDeviceSearch').focus();
}

$('#documentDeviceSearch').addEventListener('input', debounce(renderDocumentDeviceSearch, 120));
$('#documentDeviceSearchResults').addEventListener('click', e => {
    const btn = e.target.closest('[data-select-document-device]');
    if (!btn) return;
    const id = Number(btn.dataset.selectDocumentDevice);
    const device = state.devices.find(d => d.id === id);
    if (!device) return;
    state.selectedDocumentDeviceId = id;
    $('#documentDeviceSelection').textContent = `Выбран: ${device.name}${device.serialNumber ? ' · ' + device.serialNumber : ''}`;
    $('#confirmAddDeviceDocument').disabled = false;
    $all('.device-search-result').forEach(el => el.classList.toggle('selected', el === btn));
});

$('#addDocumentBtn').addEventListener('click', () => openDocumentForm());
$('#searchDocuments').addEventListener('input', debounce(renderDocuments, 180));
$('#documentModalClose').addEventListener('click', closeDocumentForm);
$('#cancelDocumentBtn').addEventListener('click', closeDocumentForm);
$('#backToDocumentsBtn').addEventListener('click', closeDocumentWorkspace);
$('#editDocumentBtn').addEventListener('click', () => state.currentDocument && openDocumentForm(state.currentDocument));
$('#deleteDocumentBtn').addEventListener('click', async () => {
    const d = state.currentDocument;
    if (!d || !confirm('Удалить документ «' + d.title + '»? Приборы останутся в общей базе.')) return;
    const r = await apiFetch('/api/documents/' + d.id, {method:'DELETE'});
    if (!r.ok) { showToast('Ошибка удаления документа', 'HTTP ' + r.status, 'error'); return; }
    state.currentDocument = null;
    $('#documentWorkspace').hidden = true; $('#documentsListView').hidden = false;
    await loadDocuments(); showToast('Документ удалён', 'Приборы не удалялись', 'info');
});
$('#documentForm').addEventListener('submit', async e => {
    e.preventDefault();
    const submitBtn = e.target.querySelector('button[type="submit"]');
    setButtonBusy(submitBtn, true, 'Сохранение…');
    try {
        const id = $('#documentId').value;
        const body = {title:$('#documentTitle').value.trim(), documentNumber:$('#documentNumber').value.trim(), description:$('#documentDescription').value.trim()};
        const r = await apiFetch(id ? '/api/documents/' + id : '/api/documents', {method:id ? 'PUT' : 'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
        if (!r.ok) { const err = await r.json().catch(()=>({})); showToast('Ошибка сохранения', err.error || ('HTTP ' + r.status), 'error'); return; }
        const saved = await r.json(); closeDocumentForm();
        if (id) { state.currentDocument = saved; renderDocumentWorkspace(); }
        await loadDocuments();
        if (!id) await openDocument(saved.id);
        showToast(id ? 'Документ обновлён' : 'Документ создан', saved.title, 'info');
    } finally {
        setButtonBusy(submitBtn, false);
    }
});
$('#tableDocuments').addEventListener('click', e => {
    const row = e.target.closest('tr[data-document-id]');
    if (row) openDocument(Number(row.dataset.documentId)).catch(err => showToast('Ошибка', err.message, 'error'));
});
$('#addDeviceToDocumentBtn').addEventListener('click', showAddDeviceToDocument);
$('#closeAddDeviceDocument').addEventListener('click', () => $('#addDeviceDocumentModal').hidden = true);
$('#cancelAddDeviceDocument').addEventListener('click', () => $('#addDeviceDocumentModal').hidden = true);
$('#confirmAddDeviceDocument').addEventListener('click', async () => {
    const btn = $('#confirmAddDeviceDocument');
    const d = state.currentDocument, deviceId = Number(state.selectedDocumentDeviceId);
    if (!d || !deviceId) return;
    setButtonBusy(btn, true, 'Добавление…');
    try {
        const r = await apiFetch(`/api/documents/${d.id}/devices/${deviceId}`, {method:'POST'});
        if (!r.ok) { const err = await r.json().catch(()=>({})); showToast('Не удалось добавить прибор', err.error || ('HTTP ' + r.status), 'error'); return; }
        state.currentDocument = await r.json(); renderDocumentWorkspace();
        $('#addDeviceDocumentModal').hidden = true;
        state.selectedDocumentDeviceId = null;
        await loadDocuments(); showToast('Прибор добавлен в документ', '', 'info');
    } finally {
        setButtonBusy(btn, false);
    }
});
$('#tableDocumentDevices').addEventListener('click', async e => {
    const btn = e.target.closest('button[data-unlink-device]');
    if (!btn || !state.currentDocument) return;
    const id = Number(btn.dataset.unlinkDevice);
    if (!confirm('Убрать прибор из документа? Сам прибор останется в общей базе.')) return;
    const r = await apiFetch(`/api/documents/${state.currentDocument.id}/devices/${id}`, {method:'DELETE'});
    if (!r.ok) { showToast('Ошибка', 'HTTP ' + r.status, 'error'); return; }
    state.currentDocument = await r.json(); renderDocumentWorkspace(); await loadDocuments();
});

/* =========================================================
 *  ОБРАБОТЧИКИ "ВСЕ ПРИБОРЫ"
 * ========================================================= */
$('#searchAll').addEventListener('input', debounce(renderAllTab, 180));
if ($('#statusFilter')) $('#statusFilter').addEventListener('change', renderAllTab);

// Текстовые фильтры по столбцам
$all('.col-filter').forEach(inp => {
    inp.addEventListener('input', debounce(() => {
        const col = inp.dataset.col;
        const value = (inp.value || '').toLowerCase().trim();
        if (value) state.columnFilters[col] = value;
        else delete state.columnFilters[col];
        renderAllTab();
    }, 200));
});

// Выпадающие фильтры-сортировки по датам в шапке таблицы
$all('.col-sort').forEach(sel => {
    // Проставляем начальное значение из state, чтобы UI соответствовал логике
    const col = sel.dataset.col;
    if (state.columnSorts[col]) sel.value = state.columnSorts[col];

    sel.addEventListener('change', () => {
        const dir = sel.value;
        if (dir) state.columnSorts[col] = dir;
        else delete state.columnSorts[col];
        renderAllTab();
    });
});

const resetBtn = $('#resetColumnFiltersBtn');
if (resetBtn) {
    resetBtn.addEventListener('click', () => {
        state.columnFilters = {};
        // Возвращаем сортировку по умолчанию: ближайшие поверки сверху
        state.columnSorts = { nextVerification: 'asc' };
        $all('.col-filter').forEach(inp => { inp.value = ''; });
        $all('.col-sort').forEach(sel => {
            sel.value = (sel.dataset.col === 'nextVerification') ? 'asc' : '';
        });
        renderAllTab();
    });
}

$('#refreshAuditBtn').addEventListener('click', () => renderAuditTab(200));
$('#showAllAuditBtn').addEventListener('click', () => renderAuditTab(999999));
$('#refreshTrashBtn').addEventListener('click', renderTrashTab);

function setFormValue(id, value) {
    const el = $('#' + id);
    if (el) el.value = value == null ? '' : String(value);
}

function openModal(device) {
    $('#modalTitle').textContent = device ? 'Редактировать прибор' : 'Новый прибор';
    setFormValue('fId', device ? device.id : '');
    setFormValue('fVersion', device ? (device.version || 0) : 0);
    setFormValue('fSerial', device?.serialNumber);
    setFormValue('fName', device?.positionName || device?.name);
    setFormValue('fLocation', device?.location);
    setFormValue('fSensorType', device?.sensorType);
    setFormValue('fUnit', device?.unit);
    setFormValue('fScaleMin', device?.scaleMin);
    setFormValue('fScaleMax', device?.scaleMax);
    setFormValue('fCategory', device?.category);
    setFormValue('fLast', device?.lastVerification);
    setFormValue('fMpi', device?.mpi);
    setFormValue('fNext', device?.nextVerification);
    setFormValue('fAccuracyClass', device?.accuracyClass);
    setFormValue('fRedLine', device?.redLine);
    setFormValue('fWriteOff', device?.writeOff);
    setFormValue('fDeferredVerification', device?.deferredVerification);
    setFormValue('fRowNumber', device?.rowNumber);
    setFormValue('fGroupName', device?.groupName);

    const prev = $('#photoPreview');
    const removeBtn = $('#removePhotoBtn');
    const hasPhoto = device && (device.photoPath || device.photoThumbPath) &&
                     device.photoPath !== 'null' && device.photoPath !== 'undefined';
    if (hasPhoto) {
        prev.innerHTML = '<img src="/' + (device.photoThumbPath || device.photoPath) + '" alt="">';
        removeBtn.hidden = false;
    } else {
        prev.innerHTML = 'Нет фото';
        removeBtn.hidden = true;
    }
    $('#fPhoto').value = '';
    $('#modal').hidden = false;
}

function collectDeviceFormData() {
    return {
        serialNumber: $('#fSerial').value.trim(),
        name: $('#fName').value.trim(),
        positionName: $('#fName').value.trim(),
        location: $('#fLocation').value.trim(),
        sensorType: $('#fSensorType').value.trim(),
        unit: $('#fUnit').value.trim(),
        scaleMin: $('#fScaleMin').value.trim(),
        scaleMax: $('#fScaleMax').value.trim(),
        category: $('#fCategory').value,
        lastVerification: $('#fLast').value || '',
        mpi: $('#fMpi').value.trim(),
        nextVerification: $('#fNext').value || '',
        accuracyClass: $('#fAccuracyClass').value.trim(),
        redLine: $('#fRedLine').value.trim(),
        writeOff: $('#fWriteOff').value || '',
        deferredVerification: $('#fDeferredVerification').value.trim(),
        rowNumber: $('#fRowNumber').value.trim(),
        groupName: $('#fGroupName').value.trim(),
        version: parseInt($('#fVersion').value, 10) || 0
    };
}

function closeModal() { $('#modal').hidden = true; }

$('#addBtn').addEventListener('click', () => openModal(null));
$('#cancelBtn').addEventListener('click', closeModal);
$('#modalCloseBtn').addEventListener('click', closeModal);

$('#removePhotoBtn').addEventListener('click', async (e) => {
    e.preventDefault();
    const id = $('#fId').value;
    if (!id) return;

    if (!confirm('Удалить фотографию этого прибора?')) return;

    beginLoading();
    try {
        const body = {
            ...collectDeviceFormData(),
            photoPath: null,
            photoThumbPath: null
        };

        const r = await fetch('/api/devices/' + id, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        if (r.status === 409) {
            const err = await r.json();
            showToast('Конфликт изменений', err.message || 'Прибор уже был изменён другим пользователем', 'error');
            await loadAll();
            closeModal();
            return;
        }
        if (!r.ok) throw new Error('HTTP ' + r.status);

        showToast('Фото удалено', '', 'info');

        $('#photoPreview').innerHTML = 'Нет фото';
        $('#removePhotoBtn').hidden = true;

        await loadAll();

    } catch (err) {
        showToast('Ошибка удаления фото', err.message, 'error');
    } finally {
        finishLoading();
    }
});

$('#deviceForm').addEventListener('submit', async e => {
    e.preventDefault();
    const submitBtn = e.target.querySelector('button[type="submit"]');
    setButtonBusy(submitBtn, true, 'Сохранение…');
    beginLoading();
    try {
        const id = $('#fId').value;
        const body = collectDeviceFormData();
        const url = id ? '/api/devices/' + id : '/api/devices';
        const method = id ? 'PUT' : 'POST';
        const r = await fetch(url, {
            method, headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        if (r.status === 409) {
            const err = await r.json();
            showToast('Конфликт изменений', err.message || 'Прибор уже был изменён другим пользователем', 'error');
            await loadAll();
            closeModal();
            return;
        }
        if (!r.ok) { showToast('Ошибка сохранения', 'HTTP ' + r.status, 'error'); return; }
        const saved = await r.json();

        const fileInput = $('#fPhoto');
        if (fileInput.files && fileInput.files[0]) {
            const file = fileInput.files[0];
            let blob = file;
            if (file.type.startsWith('image/')) {
                try { blob = await compressImage(file); }
                catch (e) { console.warn('Сжатие не удалось', e); }
            }
            const fr = await fetch('/api/devices/' + saved.id + '/photo', {
                method: 'POST',
                headers: { 'Content-Type': blob.type || 'application/octet-stream' },
                body: blob
            });
            if (!fr.ok) {
                showToast('Фото не загружено', 'HTTP ' + fr.status, 'error');
            } else {
                const photoResp = await fr.json();
                showToast('Фото обновлено', photoResp.changes || '', 'ok');
            }
        }

        if (!id) showToast('Прибор добавлен', saved.name || '', 'info');
        else {
            const changes = saved.changes;
            if (!changes || changes === 'no changes') showToast('Изменений нет', 'Поля не были изменены', 'info');
            else showToast('Изменения сохранены', humanizeChanges(changes), 'ok');
        }

        closeModal();
        await loadAll();
        if (state.currentTab === 'audit') await renderAuditTab(200);
    } finally {
        setButtonBusy(submitBtn, false);
        finishLoading();
    }
});

let currentViewDevice = null;

function formatFullDate(dateStr) {
    if (!dateStr || dateStr === '—' || dateStr === 'null') return '—';
    const m = String(dateStr).match(/^(\d{4})-(\d{2})-(\d{2})/);
    return m ? `${m[3]}.${m[2]}.${m[1]}` : String(dateStr);
}

function openViewModal(device) {
    currentViewDevice = device;
    $('#viewName').textContent = device.positionName || device.name || 'Без названия';

    const status = statusOf(device);
    const fields = [
        ['Заводской номер', device.serialNumber],
        ['Позиция', device.location],
        ['Тип датчика', device.sensorType],
        ['Единица измерения', device.unit],
        ['Минимум шкалы', device.scaleMin],
        ['Максимум шкалы', device.scaleMax],
        ['П/К', device.category],
        ['Дата поверки', formatFullDate(device.lastVerification)],
        ['МПИ', device.mpi],
        ['Дата следующей поверки', formatFullDate(device.nextVerification)],
        ['Класс точности', device.accuracyClass],
        ['Красная черта', device.redLine],
        ['Списание', device.writeOff],
        ['Отложенная поверка', device.deferredVerification],
        ['Строка', device.rowNumber],
        ['Группа', device.groupName],
        ['Наименование позиции', device.positionName || device.name],
        ['Статус поверки', statusLabel(status)]
    ];

    const details = $('#viewDetails');
    details.innerHTML = fields.map(([label, value]) => `
        <div class="detail-row">
            <span class="detail-label">${escapeHtml(label)}</span>
            <span class="detail-value">${escapeHtml(value == null || String(value).trim() === '' ? '—' : value)}</span>
        </div>
    `).join('');

    const photo = $('#viewPhoto');
    const body = document.querySelector('.view-body');
    const hasPhoto = device.photoPath && device.photoPath !== 'null' && String(device.photoPath).trim() !== '';

    if (hasPhoto) {
        photo.src = '/' + device.photoPath;
        photo.hidden = false;
        body.classList.remove('no-photo');
    } else {
        photo.hidden = true;
        photo.src = '';
        body.classList.add('no-photo');
    }

    $('#viewModal').hidden = false;
    loadDeviceHistory(device.id).catch(err => showToast('Не удалось загрузить историю', err.message, 'error'));
}
async function loadDeviceHistory(deviceId) {
    $('#physicalHistory').innerHTML = '<span class="muted">Загрузка…</span>';
    $('#documentHistory').innerHTML = '<span class="muted">Загрузка…</span>';
    const r = await apiFetch('/api/devices/' + deviceId + '/movements');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = await r.json();
    const movements = data.movements || [];
    const docs = data.documents || [];
    const latest = movements[0];
    $('#movementCurrentState').textContent = latest ? `Текущее состояние: ${latest.eventType} · ${formatFullDate(latest.eventDate)}` : 'Текущее состояние: не указано';
    $('#physicalHistory').innerHTML = movements.length ? movements.map(m => `
        <div class="history-item"><strong>${escapeHtml(m.eventType)}</strong><span class="history-date">${formatFullDate(m.eventDate)} · ${escapeHtml(m.createdBy || '')}</span>
        ${m.fromPlace ? `<p><b>Откуда:</b> ${escapeHtml(m.fromPlace)}</p>` : ''}${m.toPlace ? `<p><b>Куда:</b> ${escapeHtml(m.toPlace)}</p>` : ''}${m.note ? `<p>${escapeHtml(m.note)}</p>` : ''}</div>`).join('') : '<span class="muted">Перемещений пока нет</span>';
    $('#documentHistory').innerHTML = docs.length ? docs.map(d => `
        <div class="history-item"><strong>${escapeHtml(d.eventType)}</strong><span class="history-date">${formatFullDate(d.eventDate)} · ${escapeHtml(d.createdBy || '')}</span>
        <p>${escapeHtml(d.documentTitle || 'Документ')}${d.documentNumber ? ' № ' + escapeHtml(d.documentNumber) : ''}</p></div>`).join('') : '<span class="muted">История документов пока пуста</span>';
}

$('#addMovementBtn').addEventListener('click', () => {
    $('#movementForm').hidden = false;
    $('#movementDate').value = today();
    $('#movementType').focus();
});
$('#cancelMovementBtn').addEventListener('click', () => { $('#movementForm').hidden = true; $('#movementForm').reset(); });
$('#movementForm').addEventListener('submit', async e => {
    e.preventDefault();
    if (!currentViewDevice) return;
    const submitBtn = e.target.querySelector('button[type="submit"]');
    setButtonBusy(submitBtn, true, 'Сохранение…');
    try {
        const payload = { eventDate:$('#movementDate').value, eventType:$('#movementType').value, fromPlace:$('#movementFrom').value.trim(), toPlace:$('#movementTo').value.trim(), note:$('#movementNote').value.trim() };
        const r = await apiFetch('/api/devices/' + currentViewDevice.id + '/movements', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
        const result = await r.json();
        if (!r.ok) throw new Error(result.error || ('HTTP ' + r.status));
        $('#movementForm').hidden = true; $('#movementForm').reset();
        await loadDeviceHistory(currentViewDevice.id);
        if (payload.eventType === 'Установлен' && payload.toPlace) { currentViewDevice.location = payload.toPlace; await loadAll(); }
        showToast('Операция сохранена', payload.eventType, 'ok');
    } catch(err) { showToast('Ошибка сохранения операции', err.message, 'error'); }
    finally { setButtonBusy(submitBtn, false); }
});

function closeViewModal() {
    $('#viewModal').hidden = true;
    currentViewDevice = null;
}

$('#viewEditBtn').addEventListener('click', () => {
    if (!currentViewDevice) return;
    const d = currentViewDevice;
    closeViewModal();
    openModal(d);
});

$('#viewCloseBtn').addEventListener('click', closeViewModal);
$('#viewCloseBtn2').addEventListener('click', closeViewModal);

$('#viewDeleteBtn').addEventListener('click', async () => {
    if (!currentViewDevice) return;
    const d = currentViewDevice;
    if (!confirm('Переместить прибор «' + (d.name || '') + '» в корзину?')) return;

    beginLoading();
    try {
        const r = await fetch('/api/devices/' + d.id, { method: 'DELETE' });
        if (!r.ok) throw new Error('HTTP ' + r.status);

        closeViewModal();

        $('#searchAll').value = '';
        if ($('#statusFilter')) $('#statusFilter').value = '';

        await loadAll();
        showToast('Прибор перемещён в корзину', d.name || '', 'info');
        if (state.currentTab === 'audit') await renderAuditTab(200);
    } catch (err) {
        showToast('Ошибка удаления', err.message, 'error');
    } finally {
        finishLoading();
    }
});

$('#tableAll').addEventListener('click', async e => {
    const row = e.target.closest('tr[data-id]');
    if (!row) return;
    const id = parseInt(row.dataset.id, 10);
    const d = state.devices.find(x => x.id === id);
    if (d) openViewModal(d);
});

$('#tableTrash').addEventListener('click', async e => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;

    if (btn.dataset.act === 'restore') {
        const id = parseInt(btn.dataset.id, 10);
        if (!confirm('Восстановить этот прибор?')) return;

        beginLoading();
        try {
            const r = await fetch('/api/devices/' + id + '/restore', { method: 'POST' });
            if (!r.ok) throw new Error('HTTP ' + r.status);

            await loadAll();
            await renderTrashTab();

            showToast('Прибор восстановлен', '', 'ok');
        } catch (err) {
            showToast('Ошибка восстановления', err.message, 'error');
        } finally {
            finishLoading();
        }
    }
});

function printDeviceCard(device) {
    let printArea = document.getElementById('printArea');
    if (!printArea) {
        printArea = document.createElement('div');
        printArea.id = 'printArea';
        document.body.appendChild(printArea);
    }

    let photoBlock = '';
    if (device.photoPath) {
        photoBlock = '<div class="print-photo">' +
                     '<img src="/' + device.photoPath + '" alt="Фото прибора">' +
                     '<p>Фотография прибора</p>' +
                     '</div>';
    }

    printArea.innerHTML = `
        <div class="print-header">
            <h1>Карточка прибора КИП</h1>
            <p>Сформировано: ${new Date().toLocaleString('ru-RU')}</p>
        </div>

        <div class="print-content">
            ${photoBlock}
            <div class="print-details">
                <table>
                    <tr><td>Наименование:</td><td>${escapeHtml(device.name)}</td></tr>
                    <tr><td>Заводской номер:</td><td>${escapeHtml(device.serialNumber || '—')}</td></tr>
                    <tr><td>Позиция:</td><td>${escapeHtml(device.location || '—')}</td></tr>
                    <tr><td>Последняя поверка:</td><td>${formatDate(device.lastVerification)}</td></tr>
                    <tr><td>Следующая поверка:</td><td>${formatDate(device.nextVerification)}</td></tr>
                    <tr><td>Статус:</td><td>${statusLabel(statusOf(device))}</td></tr>
                </table>
            </div>
        </div>

        <div class="print-footer">
            <div>Ответственный: _______________________</div>
            <div>Подпись: _______________________</div>
        </div>
    `;

    window.print();
}

$('#viewPrintBtn').addEventListener('click', () => {
    if (!currentViewDevice) return;
    printDeviceCard(currentViewDevice);
});

/* =========================================================
 *  СТАРТ
 * ========================================================= */
document.addEventListener('DOMContentLoaded', () => {
    initializeAuth();
});
