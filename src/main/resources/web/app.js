// Thin UI layer: all scheduling logic lives in the Java backend. This file only renders state and calls the REST API.
const $ = (s) => document.querySelector(s);
let state = null, tab = "diagnostics", report = "workload", swapPick = null, openShift = null;

const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const hhmm = (iso) => iso.slice(11, 16);
const dayName = (d) => new Date(d + "T00:00").toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short" });

async function api(path, body) {
  const res = await fetch(path, body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const data = await res.json();
  if (!res.ok) throw data;
  return data;
}

function toast(msg, err) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast" + (err ? " err" : "");
  clearTimeout(toast.t);
  toast.t = setTimeout(() => t.classList.add("hidden"), err ? 6000 : 3000);
}

async function act(path, body) {
  try {
    const r = await api(path, body || {});
    state = r.state;
    render();
    const forced = r.overridden && r.overridden.length ? " — overrode: " + r.overridden.map((v) => v.label).join(", ") : "";
    toast(r.message + forced);
    return true;
  } catch (e) {
    toast(errText(e), true);
    return false;
  }
}

const errText = (e) => e.violations ? "Rejected: " + e.violations.map((v) => v.message).join("; ") : e.error || "Request failed";

async function load() {
  state = await api("/api/state");
  render();
}

// ---------- rendering ----------
function render() {
  const m = state.metrics;
  $("#weekLabel").textContent = state.days.length ? `${dayName(state.days[0])} – ${dayName(state.days[state.days.length - 1])} · ${state.volunteers.length} volunteers · ${state.shifts.length} events` : "No events yet. Click Events to add one.";
  $("#strategy").innerHTML = state.strategies.map((s) => `<option value="${s.id}" ${s.id === state.strategy ? "selected" : ""} title="${esc(s.description)}">${esc(s.name)}</option>`).join("");
  $("#undoBtn").disabled = !state.history.canUndo;
  $("#redoBtn").disabled = !state.history.canRedo;
  $("#undoBtn").title = state.history.undoLabel || "Nothing to undo";
  $("#redoBtn").title = state.history.redoLabel || "Nothing to redo";

  const kpi = (label, value, sub, pct) => `<div class="kpi"><div class="label">${label}</div><div class="value">${value}</div><div class="sub">${sub}</div>${pct != null ? `<div class="meter"><i style="width:${Math.round(pct * 100)}%"></i></div>` : ""}</div>`;
  $("#kpis").innerHTML =
    kpi("Seat coverage", Math.round(m.coverageRate * 100) + "%", `${m.seatsFilled} of ${m.seatsNeeded} seats filled`, m.coverageRate) +
    kpi("Fully staffed", `${m.fullyStaffed}<span class="muted" style="font-size:15px"> / ${m.totalShifts}</span>`, `${m.partiallyStaffed} partial · ${m.open} open`, m.totalShifts ? m.fullyStaffed / m.totalShifts : 0) +
    kpi("Fairness (Jain)", m.jainFairnessIndex.toFixed(3), "1.000 = perfectly even hours", m.jainFairnessIndex) +
    kpi("Avg hours", m.meanHours.toFixed(1) + " h", `σ ${m.stdDevHours.toFixed(2)} h · range ${m.minHours}–${m.maxHours} h`) +
    kpi("Active volunteers", `${m.activeVolunteers}<span class="muted" style="font-size:15px"> / ${state.volunteers.length}</span>`, state.violations.length ? `⚠ ${state.violations.length} rule violation(s)` : "No rule violations");

  renderBoard();
  renderLoads();
  renderTab();
}

function renderBoard() {
  if (!state.days.length) {
    $("#board").innerHTML = `<div class="empty">No events yet.<br><button class="small primary" data-open-evt="1">Add events</button></div>`;
    return;
  }
  $("#board").innerHTML = state.days.map((d) => {
    const shifts = state.shifts.filter((s) => s.start.startsWith(d));
    const filled = shifts.reduce((a, s) => a + Math.min(s.assigned.length, s.needed), 0), need = shifts.reduce((a, s) => a + s.needed, 0);
    return `<div class="day"><div class="day-head"><span>${dayName(d)}</span><span>${filled}/${need}</span></div>${shifts.map(card).join("")}</div>`;
  }).join("");
}

function card(s) {
  const chips = s.assigned.map((a) => {
    const sel = swapPick && swapPick.v === a.id && swapPick.s === s.id ? " selected" : "";
    return `<div class="chip${a.manual ? " manual" : ""}${sel}" data-swap="${a.id}|${s.id}" title="Click to swap">${a.manual ? "📌 " : ""}${esc(a.name)}<button class="x" data-remove="${a.id}|${s.id}" title="Remove">×</button></div>`;
  }).join("");
  const full = s.assigned.length >= s.needed;
  return `<div class="card ${s.status}">
    <div class="time"><span>${hhmm(s.start)}–${hhmm(s.end)}</span><span>${s.id}</span></div>
    <div class="title">${esc(s.activity)}</div>
    <div class="loc">${esc(s.location)}</div>
    <div class="tags"><span class="tag">${esc(s.skill)} ≥${s.minLevel}</span>${s.priority === 3 ? '<span class="tag prio3">critical</span>' : ""}<span class="seats">${s.assigned.length}/${s.needed}</span></div>
    <div class="chips">${chips}</div>
    ${full ? "" : `<button class="add" data-assign="${s.id}">+ Assign volunteer</button>`}
  </div>`;
}

function renderLoads() {
  const m = state.metrics, maxCap = Math.max(...m.loads.map((l) => l.maxWeeklyHours), 1);
  $("#spread").textContent = `mean ${m.meanHours.toFixed(1)} h`;
  if (!m.loads.length) {
    $("#loads").innerHTML = `<div class="empty">No volunteers yet.<br><button class="small primary" data-open-vol="1">Add volunteers</button></div>`;
    return;
  }
  $("#loads").innerHTML = m.loads.map((l) => `<div class="load-row">
      <div class="top-line"><span>${esc(l.name)}</span><span class="mono">${l.hours}/${l.maxWeeklyHours} h</span></div>
      <div class="bar" style="width:${(l.maxWeeklyHours / maxCap) * 100}%"><i class="${l.utilization >= 0.999 ? "hot" : ""}" style="width:${Math.min(100, l.utilization * 100)}%"></i>
      <span class="mean" style="left:${Math.min(100, (m.meanHours / l.maxWeeklyHours) * 100)}%"></span></div></div>`).join("");
}

async function renderTab() {
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  const body = $("#tabBody");
  if (tab === "diagnostics") {
    body.innerHTML = state.diagnostics.length ? state.diagnostics.map((d) => `<div class="diag">
      <div><strong>${esc(d.activity)}</strong><div class="muted mono">${esc(d.slot)} · ${d.shiftId}</div>
        <div class="muted">${d.assigned}/${d.needed} filled · missing ${d.missing}</div>
        ${d.primaryCause ? `<span class="cause ${d.primaryCause}">Root cause: ${label(d.primaryCause)}</span>` : ""}</div>
      <div>${esc(d.summary)}
        <div class="counts">${Object.entries(d.rejectionCounts).map(([k, v]) => `<span class="tag">${label(k)}: ${v}</span>`).join("")}</div>
        ${d.nearMisses.length ? `<div class="near"><strong>Near misses</strong> (one overridable rule away):${d.nearMisses.map((n) => `<div>${esc(n.detail)} <button class="small warn" data-force="${n.volunteerId}|${d.shiftId}">Force-assign</button></div>`).join("")}</div>` : ""}
      </div>
      <div><button class="small" data-assign="${d.shiftId}">Open candidates</button></div></div>`).join("")
      : `<div class="empty">Every shift is fully staffed. 🎉</div>`;
  } else if (tab === "violations") {
    body.innerHTML = state.violations.length ? table(["Rule", "Detail"], state.violations.map((v) => [v.label, v.message]))
      : `<div class="empty">No constraint violations. Force-assigned edits that break skill, availability or hour-cap rules will show up here.</div>`;
  } else if (tab === "activity") {
    body.innerHTML = state.activity.length ? table(["Time", "Action", "Description"], state.activity.map((a) => [a.at.replace("T", " "), a.action, a.description]))
      : `<div class="empty">No edits yet.</div>`;
  } else if (tab === "reports") {
    try {
      const list = await api("/api/reports"), r = await api("/api/reports/" + report);
      body.innerHTML = `<div class="report-tabs">${list.map((x) => `<button class="small ${x.id === report ? "active" : ""}" data-report="${x.id}">${esc(x.title)}</button>`).join("")}</div>
        <pre class="sql">${esc(r.sql)}</pre>${table(r.columns, r.rows.map((row) => r.columns.map((c) => row[c])))}`;
    } catch (e) { body.innerHTML = `<div class="empty">${esc(e.error || "Reports unavailable")}</div>`; }
  }
}

const LABELS = { MISSING_SKILL: "Skill deficit", SKILL_LEVEL_TOO_LOW: "Skill level too low", NOT_AVAILABLE: "Outside availability", HOUR_CAP_REACHED: "Hour cap reached", TIME_OVERLAP_CONFLICT: "Time overlap", ALREADY_ASSIGNED: "Already assigned", SHIFT_FULL: "Shift full" };
const label = (k) => LABELS[k] || k;
const table = (cols, rows) => `<table><thead><tr>${cols.map((c) => `<th>${esc(c)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`;

// ---------- assign drawer ----------
async function openAssign(shiftId) {
  openShift = shiftId;
  const s = state.shifts.find((x) => x.id === shiftId);
  $("#drawerTitle").textContent = s.activity;
  $("#drawerSub").textContent = `${s.label} · needs ${s.skill} ≥${s.minLevel} · ${s.assigned.length}/${s.needed} filled`;
  $("#drawer").classList.remove("hidden");
  $("#drawerBody").innerHTML = `<div class="empty">Checking every volunteer against the rules…</div>`;
  const cands = await api("/api/candidates?shift=" + encodeURIComponent(shiftId));
  $("#drawerBody").innerHTML = cands.map((c) => `<div class="cand ${c.eligible ? "ok" : "blocked"}">
    <div class="cand-head"><div><strong>${esc(c.name)}</strong> <span class="muted">· ${c.hours}/${c.maxWeeklyHours} h · ${esc(s.skill)} lvl ${c.level}</span></div>
      ${c.eligible ? `<button class="small primary" data-do-assign="${c.id}">Assign</button>`
        : c.forceable ? `<button class="small warn" data-do-force="${c.id}">Force</button>` : `<span class="muted">blocked</span>`}</div>
    ${c.violations.length ? `<ul>${c.violations.map((v) => `<li class="${v.overridable ? "" : "hard"}">${esc(v.message)}</li>`).join("")}</ul>` : ""}</div>`).join("");
}

// ---------- volunteers panel ----------
function openVolunteers() {
  $("#evtModal").classList.add("hidden");
  buildVolForm();
  renderVolList();
  $("#volModal").classList.remove("hidden");
  $("#volForm").vname.focus();
}

// The form offers the skills and days of the events added so far; "Add skill" / "Add date" cover anything else.
function buildVolForm() {
  $("#volSkills").innerHTML = state.skills.map((k) => skillRow(k, false)).join("");
  $("#volDays").innerHTML = state.days.length ? state.days.map((d) => dayRow(d)).join("")
    : `<p class="muted" id="noDays">No events yet, so add the dates they're free below.</p>`;
}

function skillRow(k, checked) {
  const need = state.shifts.filter((s) => s.skill === k).map((s) => s.minLevel), lo = Math.min(...need), hi = Math.max(...need);
  return `<div class="pick-row"><label><input type="checkbox" data-skill="${esc(k)}" ${checked ? "checked" : ""}> ${esc(k)}</label>
    <span class="need">${need.length ? `events need level ${lo === hi ? lo : lo + "–" + hi}` : ""}</span>
    <select data-level="${esc(k)}" class="${checked ? "" : "off"}">${[1, 2, 3, 4, 5].map((n) => `<option ${n === 3 ? "selected" : ""}>${n}</option>`).join("")}</select></div>`;
}

function dayRow(d) {
  return `<div class="pick-row"><label><input type="checkbox" data-day="${d}" checked> ${dayName(d)}</label>
    <span class="times"><input type="time" data-from="${d}" value="06:00"> to <input type="time" data-to="${d}" value="23:00"></span></div>`;
}

$("#addSkillBtn").onclick = () => {
  const k = $("#newSkill").value.trim().toLowerCase();
  if (!k) return;
  const existing = $(`[data-skill="${CSS.escape(k)}"]`);
  if (existing) { existing.checked = true; existing.dispatchEvent(new Event("change", { bubbles: true })); }
  else $("#volSkills").insertAdjacentHTML("beforeend", skillRow(k, true));
  $("#newSkill").value = "";
};

$("#addDayBtn").onclick = () => {
  const d = $("#newDay").value;
  if (!d) return toast("Pick a date first", true);
  if (!$(`[data-day="${d}"]`)) { $("#noDays")?.remove(); $("#volDays").insertAdjacentHTML("beforeend", dayRow(d)); }
  $("#newDay").value = "";
};

function renderVolList() {
  $("#volCount").textContent = state.volunteers.length ? `${state.volunteers.length} volunteer(s)` : "";
  $("#volList").innerHTML = state.volunteers.length ? state.volunteers.map((v) => `<div class="vol-item">
      <div><strong>${esc(v.name)}</strong> <span class="muted">· ${v.hours}/${v.maxWeeklyHours} h · ${v.shifts} shift(s)</span>
        <div class="muted">${Object.entries(v.skills).map(([k, l]) => `${esc(k)} ${l}`).join(" · ")}</div></div>
      <button class="small" data-del-vol="${v.id}">Remove</button></div>`).join("")
    : `<div class="empty">Nobody yet. Fill in the form above to add your first volunteer.</div>`;
}

// Add/remove, then re-run the balancer so the new roster is divided across the shifts straight away.
async function rosterChange(path, body) {
  try {
    const r = await api(path, body);
    const b = await api("/api/balance", { strategy: $("#strategy").value });
    state = b.state;
    render();
    renderVolList();
    renderEvtList();
    toast(`${r.message} · ${b.message}`);
    return true;
  } catch (e) {
    toast(errText(e), true);
    return false;
  }
}

$("#volForm").addEventListener("change", (e) => {
  const k = e.target.dataset.skill, d = e.target.dataset.day;
  if (k) $(`[data-level="${CSS.escape(k)}"]`).classList.toggle("off", !e.target.checked);
  if (d) e.target.closest(".pick-row").querySelector(".times").classList.toggle("off", !e.target.checked);
});

$("#volForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target, skills = {}, availability = [];
  f.querySelectorAll("[data-skill]:checked").forEach((c) => skills[c.dataset.skill] = +$(`[data-level="${CSS.escape(c.dataset.skill)}"]`).value);
  for (const c of f.querySelectorAll("[data-day]:checked")) {
    const d = c.dataset.day, from = $(`[data-from="${d}"]`).value, to = $(`[data-to="${d}"]`).value;
    if (!from || !to || to <= from) return toast(`${dayName(d)}: the end time must be after the start time`, true);
    availability.push({ start: `${d}T${from}`, end: `${d}T${to}` });
  }
  if (!Object.keys(skills).length) return toast("Tick at least one skill", true);
  if (!availability.length) return toast("Tick at least one day they're available", true);
  const ok = await rosterChange("/api/volunteers", { name: f.vname.value.trim(), email: f.email.value.trim(), maxWeeklyHours: +f.max.value, skills, availability });
  if (ok) {
    f.vname.value = f.email.value = "";
    f.querySelectorAll("[data-skill]:checked").forEach((c) => { c.checked = false; $(`[data-level="${CSS.escape(c.dataset.skill)}"]`).classList.add("off"); });
    f.vname.focus();
  }
});

// ---------- events panel ----------
function openEvents() {
  $("#volModal").classList.add("hidden");
  $("#skillList").innerHTML = state.skills.map((k) => `<option value="${esc(k)}">`).join("");
  const f = $("#evtForm");
  if (!f.date.value) f.date.value = state.days[state.days.length - 1] || new Date().toISOString().slice(0, 10);
  renderEvtList();
  $("#evtModal").classList.remove("hidden");
  f.ename.focus();
}

function renderEvtList() {
  $("#evtCount").textContent = state.shifts.length ? `${state.shifts.length} event(s)` : "";
  $("#evtList").innerHTML = state.shifts.length ? state.shifts.map((s) => `<div class="vol-item">
      <div><strong>${esc(s.activity)}</strong> <span class="muted">· ${dayName(s.start.slice(0, 10))} ${hhmm(s.start)}–${hhmm(s.end)}</span>
        <div class="muted">${esc(s.skill)} ≥${s.minLevel} · ${s.assigned.length}/${s.needed} volunteers${s.location ? " · " + esc(s.location) : ""}</div></div>
      <button class="small" data-del-shift="${s.id}">Remove</button></div>`).join("")
    : `<div class="empty">No events yet. Fill in the form above to add your first one.</div>`;
}

$("#evtForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target;
  if (f.to.value <= f.from.value) return toast("The event must end after it starts", true);
  const ok = await rosterChange("/api/shifts", {
    activity: f.ename.value.trim(), location: f.loc.value.trim(),
    start: `${f.date.value}T${f.from.value}`, end: `${f.date.value}T${f.to.value}`,
    requiredSkill: f.skill.value.trim().toLowerCase(), minSkillLevel: +f.level.value,
    volunteersNeeded: +f.needed.value, priority: +f.prio.value,
  });
  if (ok) {
    f.ename.value = f.loc.value = "";
    $("#skillList").innerHTML = state.skills.map((k) => `<option value="${esc(k)}">`).join("");
    f.ename.focus();
  }
});

// ---------- events ----------
document.addEventListener("click", async (e) => {
  const t = e.target.closest("[data-remove],[data-swap],[data-assign],[data-force],[data-do-assign],[data-do-force],[data-report],[data-tab],[data-del-vol],[data-open-vol],[data-del-shift],[data-open-evt]");
  if (!t) return;
  const d = t.dataset;
  if (d.delVol) {
    const v = state.volunteers.find((x) => x.id === d.delVol);
    if (confirm(`Remove ${v.name}? Their shifts will be given to other volunteers.`)) await rosterChange("/api/volunteers/remove", { volunteerId: d.delVol });
  } else if (d.delShift) {
    const s = state.shifts.find((x) => x.id === d.delShift);
    if (confirm(`Remove the event "${s.activity}"?`)) await rosterChange("/api/shifts/remove", { shiftId: d.delShift });
  } else if (d.openVol) openVolunteers();
  else if (d.openEvt) openEvents();
  else if (d.remove) {
    e.stopPropagation();
    const [v, s] = d.remove.split("|");
    swapPick = null;
    await act("/api/remove", { volunteerId: v, shiftId: s });
  } else if (d.swap) {
    const [v, s] = d.swap.split("|");
    if (!swapPick) { swapPick = { v, s }; renderBoard(); toast("Now click a volunteer on another shift to swap with"); }
    else if (swapPick.v === v && swapPick.s === s) { swapPick = null; renderBoard(); }
    else { const a = swapPick; swapPick = null; await act("/api/swap", { volunteerA: a.v, shiftA: a.s, volunteerB: v, shiftB: s }) || renderBoard(); }
  } else if (d.assign) openAssign(d.assign);
  else if (d.force) { const [v, s] = d.force.split("|"); await act("/api/assign", { volunteerId: v, shiftId: s, force: true }); }
  else if (d.doAssign || d.doForce) {
    if (await act("/api/assign", { volunteerId: d.doAssign || d.doForce, shiftId: openShift, force: !!d.doForce })) {
      const s = state.shifts.find((x) => x.id === openShift);
      s.assigned.length >= s.needed ? $("#drawer").classList.add("hidden") : openAssign(openShift);
    }
  } else if (d.report) { report = d.report; renderTab(); }
  else if (d.tab) { tab = d.tab; renderTab(); }
});

$("#runBtn").onclick = () => act("/api/balance", { strategy: $("#strategy").value });
$("#undoBtn").onclick = () => act("/api/undo");
$("#redoBtn").onclick = () => act("/api/redo");
$("#clearBtn").onclick = () => act("/api/clear");
$("#drawerClose").onclick = () => $("#drawer").classList.add("hidden");
$("#volBtn").onclick = openVolunteers;
$("#evtBtn").onclick = openEvents;
$("#evtClose").onclick = () => $("#evtModal").classList.add("hidden");
$("#volClose").onclick = () => $("#volModal").classList.add("hidden");
$("#importBtn").onclick = () => $("#importModal").classList.remove("hidden");
$("#importClose").onclick = () => $("#importModal").classList.add("hidden");
$("#importGo").onclick = async () => {
  const vf = $("#volFile").files[0], sf = $("#shiftFile").files[0];
  if (!vf || !sf) return toast("Choose both files", true);
  const format = vf.name.endsWith(".csv") ? "csv" : "json";
  if (await act("/api/import?format=" + format, { volunteers: await vf.text(), shifts: await sf.text() })) $("#importModal").classList.add("hidden");
};
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") { $("#drawer").classList.add("hidden"); $("#importModal").classList.add("hidden"); $("#volModal").classList.add("hidden"); $("#evtModal").classList.add("hidden"); swapPick = null; renderBoard(); }
  if ((e.ctrlKey || e.metaKey) && e.key === "z") { e.preventDefault(); act(e.shiftKey ? "/api/redo" : "/api/undo"); }
});

load().then(() => { if (!state.shifts.length) openEvents(); else if (!state.volunteers.length) openVolunteers(); })
  .catch(() => toast("Could not reach the server", true));
