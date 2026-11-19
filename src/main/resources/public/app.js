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
    allPageRows: [],
    referencePositions: [],
    referencePositionIndex: new Map(),
    referencePositionPage: 0,
    editingReferencePositionId: null,
    stats: { total: 0, overdue: 0, soon: 0, ok: 0 },
    currentTab: 'all',
    documents: [],
    currentDocument: null,
    users: [],
    user: null,
    columnFilters: {}, // текстовые фильтры: { col: 'строка' }
    // Сортировки по столбцам.
    // По умолчанию «Дата следующей поверки» — ASC: ближайшие поверки сверху.
    columnSorts: { nextVerification: 'asc' },
    pageOffset: 0,
    pageHasMore: false,
    pageLoading: false,
    workflowDataLoaded: false
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

function formatWorkflowTimestamp(value) {
    if (!value) return '—';
    const d = new Date(value);
    if (!Number.isNaN(d.getTime())) return d.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    return formatDateTime(String(value).replace('T', ' ')) || String(value);
}

function locationStatusOf(d) {
    const value = d.workflowStatus;
    if (value === 'WRITTEN_OFF' || d.writeOff) return 'Списан';
    if (value === 'KIP') return 'КИП';
    if (value === 'WAREHOUSE') return 'Склад';
    if (value === 'INSTALLED') return 'На позиции';
    return String(d.location || '').trim() ? 'На позиции' : 'Склад';
}

function locationStatusClassOf(d) {
    const status = locationStatusOf(d);
    return status === 'Склад' ? 'status-warehouse'
        : status === 'КИП' ? 'status-kip'
        : status === 'На позиции' ? 'status-installed'
        : 'status-written-off';
}
function locationStatusLabel(d) { return locationStatusOf(d); }

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
        case 'locationStatus':      return locationStatusLabel(d);
        case 'groupName':            { const ref = referencePositionForDevice(d); return ref ? ref.equipment : (d.groupName || ''); }
        case 'positionName':         { const ref = referencePositionForDevice(d); return ref ? ref.description : (d.positionName || ''); }
        default:                     return '';
    }
}

function populateColumnDatalists() {
    const columns = [
        'serialNumber', 'location', 'sensorType', 'scale', 'category',
        'mpi', 'accuracyClass', 'redLine', 'deferredVerification',
        'groupName', 'positionName', 'locationStatus'
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
    // Старый кеш содержал всю базу; больше не восстанавливаем его в браузер.
    try { localStorage.removeItem(DEVICE_CACHE_KEY); } catch (_) {}
    state.workflowDataLoaded = false;
    state.allPageRows = [];
    state.pageOffset = 0;
    state.pageHasMore = true;
    beginLoading();
    setTableBusy(wrap, true);
    renderSkeleton(tbody, 8, 14);
    try {
        await Promise.all([loadDevices(), loadStats(), loadReferencePositions()]);
        renderAllTab();
        updateDatalists();
        installDevicePageObserver();
        if (workflowTabs[state.currentTab]) await loadFullDevicesForWorkflow();
    } finally {
        setTableBusy(wrap, false);
        finishLoading();
    }
}

async function loadDevices() {
    state.pageOffset = 0;
    state.allPageRows = [];
    state.pageHasMore = true;
    const data = await fetchDevicePage(500, 0);
    state.allPageRows = data.items || [];
    state.pageOffset = state.allPageRows.length;
    state.pageHasMore = !!data.hasMore;
    state.devices = state.allPageRows.slice();
}

async function fetchDevicePage(limit, offset) {
    const params = new URLSearchParams();
    params.set('limit', String(limit));
    params.set('offset', String(offset));
    params.set('q', ($('#searchAll')?.value || '').trim());
    params.set('status', $('#statusFilter')?.value || '');
    params.set('filters', JSON.stringify(state.columnFilters));
    const sorts = Object.entries(state.columnSorts).filter(([,v]) => v);
    const sort = sorts.length ? sorts[sorts.length - 1] : ['nextVerification','asc'];
    params.set('sort', sort[0]);
    params.set('direction', sort[1]);
    const r = await apiFetch('/api/devices/page?' + params.toString());
    if (!r.ok) throw new Error('devices page: ' + r.status);
    return r.json();
}

async function loadMoreDevicePages() {
    if (state.pageLoading || !state.pageHasMore || state.currentTab !== 'all') return;
    state.pageLoading = true;
    try {
        const data = await fetchDevicePage(150, state.pageOffset);
        const items = data.items || [];
        state.allPageRows.push(...items);
        if (!state.workflowDataLoaded) state.devices = state.allPageRows.slice();
        state.pageOffset += items.length;
        state.pageHasMore = !!data.hasMore;
        renderAllTab();
    } catch (e) {
        showToast('Ошибка загрузки следующей страницы', e.message, 'error');
    } finally {
        state.pageLoading = false;
    }
}

let devicePageObserver = null;
function installDevicePageObserver() {
    const sentinel = document.getElementById('devicePageSentinel');
    if (!sentinel || !('IntersectionObserver' in window)) return;
    if (devicePageObserver) devicePageObserver.disconnect();
    devicePageObserver = new IntersectionObserver(entries => {
        if (entries.some(x => x.isIntersecting)) loadMoreDevicePages();
    }, { root: document.getElementById('devicesTableWrap'), rootMargin: '500px' });
    devicePageObserver.observe(sentinel);
}

let deviceFilterRequest = 0;
async function reloadDevicePageFromFilters() {
    const requestId = ++deviceFilterRequest;
    state.allPageRows = [];
    state.pageOffset = 0;
    state.pageHasMore = true;
    const tbody = document.querySelector('#tableAll tbody');
    renderSkeleton(tbody, 5, 14);
    try {
        const data = await fetchDevicePage(500, 0);
        if (requestId !== deviceFilterRequest) return;
        state.allPageRows = data.items || [];
        if (!state.workflowDataLoaded) state.devices = state.allPageRows.slice();
        state.pageOffset = state.allPageRows.length;
        state.pageHasMore = !!data.hasMore;
        renderAllTab();
    } catch (e) {
        if (requestId === deviceFilterRequest) showToast('Ошибка фильтрации приборов', e.message, 'error');
    }
}

async function loadFullDevicesForWorkflow() {
    if (state.workflowDataLoaded) return;
    const r = await apiFetch('/api/devices');
    if (!r.ok) throw new Error('devices: ' + r.status);
    state.devices = await r.json();
    state.workflowDataLoaded = true;
    renderWorkflowTab('positions');
    renderWorkflowTab('warehouse');
    renderWorkflowTab('kip');
    updateWorkflowTabBadges();
}

async function loadReferencePositions() {
    const r = await apiFetch('/api/reference-positions');
    if (!r.ok) throw new Error('reference positions: ' + r.status);
    state.referencePositions = await r.json();
    state.referencePositionIndex = new Map(state.referencePositions.map(x => [normalizePositionCode(x.position), x]));
}

function normalizePositionCode(value) {
    return String(value || '').trim().toLocaleUpperCase('ru-RU').replace(/[‐‑‒–—−]/g, '-').replace(/\s+/g, '').replace(/[ABEKMHOPCTYX]/g, ch => ({A:'А',B:'В',E:'Е',K:'К',M:'М',H:'Н',O:'О',P:'Р',C:'С',T:'Т',Y:'У',X:'Х'}[ch] || ch));
}

function referencePositionForDevice(device) {
    if (device.referencePositionId != null && device.referencePositionId !== '') {
        const linked = state.referencePositions.find(x => Number(x.id) === Number(device.referencePositionId));
        if (linked) return linked;
    }
    const candidates = [device.location, String(device.location || '').split('/').pop(), device.positionName];
    for (const candidate of candidates) {
        const found = state.referencePositionIndex.get(normalizePositionCode(candidate));
        if (found) return found;
    }
    return null;
}

async function loadStats() {
    const r = await apiFetch('/api/stats');
    if (!r.ok) return;
    state.stats = await r.json();
    updateWorkflowTabBadges();
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

function updateWorkflowTabBadges() {
    const counts = { positions: Number(state.stats.positions || 0), warehouse: Number(state.stats.warehouse || 0) };
    for (const [key, count] of Object.entries(counts)) {
        const badge = document.querySelector('.tab[data-tab="' + key + '"] .tab-count-badge');
        if (!badge) continue;
        badge.textContent = String(count);
        badge.hidden = false;
        badge.setAttribute('aria-label', count + ' приборов');
    }
}

function render() {
    renderAllTab();
    Object.keys(workflowTabs).forEach(renderWorkflowTab);
    updateWorkflowTabBadges();
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
    const rows = state.allPageRows || [];
    $('#tableAll tbody').innerHTML = rows.map(d => {
        const ref = referencePositionForDevice(d);
        return `<tr data-id="${d.id}">
        <td>${d.serialNumber ? `<button type="button" class="btn-link serial-device-link" data-open-workflow-device="${escapeHtml(d.id)}">${escapeHtml(d.serialNumber)}</button>` : '—'}</td>
        <td>${escapeHtml(d.sensorType || '—')}</td>
        <td>${escapeHtml(ref ? (ref.equipment || '—') : (d.groupName || '—'))}</td>
        <td>${escapeHtml(ref ? (ref.description || '—') : (d.positionName || d.name || '—'))}</td>
        <td>${escapeHtml(d.location || '—')}</td>
        <td>${escapeHtml(formatScale(d))}</td>
        <td>${escapeHtml(d.category || '—')}</td>
        <td>${formatTableDate(d.lastVerification)}</td>
        <td>${escapeHtml(d.mpi || '—')}</td>
        <td>${formatTableDate(d.nextVerification)}</td>
        <td>${escapeHtml(d.accuracyClass || '—')}</td>
        <td>${escapeHtml(d.redLine || '—')}</td>
        <td>${escapeHtml(d.deferredVerification || '—')}</td>
        <td><span class="location-status ${locationStatusClassOf(d)}">${escapeHtml(locationStatusOf(d))}</span></td>
    </tr>`;
    }).join('');
    const emptyEl = $('#emptyAll');
    if (emptyEl) emptyEl.hidden = rows.length !== 0 || __loadingDepth > 0;
    const sentinel = $('#devicePageSentinel');
    if (sentinel) sentinel.textContent = state.pageLoading ? 'Загрузка…' : (state.pageHasMore ? 'Прокрутите вниз для загрузки следующих приборов' : 'Все приборы загружены');
}


/* =========================================================
 *  Рабочие списки: «На позиции», «Склад», «КИП»
 *  Используют ту же структуру таблицы и те же поля, что «Все приборы».
 * ========================================================= */
const workflowTabs = {
    positions: { status: 'На позиции', title: 'На позиции', actions: [{label:'Переместить на склад', status:'WAREHOUSE', event:'Передача на склад'}] },
    warehouse: { status: 'Склад', title: 'Склад', actions: [{label:'Передать в КИП', status:'KIP', event:'Передача в КИП'}, {label:'Установить на позицию', status:'INSTALLED', event:'Установка на позицию'}, {label:'Списать', status:'WRITTEN_OFF', event:'Списание'}] },
    kip: { status: 'КИП', title: 'КИП', actions: [
        {label:'Вернуть на склад', status:'WAREHOUSE', event:'Возврат из КИП на склад'},
        {label:'Установить на позицию', status:'INSTALLED', event:'Установка на позицию'},
        {label:'Списать', status:'WRITTEN_OFF', event:'Списание'}
    ] }
};
const workflowSelection = { positions: new Set(), warehouse: new Set(), kip: new Set() };
const workflowFilters = { positions: {}, warehouse: {}, kip: {} };

function initializeWorkflowTabs() {
    const source = document.getElementById('tableAll');
    if (!source) return;
    Object.entries(workflowTabs).forEach(([key, config]) => {
        const host = document.querySelector(`[data-workflow-tab="${key}"]`);
        if (!host) return;
        const toolbar = document.createElement('div');
        toolbar.className = 'toolbar workflow-toolbar';
        toolbar.innerHTML = `<input type="search" class="workflow-search" placeholder="Поиск по названию, зав. номеру или позиции…" aria-label="Поиск">
            <span class="workflow-selected-count">Выбрано: 0</span>
            ${config.actions.map((act, i) => `<button type="button" class="btn-primary workflow-action${act.status === 'WRITTEN_OFF' ? ' workflow-action-writeoff' : ''}" data-action-index="${i}" disabled>${act.label}</button>`).join('')}
            <button type="button" class="btn workflow-clear-selection">Снять выделение</button>`;
        const wrap = document.createElement('div');
        wrap.className = 'devices-table-scroll';
        const table = source.cloneNode(true);
        table.id = `tableWorkflow_${key}`;
        table.querySelectorAll('tbody').forEach(tb => tb.innerHTML = '');
        table.querySelectorAll('datalist').forEach(el => el.remove());
        table.querySelectorAll('input.col-filter').forEach(inp => {
            inp.value = '';
            inp.dataset.workflowTab = key;
        });
        table.querySelectorAll('select.col-sort').forEach(sel => {
            sel.value = '';
            sel.dataset.workflowTab = key;
        });
        table.querySelectorAll('thead tr').forEach((tr, index) => {
            // Вкладки «На позиции», «Склад», «КИП»: статус не дублируем в таблице.
            if (tr.children.length) tr.removeChild(tr.lastElementChild);
            const th = document.createElement('th');
            th.className = 'workflow-select-cell';
            th.innerHTML = index === 0
                ? `<input type="checkbox" class="workflow-select-all" aria-label="Выбрать все видимые приборы">`
                : '';
            tr.prepend(th);
            if (key === 'warehouse') {
                const dateCell = document.createElement('th');
                dateCell.className = 'warehouse-date-column';
                dateCell.textContent = index === 0 ? 'Дата поступления на склад' : '';
                const sensorHeader = [...tr.children].find(el => el.textContent.trim() === 'Тип датчика');
                if (sensorHeader) sensorHeader.after(dateCell);
                else tr.appendChild(dateCell);
            }        });
        wrap.appendChild(table);
        const empty = document.createElement('div');
        empty.className = 'empty workflow-empty';
        empty.textContent = 'Нет приборов';
        host.replaceChildren(toolbar, wrap, empty);

        toolbar.querySelector('.workflow-search').addEventListener('input', debounce(() => renderWorkflowTab(key), 180));
        toolbar.querySelector('.workflow-clear-selection').addEventListener('click', () => {
            clearWorkflowTabSelection(key);
            renderWorkflowTab(key);
        });
        toolbar.querySelectorAll('.workflow-action').forEach(btn => btn.addEventListener('click', () => {
            const action = config.actions[Number(btn.dataset.actionIndex)];
            moveSelectedDevices(key, action).catch(err => showToast('Ошибка перемещения', err.message, 'error'));
        }));
        table.addEventListener('change', event => {
            const checkbox = event.target.closest('.workflow-device-check');
            if (checkbox) {
                const id = Number(checkbox.dataset.id);
                if (checkbox.checked) workflowSelection[key].add(id);
                else workflowSelection[key].delete(id);
                updateWorkflowToolbar(key);
                const selectAll = table.querySelector('.workflow-select-all');
                const visible = [...table.querySelectorAll('.workflow-device-check')];
                if (selectAll) selectAll.checked = visible.length > 0 && visible.every(x => x.checked);
                return;
            }
            if (event.target.matches('.workflow-select-all')) {
                table.querySelectorAll('.workflow-device-check').forEach(cb => {
                    cb.checked = event.target.checked;
                    const id = Number(cb.dataset.id);
                    if (event.target.checked) workflowSelection[key].add(id);
                    else workflowSelection[key].delete(id);
                });
                updateWorkflowToolbar(key);
            }
        });
        table.querySelectorAll('.col-filter').forEach(inp => inp.addEventListener('input', debounce(() => {
            const col = inp.dataset.col, value = (inp.value || '').toLowerCase().trim();
            if (value) workflowFilters[key][col] = value;
            else delete workflowFilters[key][col];
            renderWorkflowTab(key);
        }, 180)));
        table.querySelectorAll('.col-sort').forEach(sel => sel.addEventListener('change', () => {
            const col = sel.dataset.col, value = sel.value;
            if (value) workflowFilters[key]['__sort_' + col] = value;
            else delete workflowFilters[key]['__sort_' + col];
            renderWorkflowTab(key);
        }));
        // Карточка прибора открывается только по нажатию на заводской номер,
        // а не по любой ячейке строки (как во вкладке «Перемещения»).
        table.addEventListener('click', event => {
            const serialLink = event.target.closest('[data-open-workflow-device]');
            if (!serialLink) return;
            const deviceId = Number(serialLink.dataset.openWorkflowDevice);
            const device = state.devices.find(d => Number(d.id) === deviceId);
            if (device) openViewModal(device);
        });
    });
}

function renderWorkflowTab(key) {
    const config = workflowTabs[key];
    const host = document.querySelector(`[data-workflow-tab="${key}"]`);
    if (!config || !host) return;
    const table = host.querySelector('table');
    if (!table) return;
    const query = (host.querySelector('.workflow-search')?.value || '').toLowerCase().trim();
    const filters = workflowFilters[key];
    let rows = state.devices.filter(device => {
        if (locationStatusOf(device) !== config.status) return false;
        if (query && ![device.name,device.location,device.serialNumber,device.sensorType,device.unit,device.category,device.groupName,device.positionName,device.redLine].join(' ').toLowerCase().includes(query)) return false;
        for (const [col, needle] of Object.entries(filters)) {
            if (col.startsWith('__sort_')) continue;
            if (!String(cellValueForColumn(device, col) || '').toLowerCase().includes(needle)) return false;
        }
        return true;
    });
    const sortEntries = Object.entries(filters).filter(([col]) => col.startsWith('__sort_'));
    if (sortEntries.length) {
        const [keySort, dir] = sortEntries[sortEntries.length - 1], col = keySort.slice(7);
        rows.sort((a,b) => {
            const av = String(a[col] || ''), bv = String(b[col] || '');
            if (!av) return bv ? 1 : 0;
            if (!bv) return -1;
            return dir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av);
        });
    } else if (key === 'warehouse' || key === 'kip') {
        // В разделах «Склад» и «КИП» сверху показываем приборы,
        // которые последними были перемещены в соответствующий раздел.
        rows.sort((a, b) => {
            const at = Date.parse(a.workflowMovedAt || a.createdAt || '') || 0;
            const bt = Date.parse(b.workflowMovedAt || b.createdAt || '') || 0;
            return bt - at;
        });
    } else {
        rows.sort((a,b) => String(a.nextVerification || '9999').localeCompare(String(b.nextVerification || '9999')));
    }
    table.querySelector('tbody').innerHTML = rows.map(d => {
        const ref = referencePositionForDevice(d);
        const equipment = ref ? ref.equipment : d.groupName;
        const positionName = ref ? ref.description : (d.positionName || d.name);
        return `<tr data-id="${d.id}">
        <td class="workflow-select-cell"><input type="checkbox" class="workflow-device-check" data-id="${d.id}" aria-label="Выбрать прибор" ${workflowSelection[key].has(Number(d.id)) ? 'checked' : ''}></td>
        <td>${d.serialNumber ? `<button type="button" class="btn-link serial-device-link" data-open-workflow-device="${escapeHtml(d.id)}">${escapeHtml(d.serialNumber)}</button>` : '—'}</td>
        <td>${escapeHtml(d.sensorType || '—')}</td>
        <td>${escapeHtml(equipment || '—')}</td>
        <td>${escapeHtml(positionName || '—')}</td>
        <td>${escapeHtml(d.location || '—')}</td>
        ${key === 'warehouse' ? `<td class="warehouse-date-column">${escapeHtml(formatWorkflowTimestamp(d.workflowMovedAt))}</td>` : ''}
        <td>${escapeHtml(formatScale(d))}</td>
        <td>${escapeHtml(d.category || '—')}</td>
        <td>${formatTableDate(d.lastVerification)}</td>
        <td>${escapeHtml(d.mpi || '—')}</td>
        <td>${formatTableDate(d.nextVerification)}</td>
        <td>${escapeHtml(d.accuracyClass || '—')}</td>
        <td>${escapeHtml(d.redLine || '—')}</td>
        <td>${escapeHtml(d.deferredVerification || '—')}</td>
    </tr>`;
    }).join('');

    const empty = host.querySelector('.workflow-empty');
    if (empty) empty.hidden = rows.length > 0;
    const selectAll = table.querySelector('.workflow-select-all');
    if (selectAll) {
        const checks = [...table.querySelectorAll('.workflow-device-check')];
        selectAll.checked = checks.length > 0 && checks.every(cb => cb.checked);
        selectAll.indeterminate = checks.some(cb => cb.checked) && !selectAll.checked;
    }
    updateWorkflowToolbar(key);
}

function clearWorkflowTabSelection(key) {
    workflowSelection[key].clear();
    const host = document.querySelector(`[data-workflow-tab="${key}"]`);
    if (host) {
        host.querySelectorAll('.workflow-device-check, .workflow-select-all').forEach(cb => {
            cb.checked = false;
            cb.indeterminate = false;
        });
        const count = host.querySelector('.workflow-selected-count');
        if (count) count.textContent = 'Выбрано: 0';
        host.querySelectorAll('.workflow-action').forEach(btn => { btn.disabled = true; });
    }
}


function updateWorkflowToolbar(key) {
    const host = document.querySelector(`[data-workflow-tab="${key}"]`);
    if (!host) return;
    const selected = workflowSelection[key].size;
    host.querySelector('.workflow-selected-count').textContent = `Выбрано: ${selected}`;
    host.querySelectorAll('.workflow-action').forEach(btn => {
        const action = workflowTabs[key].actions[Number(btn.dataset.actionIndex)];
        const overBatchLimit = selected > 50;
        btn.disabled = selected === 0 || overBatchLimit;
        btn.title = overBatchLimit ? 'За одну операцию можно переместить не более 50 приборов' : '';
    });
}

async function moveSelectedDevices(fromTab, action) {
    const ids = [...workflowSelection[fromTab]];
    if (!ids.length) return;
    if (ids.length > 50) {
        showToast('Превышен лимит перемещения', `За одну операцию разрешено перемещать не более 50 приборов. Сейчас выбрано: ${ids.length}. Снимите выделение с лишних приборов.`, 'error');
        return;
    }
    const selectedDevices = ids.map(id => state.devices.find(d => Number(d.id) === id)).filter(Boolean);
    if (action.status === 'WAREHOUSE' && selectedDevices.some(device => locationStatusOf(device) === 'Склад')) {
        showToast('Перемещение невозможно', 'Прибор, который уже находится на складе, нельзя повторно отправить на склад.', 'error');
        return;
    }
    const targetLabel = action.status === 'INSTALLED' ? 'На позиции' : action.status === 'WAREHOUSE' ? 'Склад' : action.status === 'WRITTEN_OFF' ? 'Списан' : 'КИП';
    let movementNote = '';
    if (action.status === 'WRITTEN_OFF') {
        const actNumber = window.prompt('Номер акта списания (например, АКТ-2026-015):', '');
        if (actNumber === null) return;
        movementNote = `Акт списания: ${actNumber.trim()}`;
    }
    beginLoading();
    const batchMovedAt = new Date().toISOString();
    let moved = 0, failed = [], successful = [];
    try {
        // Отправляем изменения одним пакетом параллельных запросов и не перерисовываем таблицы
        // до завершения всех ответов сервера.
        const results = await Promise.all(ids.map(async id => {
            const device = state.devices.find(d => Number(d.id) === id);
            if (!device) return { id, error: 'Прибор не найден в текущем списке' };
            const previousStatus = locationStatusOf(device);
            const body = { ...device, workflowStatus: action.status, workflowMovedAt: batchMovedAt, writeOff: action.status === 'WRITTEN_OFF' ? (device.writeOff || new Date().toISOString().slice(0,10)) : (action.status === 'WAREHOUSE' || action.status === 'KIP' || action.status === 'INSTALLED' ? '' : device.writeOff), version: Number(device.version || 0) };
            try {
                const response = await apiFetch('/api/devices/' + id, {
                    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
                });
                if (!response.ok) {
                    const error = await response.json().catch(() => ({}));
                    return { id, device, error: error.message || error.error || response.status };
                }
                return { id, device, updated: await response.json(), previousStatus };
            } catch (error) {
                return { id, device, error: error.message || String(error) };
            }
        }));

        for (const result of results) {
            if (result.error) failed.push(`${result.device?.serialNumber || result.device?.name || ('#' + result.id)}: ${result.error}`);
            else {
                Object.assign(result.device, result.updated);
                successful.push(result);
            }
        }
        moved = successful.length;

        // Одна запись пакетной операции в журнале. В истории каждого прибора
        // сохраняется отдельная запись, но в общем журнале они отображаются одной строкой.
        if (successful.length) {
            const logResponse = await apiFetch('/api/movements/batch', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    deviceIds: successful.map(item => item.id),
                    eventDate: today(), eventType: action.event,
                    fromPlace: [...new Set(successful.map(item => item.previousStatus))].join(', '),
                    toPlace: targetLabel, note: movementNote
                })
            });
            if (!logResponse.ok) {
                const error = await logResponse.json().catch(() => ({}));
                failed.push('Журнал: ' + (error.message || error.error || logResponse.status));
            }
        }

        // Снимаем выделение только с успешно обработанных приборов.
        // При ошибках (например, «Гость может только просматривать данные»)
        // неуспешные приборы остаются отмеченными для повторной попытки после входа.
        successful.forEach(item => workflowSelection[fromTab].delete(Number(item.id)));
        const destinationTab = action.status === 'KIP' ? 'kip' : action.status === 'WAREHOUSE' ? 'warehouse' : action.status === 'INSTALLED' ? 'positions' : null;
        if (moved && destinationTab) {
            clearWorkflowTabSelection(destinationTab);
            successful.forEach(item => workflowSelection[destinationTab].add(Number(item.id)));
            render();
            window.location.hash = destinationTab;
            switchTab(destinationTab);
        } else {
            // Сохраняем оставшиеся выбранные (в том числе все, если запросы отклонены).
            render();
            updateWorkflowToolbar(fromTab);
        }
        if (moved) showToast('Перемещение выполнено', `Перемещено приборов: ${moved} одной операцией`, 'success');
        if (failed.length) showToast('Есть ошибки', failed.slice(0, 3).join('; '), 'error');
    } finally {
        finishLoading();
    }
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
        state.movements = rows;
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
    rows.forEach(m => { const key = movementDateKey(m.eventDate); if (!groups.has(key)) groups.set(key, []); groups.get(key).push(m); });
    let html = '';
    for (const [date, items] of groups) {
        html += `<tr class="movement-date-row"><td colspan="8">${escapeHtml(date)} <span>${items.length} ${items.length === 1 ? 'перемещение' : 'перемещений'}</span></td></tr>`;
        html += items.map(m => `<tr class="movement-open-row" data-open-movement="${escapeHtml(m.id)}" tabindex="0" title="Открыть управление перемещением">
            <td>${escapeHtml(formatDateTime(m.createdAt || m.eventDate))}</td><td><strong>${escapeHtml(m.name || (m.deviceCount + ' прибор'))}</strong></td>
            <td>${escapeHtml((m.deviceIds || m.deviceId || '').split(',').filter(Boolean).map((id, i) => { const d = state.devices.find(x => Number(x.id) === Number(id)); return d?.serialNumber || String(m.serialNumber || '').split(', ')[i] || ('Прибор #' + id); }).join(', ') || m.serialNumber || '—')}</td>
            <td>${escapeHtml(m.eventType || '—')}</td><td>${escapeHtml(m.fromPlace || '—')}</td><td>${escapeHtml(m.toPlace || '—')}</td><td>${escapeHtml(m.note || '—')}</td><td>${escapeHtml(m.createdBy || '—')}</td></tr>`).join('');
    }
    tbody.innerHTML = html; $('#emptyMovements').hidden = rows.length !== 0;
}

let currentMovementForManage = null;
function openMovementManage(m) {
    currentMovementForManage = m;
    const ids = String(m.deviceIds || m.deviceId || '').split(',').filter(Boolean).map(Number);
    $('#movementManageTitle').textContent = `${m.eventType || 'Перемещение'} — ${ids.length} прибор(ов)`;
    $('#movementManageInfo').textContent = `${formatDateTime(m.createdAt || m.eventDate)} | ${m.fromPlace || '—'} → ${m.toPlace || '—'}${m.note ? ' | ' + m.note : ''}`;
    const body = $('#movementManageTable tbody');
    body.innerHTML = ids.map(id => { const d = state.devices.find(x => Number(x.id) === id); return `<tr><td><input type="checkbox" class="movement-device-select" value="${id}"></td><td>${escapeHtml(d?.serialNumber || ('ID ' + id))}</td><td>${escapeHtml(d?.sensorType || '—')}</td><td>${escapeHtml(d?.positionName || d?.location || '—')}</td></tr>`; }).join('');
    $('#movementSelectAll').checked = false; $('#movementManageModal').hidden = false;
}
$('#tableMovements')?.addEventListener('click', e => { const row = e.target.closest('[data-open-movement]'); if (!row) return; const movementId = Number(row.dataset.openMovement); const movement = (state.movements || []).find(x => Number(x.id) === movementId); if (movement) openMovementManage(movement); });
$('#movementManageClose')?.addEventListener('click', () => $('#movementManageModal').hidden = true);
$('#movementSelectAll')?.addEventListener('change', e => document.querySelectorAll('#movementManageTable .movement-device-select').forEach(cb => cb.checked = e.target.checked));
document.getElementById('movementPrint')?.addEventListener('click', () => {
    if (!currentMovementForManage) return;
    const m = currentMovementForManage;
    const ids = String(m.deviceIds || m.deviceId || '').split(',').filter(Boolean).map(Number);
    const devices = ids.map(id => state.devices.find(d => Number(d.id) === id)).filter(Boolean);
    const rows = devices.map((d, i) => `<tr><td>${i + 1}</td><td>${escapeHtml(d.serialNumber || '—')}</td><td>${escapeHtml(d.sensorType || '—')}</td><td>${escapeHtml(d.positionName || d.location || '—')}</td></tr>`).join('');
    const w = window.open('', '_blank');
    if (!w) { showToast('Печать', 'Разрешите открытие всплывающего окна для печати.', 'warning'); return; }
    w.document.write(`<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Перемещение</title><style>body{font:14px Arial;margin:24px;color:#111}h1{font-size:20px}table{border-collapse:collapse;width:100%;margin-top:18px}th,td{border:1px solid #777;padding:7px;text-align:left}th{background:#eee}.meta{margin:8px 0} @media print{button{display:none}}</style></head><body><h1>Список приборов перемещения</h1><div class="meta"><b>Дата:</b> ${escapeHtml(formatDateTime(m.createdAt || m.eventDate))}</div><div class="meta"><b>Операция:</b> ${escapeHtml(m.eventType || '—')}</div><div class="meta"><b>Откуда:</b> ${escapeHtml(m.fromPlace || '—')} &nbsp; <b>Куда:</b> ${escapeHtml(m.toPlace || '—')}</div><div class="meta"><b>Примечание:</b> ${escapeHtml(m.note || '—')}</div><table><thead><tr><th>№</th><th>Зав. номер</th><th>Тип датчика</th><th>Позиция</th></tr></thead><tbody>${rows}</tbody></table><script>window.onload=()=>window.print();<\/script></body></html>`);
    w.document.close();
});
async function cancelMovementDevices(all = false) {
    if (!currentMovementForManage) return;
    const m = currentMovementForManage;
    const allIds = String(m.deviceIds || m.deviceId || '').split(',').filter(Boolean).map(Number);
    const selected = all ? allIds : [...document.querySelectorAll('#movementManageTable .movement-device-select:checked')].map(cb => Number(cb.value));
    if (!selected.length) { showToast('Отмена перемещения', 'Выберите приборы для отмены.', 'warning'); return; }
    beginLoading();
    try {
        const response = await apiFetch('/api/movements/cancel', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({batchId:m.batchId || '',movementId:Number(m.id || 0),deviceIds:selected})});
        const data = await response.json().catch(()=>({}));
        if (!response.ok) throw new Error(data.error || data.message || `HTTP ${response.status}`);
        showToast('Отмена перемещения', `Отменено приборов: ${data.cancelled || selected.length}`, 'success');
        await Promise.all([loadDevices(), loadMovements(movementsLoadAll)]);
        if (all || selected.length === allIds.length) $('#movementManageModal').hidden = true;
        else {
            const remaining = allIds.filter(id => !selected.includes(id));
            m.deviceIds = remaining.join(','); m.deviceCount = remaining.length;
            openMovementManage(m);
        }
        render();
    } catch (err) { showToast('Ошибка отмены', err.message || String(err), 'error'); }
    finally { finishLoading(); }
}
document.getElementById('movementRemoveSelected')?.addEventListener('click', () => cancelMovementDevices(false));
document.getElementById('movementCancelAll')?.addEventListener('click', () => cancelMovementDevices(true));
$('#movementManageModal')?.addEventListener('click', e => { if (e.target.id === 'movementManageModal') e.currentTarget.hidden = true; });
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
            <td>${r.deviceId != null && r.serialNumber && r.serialNumber !== '—'
                ? `<button type="button" class="btn-link serial-device-link" data-open-audit-device="${escapeHtml(r.deviceId)}">${escapeHtml(r.serialNumber)}</button>`
                : escapeHtml(r.serialNumber)}</td>
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
 *  СПРАВОЧНИК ПОЗИЦИЙ ИЗ КНИГА1
 * ========================================================= */
const REFERENCE_POSITION_PAGE_SIZE = 100;
function renderReferencePositions() {
    const search = ($('#referencePositionSearch')?.value || '').trim().toLocaleLowerCase('ru-RU');
    const filtered = state.referencePositions.filter(x => [x.position,x.description,x.equipment].join(' ').toLocaleLowerCase('ru-RU').includes(search));
    const pages = Math.max(1, Math.ceil(filtered.length / REFERENCE_POSITION_PAGE_SIZE));
    state.referencePositionPage = Math.min(state.referencePositionPage, pages - 1);
    const start = state.referencePositionPage * REFERENCE_POSITION_PAGE_SIZE;
    const pageRows = filtered.slice(start, start + REFERENCE_POSITION_PAGE_SIZE);
    $('#referencePositionCount').textContent = `Найдено: ${filtered.length} из ${state.referencePositions.length}`;
    $('#emptyReferencePositions').hidden = filtered.length !== 0;
    $('#tableReferencePositions tbody').innerHTML = pageRows.map(x => {
        const editing = state.editingReferencePositionId === x.id;
        if (editing) return `<tr data-id="${x.id}">
            <td><input class="reference-edit-field" data-field="position" value="${escapeHtml(x.position)}"></td>
            <td><input class="reference-edit-field" data-field="description" value="${escapeHtml(x.description)}"></td>
            <td><input class="reference-edit-field" data-field="equipment" value="${escapeHtml(x.equipment)}"></td>
            <td><button class="btn-primary" data-ref-act="save">Сохранить</button> <button class="btn" data-ref-act="cancel">Отмена</button></td>
        </tr>`;
        const editAction = state.user && state.user.role !== 'guest' ? '<button class="btn-link" data-ref-act="edit">Редактировать</button>' : '<span class="muted">Только просмотр</span>';
        const deleteAction = state.user && state.user.role !== 'guest' ? ' <button class="btn-link danger-link" data-ref-act="delete">Удалить</button>' : '';
        return `<tr data-id="${x.id}"><td>${escapeHtml(x.position)}</td><td>${escapeHtml(x.description || '—')}</td><td>${escapeHtml(x.equipment || '—')}</td><td>${editAction}${deleteAction}</td></tr>`;
    }).join('');
    $('#referencePositionPager').innerHTML = `<button class="btn" data-ref-page="prev" ${state.referencePositionPage === 0 ? 'disabled' : ''}>← Назад</button><span class="muted">Страница ${state.referencePositionPage + 1} из ${pages}</span><button class="btn" data-ref-page="next" ${state.referencePositionPage >= pages - 1 ? 'disabled' : ''}>Вперёд →</button>`;
}

async function refreshReferencePositions() {
    const r = await apiFetch('/api/reference-positions');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    state.referencePositions = await r.json();
    state.referencePositionIndex = new Map(state.referencePositions.map(x => [normalizePositionCode(x.position), x]));
    renderReferencePositions();
    renderAllTab();
}

$('#referencePositionSearch')?.addEventListener('input', () => { state.referencePositionPage = 0; renderReferencePositions(); });
$('#refreshReferencePositionsBtn')?.addEventListener('click', () => refreshReferencePositions().catch(e => showToast('Ошибка справочника позиций', e.message, 'error')));
$('#tableReferencePositions')?.addEventListener('click', async e => {
    const button = e.target.closest('button[data-ref-act]');
    if (!button) return;
    const row = button.closest('tr');
    const id = Number(row.dataset.id);
    if (button.dataset.refAct === 'edit') { state.editingReferencePositionId = id; renderReferencePositions(); return; }
    if (button.dataset.refAct === 'delete') {
        const item = state.referencePositions.find(x => x.id === id);
        button.disabled = true;
        try {
            const r = await apiFetch('/api/reference-positions/' + id, {method:'DELETE'});
            const result = await r.json().catch(() => ({}));
            if (!r.ok) throw new Error(result.error || ('HTTP ' + r.status));
            state.referencePositions = state.referencePositions.filter(x => x.id !== id);
            state.referencePositionIndex = new Map(state.referencePositions.map(x => [normalizePositionCode(x.position), x]));
            renderReferencePositions(); renderAllTab();
            showToast('Позиция удалена', item?.position || '', 'ok');
        } catch (err) { showToast('Ошибка удаления', err.message, 'error'); button.disabled = false; }
        return;
    }
    if (button.dataset.refAct === 'cancel') { state.editingReferencePositionId = null; renderReferencePositions(); return; }
    if (button.dataset.refAct === 'save') {
        const payload = {};
        row.querySelectorAll('[data-field]').forEach(input => payload[input.dataset.field] = input.value.trim());
        button.disabled = true;
        try {
            const r = await apiFetch('/api/reference-positions/' + id, {method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)});
            const result = await r.json();
            if (!r.ok) throw new Error(result.error || ('HTTP ' + r.status));
            const idx = state.referencePositions.findIndex(x => x.id === id);
            if (idx >= 0) state.referencePositions[idx] = result;
            state.referencePositionIndex = new Map(state.referencePositions.map(x => [normalizePositionCode(x.position), x]));
            state.editingReferencePositionId = null;
            renderReferencePositions(); renderAllTab();
            showToast('Позиция сохранена', result.position, 'ok');
        } catch (err) { showToast('Ошибка сохранения', err.message, 'error'); button.disabled = false; }
    }
});
$('#referencePositionPager')?.addEventListener('click', e => {
    const button = e.target.closest('button[data-ref-page]'); if (!button) return;
    state.referencePositionPage += button.dataset.refPage === 'next' ? 1 : -1;
    renderReferencePositions();
});

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
    const titles = {"all":"Все приборы","positions":"На позиции","warehouse":"Склад","kip":"КИП","movements":"Перемещения","audit":"Журнал","trash":"Корзина","position-directory":"Справочник позиций","chart":"График","users":"Пользователи","help":"Инструкция"};
    const pageTitle = $('#pageTitle');
    if (pageTitle) pageTitle.textContent = 'Приборы КИП : ' + (titles[tabName] || 'Все приборы');

    if (!state.user) return;
    if (workflowTabs[tabName]) loadFullDevicesForWorkflow().catch(e => showToast('Ошибка загрузки списка', e.message, 'error'));
    if (tabName === 'users' && state.user.role !== 'admin') { window.location.hash = 'all'; return; }
    if (tabName === 'audit') renderAuditTab(200);
    if (tabName === 'movements') loadMovements(false).catch(e => showToast('Ошибка загрузки перемещений', e.message, 'error'));
    if (tabName === 'trash') renderTrashTab();
    if (tabName === 'position-directory') renderReferencePositions();
    if (tabName === 'chart') loadFullDevicesForWorkflow().then(() => loadChart()).catch(e => showToast('Ошибка загрузки графика', e.message, 'error'));
    if (tabName === 'users') loadUsers().catch(e => showToast('Ошибка пользователей', e.message, 'error'));
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

    const allowedTabs = ['all', 'positions', 'warehouse', 'kip', 'movements', 'audit', 'trash', 'position-directory', 'chart', 'help', 'users'];
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
    const userMenu = $('#userMenu');
    if (userMenu && !state.user) userMenu.removeAttribute('open');
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
    $('#userMenu')?.removeAttribute('open');
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
    $('#userMenu')?.removeAttribute('open');
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
    if (!d) return;
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
    const r = await apiFetch(`/api/documents/${state.currentDocument.id}/devices/${id}`, {method:'DELETE'});
    if (!r.ok) { showToast('Ошибка', 'HTTP ' + r.status, 'error'); return; }
    state.currentDocument = await r.json(); renderDocumentWorkspace(); await loadDocuments();
});

/* График: отдельный список ссылок на приборы с сохранением порядка. */
let chartRows = []; let chartSearchTimer = null;
async function loadChart(){const r=await apiFetch('/api/chart');if(!r.ok)throw new Error('HTTP '+r.status);chartRows=await r.json();renderChart();}
function renderChart(){const body=$('#tableChart tbody');if(!body)return;body.innerHTML=chartRows.map((d,i)=>`<tr><td>${i+1}</td><td><button class="btn-link" data-chart-open="${d.id}">${escapeHtml(d.serialNumber||'—')}</button></td><td>${escapeHtml(d.sensorType||'—')}</td><td>${escapeHtml(d.groupName||'—')}</td><td>${escapeHtml(d.positionName||'—')}</td><td>${escapeHtml(d.location||'—')}</td><td>${escapeHtml(d.addedAt||'—')}</td><td class="chart-order"><button class="btn" data-chart-up="${d.id}" ${i===0?'disabled':''}>↑</button> <button class="btn" data-chart-down="${d.id}" ${i===chartRows.length-1?'disabled':''}>↓</button></td><td><button class="btn-danger" data-chart-remove="${d.id}">Убрать</button></td></tr>`).join('');$('#chartCount').textContent=chartRows.length+' приборов';$('#emptyChart').hidden=chartRows.length>0;}
async function searchChartDevices(){const q=$('#chartSearch').value.trim(),box=$('#chartSearchResults');if(!q){box.hidden=true;box.innerHTML='';return;}const r=await apiFetch('/api/chart/search?q='+encodeURIComponent(q));if(!r.ok)throw new Error('HTTP '+r.status);const rows=await r.json();box.innerHTML=rows.map(d=>`<div class="chart-search-row"><div><strong>${escapeHtml(d.serialNumber||'Без номера')}</strong><span>${escapeHtml(d.sensorType||'—')} · ${escapeHtml(d.positionName||d.location||'Без позиции')}</span></div><button class="${d.inChart?'btn':'btn-primary'}" data-chart-add="${d.id}" ${d.inChart?'disabled':''}>${d.inChart?'Уже в графике':'Добавить'}</button></div>`).join('')||'<div class="empty">Приборы не найдены</div>';box.hidden=false;}
$('#chartSearch')?.addEventListener('input',()=>{clearTimeout(chartSearchTimer);chartSearchTimer=setTimeout(()=>searchChartDevices().catch(e=>showToast('Ошибка поиска',e.message,'error')),220);});
$('#chartRefreshBtn')?.addEventListener('click',()=>loadChart().catch(e=>showToast('Ошибка графика',e.message,'error')));
$('#chartSearchResults')?.addEventListener('click',async e=>{const b=e.target.closest('[data-chart-add]');if(!b)return;b.disabled=true;try{const r=await apiFetch('/api/chart/'+b.dataset.chartAdd,{method:'POST'});if(!r.ok)throw new Error('Не удалось добавить прибор');await loadChart();await searchChartDevices();}catch(err){b.disabled=false;showToast('Ошибка добавления',err.message,'error');}});
$('#tableChart')?.addEventListener('click',async e=>{const open=e.target.closest('[data-chart-open]');if(open){const d=state.devices.find(x=>Number(x.id)===Number(open.dataset.chartOpen));if(d)openViewModal(d);return;}const rm=e.target.closest('[data-chart-remove]');if(rm){const r=await apiFetch('/api/chart/'+rm.dataset.chartRemove,{method:'DELETE'});if(!r.ok){showToast('Ошибка','Не удалось убрать прибор','error');return;}await loadChart();return;}const move=e.target.closest('[data-chart-up],[data-chart-down]');if(move){const id=Number(move.dataset.chartUp||move.dataset.chartDown),i=chartRows.findIndex(x=>Number(x.id)===id),j=i+(move.hasAttribute('data-chart-up')?-1:1);if(i<0||j<0||j>=chartRows.length)return;[chartRows[i],chartRows[j]]=[chartRows[j],chartRows[i]];const r=await apiFetch('/api/chart/order',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({ids:chartRows.map(x=>x.id)})});if(!r.ok){await loadChart();showToast('Ошибка','Не удалось сохранить порядок','error');return;}renderChart();}});

/* =========================================================
 *  ОБРАБОТЧИКИ "ВСЕ ПРИБОРЫ"
 * ========================================================= */
$('#searchAll').addEventListener('input', debounce(reloadDevicePageFromFilters, 250));
if ($('#statusFilter')) $('#statusFilter').addEventListener('change', reloadDevicePageFromFilters);

// Текстовые фильтры по столбцам
$all('.col-filter').forEach(inp => {
    inp.addEventListener('input', debounce(() => {
        const col = inp.dataset.col;
        const value = (inp.value || '').toLowerCase().trim();
        if (value) state.columnFilters[col] = value;
        else delete state.columnFilters[col];
        reloadDevicePageFromFilters();
    }, 250));
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
        reloadDevicePageFromFilters();
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
        reloadDevicePageFromFilters();
    });
}

$('#refreshAuditBtn').addEventListener('click', () => renderAuditTab(200));
$('#showAllAuditBtn').addEventListener('click', () => renderAuditTab(999999));
$('#tableAudit')?.addEventListener('click', e => {
    const btn = e.target.closest('[data-open-audit-device]');
    if (!btn) return;
    const id = Number(btn.dataset.openAuditDevice);
    const device = state.devices.find(d => Number(d.id) === id);
    if (device) openViewModal(device);
    else showToast('Прибор', 'Карточка прибора не найдена в загруженном списке', 'info');
});
$('#refreshTrashBtn').addEventListener('click', renderTrashTab);

function setFormValue(id, value) {
    const el = $('#' + id);
    if (el) el.value = value == null ? '' : String(value);
}

function updateSelectedPositionPreview() {
    const selectedId = $('#fLocation').value;
    const ref = state.referencePositions.find(x => String(x.id) === String(selectedId));
    $('#fPositionDescription').textContent = ref?.description || 'Не выбрана';
    $('#fPositionEquipment').textContent = ref?.equipment || '—';
}

function populatePositionSelect(device) {
    const select = $('#fLocation');
    const linked = device?.referencePositionId != null
        ? state.referencePositions.find(x => Number(x.id) === Number(device.referencePositionId))
        : null;
    const byCode = linked || state.referencePositionIndex.get(normalizePositionCode(device?.location));
    select.innerHTML = '<option value="">— не выбрана —</option>' + state.referencePositions.map(x =>
        `<option value="${escapeHtml(x.id)}">${escapeHtml(x.position)}</option>`
    ).join('');
    if (byCode) select.value = String(byCode.id);
    else if (device?.location) {
        // Сохраняем видимость старого значения, если его уже нет в справочнике.
        const legacy = document.createElement('option');
        legacy.value = '__legacy__';
        legacy.textContent = `${device.location} (нет в справочнике)`;
        select.appendChild(legacy);
        select.value = '__legacy__';
    }
    updateSelectedPositionPreview();
}

function openModal(device) {
    $('#modalTitle').textContent = device ? 'Редактировать прибор' : 'Новый прибор';
    setFormValue('fId', device ? device.id : '');
    setFormValue('fVersion', device ? (device.version || 0) : 0);
    setFormValue('fSerial', device?.serialNumber);
    populatePositionSelect(device);
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
    setFormValue('fWorkflowStatus', device?.workflowStatus || (device?.writeOff ? 'WRITTEN_OFF' : (device?.location ? 'INSTALLED' : 'WAREHOUSE')));
    $('#copyDeviceBtn').hidden = !device;
    $('#copyDeviceBtn').dataset.sourceId = device ? device.id : '';

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
    const selectedId = $('#fLocation').value;
    const ref = state.referencePositions.find(x => String(x.id) === String(selectedId));
    const description = ref?.description || '';
    const equipment = ref?.equipment || '';
    const existing = state.devices.find(d => String(d.id) === String($('#fId').value));
    const keepLegacy = selectedId === '__legacy__';
    return {
        serialNumber: $('#fSerial').value.trim(),
        name: description || (keepLegacy ? existing?.name : '') || existing?.name || 'Без позиции',
        positionName: description || (keepLegacy ? existing?.positionName : '') || '',
        location: $('#fWorkflowStatus').value === 'INSTALLED'
            ? (ref?.position || (keepLegacy ? existing?.location : '') || '')
            : '',
        referencePositionId: ref ? Number(ref.id) : (keepLegacy ? (existing?.referencePositionId || '') : ''),
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
        writeOff: $('#fWorkflowStatus').value === 'WRITTEN_OFF' ? ($('#fWriteOff').value || '') : '',
        deferredVerification: $('#fDeferredVerification').value.trim(),
        groupName: equipment || (keepLegacy ? existing?.groupName : '') || '',
        workflowStatus: $('#fWorkflowStatus').value,
        workflowMovedAt: state.devices.find(d => String(d.id) === String($('#fId').value))?.workflowMovedAt || '',
        version: parseInt($('#fVersion').value, 10) || 0
    };
}

$('#fLocation').addEventListener('change', updateSelectedPositionPreview);

function closeModal() { $('#modal').hidden = true; }

$('#addBtn').addEventListener('click', () => openModal(null));
$('#copyDeviceBtn').addEventListener('click', () => {
    const source = state.devices.find(d => String(d.id) === String($('#copyDeviceBtn').dataset.sourceId));
    if (!source) return;
    const copy = { ...source, id: null, version: 0, serialNumber: '', location: '', referencePositionId: '', positionName: '', groupName: '', rowNumber: '', workflowStatus: 'WAREHOUSE', workflowMovedAt: '', writeOff: '', photoPath: null, photoThumbPath: null };
    openModal(copy);
    setFormValue('fId', '');
    setFormValue('fVersion', 0);
    $('#copyDeviceBtn').hidden = true;
    $('#modalTitle').textContent = 'Новый прибор — копия';
    showToast('Создана копия карточки', 'Укажите новый заводской номер и сохраните прибор', 'info');
});
$('#cancelBtn').addEventListener('click', closeModal);
$('#modalCloseBtn').addEventListener('click', closeModal);

$('#removePhotoBtn').addEventListener('click', async (e) => {
    e.preventDefault();
    const id = $('#fId').value;
    if (!id) return;


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
        ['Группа', referencePositionForDevice(device)?.equipment || device.groupName],
        ['Наименование позиции', referencePositionForDevice(device)?.description || device.positionName || device.name],
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

/* Закрытие активного всплывающего окна клавишей Escape.
   Работает для карточки прибора, редактирования, документов, входа
   и остальных окон с общим классом .modal. */
document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const openedModals = Array.from(document.querySelectorAll('.modal:not([hidden])'));
    if (!openedModals.length) return;
    event.preventDefault();
    const activeModal = openedModals[openedModals.length - 1];
    activeModal.hidden = true;
    // Сброс временного состояния форм входа и добавления прибора в документ.
    if (activeModal.id === 'passwordModal') {
        const password = document.querySelector('#loginPassword');
        const error = document.querySelector('#loginError');
        if (password) password.value = '';
        if (error) error.hidden = true;
    }
    if (activeModal.id === 'addDeviceDocumentModal') {
        state.selectedDocumentDeviceId = null;
        const search = document.querySelector('#documentDeviceSearch');
        const selection = document.querySelector('#documentDeviceSelection');
        const addButton = document.querySelector('#confirmAddDeviceDocument');
        if (search) search.value = '';
        if (selection) selection.textContent = 'Прибор не выбран';
        if (addButton) addButton.disabled = true;
    }
});

/* =========================================================
 *  СТАРТ
 * ========================================================= */
function updateLiveClock() {
    const clock = $('#liveClock');
    if (!clock) return;
    const now = new Date();
    clock.textContent = new Intl.DateTimeFormat('ru-RU', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
    }).format(now);
}
document.addEventListener('DOMContentLoaded', () => {
    initializeWorkflowTabs();
    updateLiveClock();
    window.setInterval(updateLiveClock, 1000);
    initializeAuth();
});

