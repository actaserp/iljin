/*
 * 키오스크 화면 공통 (생산 실적 · 불량 실적)
 *
 *  IljinKiosk.useFooterArea()
 *      메인 화면 푸터(.footer, 30px)가 iframe 아래를 덮는다.
 *      이 탭이 보이는 동안 푸터를 숨기고 그 30px 까지 iframe 이 쓰게 한다.
 *      다른 탭으로 가면 원래대로. (nth-tabs 가 calc(100% - 30px) 를 겹겹이 빼고 있어
 *      CSS 한 줄로는 안 된다 — iframe 과 그 위 탭 영역 높이를 화면 바닥까지 늘린다)
 *
 *  IljinKiosk.openTab(code, title, fromCode)
 *      메뉴 code 화면을 메인 화면 탭으로 연다 (있으면 그 탭으로).
 *      fromCode 를 기억해 두면 열린 화면에서 back() 으로 그 탭에 돌아간다.
 *      예) 레이저 키오스크 → [불량] → 불량 실적 → [실적으로] → 레이저 키오스크
 *
 *  IljinKiosk.back()   기억해 둔 탭으로 돌아간다. 없으면 false
 *  IljinKiosk.ctx()    openTab 때 넘긴 상황 {op, machine, worker, workerId, t}
 *      불량 화면은 이걸로 공정·설비·작업자를 미리 박는다 (레이저에서 왔으면 레이저).
 *      t 는 넘긴 시각 — 같은 값이면 이미 반영한 것.
 *  IljinKiosk.from()   기억해 둔 탭 코드 (없으면 '')
 */
(function (w) {
    'use strict';

    var FROM_KEY = 'iljin.kiosk.from';
    var CTX_KEY = 'iljin.kiosk.ctx';

    function parentWin() {
        try { return (w.frameElement && w.parent && w.parent.document) ? w.parent : null; } catch (e) { return null; }
    }

    /* 메인 화면 nthTabs 는 let 전역이라 window.parent.nthTabs 로는 안 보인다 → eval */
    function tabs() {
        var pw = parentWin();
        if (!pw) return null;
        try { return pw.eval('typeof nthTabs !== "undefined" ? nthTabs : null'); } catch (e) { return null; }
    }

    /* 메뉴와 똑같이 연다: common.js 의 withCtx('/gui/{코드}/default') */
    function urlOf(code) {
        var path = '/gui/' + code + '/default';
        var pw = parentWin();
        try { if (pw && typeof pw.withCtx === 'function') return pw.withCtx(path); } catch (e) { /* 아래로 */ }
        if (typeof w.withCtx === 'function') return w.withCtx(path);
        return path;
    }

    function openTab(code, title, fromCode, ctxObj) {
        try {
            if (fromCode) sessionStorage.setItem(FROM_KEY, fromCode);
            if (ctxObj) { ctxObj.t = Date.now(); sessionStorage.setItem(CTX_KEY, JSON.stringify(ctxObj)); }
        } catch (e) { /* 무시 */ }
        var t = tabs();
        if (t) {
            if (t.isExistsTab('#' + code)) t.toggleTab('#' + code);
            else t.addTab({id: code, title: title || code, url: urlOf(code), active: true, allowClose: true});
            return;
        }
        w.location.href = urlOf(code);
    }

    function from() {
        try { return sessionStorage.getItem(FROM_KEY) || ''; } catch (e) { return ''; }
    }

    function ctx() {
        try { return JSON.parse(sessionStorage.getItem(CTX_KEY) || 'null'); } catch (e) { return null; }
    }

    function back() {
        var code = from();
        if (!code) return false;
        var t = tabs();
        if (t) {
            if (t.isExistsTab('#' + code)) t.toggleTab('#' + code);
            else t.addTab({id: code, title: '생산 실적', url: urlOf(code), active: true, allowClose: true});
            return true;
        }
        w.location.href = urlOf(code);
        return true;
    }

    function useFooterArea() {
        var pw = parentWin(), fe, foot;
        if (!pw) return;
        try { fe = w.frameElement; foot = pw.document.querySelector('.footer'); } catch (e) { return; }
        if (!fe || !foot) return;
        var touched = [];
        function chain() {
            var a = [];
            for (var n = fe; n && n !== pw.document.body; n = n.parentElement) {
                if (n.classList && n.classList.contains('con_area')) break;
                a.push(n);
            }
            return a;
        }
        function stretch() {
            var H = pw.innerHeight;
            chain().forEach(function (n) {
                var b = n.getBoundingClientRect().bottom;
                if (H - b > 1) {
                    if (touched.indexOf(n) < 0) touched.push(n);
                    // 탭 영역은 다른 키오스크 탭과 같이 쓴다. 원래 값은 처음 늘린 쪽이 한 번만 적어 둔다
                    if (!('kioskH' in n.dataset)) n.dataset.kioskH = n.style.height || '';
                    n.style.height = (n.offsetHeight + (H - b)) + 'px';
                }
            });
        }
        function restore() {
            touched.splice(0).forEach(function (n) { if ('kioskH' in n.dataset) { n.style.height = n.dataset.kioskH; delete n.dataset.kioskH; } });
            foot.style.display = '';
        }
        var on = false;
        function sync() {
            var vis = fe.getClientRects().length > 0 && fe.offsetParent !== null;
            if (vis) { foot.style.display = 'none'; stretch(); on = true; }
            else if (on) { restore(); on = false; }
        }
        sync();
        setInterval(sync, 700);
        pw.addEventListener('resize', function () { if (on) { restore(); on = false; setTimeout(sync, 50); } });
        w.addEventListener('pagehide', function () { if (on) restore(); });
    }

    w.IljinKiosk = {useFooterArea: useFooterArea, openTab: openTab, back: back, from: from, ctx: ctx};
})(window);