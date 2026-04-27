let DATA = [];
let currentFilter = 'all';
let currentSearch = '';
let currentDist = '';
let currentPage = 1;
let ROWS_PER_PAGE = 10;

function fetchData() {
    const loadingEl = document.getElementById('loading');
    if (loadingEl) loadingEl.style.display = 'block';
    
    fetch('/api/nodes?t=' + Date.now())
        .then(res => res.json())
        .then(json => {
            if (loadingEl) loadingEl.style.display = 'none';
            
            // If server is still loading, show status and poll faster
            if (json.loading) {
                showLoadingState('Server is processing data...');
                setTimeout(fetchData, 800);
                return;
            }
            
            DATA = json.data || [];
            
            const reportDateEl = document.getElementById('reportDate');
            if (reportDateEl) {
                reportDateEl.textContent = "Report Date: " + (json.date || "N/A");
            }
            
            const lastSyncEl = document.getElementById('lastSyncTime');
            if (lastSyncEl) {
                lastSyncEl.textContent = "Last Sync: " + new Date().toLocaleTimeString();
            }
            
            populateDistricts();
            currentPage = 1;
            render();
        })
        .catch(err => {
            console.error("Fetch failed:", err);
            if (loadingEl) loadingEl.style.display = 'none';
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
    // Hide pagination while loading
    const paginationEl = document.getElementById('pagination');
    if (paginationEl) paginationEl.style.display = 'none';
}

function populateDistricts() {
    const dists = [...new Set(DATA.map(d => d.district))].sort();
    const distSel = document.getElementById('distFilter');
    if (distSel) {
        distSel.innerHTML = '<option value="">All Districts</option>';
        dists.forEach(d => {
            const o = document.createElement('option');
            o.value = d; o.textContent = d;
            distSel.appendChild(o);
        });
    }
}

function filter(f, btn) {
    currentFilter = f;
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    currentPage = 1; // Reset to page 1 on filter change
    render();
}

function getFilteredData() {
    return DATA.filter(d => {
        if (currentFilter !== 'all' && d.status !== currentFilter) return false;
        if (currentDist && d.district !== currentDist) return false;
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

    const filtered = getFilteredData();
    const totalPages = Math.max(1, Math.ceil(filtered.length / ROWS_PER_PAGE));
    
    // Clamp current page
    if (currentPage > totalPages) currentPage = totalPages;
    if (currentPage < 1) currentPage = 1;

    const startIdx = (currentPage - 1) * ROWS_PER_PAGE;
    const endIdx = Math.min(startIdx + ROWS_PER_PAGE, filtered.length);
    const pageData = filtered.slice(startIdx, endIdx);

    // Calculate stats from full dataset
    let totalU = 0, totalR = 0;
    DATA.forEach(d => { if(d.status === 'UNREACHABLE') totalU++; else totalR++; });

    // Build table rows with DocumentFragment
    const fragment = document.createDocumentFragment();
    
    for (let i = 0; i < pageData.length; i++) {
        const d = pageData[i];
        const globalIdx = startIdx + i; // Global row number
        const tr = document.createElement('tr');
        const isDown = d.status === 'UNREACHABLE';
        
        tr.innerHTML = `
            <td>${globalIdx + 1}</td>
            <td>${d.district || ''}</td>
            <td>${d.name || ''}</td>
            <td>${d.blockCode || ''}</td>
            <td>${d.ip || ''}</td>
            <td>${d.gpBlock || ''}</td>
            <td>${isDown ? '<span class="status-badge down">DOWN</span>' : '<span class="status-badge up">UP</span>'}</td>
            <td>${d.gpCount}</td>
            <td>
                <button class="gp-view-btn" onclick="openGPsOnly('${d.ip}')">
                    View Locations
                </button>
            </td>
            <td style="text-align:center;">
                <button class="preview-btn" onclick="openPreview('${d.ip}')" title="View Details">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                </button>
            </td>`;
        fragment.appendChild(tr);
    }

    tbody.innerHTML = '';
    if (fragment.children.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" style="text-align:center;padding:40px;">No data found</td></tr>';
    } else {
        tbody.appendChild(fragment);
    }

    // Update stats
    document.getElementById('totalNum').textContent = DATA.length;
    document.getElementById('upNum').textContent = totalR;
    document.getElementById('downNum').textContent = totalU;

    // Render pagination
    renderPagination(filtered.length, totalPages);
}

function renderPagination(totalItems, totalPages) {
    const paginationEl = document.getElementById('pagination');
    if (!paginationEl) return;

    if (totalItems === 0) {
        paginationEl.style.display = 'none';
        return;
    }

    paginationEl.style.display = 'flex';

    const startItem = (currentPage - 1) * ROWS_PER_PAGE + 1;
    const endItem = Math.min(currentPage * ROWS_PER_PAGE, totalItems);

    let html = '';

    // Left section: Page size + info
    html += '<div class="page-left">';
    html += '<span class="page-label">Page size</span>';
    html += `<select class="page-size-select" onchange="changePageSize(this.value)">`;
    [10, 20, 50, 100].forEach(size => {
        html += `<option value="${size}" ${ROWS_PER_PAGE === size ? 'selected' : ''}>${size}</option>`;
    });
    html += '</select>';
    html += `<span class="page-info">Showing ${startItem} - ${endItem} of ${totalItems} items.</span>`;
    html += '</div>';

    // Middle section: Go To
    html += '<div class="page-goto">';
    html += '<span class="page-label">Go To</span>';
    html += `<input type="number" class="page-goto-input" id="gotoPageInput" value="${currentPage}" min="1" max="${totalPages}" 
             onkeydown="if(event.key==='Enter')goToInputPage()">`;
    html += `<button class="page-goto-btn" onclick="goToInputPage()">▶</button>`;
    html += '</div>';

    // Right section: Page buttons
    html += '<div class="page-buttons">';

    // « First page
    html += `<button class="page-btn ${currentPage === 1 ? 'disabled' : ''}" 
             onclick="goToPage(1)" ${currentPage === 1 ? 'disabled' : ''}>«</button>`;

    // ‹ Previous page
    html += `<button class="page-btn ${currentPage === 1 ? 'disabled' : ''}" 
             onclick="goToPage(${currentPage - 1})" ${currentPage === 1 ? 'disabled' : ''}>‹</button>`;

    // Page numbers
    const pages = getPageRange(currentPage, totalPages);
    for (const p of pages) {
        if (p === '...') {
            html += `<span class="page-ellipsis">…</span>`;
        } else {
            html += `<button class="page-btn ${p === currentPage ? 'active' : ''}" 
                     onclick="goToPage(${p})">${p}</button>`;
        }
    }

    // › Next page
    html += `<button class="page-btn ${currentPage === totalPages ? 'disabled' : ''}" 
             onclick="goToPage(${currentPage + 1})" ${currentPage === totalPages ? 'disabled' : ''}>›</button>`;

    // » Last page
    html += `<button class="page-btn ${currentPage === totalPages ? 'disabled' : ''}" 
             onclick="goToPage(${totalPages})" ${currentPage === totalPages ? 'disabled' : ''}>»</button>`;

    html += '</div>';

    paginationEl.innerHTML = html;
}

function changePageSize(val) {
    ROWS_PER_PAGE = parseInt(val);
    currentPage = 1;
    render();
}

function goToInputPage() {
    const input = document.getElementById('gotoPageInput');
    if (!input) return;
    const page = parseInt(input.value);
    if (!isNaN(page)) goToPage(page);
}

function getPageRange(current, total) {
    if (total <= 5) {
        return Array.from({length: total}, (_, i) => i + 1);
    }

    const pages = [];
    let start = Math.max(1, current - 2);
    let end = Math.min(total, start + 4);
    if (end - start < 4) {
        start = Math.max(1, end - 4);
    }
    for (let i = start; i <= end; i++) {
        pages.push(i);
    }
    return pages;
}

function goToPage(page) {
    const filtered = getFilteredData();
    const totalPages = Math.max(1, Math.ceil(filtered.length / ROWS_PER_PAGE));
    
    if (page < 1 || page > totalPages) return;
    currentPage = page;
    render();

    // Scroll to top of table
    const card = document.querySelector('.card');
    if (card) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function openGPsOnly(ip) {
    const node = DATA.find(d => d.ip === ip);
    if (!node) return;
    const gpTags = (node.gps || []).map(g => `<span class="gp-tag ${g.status === 'DOWN' ? 'down' : ''}">${g.loc}</span>`).join('');

    document.getElementById('previewTitle').textContent = "GP Locations: " + node.name;
    document.getElementById('previewBody').innerHTML = `
        <div class="gp-container" style="max-height:300px; border:1px solid #eee; padding:15px; border-radius:8px; background:#fdfdfd;">
            ${gpTags || '<span style="color:#999;">No locations found.</span>'}
        </div>
    `;
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
        <div class="attr-row"><span class="attr-key">GP Count:</span><span class="attr-val">${node.gpCount}</span></div>
        <div style="margin-top:15px; font-weight:bold; color:#666; font-size:12px;">GP LOCATIONS:</div>
        <div class="gp-container" style="max-height:150px; margin-top:10px; border:1px solid #eee; padding:10px; border-radius:5px;">${gpTags || '—'}</div>
    `;
    document.getElementById('previewModal').style.display = 'flex';
}

function closePreview() {
    document.getElementById('previewModal').style.display = 'none';
}

async function syncLiveApi(isManual = true) {
    // Blink the refresh button light green
    const btn = document.getElementById('refreshBtn');
    if (btn && isManual) {
        btn.style.transition = 'background-color 0.15s ease';
        btn.style.backgroundColor = '#7dff7d';
        setTimeout(() => { btn.style.backgroundColor = '#28a745'; }, 300);
    }
    
    try {
        const res = await fetch('/api/sync-live', { method: 'POST' });
        
        if (res.status === 400 && isManual) {
            const json = await res.json();
            console.log("Sync info:", json.message || "Sync already running");
        }
        pollSync(isManual);
    } catch (e) {
        console.error("Sync failed:", e);
    }
}

async function pollSync(isManual = true) {
    try {
        const res = await fetch('/api/sync-status?t=' + Date.now());
        const data = await res.json();
        
        if (data.status === "Completed" || data.status === "Stopped" || data.status === "Idle") {
            fetchData();
            if (isManual) showToast("Data refreshed successfully!");
        } else {
            setTimeout(() => pollSync(isManual), 2000);
        }
    } catch (e) {
        console.error("Poll failed:", e);
        setTimeout(() => pollSync(isManual), 5000);
    }
}

function showToast(msg) {
    let toast = document.getElementById('toast-popup');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'toast-popup';
        toast.style.cssText = 'position:fixed;top:20px;right:20px;background:#28a745;color:#fff;padding:12px 24px;border-radius:8px;font-size:14px;font-weight:500;box-shadow:0 4px 12px rgba(0,0,0,0.2);z-index:99999;opacity:0;transform:translateX(100px);transition:all 0.3s ease;';
        document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.style.opacity = '1';
    toast.style.transform = 'translateX(0)';
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100px)';
    }, 3000);
}

document.addEventListener('DOMContentLoaded', () => {
    showLoadingState('Loading dashboard data...');
    fetchData();
    
    // Auto-refresh every 2 minutes
    setInterval(() => {
        const now = new Date().toLocaleTimeString();
        console.log("[" + now + "] Auto-refreshing data...");
        syncLiveApi(false);
    }, 120000);

    const distFilter = document.getElementById('distFilter');
    if (distFilter) distFilter.onchange = (e) => { currentDist = e.target.value; currentPage = 1; render(); };
    const searchInput = document.getElementById('search');
    if (searchInput) {
        let debounceTimer;
        searchInput.oninput = (e) => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                currentSearch = e.target.value.toLowerCase();
                currentPage = 1;
                render();
            }, 250);
        };
    }
});
