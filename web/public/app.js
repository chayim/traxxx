const $ = (id) => document.getElementById(id);
const userSel = $('user');
const typeSel = $('type');
const fromEl = $('from');
const toEl = $('to');
const tbody = document.querySelector('#activities tbody');
const empty = $('empty');
const summary = $('summary');

async function api(path) {
    const r = await fetch(path);
    if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
    return r.json();
}

function fmtDuration(secs) {
    if (secs == null) return '—';
    secs = Math.round(secs);
    const h = Math.floor(secs / 3600);
    const m = Math.floor((secs % 3600) / 60);
    const s = secs % 60;
    return h ? `${h}h ${m}m` : m ? `${m}m ${s}s` : `${s}s`;
}

function fmtPace(secs) {
    if (!secs) return '—';
    secs = Math.round(secs);
    const m = Math.floor(secs / 60);
    const s = String(secs % 60).padStart(2, '0');
    return `${m}:${s}/km`;
}

function fmtTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

async function loadUsers() {
    const users = await api('/api/users');
    userSel.innerHTML = '';
    if (!users.length) {
        const o = document.createElement('option');
        o.textContent = '(no users — run server --seed)';
        o.value = '';
        userSel.appendChild(o);
        return;
    }
    const saved = localStorage.getItem('traxxx.user');
    for (const u of users) {
        const o = document.createElement('option');
        o.value = u.id;
        o.textContent = u.name;
        userSel.appendChild(o);
    }
    if (saved && users.some(u => String(u.id) === saved)) userSel.value = saved;
}

async function loadTypes() {
    const types = await api('/api/types');
    typeSel.innerHTML = '<option value="">All</option>';
    for (const t of types) {
        const o = document.createElement('option');
        o.value = t.id;
        o.textContent = t.name;
        typeSel.appendChild(o);
    }
}

async function loadActivities() {
    const uid = userSel.value;
    if (!uid) { tbody.innerHTML = ''; empty.hidden = false; summary.innerHTML = ''; return; }
    localStorage.setItem('traxxx.user', uid);
    const params = new URLSearchParams({ user_id: uid });
    if (typeSel.value) params.set('type_id', typeSel.value);
    if (fromEl.value) params.set('from', fromEl.value);
    if (toEl.value) params.set('to', toEl.value);
    const acts = await api('/api/activities?' + params.toString());

    tbody.innerHTML = '';
    empty.hidden = acts.length !== 0;

    let totalSecs = 0;
    for (const a of acts) {
        if (a.total_seconds) totalSecs += a.total_seconds;
        const tr = document.createElement('tr');
        tr.className = 'row';
        tr.dataset.id = a.id;
        tr.innerHTML = `
            <td>${String(a.activity_date).slice(0, 10)}</td>
            <td>${escapeHtml(a.name)}</td>
            <td><span class="badge">${escapeHtml(a.type_name)}</span></td>
            <td>${fmtDuration(a.total_seconds)}</td>
            <td>${a.has_track ? `${a.track_point_count} pts` : '<span class="muted">—</span>'}</td>
        `;
        tr.addEventListener('click', () => toggleDetail(tr, a));
        tbody.appendChild(tr);
    }
    summary.innerHTML = `
        <div class="tile"><span>Activities</span><strong>${acts.length}</strong></div>
        <div class="tile"><span>Total time</span><strong>${fmtDuration(totalSecs)}</strong></div>
    `;
}

async function toggleDetail(row, activity) {
    const next = row.nextElementSibling;
    if (next && next.classList.contains('detail')) {
        next.remove();
        row.classList.remove('open');
        return;
    }
    // Collapse any other open detail.
    document.querySelectorAll('tr.detail').forEach(el => el.remove());
    document.querySelectorAll('tr.row.open').forEach(el => el.classList.remove('open'));
    row.classList.add('open');

    const det = document.createElement('tr');
    det.className = 'detail';
    const td = document.createElement('td');
    td.colSpan = 5;
    td.innerHTML = `<div class="inner"><p class="muted">Loading…</p></div>`;
    det.appendChild(td);
    row.after(det);

    try {
        const d = await api(`/api/activities/${activity.id}/detail`);
        renderDetail(td.querySelector('.inner'), d);
    } catch (e) {
        td.querySelector('.inner').innerHTML = `<p style="color:var(--bad)">Error: ${escapeHtml(e.message)}</p>`;
    }
}

function renderDetail(host, d) {
    const a = d.activity;
    const thisAvg = d.this_avg_secs_per_km;
    const overallAvg = d.overall_avg_secs_per_km;
    const maxSplit = d.splits.reduce((m, s) => Math.max(m, s.seconds), 0) || 1;

    host.innerHTML = `
        <div class="meta">
            <div><span>Type</span><strong>${escapeHtml(a.type_name)}</strong></div>
            <div><span>Start</span><strong>${fmtTime(a.started_at)}</strong></div>
            <div><span>Total time</span><strong>${fmtDuration(a.total_seconds)}</strong></div>
            <div><span>Distance</span><strong>${(d.total_meters/1000).toFixed(2)} km</strong></div>
            <div><span>Avg pace (this)</span><strong>${fmtPace(thisAvg)}</strong></div>
            <div><span>Avg pace (overall)</span><strong>${overallAvg ? fmtPace(overallAvg) : '—'}</strong></div>
        </div>
        <div id="map-${a.id}" class="map"></div>
        <div class="split-head">
            <div></div><div>km time</div><div>time</div><div>vs this</div><div>vs overall</div>
        </div>
        <div class="splits">
            ${d.splits.map(s => renderSplit(s, maxSplit, thisAvg, overallAvg)).join('')}
        </div>
    `;

    // Map
    const mapEl = host.querySelector(`#map-${a.id}`);
    if (d.track && d.track.length > 1) {
        const map = L.map(mapEl);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        const latlngs = d.track.map(p => [p.lat, p.lon]);
        const poly = L.polyline(latlngs, { color: '#ff5a3c', weight: 4 }).addTo(map);
        L.circleMarker(latlngs[0], { radius: 6, color: '#46c38f' }).addTo(map).bindTooltip('Start');
        L.circleMarker(latlngs[latlngs.length-1], { radius: 6, color: '#ff6b6b' }).addTo(map).bindTooltip('End');
        map.fitBounds(poly.getBounds(), { padding: [20, 20] });
    } else {
        mapEl.classList.add('empty');
        mapEl.textContent = 'No GPS track recorded for this activity.';
    }
}

function renderSplit(s, maxSplit, thisAvg, overallAvg) {
    const pctBar = (s.seconds / maxSplit) * 100;
    const dThis = thisAvg ? ((s.seconds - thisAvg) / thisAvg) * 100 : null;
    const dOver = overallAvg ? ((s.seconds - overallAvg) / overallAvg) * 100 : null;
    const fmtDelta = (p) => {
        if (p == null) return '<span class="muted">—</span>';
        const cls = p >= 0 ? 'pos' : 'neg';
        const sign = p >= 0 ? '+' : '';
        return `<span class="d ${cls}">${sign}${p.toFixed(1)}%</span>`;
    };
    return `
        <div class="split">
            <div class="k">${s.km}</div>
            <div class="bar"><div class="fill" style="width:${pctBar}%"></div></div>
            <div class="t">${fmtPace(s.seconds)}</div>
            <div>${fmtDelta(dThis)}</div>
            <div>${fmtDelta(dOver)}</div>
        </div>
    `;
}

$('refresh').addEventListener('click', loadActivities);
[userSel, typeSel, fromEl, toEl].forEach(el => el.addEventListener('change', loadActivities));

(async () => {
    try {
        await Promise.all([loadUsers(), loadTypes()]);
        await loadActivities();
    } catch (e) {
        document.body.insertAdjacentHTML('afterbegin',
            `<pre style="padding:12px;color:#f88;background:#200">Error: ${escapeHtml(e.message)}</pre>`);
    }
})();
