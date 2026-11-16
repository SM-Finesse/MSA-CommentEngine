/* tetris 칩 화면 푸터에 CommentEngine 로고를 붙인다 (MSA에서만, default.conf.template 이 끼워 넣음).
   누르면 서비스 선택 화면으로 간다 (TETRIS_ENGINE_HOME_URL, 비우면 /engine/). 화면 이미지 코드는 바꾸지 않는다.
   화면은 React라 페이지를 옮길 때 푸터가 다시 그려지므로, 바뀔 때마다 로고가 있는지 확인해 다시 붙인다. */
(function () {
  'use strict';

  // 로고가 갈 주소: compose 의 TETRIS_ENGINE_HOME_URL (nginx가 window.CE_ENGINE_HOME 으로 넣어 줌).
  // {host} 는 지금 접속한 주소로 바뀐다 (12번에선 localhost, 실습실 다른 PC에선 192.168.0.112). 비어 있으면 /engine/
  var RAW = String(window.CE_ENGINE_HOME || '').trim();
  var HOME = RAW ? RAW.replace(/\{host\}/g, location.hostname) : '/engine/';
  var ID = 'ce-engine-home';

  var style = document.createElement('style');
  style.textContent =
    '#' + ID + '{display:inline-flex;align-items:center;gap:8px;margin-left:18px;padding:4px 12px 4px 4px;' +
    'border-radius:999px;font-size:13px;font-weight:700;color:#C7D5E0;text-decoration:none;transition:background .15s,color .15s}' +
    '#' + ID + ':hover{background:rgba(255,255,255,.06);color:#fff}' +
    '#' + ID + ':focus-visible{outline:2px solid #66C0F4;outline-offset:2px}' +
    '#' + ID + ' .ce-mark{width:22px;height:22px;border-radius:50%;flex-shrink:0;' +
    'background:radial-gradient(circle at 35% 35%,#fff 0 18%,transparent 19%),' +
    'conic-gradient(#ff5a5a 0 25%,#ffc93c 0 50%,#3cc4ff 0 75%,#7c4dff 0)}';
  document.head.appendChild(style);

  function label() {
    var lang = (document.documentElement.lang || '').toLowerCase();
    return lang.indexOf('en') === 0 ? 'Back to service selection' : '서비스 선택 화면으로 돌아가기';
  }

  function makeLink() {
    var a = document.createElement('a');
    a.id = ID;
    a.href = HOME;
    a.title = label();
    a.setAttribute('aria-label', label());
    var mark = document.createElement('span');
    mark.className = 'ce-mark';
    mark.setAttribute('aria-hidden', 'true');
    a.appendChild(mark);
    a.appendChild(document.createTextNode('CommentEngine'));
    return a;
  }

  // 푸터의 버전 표시(v0.1) 바로 오른쪽에 붙인다. 못 찾으면 푸터 끝에 붙인다
  function findVersion(footer) {
    var spans = footer.querySelectorAll('span');
    for (var i = 0; i < spans.length; i++) {
      if (/^v\d+(\.\d+)*$/.test((spans[i].textContent || '').trim())) return spans[i];
    }
    return null;
  }

  function attach() {
    var footer = document.querySelector('footer');
    if (!footer) return;
    var existing = document.getElementById(ID);
    if (existing && footer.contains(existing)) return;
    if (existing) existing.remove();

    var link = makeLink();
    var ver = findVersion(footer);
    if (ver && ver.parentNode) ver.parentNode.insertBefore(link, ver.nextSibling);
    else footer.appendChild(link);
  }

  var scheduled = false;
  new MutationObserver(function () {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      attach();
    });
  }).observe(document.body, { childList: true, subtree: true });

  attach();
})();
