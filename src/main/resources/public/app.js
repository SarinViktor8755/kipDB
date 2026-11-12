const state = {
    devices: [],
    stats: { total: 0, overdue: 0, soon: 0, ok: 0 },
    currentTab: 'all'
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

function renderAllTab() {
    const q = ($('#searchAll').value || '').toLowerCase().trim();
    const f = $('#statusFilter').value;
    const sort = $('#sortFilter').value;

    let rows = state.devices.filter(d => {
        if (q && !((d.name || '').toLowerCase().includes(q) ||
                   (d.location || '').toLowerCase().includes(q) ||
                   (d.serialNumber || '').toLowerCase().includes(q))) return false;
        if (f && statusOf(d) !== f) return false;
        return true;
    });

    rows.sort((a, b) => {
        if (sort === 'name_asc') {
            return (a.name || '').localeCompare(b.name || '');
        } else if (sort === 'name_desc') {
            return (b.name || '').localeCompare(a.name || '');
        } else if (sort === 'date_new') {
            return (b.createdAt || '').localeCompare(a.createdAt || '');
        } else if (sort === 'date_old') {
            return (a.createdAt || '').localeCompare(b.createdAt || '');
        }
        return 0;
    });

    const tbody = $('#tableAll tbody');
    tbody.innerHTML = rows.map(d => {
        const s = statusOf(d);
        return `<tr data-id="${d.id}">
            <td class="photo-cell">${photoHtml(d)}</td>
            <td>${escapeHtml(d.name)}</td>
            <td>${escapeHtml(d.serialNumber || '—')}</td>
            <td>${escapeHtml(d.location)}</td>
            <td>${formatDate(d.lastVerification)}</td>
            <td>${formatDate(d.nextVerification)}</td>
            <td>${statusBadge(s)}</td>
        </tr>`;
    }).join('');
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

    // Загружаем данные для нужной вкладки
    if (tabName === 'audit') renderAuditTab(200);
    if (tabName === 'trash') renderTrashTab();
}

// Слушаем изменения хэша (кнопки Назад/Вперёд)
window.addEventListener('hashchange', () => {
    const hash = window.location.hash.replace('#', '') || 'all';
    switchTab(hash);
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

// При загрузке страницы определяем вкладку по URL
document.addEventListener('DOMContentLoaded', () => {
    const hash = window.location.hash.replace('#', '') || 'all';
    switchTab(hash);
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

function openModal(device) {
    $('#modalTitle').textContent = device ? 'Редактировать прибор' : 'Новый прибор';
    $('#fId').value = device ? device.id : '';
    $('#fSerial').value = device ? (device.serialNumber || '') : '';
    $('#fName').value = device ? (device.name || '') : '';
    $('#fLocation').value = device ? (device.location || '') : '';
    $('#fLast').value = device ? (device.lastVerification || '') : '';
    $('#fNext').value = device ? (device.nextVerification || '') : '';
    $('#fVersion').value = device ? (device.version || 0) : 0;

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
            serialNumber: $('#fSerial').value.trim(),
            name: $('#fName').value.trim(),
            location: $('#fLocation').value.trim(),
            lastVerification: $('#fLast').value || '',
            nextVerification: $('#fNext').value || '',
            photoPath: null,
            photoThumbPath: null,
            version: parseInt($('#fVersion').value, 10) || 0
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
    const body = {
        serialNumber: $('#fSerial').value.trim(),
        name: $('#fName').value.trim(),
        location: $('#fLocation').value.trim(),
        lastVerification: $('#fLast').value || '',
        nextVerification: $('#fNext').value || '',
        version: parseInt($('#fVersion').value, 10) || 0
    };
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

function openViewModal(device) {
    currentViewDevice = device;
    $('#viewName').textContent = device.name || 'Без названия';
    $('#viewSerial').textContent = device.serialNumber || '—';
    $('#viewLocation').textContent = device.location || '—';
    $('#viewLast').textContent = formatDate(device.lastVerification);
    $('#viewNext').textContent = formatDate(device.nextVerification);

    const s = statusOf(device);
    $('#viewStatus').innerHTML = statusBadge(s);

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
}

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

loadAll().catch(e => console.error(e));