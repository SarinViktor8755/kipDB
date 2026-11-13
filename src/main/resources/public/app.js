const state = {
    selectedDocumentDeviceId: null,
    devices: [],
    stats: { total: 0, overdue: 0, soon: 0, ok: 0 },
    currentTab: 'all',
    documents: [],
    currentDocument: null,
    users: [],
    user: null
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

function updateDatalists() {
    const names = [...new Set(state.devices.map(d => d.name).filter(Boolean))].sort();
    const locations = [...new Set(state.devices.map(d => d.location).filter(Boolean))].sort();

    $('#listNames').innerHTML = names.map(n => `<option value="${escapeHtml(n)}">`).join('');
    $('#listLocations').innerHTML = locations.map(l => `<option value="${escapeHtml(l)}">`).join('');
}

async function loadAll() {
    await Promise.all([loadDevices(), loadStats()]);
    render();
    updateDatalists();
}
async function loadDevices() {
    const r = await fetch('/api/devices');
    if (!r.ok) throw new Error('devices: ' + r.status);
    state.devices = await r.json();
}
async function loadStats() {
    const r = await fetch('/api/stats');
    if (!r.ok) return;
    state.stats = await r.json();
}
async function loadAudit(limit = 200) {
    const r = await fetch('/api/audit?limit=' + limit);
    if (!r.ok) throw new Error('audit: ' + r.status);
    return r.json();
}
async function loadTrash() {
    const r = await fetch('/api/devices/deleted');
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

function renderAllTab() {
    const q = ($('#searchAll').value || '').toLowerCase().trim();
    const f = $('#statusFilter').value;
    const sort = $('#sortFilter').value;
    let rows = state.devices.filter(d => {
        const haystack = [d.name,d.location,d.serialNumber,d.sensorType,d.unit,d.category,d.groupName,d.positionName,d.redLine].join(' ').toLowerCase();
        if (q && !haystack.includes(q)) return false;
        if (f && statusOf(d) !== f) return false;
        return true;
    });
    rows.sort((a,b) => {
        if (sort === 'name_asc') return (a.positionName || a.name || '').localeCompare(b.positionName || b.name || '');
        if (sort === 'name_desc') return (b.positionName || b.name || '').localeCompare(a.positionName || a.name || '');
        if (sort === 'date_new') return (b.createdAt || '').localeCompare(a.createdAt || '');
        if (sort === 'date_old') return (a.createdAt || '').localeCompare(b.createdAt || '');
        return 0;
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
    $('#emptyAll').hidden = rows.length !== 0;
}

async function renderAuditTab(limit = 200) {
    const rows = await loadAudit(limit);
    const tbody = $('#tableAudit tbody');
    tbody.innerHTML = rows.map(r => `<tr>
        <td>${formatDateTime(r.when)}</td>
        <td>${escapeHtml(r.who)}</td>
        <td>${escapeHtml(r.action)}</td>
        <td>${escapeHtml(r.name)}</td>
        <td>${escapeHtml(r.serialNumber)}</td>
        <td>${escapeHtml(r.details)}</td>
    </tr>`).join('');
    $('#emptyAudit').hidden = rows.length !== 0;
}

async function renderTrashTab() {
    const rows = await loadTrash();
    const tbody = $('#tableTrash tbody');
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
}

// ==========================================
// РОУТИНГ (URL с хэшем)
// ==========================================
function switchTab(tabName) {
    // Обновляем классы активности
    $all('.tab').forEach(b => b.classList.remove('active'));
    $all('.tab-panel').forEach(p => p.classList.remove('active'));

    const btn = document.querySelector(`.tab[data-tab="${tabName}"]`);
    if (btn) btn.classList.add('active');

    const panel = $('#tab-' + tabName);
    if (panel) panel.classList.add('active');

    state.currentTab = tabName;

    // Данные вкладок загружаются только после авторизации.
    if (!state.user) return;
    if (tabName === 'users' && state.user.role !== 'admin') { window.location.hash = 'all'; return; }
    if (tabName === 'audit') renderAuditTab(200);
    if (tabName === 'trash') renderTrashTab();
    if (tabName === 'users') loadUsers().catch(e => showToast('Ошибка пользователей', e.message, 'error'));
    if (tabName === 'documents') {
        state.currentDocument = null;
        $('#documentWorkspace').hidden = true;
        $('#documentsListView').hidden = false;
        loadDocuments().catch(e => showToast('Ошибка загрузки документов', e.message, 'error'));
    }
}

// Разделы инструкции используют собственные якоря #help-*. Они не должны
// попадать в общий обработчик вкладок приложения.
function handleAppRoute() {
    const hash = decodeURIComponent(window.location.hash.replace(/^#/, '')) || 'all';
    const helpTarget = document.getElementById(hash);

    if (helpTarget && helpTarget.classList.contains('help-section')) {
        switchTab('help');
        requestAnimationFrame(() => helpTarget.scrollIntoView({ behavior: 'auto', block: 'start' }));
        return;
    }

    const allowedTabs = ['all', 'documents', 'audit', 'trash', 'help', 'users'];
    switchTab(allowedTabs.includes(hash) ? hash : 'all');
}

// Слушаем изменения хэша (кнопки Назад/Вперёд и прямые ссылки).
window.addEventListener('hashchange', handleAppRoute);

// Перехватываем содержание инструкции: прокручиваем к разделу без изменения
// URL-хэша, чтобы маршрутизатор вкладок не воспринимал якорь как вкладку.
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

// Обработчик кликов по меню
$all('.tab').forEach(btn => {
    btn.addEventListener('click', (e) => {
        e.preventDefault(); // Отменяем стандартный переход по ссылке
        const tabName = btn.dataset.tab;
        // Меняем URL (это вызовет hashchange, который переключит вкладку)
        window.location.hash = tabName;
        // Если хэш не изменился (например, кликнули на текущую вкладку),
        // принудительно вызываем switchTab
        if (window.location.hash.replace('#', '') === tabName) {
            switchTab(tabName);
        }
    });
});

// При загрузке страницы определяем вкладку по URL. Если адрес содержит
// якорь раздела инструкции, открываем инструкцию и прокручиваем к нему.
document.addEventListener('DOMContentLoaded', handleAppRoute);

// ==========================================
// АВТОРИЗАЦИЯ И РОЛИ
// ==========================================
function roleTitle(role) { return role === 'admin' ? 'Администратор' : role === 'guest' ? 'Гость · просмотр' : 'Пользователь'; }

function fillUserSelect() {
    const select = $('#userSelect');
    const current = state.user ? state.user.login : 'guest';
    select.innerHTML = '<option value="guest">Гость (только просмотр)</option>' +
        state.users.map(u => `<option value="${escapeHtml(u.login).replaceAll('"', '&quot;')}">${escapeHtml(u.name)}</option>`).join('');
    select.value = current;
}

function applyRoleUI() {
    const isAdmin = state.user && state.user.role === 'admin';
    const isGuest = state.user && state.user.role === 'guest';
    document.body.classList.toggle('read-only', !!isGuest);
    document.body.classList.toggle('auth-locked', !state.user);
    $all('.admin-only').forEach(el => { el.hidden = !isAdmin; });
    $('#currentUser').textContent = state.user ? `${state.user.name} · ${roleTitle(state.user.role)}` : 'Не выполнен вход';
    $('#logoutBtn').hidden = !state.user;
    if (state.user) $('#userSelect').value = state.user.login;
}

async function initializeAuth() {
    try {
        const [usersResponse, meResponse] = await Promise.all([fetch('/api/auth/users'), fetch('/api/auth/me')]);
        if (!usersResponse.ok || !meResponse.ok) throw new Error('Не удалось получить список пользователей');
        state.users = await usersResponse.json();
        const me = await meResponse.json();
        fillUserSelect();
        if (me.authenticated) {
            state.user = me.user; applyRoleUI(); await afterLogin();
        } else {
            $('#userSelect').value = 'guest';
            await loginSelectedUser('');
        }
    } catch (e) {
        showToast('Ошибка авторизации', e.message, 'error');
    }
}

async function afterLogin() {
    applyRoleUI();
    await loadAll();
    switchTab(state.currentTab || 'all');
}

async function loginSelectedUser(password = '') {
    const login = $('#userSelect').value;
    if (!login) return;
    const r = await fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({login, password})});
    if (!r.ok) { const err = await r.json().catch(()=>({})); $('#loginError').textContent = err.error || 'Ошибка входа'; $('#loginError').hidden = false; return false; }
    const result = await r.json();
    state.user = result.user;
    $('#passwordModal').hidden = true; $('#loginPassword').value = ''; $('#loginError').hidden = true;
    await afterLogin();
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
    try { await loginSelectedUser($('#loginPassword').value); }
    catch (err) { $('#loginError').textContent = err.message; $('#loginError').hidden = false; }
});
$('#cancelLoginBtn').addEventListener('click', () => {
    $('#passwordModal').hidden = true; $('#loginPassword').value = ''; $('#userSelect').value = state.user ? state.user.login : '';
});
$('#logoutBtn').addEventListener('click', async () => {
    await fetch('/api/auth/logout', {method:'POST'});
    state.user = null;
    $('#userSelect').value = 'guest';
    await loginSelectedUser('');
    if (state.currentTab === 'users') { window.location.hash = 'all'; }
    showToast('Выполнен выход', 'Открыт режим гостя', 'info');
});

async function loadUsers() {
    const r = await fetch('/api/users');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const users = await r.json();
    $('#tableUsers tbody').innerHTML = users.map(u => `<tr><td>${escapeHtml(u.name)}</td><td>${u.role === 'admin' ? 'Администратор' : 'Пользователь'}</td><td>${u.role === 'admin' ? '<span class="muted">Защищённая учётная запись</span>' : `<button class="btn-danger btn-small" data-delete-user="${u.id}" data-user-name="${escapeHtml(u.name).replaceAll('\"','&quot;')}">Удалить</button>`}</td></tr>`).join('');
    $('#emptyUsers').hidden = users.length > 0;
}

$('#createUserForm').addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('#newUserName').value.trim(), password = $('#newUserPassword').value;
    const r = await fetch('/api/users', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({name,password})});
    if (!r.ok) { const err = await r.json().catch(()=>({})); showToast('Не удалось создать пользователя', err.error || `HTTP ${r.status}`, 'error'); return; }
    $('#newUserName').value = ''; $('#newUserPassword').value = '';
    const usersResponse = await fetch('/api/auth/users'); state.users = await usersResponse.json(); fillUserSelect();
    await loadUsers(); showToast('Пользователь создан', name, 'ok');
});

$('#tableUsers').addEventListener('click', async e => {
    const btn = e.target.closest('button[data-delete-user]');
    if (!btn) return;
    const id = Number(btn.dataset.deleteUser);
    const name = btn.dataset.userName || 'пользователя';
    if (!confirm(`Удалить пользователя «${name}»? Его активные сессии будут завершены.`)) return;
    const r = await fetch('/api/users/' + id, {method:'DELETE'});
    if (!r.ok) {
        const err = await r.json().catch(()=>({}));
        showToast('Не удалось удалить пользователя', err.error || `HTTP ${r.status}`, 'error');
        return;
    }
    const usersResponse = await fetch('/api/auth/users');
    state.users = await usersResponse.json();
    fillUserSelect();
    await loadUsers();
    showToast('Пользователь удалён', name, 'ok');
});

// ==========================================
// ДОКУМЕНТЫ
// ==========================================
async function loadDocuments() {
    const r = await fetch('/api/documents');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    state.documents = await r.json();
    renderDocuments();
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
    const r = await fetch('/api/documents/' + id);
    if (!r.ok) throw new Error('Не удалось открыть документ: HTTP ' + r.status);
    state.currentDocument = await r.json();
    renderDocumentWorkspace();
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

$('#documentDeviceSearch').addEventListener('input', renderDocumentDeviceSearch);
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
$('#searchDocuments').addEventListener('input', renderDocuments);
$('#documentModalClose').addEventListener('click', closeDocumentForm);
$('#cancelDocumentBtn').addEventListener('click', closeDocumentForm);
$('#backToDocumentsBtn').addEventListener('click', closeDocumentWorkspace);
$('#editDocumentBtn').addEventListener('click', () => state.currentDocument && openDocumentForm(state.currentDocument));
$('#deleteDocumentBtn').addEventListener('click', async () => {
    const d = state.currentDocument;
    if (!d || !confirm('Удалить документ «' + d.title + '»? Приборы останутся в общей базе.')) return;
    const r = await fetch('/api/documents/' + d.id, {method:'DELETE'});
    if (!r.ok) { showToast('Ошибка удаления документа', 'HTTP ' + r.status, 'error'); return; }
    state.currentDocument = null;
    $('#documentWorkspace').hidden = true; $('#documentsListView').hidden = false;
    await loadDocuments(); showToast('Документ удалён', 'Приборы не удалялись', 'info');
});
$('#documentForm').addEventListener('submit', async e => {
    e.preventDefault();
    const id = $('#documentId').value;
    const body = {title:$('#documentTitle').value.trim(), documentNumber:$('#documentNumber').value.trim(), description:$('#documentDescription').value.trim()};
    const r = await fetch(id ? '/api/documents/' + id : '/api/documents', {method:id ? 'PUT' : 'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
    if (!r.ok) { const err = await r.json().catch(()=>({})); showToast('Ошибка сохранения', err.error || ('HTTP ' + r.status), 'error'); return; }
    const saved = await r.json(); closeDocumentForm();
    if (id) { state.currentDocument = saved; renderDocumentWorkspace(); }
    await loadDocuments();
    if (!id) await openDocument(saved.id);
    showToast(id ? 'Документ обновлён' : 'Документ создан', saved.title, 'info');
});
$('#tableDocuments').addEventListener('click', e => {
    const row = e.target.closest('tr[data-document-id]');
    if (row) openDocument(Number(row.dataset.documentId)).catch(err => showToast('Ошибка', err.message, 'error'));
});
$('#addDeviceToDocumentBtn').addEventListener('click', showAddDeviceToDocument);
$('#closeAddDeviceDocument').addEventListener('click', () => $('#addDeviceDocumentModal').hidden = true);
$('#cancelAddDeviceDocument').addEventListener('click', () => $('#addDeviceDocumentModal').hidden = true);
$('#confirmAddDeviceDocument').addEventListener('click', async () => {
    const d = state.currentDocument, deviceId = Number(state.selectedDocumentDeviceId);
    if (!d || !deviceId) return;
    const r = await fetch(`/api/documents/${d.id}/devices/${deviceId}`, {method:'POST'});
    if (!r.ok) { const err = await r.json().catch(()=>({})); showToast('Не удалось добавить прибор', err.error || ('HTTP ' + r.status), 'error'); return; }
    state.currentDocument = await r.json(); renderDocumentWorkspace();
    $('#addDeviceDocumentModal').hidden = true;
    state.selectedDocumentDeviceId = null;
    await loadDocuments(); showToast('Прибор добавлен в документ', '', 'info');
});
$('#tableDocumentDevices').addEventListener('click', async e => {
    const btn = e.target.closest('button[data-unlink-device]');
    if (!btn || !state.currentDocument) return;
    const id = Number(btn.dataset.unlinkDevice);
    if (!confirm('Убрать прибор из документа? Сам прибор останется в общей базе.')) return;
    const r = await fetch(`/api/documents/${state.currentDocument.id}/devices/${id}`, {method:'DELETE'});
    if (!r.ok) { showToast('Ошибка', 'HTTP ' + r.status, 'error'); return; }
    state.currentDocument = await r.json(); renderDocumentWorkspace(); await loadDocuments();
});

// ==========================================
// ОСТАЛЬНЫЕ ОБРАБОТЧИКИ
// ==========================================

$('#searchAll').addEventListener('input', renderAllTab);
$('#statusFilter').addEventListener('change', renderAllTab);
$('#sortFilter').addEventListener('change', renderAllTab);
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
    }
});

$('#deviceForm').addEventListener('submit', async e => {
    e.preventDefault();
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
    const r = await fetch('/api/devices/' + deviceId + '/movements');
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
    const payload = { eventDate:$('#movementDate').value, eventType:$('#movementType').value, fromPlace:$('#movementFrom').value.trim(), toPlace:$('#movementTo').value.trim(), note:$('#movementNote').value.trim() };
    try {
        const r = await fetch('/api/devices/' + currentViewDevice.id + '/movements', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
        const result = await r.json();
        if (!r.ok) throw new Error(result.error || ('HTTP ' + r.status));
        $('#movementForm').hidden = true; $('#movementForm').reset();
        await loadDeviceHistory(currentViewDevice.id);
        if (payload.eventType === 'Установлен' && payload.toPlace) { currentViewDevice.location = payload.toPlace; await loadAll(); }
        showToast('Операция сохранена', payload.eventType, 'ok');
    } catch(err) { showToast('Ошибка сохранения операции', err.message, 'error'); }
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

    try {
        const r = await fetch('/api/devices/' + d.id, { method: 'DELETE' });
        if (!r.ok) throw new Error('HTTP ' + r.status);

        closeViewModal();

        $('#searchAll').value = '';
        $('#statusFilter').value = '';

        await loadAll();
        showToast('Прибор перемещён в корзину', d.name || '', 'info');
        if (state.currentTab === 'audit') await renderAuditTab(200);
    } catch (err) {
        showToast('Ошибка удаления', err.message, 'error');
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

        try {
            const r = await fetch('/api/devices/' + id + '/restore', { method: 'POST' });
            if (!r.ok) throw new Error('HTTP ' + r.status);

            await loadAll();
            await renderTrashTab();

            showToast('Прибор восстановлен', '', 'ok');
        } catch (err) {
            showToast('Ошибка восстановления', err.message, 'error');
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

initializeAuth();