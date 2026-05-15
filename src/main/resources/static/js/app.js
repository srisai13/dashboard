let DATA = [];
let currentFilter = 'all';
let currentSearch = '';
let currentDist = '';
let startDate = '';
let endDate = '';
let currentStatus = 'ALL';
let isSyncActive = false;


let currentPage = 1;
let ROWS_PER_PAGE = 10;
let lastDataVersion = 0;
let isPolling = false;

function fetchData() {
    fetch('/api/nodes?t=' + Date.now())
        .then(res => res.json())
        .then(json => {
            if (json.loading) {
                showLoadingState('Server is processing data...');
                setTimeout(fetchData, 800);
                return;
            }

            DATA = json.data || [];
            if (json.dataVersion) lastDataVersion = json.dataVersion;

            populateDistricts();
            render();
        })
        .catch(err => {
            console.error("Fetch failed:", err);
            setTimeout(fetchData, 1500);
        });
}


function showLoadingState(message) {
    const tbody = document.getElementById('tbody');
    if (tbody) {
        tbody.innerHTML = `<tr><td colspan="10" style="text-align:center;padding:40px;">
            <div style="display:flex;flex-direction:column;align-items:center;gap:12px;">
                <div class="sync-spinner" style="position:static;"></div>
                <span style="color:#666;font-size:14px;">${message}</span>
            </div>
        </td></tr>`;
    }
}

function populateDistricts() {
    const dists = [...new Set(DATA.map(d => d.district))].filter(Boolean).sort();
    const distSel = document.getElementById('distFilter');
    if (distSel) {
        const savedDist = currentDist;
        distSel.innerHTML = '<option value="">All Districts</option>';
        dists.forEach(d => {
            const o = document.createElement('option');
            o.value = d; o.textContent = d;
            if (d === savedDist) o.selected = true;
            distSel.appendChild(o);
        });
    }
}

function filter(f, btn) {
    currentFilter = f;
    if (f.toUpperCase() === 'ALL') {
        currentDist = '';
        startDate = '';
        endDate = '';
        const distFilter = document.getElementById('distFilter');
        if (distFilter) distFilter.value = '';
        const startInput = document.getElementById('startDateFilter');
        const endInput = document.getElementById('endDateFilter');
        if (startInput) startInput.value = '';
        if (endInput) endInput.value = '';
        updateDateDisplay();
    }
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    currentPage = 1;
    render();
}

function getFilteredData() {
    return DATA.filter(d => {
        if (currentFilter !== 'all') {
            if (currentFilter === 'UP' && d.status === 'UNREACHABLE') return false;
            if (currentFilter === 'UNREACHABLE' && d.status !== 'UNREACHABLE') return false;
        }
        if (currentDist && d.district !== currentDist) return false;
        if (startDate || endDate) {
            const nodeDateStr = (d.createdDate && d.createdDate !== '--') ? d.createdDate : d.stateChange;
            if (!nodeDateStr || nodeDateStr === '--') return false;

            // Robust date parsing (handles dd-mm-yyyy, yyyy-mm-dd, and slashes)
            const parseDate = (str) => {
                const datePart = str.split(' ')[0];
                const p = datePart.split(/[-/]/);
                if (p.length !== 3) return null;

                let year, month, day;
                if (p[0].length === 4) { // yyyy-mm-dd
                    year = parseInt(p[0]); month = parseInt(p[1]) - 1; day = parseInt(p[2]);
                } else { // dd-mm-yyyy
                    year = parseInt(p[2]); month = parseInt(p[1]) - 1; day = parseInt(p[0]);
                }
                const dObj = new Date(year, month, day);
                dObj.setHours(0, 0, 0, 0);
                return dObj;
            };

            const nodeDateObj = parseDate(nodeDateStr);
            if (!nodeDateObj) return false;

            if (startDate) {
                const s = startDate.split('-');
                const startObj = new Date(parseInt(s[0]), parseInt(s[1]) - 1, parseInt(s[2]));
                startObj.setHours(0, 0, 0, 0);
                if (nodeDateObj < startObj) return false;
            }
            if (endDate) {
                const e = endDate.split('-');
                const endObj = new Date(parseInt(e[0]), parseInt(e[1]) - 1, parseInt(e[2]));
                endObj.setHours(0, 0, 0, 0);
                if (nodeDateObj > endObj) return false;
            }
        }
        if (currentSearch) {
            const s = currentSearch;
            const gpMatch = d.gps && d.gps.some(g => g.loc.toLowerCase().includes(s));
            if (!d.name.toLowerCase().includes(s) && !(d.blockCode || "").toLowerCase().includes(s) && !d.ip.includes(s) && !d.district.toLowerCase().includes(s) && !gpMatch) return false;
        }
        return true;
    });
}

function render() {
    const tbody = document.getElementById('tbody');
    if (!tbody) return;

    updateStats();
    const filtered = getFilteredData();
    const totalPages = Math.max(1, Math.ceil(filtered.length / ROWS_PER_PAGE));
    if (currentPage > totalPages) currentPage = totalPages;

    const start = (currentPage - 1) * ROWS_PER_PAGE;
    const end = start + ROWS_PER_PAGE;
    const paginated = filtered.slice(start, end);

    const fragment = document.createDocumentFragment();

    paginated.forEach((d, idx) => {
        const tr = document.createElement('tr');
        const isDown = d.status === 'UNREACHABLE';

        tr.innerHTML = `
            <td>${start + idx + 1}</td>
            <td>${d.district || ''}</td>
            <td>${d.name || ''}</td>
            <td>${d.blockCode || ''}</td>
            <td>${d.ip || ''}</td>
            <td>${d.gpBlock || ''}</td>
            <td>${isDown ? '<span class="status-badge down">DOWN</span>' : '<span class="status-badge up">UP</span>'}</td>
            <td>${d.gpCount}</td>
            <td>
                <button class="gp-view-btn" onclick="openGPsOnly('${d.ip}')">View Locations</button>
            </td>
            <td style="text-align:center;">
                <button class="preview-btn" onclick="openPreview('${d.ip}')" title="View Details">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                </button>
            </td>`;
        fragment.appendChild(tr);
    });

    tbody.innerHTML = '';
    if (fragment.children.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" style="text-align:center;padding:40px;">No data found</td></tr>';
    } else {
        tbody.appendChild(fragment);
    }

    renderPagination(filtered.length, totalPages);
}

function renderPagination(totalItems, totalPages) {
    const el = document.getElementById('pagination');
    if (!el) return;

    if (totalItems === 0) { el.style.display = 'none'; return; }
    el.style.display = 'flex';

    const startItem = (currentPage - 1) * ROWS_PER_PAGE + 1;
    const endItem = Math.min(currentPage * ROWS_PER_PAGE, totalItems);

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
            <button class="page-btn ${currentPage === 1 ? 'disabled' : ''}" onclick="goTo(1)" ${currentPage === 1 ? 'disabled' : ''}>«</button>
            <button class="page-btn ${currentPage === 1 ? 'disabled' : ''}" onclick="goTo(${currentPage - 1})" ${currentPage === 1 ? 'disabled' : ''}>‹</button>
            ${getPageRange(currentPage, totalPages).map(p => p === '...' ? `<span class="page-ellipsis">…</span>` : `<button class="page-btn ${p === currentPage ? 'active' : ''}" onclick="goTo(${p})">${p}</button>`).join('')}
            <button class="page-btn ${currentPage === totalPages ? 'disabled' : ''}" onclick="goTo(${currentPage + 1})" ${currentPage === totalPages ? 'disabled' : ''}>›</button>
            <button class="page-btn ${currentPage === totalPages ? 'disabled' : ''}" onclick="goTo(${totalPages})" ${currentPage === totalPages ? 'disabled' : ''}>»</button>
        </div>
    `;
    el.innerHTML = html;
}

function getPageRange(current, total) {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
    const pages = [1];
    if (current > 4) pages.push('...');
    let start = Math.max(2, current - 1);
    let end = Math.min(total - 1, current + 1);
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
    const total = Math.max(1, Math.ceil(getFilteredData().length / ROWS_PER_PAGE));
    if (p < 1 || p > total) return;
    currentPage = p;
    render();
    const card = document.querySelector('.card');
    if (card) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function openGPsOnly(ip) {
    const node = DATA.find(d => d.ip === ip);
    if (!node) return;
    const gpTags = (node.gps || []).map(g => `<span class="gp-tag ${g.status === 'DOWN' ? 'down' : ''}">${g.loc}</span>`).join('');
    document.getElementById('previewTitle').textContent = "GP Locations: " + node.name;
    document.getElementById('previewBody').innerHTML = `<div class="gp-container" style="max-height:300px; border:1px solid #eee; padding:15px; border-radius:8px; background:#fdfdfd;">${gpTags || '<span style="color:#999;">No locations found.</span>'}</div>`;
    document.getElementById('previewModal').style.display = 'flex';
}

function openPreview(ip) {
    const node = DATA.find(d => d.ip === ip);
    if (!node) return;
    const isDown = node.status === 'UNREACHABLE';
    const statusColor = isDown ? '#f44336' : '#4caf50';
    const gpTags = (node.gps || []).map(g => `<span class="gp-tag ${g.status === 'DOWN' ? 'down' : ''}">${g.loc}</span>`).join('');
    document.getElementById('previewTitle').textContent = "Node: " + node.name;
    document.getElementById('previewBody').innerHTML = `
        <div class="attr-row"><span class="attr-key">District:</span><span class="attr-val">${node.district || '—'}</span></div>
        <div class="attr-row"><span class="attr-key">Block Node Name:</span><span class="attr-val">${node.name || '—'}</span></div>
        <div class="attr-row"><span class="attr-key">Block Code:</span><span class="attr-val">${node.blockCode || '—'}</span></div>
        <div class="attr-row"><span class="attr-key">IP Address:</span><span class="attr-val">${node.ip || '—'}</span></div>
        <div class="attr-row"><span class="attr-key">Status:</span><span class="attr-val" style="color:${statusColor}; font-weight:bold;">${node.status}</span></div>
        <div class="attr-row"><span class="attr-key">Incident Created At:</span><span class="attr-val">${node.createdDate || '—'}</span></div>
        <div class="attr-row"><span class="attr-key">Last State Change:</span><span class="attr-val">${node.stateChange || '—'}</span></div>
        <div class="attr-row"><span class="attr-key">GP Count:</span><span class="attr-val">${node.gpCount}</span></div>
        <div style="margin-top:15px; font-weight:bold; color:#666; font-size:12px;">GP LOCATIONS:</div>
        <div class="gp-container" style="max-height:150px; margin-top:10px; border:1px solid #eee; padding:10px; border-radius:5px;">${gpTags || '—'}</div>
    `;
    document.getElementById('previewModal').style.display = 'flex';
}

function closePreview() { document.getElementById('previewModal').style.display = 'none'; }

async function syncLiveApi(isManual = true) {
    const btn = document.getElementById('refreshBtn');
    const spinner = document.getElementById('syncSpinner');

    // If manual click and sync is active, it acts as a 'Cancel' button
    if (isManual && isSyncActive) {
        try {
            await fetch('/api/stop-sync', { method: 'POST' });
            showToast("Stopping sync...");
            return;
        } catch (e) {
            console.error("Stop failed", e);
            return;
        }
    }

    // For auto-refresh (isManual = false), don't trigger if already syncing
    if (!isManual && isSyncActive) {
        console.log("Auto-refresh skipped: Sync already in progress");
        return;
    }

    if (btn && isManual) {
        currentPage = 1;
        if (spinner) spinner.style.display = 'inline-block';
    }

    try {
        const res = await fetch('/api/sync-live', { method: 'POST' });
        if (res.status === 400) {
            showToast("Sync already in progress...");
        } else if (!res.ok) {
            showToast("Failed to start sync");
        }

        if (!isPolling) {
            pollSync('incident');
        }
    } catch (e) {
        console.error("Sync trigger failed:", e);
        if (isManual) showToast("Error connecting to server");
    }
}

async function pollSync(type) {
    isPolling = true;
    try {
        const res = await fetch('/api/status?t=' + Date.now());
        const data = await res.json();

        const progress = data.syncProgress || "";
        const running = data.syncRunning;
        isSyncActive = running;

        const refreshBtn = document.getElementById('refreshBtn');
        const spinner = document.getElementById('syncSpinner');
        
        if (refreshBtn) {
            if (running) {
                refreshBtn.textContent = 'Cancel';
                refreshBtn.className = 'btn btn-cancel';
                if (spinner) spinner.classList.add('red');
            } else {
                refreshBtn.textContent = 'Refresh';
                refreshBtn.className = 'btn btn-refresh';
                if (spinner) spinner.classList.remove('red');
            }
        }

        // Update data if version changed
        if (data.dataVersion !== lastDataVersion) {
            lastDataVersion = data.dataVersion;
            fetchData();
        }

        if (!running) {
            // Sync stopped or finished
            if (spinner) spinner.style.display = 'none';
            
            if (progress.includes("Completed") || progress.includes("Finished")) {
                showToast("Refresh Done!");
            } else if (progress.includes("Error")) {
                showToast("Sync failed: " + progress);
            } else if (progress.includes("Stopped")) {
                showToast("Sync cancelled");
            }
            
            isPolling = false;
            // Stop polling since not running
            return; 
        } else {
            // Still running, show spinner and poll again
            if (spinner) spinner.style.display = 'inline-block';
            setTimeout(() => pollSync(type), 2000);
        }
    } catch (e) {
        console.error("Poll failed:", e);
        // Retry with longer delay on network error
        setTimeout(() => pollSync(type), 5000);
    }
}

function showToast(msg) {
    const t = document.getElementById('toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('show');
    setTimeout(() => t.classList.remove('show'), 3000);
}

function updateStats() {
    if (DATA.length === 0) return;
    let totalU = 0, totalR = 0;
    DATA.forEach(d => { if (d.status === 'UNREACHABLE') totalU++; else totalR++; });
    document.getElementById('totalNum').textContent = DATA.length;
    document.getElementById('upNum').textContent = totalR;
    document.getElementById('downNum').textContent = totalU;
}

function updateDateDisplay() {
    const clearBtn = document.getElementById('clearDateBtn');
    const dateDisplay = document.getElementById('dateDisplay');

    if (!startDate && !endDate) {
        if (dateDisplay) dateDisplay.textContent = "All Dates";
        if (clearBtn) clearBtn.style.display = 'none';
        return;
    }

    if (clearBtn) clearBtn.style.display = 'block';

    const formatDate = (val) => {
        if (!val) return '';
        const p = val.split('-');
        const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        return `${p[2]} ${months[parseInt(p[1]) - 1]}`;
    };

    if (startDate && endDate) {
        dateDisplay.textContent = `${formatDate(startDate)} - ${formatDate(endDate)}`;
    } else if (startDate) {
        dateDisplay.textContent = `From ${formatDate(startDate)}`;
    } else if (endDate) {
        dateDisplay.textContent = `Until ${formatDate(endDate)}`;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    fetchData();
    
    // Check if sync is already running on server to resume polling
    fetch('/api/status?t=' + Date.now())
        .then(res => res.json())
        .then(data => {
            if (data.syncRunning) {
                isSyncActive = true;
                if (!isPolling) pollSync('incident');
            }
        }).catch(err => console.error("Initial status check failed:", err));

    // setInterval(() => syncLiveApi(false), 120000); // Removed auto-refresh timer

    const distFilter = document.getElementById('distFilter');
    if (distFilter) distFilter.onchange = (e) => { currentDist = e.target.value; currentPage = 1; render(); };
    window.clearDateFilter = function (e) {
        if (e) e.stopPropagation();
        startDate = '';
        endDate = '';
        const startInput = document.getElementById('startDateFilter');
        const endInput = document.getElementById('endDateFilter');
        if (startInput) startInput.value = '';
        if (endInput) endInput.value = '';
        updateDateDisplay();
        const dd = document.getElementById('dateRangeDropdown');
        if (dd) dd.classList.remove('show');
        currentPage = 1;
        render();
    };



    const searchInput = document.getElementById('search');

    window.toggleDateDropdown = function (e) {
        e.stopPropagation();
        const dd = document.getElementById('dateRangeDropdown');
        if (dd) dd.classList.toggle('show');
    };

    window.applyDateRange = function () {
        startDate = document.getElementById('startDateFilter').value;
        endDate = document.getElementById('endDateFilter').value;
        updateDateDisplay();
        document.getElementById('dateRangeDropdown').classList.remove('show');
        currentPage = 1;
        render();
    };

    window.setDatePreset = function (type) {
        const today = new Date();
        let start = new Date();
        let end = new Date();

        if (type === 'today') {
            // No changes needed
        } else if (type === 'yesterday') {
            start.setDate(today.getDate() - 1);
            end.setDate(today.getDate() - 1);
        } else if (type === '7days') {
            start.setDate(today.getDate() - 7);
        } else if (type === '30days') {
            start.setDate(today.getDate() - 30);
        }

        const toISO = (d) => d.toISOString().split('T')[0];
        startDate = toISO(start);
        endDate = toISO(end);

        document.getElementById('startDateFilter').value = startDate;
        document.getElementById('endDateFilter').value = endDate;

        applyDateRange();
    };

    // Close dropdown on outside click
    document.addEventListener('click', (e) => {
        const dd = document.getElementById('dateRangeDropdown');
        const btn = document.getElementById('datePickerBtn');
        if (dd && dd.classList.contains('show') && !dd.contains(e.target) && !btn.contains(e.target)) {
            dd.classList.remove('show');
        }
    });

    if (searchInput) {
        let timer;
        searchInput.oninput = (e) => {
            clearTimeout(timer);
            timer = setTimeout(() => {
                currentSearch = e.target.value.toLowerCase();
                currentPage = 1;
                render();
            }, 250);
        };
    }
});


