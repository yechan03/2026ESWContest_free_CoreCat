/* IN-GPS Web — 프런트엔드 로직 (쿠키 세션 기반) */
const INGPS = (() => {
  const $ = (id) => document.getElementById(id);

  function showMsg(text, kind) {
    const el = $("msg");
    if (!el) return;
    el.textContent = text;
    el.className = "msg " + (kind || "error");
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      ...opts,
    });
    let body = null;
    try { body = await res.json(); } catch (_) {}
    return { ok: res.ok, status: res.status, body };
  }

  // ── 로그인 페이지 ──
  function initLogin() {
    $("login-form").addEventListener("submit", async (e) => {
      e.preventDefault();
      const btn = $("submit-btn");
      btn.disabled = true;
      const r = await api("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username: $("username").value, password: $("password").value }),
      });
      btn.disabled = false;
      if (r.ok) {
        window.location.href = "index.html";
      } else {
        showMsg((r.body && r.body.detail) || "로그인에 실패했습니다.", "error");
      }
    });
  }

  // ── 회원가입 페이지 ──
  function initSignup() {
    $("signup-form").addEventListener("submit", async (e) => {
      e.preventDefault();
      const btn = $("submit-btn");
      btn.disabled = true;
      const r = await api("/auth/signup", {
        method: "POST",
        body: JSON.stringify({ username: $("username").value, password: $("password").value }),
      });
      btn.disabled = false;
      if (r.ok) {
        showMsg("회원가입 완료! 로그인 페이지로 이동합니다…", "ok");
        setTimeout(() => (window.location.href = "login.html"), 1200);
      } else {
        showMsg((r.body && r.body.detail) || "회원가입에 실패했습니다.", "error");
      }
    });
  }

  // ── 대시보드 ──
  async function initDashboard() {
    // 인증 확인 (미로그인 시 로그인 페이지로)
    const me = await api("/auth/me");
    if (!me.ok) { window.location.href = "login.html"; return; }
    $("user-label").textContent = me.body.username + " 님";

    // 디바이스 목록 채우기
    const dev = await api("/devices");
    if (dev.ok && dev.body && dev.body.items) {
      const sel = $("device-select");
      dev.body.items.forEach((d) => {
        const opt = document.createElement("option");
        opt.value = d.device_id;
        opt.textContent = d.device_id;
        sel.appendChild(opt);
      });
    }

    $("logout-btn").addEventListener("click", async () => {
      await api("/auth/logout", { method: "POST" });
      window.location.href = "login.html";
    });

    $("download-btn").addEventListener("click", () => {
      const did = $("device-select").value;
      const qs = new URLSearchParams({ limit: "1000" });
      if (did) qs.set("device_id", did);
      // 쿠키가 자동 전송되므로 직접 내비게이션으로 다운로드
      window.location.href = "/export/sensor.csv?" + qs.toString();
      showMsg("다운로드를 시작했습니다.", "ok");
    });

    $("gwhealth-btn").addEventListener("click", loadGatewayHealth);
  }

  // ── Gateway Health 로그 조회/렌더 ──
  function esc(v) {
    return String(v ?? "").replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
  }

  function fmtUptime(sec) {
    const s = Number(sec);
    if (!Number.isFinite(s)) return "-";
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const r = Math.floor(s % 60);
    return `${h}h ${m}m ${r}s`;
  }

  function setGwMsg(text, kind) {
    const el = $("gwhealth-msg");
    if (!el) return;
    el.textContent = text;
    el.className = "msg " + (kind || "");
  }

  async function loadGatewayHealth() {
    const box = $("gwhealth-table");
    setGwMsg("불러오는 중…", "ok");
    const r = await api("/gateway_health?limit=100");
    if (!r.ok || !r.body || !r.body.items) {
      setGwMsg("조회에 실패했습니다.", "error");
      return;
    }
    const items = r.body.items;
    if (items.length === 0) {
      setGwMsg("기록이 없습니다.", "ok");
      box.innerHTML = "";
      return;
    }
    setGwMsg(items.length + "건 조회됨", "ok");
    let html =
      '<table class="gwh-table"><thead><tr>' +
      "<th>시각</th><th>Gateway</th><th>Event</th>" +
      "<th>init</th><th>mqtt_conn</th><th>pub</th><th>fail</th><th>uptime</th>" +
      "</tr></thead><tbody>";
    for (const it of items) {
      const failCls = Number(it.prev_fail_count) > 0 ? ' class="fail"' : "";
      html +=
        "<tr>" +
        `<td>${esc(it.created_at)}</td>` +
        `<td>${esc(it.gateway_id)}</td>` +
        `<td>${esc(it.event)}</td>` +
        `<td>${esc(it.prev_init_result)}</td>` +
        `<td>${esc(it.prev_mqtt_conn_result)}</td>` +
        `<td>${esc(it.prev_pub_count)}</td>` +
        `<td${failCls}>${esc(it.prev_fail_count)}</td>` +
        `<td>${fmtUptime(it.prev_uptime_sec)}</td>` +
        "</tr>";
    }
    html += "</tbody></table>";
    box.innerHTML = html;
  }

  return { initLogin, initSignup, initDashboard };
})();
