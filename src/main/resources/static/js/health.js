// ── State ──────────────────────────────────────────────────────────────────
let DATA = [];
let currentFilter   = 'all';
let currentDistrict = 'all';
let currentSearch   = '';
let currentPage     = 1;
let ROWS_PER_PAGE   = 10;
let lastDataHash  = '';

// ── Boot ───────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    loadHealth();

    const searchInput = document.getElementById('search');
    if (searchInput) {
        let timer;
        searchInput.oninput = (e) => {
            clearTimeout(timer);
            timer = setTimeout(() => {
                currentSearch = e.target.value.toLowerCase().trim();
                currentPage = 1;
                render();
            }, 250);
        };
    }

    const distFilter = document.getElementById('districtFilter');
    if (distFilter) {
        distFilter.onchange = (e) => {
            currentDistrict = e.target.value;
            currentPage = 1;
            render();
        };
    }

    // No auto-refresh - strictly manual via Refresh button
});

// ── Fetch ──────────────────────────────────────────────────────────────────
function loadHealth(isManual = false) {
    if (isManual) {
        setSpinner(true);
        
        const btn = document.getElementById('refreshBtn');
        if (btn) {
            btn.style.opacity = '0.7';
            btn.textContent = 'Syncing...';
            btn.disabled = true;
        }

        fetch('/api/sync-health', { method: 'POST' })
            .then(() => {
                setTimeout(() => loadHealth(false), 800);
            })
            .catch(err => {
                console.error(err);
                setSpinner(false);
                hideSyncPopup();
            });
        return;
    }

    fetch('/api/health-indicators?t=' + Date.now())
        .then(res => res.json())
        .then(json => {
            const newData = json.data || [];
            const newHash = computeHash(newData);

            // Update Report Date
            const reportDateEl = document.getElementById('reportDate');
            if (reportDateEl) {
                reportDateEl.textContent = 'Report Date: ' + (json.date || 'Initializing...');
            }

            if (newHash !== lastDataHash) {
                // Sort DATA alphabetically by OLT Name
                newData.sort((a, b) => {
                    const nameA = (a.assetName || '').toUpperCase();
                    const nameB = (b.assetName || '').toUpperCase();
                    return nameA.localeCompare(nameB);
                });

                DATA = newData;
                lastDataHash = newHash;
                updateDistrictDropdown();
                render();
            }

            // Polling logic: if still running, check again soon
            if (json.fetchRunning) {
                setSpinner(true);
                setTimeout(() => loadHealth(false), 2000);
            } else {
                setSpinner(false);
                const btn = document.getElementById('refreshBtn');
                if (btn) {
                    btn.style.opacity = '1';
                    btn.textContent = 'Refresh';
                    btn.disabled = false;
                }
            }
        })
        .catch(err => {
            console.error('Failed to fetch health indicators', err);
            setSpinner(false);
        });
}

function showSyncPopup(message) {
    let popup = document.getElementById('syncPopup');
    if (!popup) {
        popup = document.createElement('div');
        popup.id = 'syncPopup';
        popup.style = `
            position: fixed; bottom: 20px; right: 20px;
            background: #fff; padding: 12px 20px; border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.15); border: 1px solid #007bff;
            display: flex; align-items: center; gap: 10px; z-index: 9999;
            font-size: 14px; font-weight: 500; color: #333;
        `;
        popup.innerHTML = `
            <div class="sync-spinner" style="width:16px;height:16px;border-width:2px;margin:0;"></div>
            <span id="syncPopupMsg"></span>
        `;
        document.body.appendChild(popup);
    }
    document.getElementById('syncPopupMsg').textContent = message;
    popup.style.display = 'flex';
}

function hideSyncPopup() {
    const popup = document.getElementById('syncPopup');
    if (popup) popup.style.display = 'none';
}

function computeHash(data) {
    return data.map(d => (d.assetName || '') + '|' + (d.healthStatus || '') + '|' + (d.district || '')).join(',');
}

function updateDistrictDropdown() {
    const filter = document.getElementById('districtFilter');
    if (!filter) return;
    
    const prevVal = filter.value;
    const districts = [...new Set(DATA.map(d => d.district || 'N/A'))].sort();
    
    let html = '<option value="all">All Districts</option>';
    districts.forEach(d => {
        html += `<option value="${d}">${d}</option>`;
    });
    
    filter.innerHTML = html;
    filter.value = districts.includes(prevVal) ? prevVal : 'all';
    currentDistrict = filter.value;
}

function setSpinner(active) {
    const spinner = document.getElementById('btnSpinner');
    const btn     = document.getElementById('refreshBtn');
    if (spinner) spinner.classList.toggle('active', active);
    if (btn) btn.disabled = active;
}

// ── Filters ────────────────────────────────────────────────────────────────
function setFilter(f, btn) {
    currentFilter = f;
    // Handle both direct button passing and ID-based cleanup
    const btnGroup = document.querySelector('.btn-group');
    if (btnGroup) {
        btnGroup.querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
    }
    if (btn) btn.classList.add('active');
    
    currentPage = 1;
    render();
}

function getFiltered() {
    return DATA.filter(d => {
        const status = (d.healthStatus || 'UNKNOWN').toUpperCase();
        if (currentFilter !== 'all' && status !== currentFilter) return false;
        
        // Ensure 'all' district really shows everything
        if (currentDistrict && currentDistrict !== 'all') {
            const dist = d.district || 'N/A';
            if (dist !== currentDistrict) return false;
        }

        if (currentSearch) {
            const s = currentSearch;
            const name  = (d.assetName || '').toLowerCase();
            const code  = (d.code      || '').toLowerCase();
            const block = (d.block     || '').toLowerCase();
            const dist  = (d.district  || '').toLowerCase();
            if (!name.includes(s) && !code.includes(s) && !block.includes(s) && !dist.includes(s)) return false;
        }
        return true;
    });
}

// ── Render ─────────────────────────────────────────────────────────────────
function render() {
    updateStats();

    const filtered   = getFiltered();
    const totalPages = Math.max(1, Math.ceil(filtered.length / ROWS_PER_PAGE));
    
    if (currentPage > totalPages) currentPage = totalPages;
    if (currentPage < 1)          currentPage = 1;

    const startIdx = (currentPage - 1) * ROWS_PER_PAGE;
    const pageData = filtered.slice(startIdx, startIdx + ROWS_PER_PAGE);

    const tbody = document.getElementById('tbody');
    if (!tbody) return;

    if (DATA.length === 0) {
        tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;padding:40px;color:#666;">Loading OLT data...</td></tr>`;
        document.getElementById('pagination').style.display = 'none';
        return;
    }

    if (pageData.length === 0) {
        tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;padding:40px;">No results found.</td></tr>`;
        document.getElementById('pagination').style.display = 'none';
        return;
    }

    const newHtml = pageData.map((d, i) => {
        const status = (d.healthStatus || 'UNKNOWN').toUpperCase();
        const badgeClass = status === 'UP' ? 'up' : (status === 'DOWN' ? 'down' : 'unknown');
        const badgeText  = status === 'UP' ? 'UP' : (status === 'DOWN' ? 'DOWN' : 'NO DATA');

        return `<tr>
            <td>${startIdx + i + 1}</td>
            <td>${d.district || 'N/A'}</td>
            <td>${d.block || 'N/A'}</td>
            <td>${d.assetName || 'N/A'}</td>
            <td>${d.code || 'N/A'}</td>
            <td><span class="status-badge ${badgeClass}">${badgeText}</span></td>
            <td style="text-align:center;">
                <button class="preview-btn" onclick="showPreview(${startIdx + i})" title="View Details">
                    <i class="fas fa-eye"></i>
                </button>
            </td>
        </tr>`;
    }).join('');

    if (tbody.innerHTML !== newHtml) {
        tbody.innerHTML = newHtml;
    }

    renderPagination(filtered.length, totalPages);
}

function updateStats() {
    let up = 0, down = 0;
    DATA.forEach(d => {
        const s = (d.healthStatus || '').toUpperCase();
        if      (s === 'UP')   up++;
        else if (s === 'DOWN') down++;
    });
    
    const set = (id, val) => {
        const el = document.getElementById(id);
        if (el && el.textContent !== String(val)) el.textContent = val;
    };
    
    set('totalNum', DATA.length);
    set('upNum',    up);
    set('downNum',  down);
}

// ── Pagination ─────────────────────────────────────────────────────────────
function renderPagination(totalItems, totalPages) {
    const el = document.getElementById('pagination');
    if (!el) return;

    if (totalItems === 0) { el.style.display = 'none'; return; }
    el.style.display = 'flex';

    const startItem = (currentPage - 1) * ROWS_PER_PAGE + 1;
    const endItem   = Math.min(currentPage * ROWS_PER_PAGE, totalItems);

    let html = `
        <div class="page-left">
            <span class="page-label">Page size</span>
            <select class="page-size-select" onchange="changePageSize(this.value)">
                ${[10, 20, 50, 100].map(s => `<option value="${s}" ${ROWS_PER_PAGE === s ? 'selected' : ''}>${s}</option>`).join('')}
            </select>
            <span class="page-info">Showing ${startItem} - ${endItem} of ${totalItems} items.</span>
        </div>
        <div class="page-goto">
            <span class="page-label">Go To</span>
            <input type="number" class="page-goto-input" id="gotoPageInput" value="${currentPage}" min="1" max="${totalPages}" onkeydown="if(event.key==='Enter')goToInputPage()">
            <button class="page-goto-btn" onclick="goToInputPage()">▶</button>
        </div>
        <div class="page-buttons">
            <button class="page-btn ${currentPage===1?'disabled':''}" onclick="goTo(1)" ${currentPage===1?'disabled':''}>«</button>
            <button class="page-btn ${currentPage===1?'disabled':''}" onclick="goTo(${currentPage-1})" ${currentPage===1?'disabled':''}>‹</button>
            ${getPageRange(currentPage, totalPages).map(p => p === '...' ? `<span class="page-ellipsis">…</span>` : `<button class="page-btn ${p===currentPage?'active':''}" onclick="goTo(${p})">${p}</button>`).join('')}
            <button class="page-btn ${currentPage===totalPages?'disabled':''}" onclick="goTo(${currentPage+1})" ${currentPage===totalPages?'disabled':''}>›</button>
            <button class="page-btn ${currentPage===totalPages?'disabled':''}" onclick="goTo(${totalPages})" ${currentPage===totalPages?'disabled':''}>»</button>
        </div>
    `;
    el.innerHTML = html;
}

function getPageRange(current, total) {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
    const pages = [1];
    if (current > 4) pages.push('...');
    let start = Math.max(2, current - 1);
    let end   = Math.min(total - 1, current + 1);
    if (current <= 4) end = 5;
    else if (current >= total - 3) start = total - 4;
    for (let i = start; i <= end; i++) pages.push(i);
    if (current < total - 3) pages.push('...');
    pages.push(total);
    return pages;
}

function changePageSize(v) { ROWS_PER_PAGE = parseInt(v); currentPage = 1; render(); }
function goToInputPage() { const v = parseInt(document.getElementById('gotoPageInput').value); if (!isNaN(v)) goTo(v); }
function goTo(p) {
    const total = Math.max(1, Math.ceil(getFiltered().length / ROWS_PER_PAGE));
    if (p < 1 || p > total) return;
    currentPage = p;
    render();
    const card = document.querySelector('.card');
    if (card) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ── Preview Modal ──────────────────────────────────────────────────────────
function showPreview(index) {
    const item = getFiltered()[index];
    if (!item) return;

    const modal = document.getElementById('previewModal');
    const title = document.getElementById('previewTitle');
    const body  = document.getElementById('previewBody');

    if (modal && title && body) {
        title.textContent = item.assetName || 'Asset Details';
        const status = (item.healthStatus || '').toUpperCase();
        const badgeClass = status === 'UP' ? 'up' : (status === 'DOWN' ? 'down' : 'unknown');

        body.innerHTML = `
            <div class="preview-grid">
                <div class="preview-item"><strong>Block:</strong> <span>${item.block || 'N/A'}</span></div>
                <div class="preview-item"><strong>Asset Name:</strong> <span>${item.assetName || 'N/A'}</span></div>
                <div class="preview-item"><strong>OLT Code:</strong> <span>${item.code || 'N/A'}</span></div>
                <div class="preview-item"><strong>Status:</strong> <span class="status-badge ${badgeClass}">${status}</span></div>
                <div class="preview-item" style="grid-column: 1 / -1;">
                    <strong>Remarks / Alarm:</strong>
                    <p style="margin-top:8px; color:#555; line-height:1.5; background:#f9f9f9; padding:10px; border-radius:4px; border-left:4px solid #ddd;">
                        ${item.alarmCause || 'No details available.'}
                    </p>
                </div>
            </div>
        `;
        modal.style.display = 'flex';
        document.body.style.overflow = 'hidden';
    }
}

function closePreview() {
    const modal = document.getElementById('previewModal');
    if (modal) { modal.style.display = 'none'; document.body.style.overflow = 'auto'; }
}

window.onclick = e => { if (e.target == document.getElementById('previewModal')) closePreview(); };
