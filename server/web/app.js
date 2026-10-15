const $ = (id) => document.getElementById(id);
const typeSel = $('type');
const fromEl = $('from');
const toEl = $('to');
const tbody = document.querySelector('#activities tbody');
const empty = $('empty');
const summary = $('summary');
const perPageSel = $('perPage');
const pager = $('pager');
const prevBtn = $('prevPage');
const nextBtn = $('nextPage');
const pageInfo = $('pageInfo');
const breakdown = $('breakdown');
const breakdownBody = document.querySelector('#breakdown-table tbody');

const homeView = $('home-view');
const activityView = $('activity-view');
const activityControls = $('activity-controls');
const userCrumb = $('user-crumb');
const crumbUser = $('crumb-user');
const userGrid = $('user-grid');
const noUsers = $('no-users');

let allActivities = [];
let currentPage = 1;
let currentUserId = null;
let usersCache = [];

function startOfWeek(d) {
    const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    const dow = (x.getDay() + 6) % 7; // Monday=0
    x.setDate(x.getDate() - dow);
    return x;
}
function startOfMonth(d) { return new Date(d.getFullYear(), d.getMonth(), 1); }

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

// Delta vs reference, using detail-view semantics: slower (higher secs/km) is
// worse → .pos (red); faster is better → .neg (green). The muted em-dash is
// returned when either input is missing (e.g. time-only activity).
function fmtPaceDelta(secs, refSecs) {
    if (!secs || !refSecs) return '<span class="muted">—</span>';
    const p = ((secs - refSecs) / refSecs) * 100;
    const cls = p >= 0 ? 'pos' : 'neg';
    const sign = p >= 0 ? '+' : '';
    return `<span class="d ${cls}">${sign}${p.toFixed(1)}%</span>`;
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
    usersCache = await api('/api/users');
    return usersCache;
}

function renderUserGrid() {
    userGrid.innerHTML = '';
    if (!usersCache.length) {
        noUsers.hidden = false;
        return;
    }
    noUsers.hidden = true;
    for (const u of usersCache) {
        const card = document.createElement('a');
        card.className = 'user-card';
        card.href = `#/user/${u.id}`;
        const initial = (u.name || '?').trim().charAt(0);
        card.innerHTML = `
            <div class="user-avatar">${escapeHtml(initial)}</div>
            <div class="user-name">${escapeHtml(u.name)}</div>
            <div class="user-meta">#${u.id}</div>
        `;
        userGrid.appendChild(card);
    }
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
    if (!currentUserId) return;
    const params = new URLSearchParams({ user_id: currentUserId });
    if (typeSel.value) params.set('type_id', typeSel.value);
    if (fromEl.value) params.set('from', fromEl.value);
    if (toEl.value) params.set('to', toEl.value);
    allActivities = await api('/api/activities?' + params.toString());
    currentPage = 1;
    renderSummary(allActivities);
    renderBreakdown(allActivities);
    renderPage();
}

function renderPage() {
    const perPageVal = perPageSel.value;
    const perPage = perPageVal === 'all' ? allActivities.length || 1 : Number(perPageVal);
    const totalPages = Math.max(1, Math.ceil(allActivities.length / perPage));
    if (currentPage > totalPages) currentPage = totalPages;
    const start = (currentPage - 1) * perPage;
    const slice = allActivities.slice(start, start + perPage);

    tbody.innerHTML = '';
    empty.hidden = allActivities.length !== 0;

    for (const a of slice) {
        const tr = document.createElement('tr');
        tr.className = 'row';
        tr.dataset.id = a.id;
        tr.innerHTML = `
            <td>${String(a.activity_date).slice(0, 10)}</td>
            <td>${escapeHtml(a.name)}</td>
            <td><span class="badge">${escapeHtml(a.type_name)}</span></td>
            <td>${fmtDuration(a.total_seconds)}</td>
            <td class="pace">${fmtPace(a.avg_secs_per_km)}</td>
            <td class="pace-delta">${fmtPaceDelta(a.avg_secs_per_km, a.type_avg_secs_per_km)}</td>
        `;
        tr.addEventListener('click', () => toggleDetail(tr, a));
        tbody.appendChild(tr);
    }

    pager.hidden = allActivities.length === 0 || perPageVal === 'all';
    pageInfo.textContent = `Page ${currentPage} of ${totalPages} · ${allActivities.length} total`;
    prevBtn.disabled = currentPage <= 1;
    nextBtn.disabled = currentPage >= totalPages;
}

function renderSummary(acts) {
    let totalSecs = 0, weekSecs = 0, monthSecs = 0;
    const now = new Date();
    const wkStart = startOfWeek(now);
    const moStart = startOfMonth(now);
    for (const a of acts) {
        const secs = a.total_seconds || 0;
        totalSecs += secs;
        const d = new Date(String(a.activity_date).slice(0, 10) + 'T00:00:00');
        if (d >= wkStart) weekSecs += secs;
        if (d >= moStart) monthSecs += secs;
    }
    const toMin = (s) => Math.round(s / 60).toLocaleString();
    summary.innerHTML = `
        <div class="tile"><span>Activities</span><strong>${acts.length}</strong></div>
        <div class="tile"><span>Total time</span><strong>${fmtDuration(totalSecs)}</strong></div>
        <div class="tile"><span>This week (min)</span><strong>${toMin(weekSecs)}</strong></div>
        <div class="tile"><span>This month (min)</span><strong>${toMin(monthSecs)}</strong></div>
    `;
}

function renderBreakdown(acts) {
    const byType = new Map();
    for (const a of acts) {
        const secs = a.total_seconds || 0;
        const row = byType.get(a.type_name) || { count: 0, secs: 0 };
        row.count += 1;
        row.secs += secs;
        byType.set(a.type_name, row);
    }
    const entries = [...byType.entries()].sort((a, b) => b[1].secs - a[1].secs);
    const total = entries.reduce((s, [, v]) => s + v.secs, 0);
    if (entries.length === 0) { breakdown.hidden = true; return; }
    breakdown.hidden = false;

    breakdownBody.innerHTML = entries.map(([name, v]) => {
        const mins = Math.round(v.secs / 60);
        const pct = total ? (v.secs / total) * 100 : 0;
        return `
            <tr>
                <td><span class="badge">${escapeHtml(name)}</span></td>
                <td>${v.count}</td>
                <td>${fmtDuration(v.secs)}</td>
                <td>${mins.toLocaleString()}</td>
                <td>
                    <div class="share">
                        <div class="share-bar"><div class="share-fill" style="width:${pct.toFixed(2)}%"></div></div>
                        <span class="share-pct">${pct.toFixed(1)}%</span>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
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
    td.colSpan = 6;
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
        <div class="map-wrap">
            <div id="map-${a.id}" class="map"></div>
            <div class="map-controls" id="mc-${a.id}" hidden>
                <button id="rotL-${a.id}" title="Rotate left">↺</button>
                <button id="rotR-${a.id}" title="Rotate right">↻</button>
                <button id="rotN-${a.id}" title="Reset orientation (north up)">N</button>
                <select class="speed" id="speed-${a.id}">
                    <option value="4">4×</option>
                    <option value="8" selected>8×</option>
                    <option value="16">16×</option>
                    <option value="32">32×</option>
                </select>
                <button id="play-${a.id}" title="Play">▶</button>
            </div>
        </div>
        <div class="split-head">
            <div></div><div>km time</div><div>time</div><div>vs this</div><div>vs overall</div>
        </div>
        <div class="splits">
            ${d.splits.map(s => renderSplit(s, maxSplit, thisAvg, overallAvg)).join('')}
        </div>
    `;

    const mapEl = host.querySelector(`#map-${a.id}`);
    if (d.track && d.track.length > 1) {
        setupMap(mapEl, d, host, a.id);
    } else {
        mapEl.classList.add('empty');
        mapEl.textContent = 'No GPS track recorded for this activity.';
    }
}

function bearingDeg(lat1, lon1, lat2, lon2) {
    const toRad = x => x * Math.PI / 180;
    const toDeg = x => x * 180 / Math.PI;
    const dLon = toRad(lon2 - lon1);
    const y = Math.sin(dLon) * Math.cos(toRad(lat2));
    const x = Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
              Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(dLon);
    return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

function shortestAngleDelta(from, to) {
    let d = ((to - from + 540) % 360) - 180;
    return d;
}

function setupMap(mapEl, d, host, actID) {
    const coords = d.track.map(p => ({
        lng: p.lon, lat: p.lat, t: new Date(p.recorded_at).getTime()
    }));
    const lineCoords = coords.map(c => [c.lng, c.lat]);
    const trackGeoJSON = {
        type: 'Feature', properties: {},
        geometry: { type: 'LineString', coordinates: lineCoords }
    };

    const bounds = lineCoords.reduce(
        (b, c) => b.extend(c),
        new maplibregl.LngLatBounds(lineCoords[0], lineCoords[0])
    );

    const map = new maplibregl.Map({
        container: mapEl,
        style: 'https://tiles.openfreemap.org/styles/liberty',
        bounds, fitBoundsOptions: { padding: 40 },
        pitch: 0, bearing: 0,
        attributionControl: { compact: true },
    });

    const startMarker = new maplibregl.Marker({ color: '#46c38f' })
        .setLngLat(lineCoords[0]).addTo(map);
    const endMarker = new maplibregl.Marker({ color: '#ff6b6b' })
        .setLngLat(lineCoords[lineCoords.length - 1]).addTo(map);
    let moverEl = null, moverMarker = null;

    map.on('load', () => {
        map.addSource('track', { type: 'geojson', data: trackGeoJSON });
        map.addLayer({
            id: 'track-line', type: 'line', source: 'track',
            layout: { 'line-cap': 'round', 'line-join': 'round' },
            paint: { 'line-color': '#ff5a3c', 'line-width': 5, 'line-opacity': 0.85 }
        });
        map.addSource('trail', {
            type: 'geojson',
            data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [] } }
        });
        map.addLayer({
            id: 'trail-line', type: 'line', source: 'trail',
            layout: { 'line-cap': 'round', 'line-join': 'round' },
            paint: { 'line-color': '#ffd166', 'line-width': 6 }
        });
    });

    const controls = host.querySelector(`#mc-${actID}`);
    controls.hidden = false;
    const playBtn = host.querySelector(`#play-${actID}`);
    const speedSel = host.querySelector(`#speed-${actID}`);

    const player = { rafId: 0, playing: false, startWall: 0, startTrack: 0, bearing: 0, rotOffset: 0 };

    const rotLBtn = host.querySelector(`#rotL-${actID}`);
    const rotRBtn = host.querySelector(`#rotR-${actID}`);
    const rotNBtn = host.querySelector(`#rotN-${actID}`);
    const ROT_STEP = 30;
    const spin = (delta) => {
        if (player.playing) {
            player.rotOffset = (player.rotOffset + delta + 360) % 360;
        } else {
            map.easeTo({ bearing: (map.getBearing() + delta) % 360, duration: 300 });
        }
    };
    rotLBtn.addEventListener('click', () => spin(-ROT_STEP));
    rotRBtn.addEventListener('click', () => spin(ROT_STEP));
    rotNBtn.addEventListener('click', () => {
        if (player.playing) {
            player.rotOffset = 0;
        } else {
            map.easeTo({ bearing: 0, duration: 400 });
        }
    });

    const stop = () => {
        if (player.rafId) cancelAnimationFrame(player.rafId);
        player.rafId = 0;
        player.playing = false;
        playBtn.textContent = '▶';
        playBtn.title = 'Play';
    };
    const resetView = () => {
        player.rotOffset = 0;
        if (map.getLayer('track-line')) map.setLayoutProperty('track-line', 'visibility', 'visible');
        if (moverMarker) { moverMarker.remove(); moverMarker = null; }
        if (map.getSource('trail')) {
            map.getSource('trail').setData({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [] } });
        }
        map.easeTo({ pitch: 0, bearing: 0, duration: 800 });
        map.fitBounds(bounds, { padding: 40, duration: 1000 });
    };

    playBtn.addEventListener('click', () => {
        if (player.playing) { stop(); resetView(); return; }
        if (!map.getLayer('track-line')) return; // style not ready

        player.playing = true;
        playBtn.textContent = '⏸';
        playBtn.title = 'Pause';

        // Hide the full-track overview; keep yellow trail for behind-you path.
        map.setLayoutProperty('track-line', 'visibility', 'none');

        // Mover: a small coloured dot you follow from behind.
        moverEl = document.createElement('div');
        moverEl.style.cssText = 'width:14px;height:14px;border-radius:50%;background:#ff5a3c;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.5)';
        moverMarker = new maplibregl.Marker({ element: moverEl })
            .setLngLat(lineCoords[0]).addTo(map);

        // Initial bearing from first few points so camera faces forward on frame 1.
        const look0 = coords[Math.min(coords.length - 1, 5)];
        player.bearing = bearingDeg(coords[0].lat, coords[0].lng, look0.lat, look0.lng);

        map.easeTo({
            center: lineCoords[0],
            zoom: 18.5, pitch: 70, bearing: player.bearing,
            duration: 1500,
        });

        player.startWall = performance.now() + 1500; // start after the easeTo settles
        player.startTrack = coords[0].t;

        const frame = () => {
            if (!player.playing) return;
            const now = performance.now();
            if (now < player.startWall) {
                player.rafId = requestAnimationFrame(frame);
                return;
            }
            const speed = Number(speedSel.value) || 8;
            const elapsed = (now - player.startWall) * speed;
            const targetT = player.startTrack + elapsed;

            // Find segment [i-1, i] containing targetT.
            let i = 1;
            while (i < coords.length && coords[i].t < targetT) i++;
            if (i >= coords.length) {
                moverMarker.setLngLat(lineCoords[lineCoords.length - 1]);
                map.getSource('trail').setData({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: lineCoords } });
                stop();
                setTimeout(resetView, 400);
                return;
            }
            const A = coords[i - 1], B = coords[i];
            const frac = Math.max(0, Math.min(1, (targetT - A.t) / Math.max(1, B.t - A.t)));
            const lng = A.lng + (B.lng - A.lng) * frac;
            const lat = A.lat + (B.lat - A.lat) * frac;

            // Look-ahead bearing (several points) for stability.
            const look = coords[Math.min(coords.length - 1, i + 4)];
            const desiredBearing = bearingDeg(lat, lng, look.lat, look.lng);
            // Smooth toward desired bearing, taking shortest angular path.
            const delta = shortestAngleDelta(player.bearing, desiredBearing);
            player.bearing = (player.bearing + delta * 0.15 + 360) % 360;

            moverMarker.setLngLat([lng, lat]);
            map.getSource('trail').setData({
                type: 'Feature', properties: {},
                geometry: { type: 'LineString', coordinates: lineCoords.slice(0, i).concat([[lng, lat]]) }
            });

            // Camera: slightly behind the mover along the inverse-bearing vector.
            // User rotation offset spins the follow-cam around the mover.
            const effBearing = (player.bearing + player.rotOffset + 360) % 360;
            const behindOffsetDeg = 0.00008; // ~9 m behind
            const brRad = effBearing * Math.PI / 180;
            const camLng = lng - Math.sin(brRad) * behindOffsetDeg;
            const camLat = lat - Math.cos(brRad) * behindOffsetDeg;

            map.jumpTo({ center: [camLng, camLat], bearing: effBearing, pitch: 70, zoom: 18.5 });

            player.rafId = requestAnimationFrame(frame);
        };
        player.rafId = requestAnimationFrame(frame);
    });
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
[typeSel, fromEl, toEl].forEach(el => el.addEventListener('change', loadActivities));
perPageSel.addEventListener('change', () => {
    localStorage.setItem('traxxx.perPage', perPageSel.value);
    currentPage = 1;
    renderPage();
});
prevBtn.addEventListener('click', () => { if (currentPage > 1) { currentPage--; renderPage(); window.scrollTo({ top: 0, behavior: 'smooth' }); } });
nextBtn.addEventListener('click', () => { currentPage++; renderPage(); window.scrollTo({ top: 0, behavior: 'smooth' }); });

const savedPerPage = localStorage.getItem('traxxx.perPage');
if (savedPerPage && [...perPageSel.options].some(o => o.value === savedPerPage)) {
    perPageSel.value = savedPerPage;
}

function showHome() {
    currentUserId = null;
    homeView.hidden = false;
    activityView.hidden = true;
    activityControls.hidden = true;
    userCrumb.hidden = true;
    renderUserGrid();
}

async function showUser(uid) {
    const user = usersCache.find(u => String(u.id) === String(uid));
    if (!user) {
        // Unknown user id — fall back home.
        location.hash = '#/';
        return;
    }
    currentUserId = uid;
    homeView.hidden = true;
    activityView.hidden = false;
    activityControls.hidden = false;
    userCrumb.hidden = false;
    crumbUser.textContent = user.name;
    // Clear any open activity detail from a previous user.
    document.querySelectorAll('tr.detail').forEach(el => el.remove());
    await loadActivities();
}

async function route() {
    const h = location.hash || '#/';
    const m = h.match(/^#\/user\/(\d+)$/);
    if (m) {
        await showUser(m[1]);
    } else {
        showHome();
    }
}

window.addEventListener('hashchange', () => { route().catch(err => console.error(err)); });

(async () => {
    try {
        await Promise.all([loadUsers(), loadTypes()]);
        await route();
    } catch (e) {
        document.body.insertAdjacentHTML('afterbegin',
            `<pre style="padding:12px;color:#f88;background:#200">Error: ${escapeHtml(e.message)}</pre>`);
    }
})();
