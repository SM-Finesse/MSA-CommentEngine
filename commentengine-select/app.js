(function () {
  "use strict";

  var CONFIG_URL = "config.json";
  var THEME_KEY = "ce-theme";

  var chipsEl = document.getElementById("chips");
  var captionEl = document.getElementById("chip-caption");
  var siteNameEl = document.getElementById("site-name");
  var questionEl = document.getElementById("question");

  var items = [];   // 선택 가능한 카드(활성/비활성 칩) 엘리먼트
  var chips = [];   // 설정의 칩 데이터
  var focusIndex = 0;

  // ── 테마 ──────────────────────────
  function applyTheme(theme) {
    if (theme) document.documentElement.setAttribute("data-theme", theme);
    else document.documentElement.removeAttribute("data-theme");
  }
  function savedTheme() {
    try { return localStorage.getItem(THEME_KEY); } catch (e) { return null; }
  }
  applyTheme(savedTheme());

  document.getElementById("btn-theme").addEventListener("click", function () {
    var current = document.documentElement.getAttribute("data-theme");
    if (!current) {
      current = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }
    var next = current === "dark" ? "light" : "dark";
    applyTheme(next);
    try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* 무시 */ }
  });

  document.getElementById("btn-reload").addEventListener("click", load);

  // ── 시계 ──────────────────────────
  var clockEl = document.getElementById("clock");
  function tick() {
    var d = new Date();
    clockEl.textContent =
      String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
  }
  tick();
  setInterval(tick, 10000);

  // ── 카드 아트 ─────────────────────
  // 테트로미노 모양을 흩뿌린 SVG (외부 이미지 없이 그림)
  function blocksArt() {
    var c = 22; // 셀 크기 (viewBox 200 기준)
    var pieces = [
      { color: "#00d8ff", cells: [[0, 0], [1, 0], [2, 0], [3, 0]], x: 18, y: 24 },
      { color: "#ffd23c", cells: [[0, 0], [1, 0], [0, 1], [1, 1]], x: 132, y: 18 },
      { color: "#b46bff", cells: [[1, 0], [0, 1], [1, 1], [2, 1]], x: 108, y: 82 },
      { color: "#4cff7a", cells: [[1, 0], [2, 0], [0, 1], [1, 1]], x: 20, y: 70 },
      { color: "#ff5c5c", cells: [[0, 0], [1, 0], [1, 1], [2, 1]], x: 40, y: 118 },
      { color: "#ff9a3c", cells: [[2, 0], [0, 1], [1, 1], [2, 1]], x: 130, y: 128 }
    ];
    var rects = pieces.map(function (p) {
      return p.cells.map(function (cell) {
        var x = p.x + cell[0] * c, y = p.y + cell[1] * c;
        return '<rect x="' + x + '" y="' + y + '" width="' + (c - 2) + '" height="' + (c - 2) +
          '" rx="3" fill="' + p.color + '"/>' +
          '<rect x="' + (x + 3) + '" y="' + (y + 3) + '" width="' + (c - 12) + '" height="4" rx="2" fill="rgba(255,255,255,.45)"/>';
      }).join("");
    }).join("");
    return '<svg viewBox="0 0 200 200" preserveAspectRatio="xMidYMid slice" aria-hidden="true">' + rects + "</svg>";
  }

  function artFor(chip) {
    var art = document.createElement("div");
    art.className = "chip-art";
    if (chip.image) {
      art.style.background = "center / cover no-repeat url(" + JSON.stringify(chip.image) + ")";
      return art;
    }
    if (chip.art === "blocks") {
      art.classList.add("art-blocks");
      art.innerHTML = blocksArt();
    } else if (chip.art === "soon") {
      art.classList.add("art-soon");
      art.innerHTML = "<span>?</span>";
    } else {
      art.classList.add("art-initial");
      art.style.background = chip.color || "#607d8b";
      var s = document.createElement("span");
      s.textContent = (chip.name || "?").charAt(0).toUpperCase();
      art.appendChild(s);
    }
    return art;
  }

  // ── 렌더 ──────────────────────────
  function render(config) {
    var siteName = config.siteName || "CommentEngine";
    siteNameEl.textContent = siteName;
    document.title = siteName;
    if (config.question) questionEl.textContent = config.question;

    chips = Array.isArray(config.chips) ? config.chips : [];
    chipsEl.innerHTML = "";
    items = [];

    chips.forEach(function (chip, i) {
      var enabled = chip.enabled !== false && !!chip.url;
      var li = document.createElement("li");
      li.className = "chip" + (enabled ? "" : " is-disabled");
      li.setAttribute("role", "option");
      li.setAttribute("tabindex", "-1");
      li.setAttribute("aria-disabled", String(!enabled));
      li.setAttribute("aria-label", (chip.name || "") + (enabled ? "" : " (준비 중)"));

      li.appendChild(artFor(chip));

      if (chip.art !== "soon") {
        var title = document.createElement("span");
        title.className = "chip-title";
        title.textContent = chip.name || "";
        li.appendChild(title);
      }
      if (!enabled) {
        var badge = document.createElement("span");
        badge.className = "chip-badge";
        badge.textContent = "준비 중";
        li.appendChild(badge);
      }

      li.addEventListener("mouseenter", function () { setFocus(i, false); });
      li.addEventListener("click", function () { setFocus(i, true); open(i); });

      chipsEl.appendChild(li);
      items.push(li);
    });

    var empties = Math.max(0, Number(config.emptySlots) || 0);
    for (var k = 0; k < empties; k++) {
      var e = document.createElement("li");
      e.className = "chip is-empty";
      e.setAttribute("aria-hidden", "true");
      chipsEl.appendChild(e);
    }

    if (items.length === 0) {
      showError("표시할 서비스가 없습니다. config.json의 chips를 확인해 주세요.");
      return;
    }
    var first = chips.findIndex(function (c) { return c.enabled !== false && c.url; });
    setFocus(first >= 0 ? first : 0, true);
  }

  function setFocus(i, moveDomFocus) {
    if (!items.length) return;
    focusIndex = (i + items.length) % items.length;
    items.forEach(function (el, idx) {
      var on = idx === focusIndex;
      el.classList.toggle("is-focused", on);
      el.setAttribute("aria-selected", String(on));
    });
    var el = items[focusIndex];
    if (moveDomFocus) el.focus({ preventScroll: true });
    el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });

    var chip = chips[focusIndex];
    captionEl.innerHTML = "";
    var name = document.createTextNode(chip.name || "");
    captionEl.appendChild(name);
    if (chip.description) {
      var d = document.createElement("span");
      d.className = "desc";
      d.textContent = chip.description;
      captionEl.appendChild(d);
    }
  }

  function open(i) {
    var chip = chips[i];
    if (!chip || chip.enabled === false || !chip.url) return;
    // {host} 는 지금 접속한 주소로 바꾼다 (예: http://{host}:8088 → http://192.168.0.112:8088)
    window.location.href = String(chip.url).replace(/\{host\}/g, location.hostname);
  }

  function showError(msg) {
    chipsEl.innerHTML = "";
    captionEl.innerHTML = "&nbsp;";
    var p = document.createElement("li");
    p.className = "error";
    p.textContent = msg;
    chipsEl.appendChild(p);
  }

  // ── 키보드 ────────────────────────
  document.addEventListener("keydown", function (ev) {
    if (!items.length) return;
    if (ev.target && ev.target.closest && ev.target.closest(".dock")) return;
    if (ev.key === "ArrowRight") { setFocus(focusIndex + 1, true); ev.preventDefault(); }
    else if (ev.key === "ArrowLeft") { setFocus(focusIndex - 1, true); ev.preventDefault(); }
    else if (ev.key === "Enter" || ev.key === " ") { open(focusIndex); ev.preventDefault(); }
  });

  // ── 설정 로드 ─────────────────────
  function load() {
    fetch(CONFIG_URL, { cache: "no-store" })
      .then(function (res) {
        if (!res.ok) throw new Error("HTTP " + res.status);
        return res.json();
      })
      .then(render)
      .catch(function (err) {
        console.error("[CommentEngine] config.json 로드 실패:", err);
        showError("서비스 목록(config.json)을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
      });
  }

  load();
})();
