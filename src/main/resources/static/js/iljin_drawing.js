/**
 * 일진 품목 도면 보기 (키오스크 · 현장 모니터용)
 *
 * 생산 지시 화면에서 올린 품목 도면(attach_file, TableName='suju_drawing')을 크게 띄운다.
 * 장갑 낀 손으로 누르는 화면이라 목록 줄과 버튼을 크게 둔다.
 *
 * [필요]  common.js 의 AjaxUtil, withCtx
 *         /js/pdfjs/ (pdf.js 3.11 legacy — pdf.min.js · pdf.worker.min.js · cmaps · standard_fonts)
 *   <script th:src="@{/js/iljin_drawing.js}"></script>     ← pdf.js 는 처음 열 때 알아서 불러온다
 *
 * [사용]
 *   IljinDrawing.open({sujuId: 123, title: 'A13', projNo: '2026-001'});  // 품목 도면
 *   IljinDrawing.search({projNo: '2026-001', title: '2026-001'});         // 도면번호로 찾기
 *
 * [검색]  위쪽 칸에 키보드로 도면번호 일부를 친다. '-'·공백은 무시 → '0103' 도 FD10-01-03.
 *   품목 도면을 연 상태에서는 그 품목 안에서 거르고, [프로젝트 전체] 를 누르면 서버에서 찾는다.
 *
 * [확대]  PDF 는 브라우저 내장 뷰어 대신 pdf.js 로 직접 그린다. 내장 뷰어는 터치 확대가
 *   기기마다 달라 키오스크에서 안 먹는 경우가 있다.
 *   두 손가락 벌리기/오므리기 = 확대/축소 · 한 손가락 끌기 = 이동 · 두 번 톡 = 2.5배 ↔ 맞춤
 *   마우스 휠 · 키보드 + − 0 도 된다. 장갑용으로 [＋][－][맞춤] 버튼도 둔다.
 *   확대가 멈추면 그 배율로 다시 그려서 작은 치수 글씨도 선명하게 보인다.
 *
 * [도면번호 · 리비전]  파일 이름에서 읽는다 (생산 지시 화면 drawParse 와 같은 규칙)
 *   FD10-01-03-REV11.pdf → FD10-01-03, REV11
 *   FD10-01-05-00.pdf    → FD10-01-05, REV0   (4단 번호의 꼬리 '-00')
 *   같은 도면번호 중 리비전이 가장 높은 것만 보여 준다 — 현장은 최신만 봐야 한다.
 *
 * [높이]  화면은 메인 페이지 탭 iframe 안에서 돈다. iframe 아래쪽이 메인 푸터에 가려지므로
 *   실제로 보이는 높이에 맞춘다. 전체화면이면 화면 전체를 쓴다.
 */
(function (global) {
    'use strict';

    var API = '/api/production/part_drawing';
    var PDFJS = '/js/pdfjs/';
    var MAX_Z = 8;            // 맞춤 대비 최대 배율
    var MAX_PX = 6000;        // 다시 그릴 때 캔버스 긴 변 상한 (메모리)
    var box = null, state = null, view = null;

    function url(u) { return (typeof withCtx === 'function') ? withCtx(u) : u; }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function norm(s) { return String(s || '').toUpperCase().replace(/[\s\-_.]/g, ''); }

    function parse(fileName) {
        var base = String(fileName || '').trim()
            .replace(/\.[A-Za-z0-9]{2,4}$/, '')
            .replace(/\s*\(\d+\)$/, '');
        var rev = null;
        var m = base.match(/[\s_\-.]*(?:REV|R|V)[\s_.\-]*(\d+)$/i);
        if (m) {
            rev = +m[1];
            base = base.slice(0, m.index);
        } else {
            var t = base.match(/^(.+[\-_]\d{2}[\-_]\d{2})[\-_](00)$/);
            if (t) { base = t[1]; rev = 0; }
        }
        return {no: base.toUpperCase(), rev: rev};
    }

    function extOf(d) { return String(d.ext || '').replace('.', '').toLowerCase(); }
    function isCad(d) { return extOf(d) === 'dwg' || extOf(d) === 'dxf'; }
    function rank(d) {
        var e = extOf(d);
        if (e === 'pdf') return 0;
        if (IMG_EXT.indexOf(e) >= 0) return 1;
        return isCad(d) ? 3 : 2;
    }

    /* ================= 원본(DWG/DXF) 짝 찾기 =================
     * PDF·PNG 는 부품 한 장씩이지만 원본은 묶여서 오는 경우가 많다 (일진 샘플 확인).
     *   부품 원본  FD10-01-03.dwg          = PDF 와 이름이 같다
     *   유닛 원본  FD10-01-00.dxf · 001-000.dxf = 유닛 하나에 부품 시트가 여러 장
     *   통파일     LT2-RR_FLR_COMPL_PRF20_251215.dwg = 공정(지그) 전체가 한 파일
     * 그래서 이 순서로 찾는다.
     *   1) 이름이 같은 원본 (짧게 지은 번호면 끝이 같은 것: …-001-003 ↔ 001-003)
     *   2) 번호 앞부분이 같고 뒤가 0 인 원본 (FD10-01-03 → FD10-01-00, FD10-01)
     *      짧은 번호는 끝에 맞춘다 (C5-760-A08-001-003 → 001-000)
     *   3) 같은 품목에 올린 원본 중 어느 도면과도 번호가 안 맞는 것 = 통파일
     * 후보가 여럿이면 고르게 한다. 번호가 같은 원본은 리비전 높은 것 하나만.
     */
    function segs(no) { return String(no || '').toUpperCase().split(/[\s\-_.]+/).filter(Boolean); }
    function segEq(a, b) { return a === b || (/^\d+$/.test(a) && /^\d+$/.test(b) && +a === +b); }
    function isZero(x) { return /^0+$/.test(x); }

    /** 원본 번호가 도면 번호를 감싸는 정도. 0 = 안 맞음, 1000 = 같은 번호, 100+ = 위 단계(유닛·공정) */
    function origScore(drawNo, cadNo) {
        var a = segs(drawNo), c = segs(cadNo);
        if (!a.length || !c.length) return 0;
        if (a.length === c.length && a.every(function (x, i) { return segEq(x, c[i]); })) return 1000;
        // 짧은 번호가 끝까지 같다 (C5-760-A08-001-003 ↔ 001-003, …-000-000 ↔ 000-000) = 같은 도면
        if (c.length < a.length && c.every(function (x, i) { return segEq(x, a[a.length - c.length + i]); })) return 900;
        var p = c.slice();
        while (p.length && isZero(p[p.length - 1])) p.pop();
        if (!p.length || p.length >= a.length) return 0;
        var at = function (k) { return p.every(function (x, i) { return segEq(x, a[k + i]); }); };
        if (at(0)) return 100 + p.length * 10;                 // 앞에서부터 같다
        var k = a.length - c.length;                            // 짧은 번호(001-000)는 끝에 맞춘다
        if (k > 0 && at(k)) return 99 + p.length * 10;
        return 0;
    }

    /** 원본이 도면보다 몇 단계 위인지 (1 = 유닛). origScore 가 0 이 아닐 때만 쓴다 */
    function origLevel(drawNo, cadNo) {
        var a = segs(drawNo), c = segs(cadNo), p = c.slice();
        while (p.length && isZero(p[p.length - 1])) p.pop();
        var k = p.every(function (x, i) { return segEq(x, a[i]); }) ? 0 : a.length - c.length;
        return a.length - (k + p.length);
    }

    /** 번호별 최신 리비전 원본 하나씩 */
    function cadLatest(files) {
        var m = {};
        files.forEach(function (o) {
            if (!isCad(o)) return;
            var p = parse(o.file_name), r = p.rev == null ? -1 : p.rev;
            var k = p.no + '|' + extOf(o);
            var c = m[k];
            if (!c || r > c._r || (r === c._r && o.id > c.id)) m[k] = Object.assign({}, o, {_no: p.no, _rev: p.rev, _r: r});
        });
        return Object.keys(m).map(function (k) { return m[k]; });
    }

    /**
     * 도면 한 장의 원본 후보.
     * @param d      도면 (file_name, 있으면 _rev)
     * @param files  같은 품목에 올린 파일 전부 (/list 결과)
     * @return [{id, name, ext, rev, kind:'part'|'unit'|'whole', label}]  비면 원본 없음
     */
    function findOriginals(d, files) {
        files = files || [];
        var p = parse(d.file_name), dRev = p.rev == null ? -1 : p.rev;
        var pack = function (o, kind) {
            return {id: o.id, name: o.file_name, ext: extOf(o).toUpperCase(), rev: o._rev, no: o._no, kind: kind,
                label: kind === 'part' ? '이 도면 원본' : kind === 'unit' ? '유닛 원본' : '전체 원본'};
        };
        if (isCad(d)) return [pack(Object.assign({}, d, {_no: p.no, _rev: p.rev}), 'part')];

        // 1) 같은 번호 — 같은 리비전이 있으면 그것, 없으면 최신
        var same = files.filter(function (o) { return isCad(o) && origScore(p.no, parse(o.file_name).no) >= 900; })
            .map(function (o) { var q = parse(o.file_name); return Object.assign({}, o, {_no: q.no, _rev: q.rev, _r: q.rev == null ? -1 : q.rev}); });
        if (same.length) {
            var byExt = {};
            same.forEach(function (o) {
                var e = extOf(o), c = byExt[e];
                var pick = !c || (o._r === dRev && c._r !== dRev) || (c._r !== dRev && o._r > c._r) || (o._r === c._r && o.id > c.id);
                if (pick) byExt[e] = o;
            });
            return Object.keys(byExt).sort().map(function (e) { return pack(byExt[e], 'part'); });
        }

        var cads = cadLatest(files);
        // 2) 유닛·공정 번호 — 가장 가까운(긴) 것
        var best = 0, hit = [];
        cads.forEach(function (o) {
            var s = origScore(p.no, o._no);
            if (s >= 900) return;
            if (s > best) { best = s; hit = [o]; }
            else if (s && s === best) hit.push(o);
        });
        // 바로 위 단계(FD10-01-00 → FD10-01-03)면 유닛, 더 위(FD10 → FD10-01-03)면 공정 전체
        if (hit.length) return hit.map(function (o) { return pack(o, origLevel(p.no, o._no) === 1 ? 'unit' : 'whole'); });

        // 3) 어느 도면과도 번호가 안 맞는 원본 = 공정 통파일
        var draws = {};
        files.forEach(function (f) { if (!isCad(f)) draws[parse(f.file_name).no] = 1; });
        var dn = Object.keys(draws);
        return cads.filter(function (o) {
            if (!segs(o._no).some(function (x) { return !isZero(x); })) return false;   // 000-000 = 공정 조립도 한 장
            return !dn.some(function (n) { return origScore(n, o._no) > 0; });
        }).map(function (o) { return pack(o, 'whole'); });
    }

    /**
     * 올리는 원본이 어떤 원본인지 (도면 올리기 상태 칸).
     * @param cadName   원본 파일 이름
     * @param drawNos   같은 품목의 PDF·그림 도면번호들 (이미 올린 것 + 이번에 올리는 것)
     * @return {kind:'part'|'unit'|'whole'|'none', n: 이 원본으로 열리는 도면 수}
     */
    function origKind(cadName, drawNos) {
        var no = parse(cadName).no, part = 0, unit = 0;
        (drawNos || []).forEach(function (n) {
            var sc = origScore(n, no);
            if (sc >= 900) part++;
            else if (sc) unit++;
        });
        if (part) return {kind: 'part', n: part};
        if (unit) return {kind: 'unit', n: unit};
        if (drawNos && drawNos.length && segs(no).some(function (x) { return !isZero(x); })) return {kind: 'whole', n: drawNos.length};
        return {kind: 'none', n: 0};
    }

    /**
     * 목록에 따로 줄을 만들지 않는 원본 — 어떤 도면의 [원본 열기] 로 열리는 것.
     * 같은 품목에 PDF·그림이 하나라도 있으면: 번호가 맞는 원본, 통파일. (000-000 조립도 원본은 줄로 남는다)
     */
    function hiddenOriginals(files) {
        var dn = {}, out = {};
        files.forEach(function (f) { if (!isCad(f)) dn[parse(f.file_name).no] = 1; });
        var nos = Object.keys(dn);
        if (!nos.length) return out;
        files.forEach(function (o) {
            if (!isCad(o)) return;
            var no = parse(o.file_name).no;
            var hit = nos.some(function (n) { return origScore(n, no) > 0; });
            var whole = !hit && segs(no).some(function (x) { return !isZero(x); });
            if (hit || whole) out[o.id] = 1;
        });
        return out;
    }

    /**
     * 도면번호별 최신 1건 (검색은 품목이 섞이므로 품목+번호 단위). 번호 순.
     * 같은 리비전이면 화면에 보이는 PDF·그림을 고른다. DWG/DXF 는 목록에 없고 [원본 열기] 로만 연다.
     */
    function latest(rows) {
        var map = {}, by = {}, hide = {};
        rows.forEach(function (d) { (by[d.suju_id] = by[d.suju_id] || []).push(d); });
        Object.keys(by).forEach(function (k) { Object.assign(hide, hiddenOriginals(by[k])); });
        rows.forEach(function (d) {
            if (hide[d.id] || isCad(d)) return;     // 원본(DWG/DXF)은 목록에 안 나온다 — [원본 열기] 로만
            var p = parse(d.file_name);
            var key = (d.suju_id != null ? d.suju_id : '') + '|' + p.no;
            var r = p.rev == null ? -1 : p.rev;
            var x = Object.assign({}, d, {_no: p.no, _rev: p.rev, _r: r});
            var cur = map[key];
            // 같은 리비전이면 PDF → 그림 → 기타 → DWG/DXF 순으로 화면에 띄울 파일을 고른다
            var better = !cur || r > cur._r
                || (r === cur._r && (rank(d) < rank(cur) || (rank(d) === rank(cur) && d.id > cur.id)));
            if (better) map[key] = x;
        });
        return Object.keys(map).map(function (k) { return map[k]; })
            .sort(function (a, b) {
                return a._no.localeCompare(b._no, 'ko', {numeric: true})
                    || String(a.item_name || '').localeCompare(String(b.item_name || ''), 'ko', {numeric: true});
            });
    }

    function visibleHeight() {
        var h = window.innerHeight;
        if (document.fullscreenElement) return h;
        try {
            var fe = window.frameElement;
            if (fe && window.parent && window.parent.document) {
                var top = fe.getBoundingClientRect().top;
                var ft = window.parent.document.querySelector('.footer');
                // 키오스크는 푸터를 숨긴다(display:none → rect 가 0). 그때는 화면 바닥까지
                var bottom = (ft && ft.getClientRects().length) ? ft.getBoundingClientRect().top : window.parent.innerHeight;
                h = Math.min(h, Math.max(300, bottom - top));
            }
        } catch (e) { /* 다른 출처 — 그대로 */ }
        return h;
    }

    function fit() {
        if (!box) return;
        box.style.height = visibleHeight() + 'px';
        if (view) view.refit();
    }

    /* ================= pdf.js 지연 로드 ================= */
    var pdfReady = null, pdfWhy = '';
    /*
     * pdf.min.js 는 UMD 라, 페이지에 AMD(define.amd)나 CommonJS(module/exports) 흔적이 있으면
     * window.pdfjsLib 대신 그쪽에 붙어 버린다 (MES 레이아웃은 라이브러리가 많아 그럴 수 있다).
     * 불러오는 동안만 그 셋을 가려서 늘 window.pdfjsLib 로 받는다.
     */
    function loadPdfJs() {
        if (pdfReady) return pdfReady;
        pdfReady = new Promise(function (resolve, reject) {
            if (global.pdfjsLib) { resolve(global.pdfjsLib); return; }
            var saved = {}, keys = ['define', 'module', 'exports'];
            keys.forEach(function (k) { saved[k] = global[k]; try { global[k] = undefined; } catch (e) { /* 무시 */ } });
            var restore = function () { keys.forEach(function (k) { try { global[k] = saved[k]; } catch (e) { /* 무시 */ } }); };
            var s = document.createElement('script');
            s.src = url(PDFJS + 'pdf.min.js');
            s.onload = function () {
                restore();
                var lib = global.pdfjsLib || global['pdfjs-dist/build/pdf'];
                if (!lib) { pdfReady = null; pdfWhy = 'pdf.min.js 는 받았지만 pdfjsLib 가 없습니다'; reject(new Error(pdfWhy)); return; }
                global.pdfjsLib = lib;
                lib.GlobalWorkerOptions.workerSrc = url(PDFJS + 'pdf.worker.min.js');
                resolve(lib);
            };
            s.onerror = function () {
                restore();
                pdfReady = null;
                pdfWhy = s.src + ' 를 받지 못했습니다 (파일 없음 또는 접근 막힘)';
                reject(new Error(pdfWhy));
            };
            document.head.appendChild(s);
        });
        return pdfReady;
    }

    /* ================= 확대·이동 보기 =================
     *  stage(잘라내는 창) 안의 content 를 translate + scale 로 움직인다.
     *  content 의 CSS 크기 = '맞춤' 크기(w0×h0). 배율 z 는 그 위에 곱한다.
     */
    function ZoomView(stage, natW, natH, onSettle) {
        var content = document.createElement('div');
        content.style.cssText = 'position:absolute;left:0;top:0;transform-origin:0 0;will-change:transform;background:#fff;'
            + 'box-shadow:0 2px 14px rgba(0,0,0,.35);';
        stage.appendChild(content);

        var z = 1, tx = 0, ty = 0, w0 = 0, h0 = 0, settleT = null;

        // 손바닥 커서: 잡고 끌 수 있으면 grab, 끄는 중이면 grabbing
        stage.style.cursor = 'grab';
        function apply() {
            content.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + z + ')';
            if (zoomLabel) zoomLabel.textContent = Math.round(z * 100) + '%';
        }
        function clamp() {
            var W = stage.clientWidth, H = stage.clientHeight, cw = w0 * z, ch = h0 * z;
            tx = cw <= W ? (W - cw) / 2 : Math.min(0, Math.max(W - cw, tx));
            ty = ch <= H ? (H - ch) / 2 : Math.min(0, Math.max(H - ch, ty));
        }
        function settle() {
            clearTimeout(settleT);
            settleT = setTimeout(function () { onSettle && onSettle(z); }, 250);
        }
        /** 화면상의 (px,py) 를 고정한 채 배율을 nz 로 */
        function zoomAt(nz, px, py) {
            nz = Math.max(1, Math.min(MAX_Z, nz));
            var cx = (px - tx) / z, cy = (py - ty) / z;
            z = nz;
            tx = px - cx * z;
            ty = py - cy * z;
            clamp(); apply(); settle();
        }
        function refit() {
            var W = stage.clientWidth, H = stage.clientHeight;
            var k = Math.min(W / natW, H / natH);
            w0 = Math.max(1, Math.round(natW * k));
            h0 = Math.max(1, Math.round(natH * k));
            content.style.width = w0 + 'px';
            content.style.height = h0 + 'px';
            z = 1; clamp(); apply(); settle();
        }

        /* --- 포인터: 한 손가락 이동 · 두 손가락 확대 · 두 번 톡 --- */
        var pts = {}, pinch = null, drag = null, lastTap = 0;
        function local(e) {
            var r = stage.getBoundingClientRect();
            return {x: e.clientX - r.left, y: e.clientY - r.top};
        }
        function twoPts() {
            var k = Object.keys(pts);
            return k.length >= 2 ? [pts[k[0]], pts[k[1]]] : null;
        }
        stage.addEventListener('pointerdown', function (e) {
            if (e.target.closest && e.target.closest('[data-zbtn]')) return;
            stage.setPointerCapture && stage.setPointerCapture(e.pointerId);
            pts[e.pointerId] = local(e);
            var two = twoPts();
            if (two) {
                drag = null;
                pinch = {
                    d: Math.hypot(two[0].x - two[1].x, two[0].y - two[1].y) || 1,
                    z: z,
                    mx: (two[0].x + two[1].x) / 2, my: (two[0].y + two[1].y) / 2,
                    tx: tx, ty: ty
                };
            } else {
                var p = pts[e.pointerId];
                var now = Date.now();
                if (now - lastTap < 300) {        // 두 번 톡
                    lastTap = 0;
                    if (z > 1.05) refit(); else zoomAt(2.5, p.x, p.y);
                    return;
                }
                lastTap = now;
                drag = {x: p.x, y: p.y, tx: tx, ty: ty};
                stage.style.cursor = 'grabbing';
            }
        });
        stage.addEventListener('pointermove', function (e) {
            if (!pts[e.pointerId]) return;
            pts[e.pointerId] = local(e);
            var two = twoPts();
            if (two && pinch) {
                var d = Math.hypot(two[0].x - two[1].x, two[0].y - two[1].y) || 1;
                var mx = (two[0].x + two[1].x) / 2, my = (two[0].y + two[1].y) / 2;
                var nz = Math.max(1, Math.min(MAX_Z, pinch.z * d / pinch.d));
                // 처음 손가락 중점 아래 있던 지점이 지금 중점 아래로 오게 (확대 + 이동 동시)
                var cx = (pinch.mx - pinch.tx) / pinch.z, cy = (pinch.my - pinch.ty) / pinch.z;
                z = nz; tx = mx - cx * z; ty = my - cy * z;
                clamp(); apply();
            } else if (drag) {
                var p = pts[e.pointerId];
                if (Math.abs(p.x - drag.x) + Math.abs(p.y - drag.y) > 6) lastTap = 0;   // 끌면 톡이 아니다
                tx = drag.tx + (p.x - drag.x);
                ty = drag.ty + (p.y - drag.y);
                clamp(); apply();
            }
        });
        function up(e) {
            if (!pts[e.pointerId]) return;
            delete pts[e.pointerId];
            if (pinch) { pinch = null; settle(); }
            var k = Object.keys(pts);
            // 두 손가락 중 하나만 떼면 남은 손가락으로 이어서 이동
            drag = k.length === 1 ? {x: pts[k[0]].x, y: pts[k[0]].y, tx: tx, ty: ty} : null;
            if (!drag) stage.style.cursor = 'grab';
        }
        stage.addEventListener('pointerup', up);
        stage.addEventListener('pointercancel', up);
        stage.addEventListener('wheel', function (e) {
            e.preventDefault();
            var p = local(e);
            zoomAt(z * Math.exp(-e.deltaY * 0.0015), p.x, p.y);
        }, {passive: false});

        /* --- 버튼 (장갑용) --- */
        var bar = document.createElement('div');
        bar.setAttribute('data-zbtn', '1');
        // 오른쪽 아래는 도면 표제란(도면번호·재질·수량) 자리라 가리지 않게 왼쪽 아래에 둔다
        bar.style.cssText = 'position:absolute;left:14px;bottom:14px;display:flex;gap:8px;align-items:center;z-index:2;';
        var btnCss = 'width:64px;height:64px;border:0;border-radius:14px;background:rgba(31,42,68,.88);color:#fff;'
            + 'font-size:30px;font-weight:800;cursor:pointer;line-height:1;';
        bar.innerHTML = '<span data-r="zl" style="min-width:64px;text-align:center;color:#fff;font-weight:800;font-size:16px;'
            + 'background:rgba(31,42,68,.7);border-radius:10px;padding:8px 6px;">100%</span>'
            + '<button data-z="out" style="' + btnCss + '">－</button>'
            + '<button data-z="fit" style="' + btnCss + 'width:86px;font-size:18px;">맞춤</button>'
            + '<button data-z="in" style="' + btnCss + '">＋</button>';
        stage.appendChild(bar);
        var zoomLabel = bar.querySelector('[data-r=zl]');
        function center() { return {x: stage.clientWidth / 2, y: stage.clientHeight / 2}; }
        bar.querySelector('[data-z=in]').onclick = function () { var c = center(); zoomAt(z * 1.5, c.x, c.y); };
        bar.querySelector('[data-z=out]').onclick = function () { var c = center(); zoomAt(z / 1.5, c.x, c.y); };
        bar.querySelector('[data-z=fit]').onclick = refit;

        refit();
        return {
            content: content,
            refit: refit,
            zoomBy: function (k) { var c = center(); zoomAt(z * k, c.x, c.y); },
            size: function () { return {w: w0, h: h0, z: z}; }
        };
    }

    /* ================= 화면 틀 ================= */
    function build() {
        box = document.createElement('div');
        box.id = 'iljinDrawing';
        // 생산 지시 화면의 도면 모달(z 10000) 위에서도 열리게 한 칸 높게
        box.style.cssText = 'display:none;position:fixed;left:0;top:0;right:0;z-index:10001;'
            + 'background:rgba(0,0,0,.6);padding:10px;box-sizing:border-box;';
        // 레이아웃 CSS 가 input/button 높이를 30px 로 묶는다 → !important 로 푼다
        box.innerHTML = ''
            + '<style>'
            + '#iljinDrawing input[data-r=q]{height:52px!important;line-height:normal!important;padding:0 14px!important;'
            + '  font-size:19px!important;border:2px solid #9fb3d1!important;border-radius:10px!important;box-sizing:border-box;}'
            + '#iljinDrawing input[data-r=q]:focus{border-color:#ffd166!important;outline:none;}'
            + '#iljinDrawing button{height:auto;}'
            + '#iljinDrawing .dw-scope{height:52px!important;padding:0 14px;border:2px solid #9fb3d1;border-radius:10px;'
            + '  background:transparent;color:#fff;font-size:16px;font-weight:800;cursor:pointer;white-space:nowrap;}'
            + '#iljinDrawing .dw-scope.on{background:#ffd166;border-color:#ffd166;color:#1f2a44;}'
            + '</style>'
            + '<div style="background:#fff;border-radius:12px;height:100%;display:flex;flex-direction:column;overflow:hidden;">'
            + '  <div style="display:flex;align-items:center;gap:10px;padding:10px 14px;background:#1f2a44;color:#fff;">'
            + '    <b data-r="title" style="font-size:18px;flex:0 1 auto;min-width:0;max-width:40%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"></b>'
            + '    <input data-r="q" type="search" autocomplete="off" placeholder="도면번호 검색 (예: 01-03, 0103)" style="flex:1;min-width:160px;">'
            + '    <button data-r="scope" class="dw-scope" style="display:none">프로젝트 전체</button>'
            + '    <button data-r="orig" class="dw-scope" style="display:none" title="PC 에 설치한 도면 뷰어로 원본 열기"></button>'
            + '    <button data-r="close" style="flex:none;min-width:110px;height:52px;border:0;border-radius:10px;'
            + '            background:#e74c3c;color:#fff;font-size:18px;font-weight:800;cursor:pointer;">닫기 ✕</button>'
            + '  </div>'
            + '  <div style="flex:1;display:flex;min-height:0;">'
            + '    <div data-r="list" style="width:280px;flex:none;overflow:auto;border-right:1px solid #e2e8f0;background:#f7f9fc;"></div>'
            + '    <div data-r="pane" style="flex:1;min-width:0;background:#525659;position:relative;overflow:hidden;touch-action:none;'
            + '         user-select:none;-webkit-user-select:none;"></div>'
            + '  </div>'
            + '</div>';
        document.body.appendChild(box);
        box.querySelector('[data-r=close]').onclick = close;

        var q = box.querySelector('[data-r=q]'), qt = null;
        q.addEventListener('input', function () {
            clearTimeout(qt);
            qt = setTimeout(runQuery, state && state.remote ? 300 : 0);
        });
        q.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {              // 엔터 = 첫 결과 열기
                e.preventDefault();
                clearTimeout(qt);
                runQuery(true);
            }
        });
        box.querySelector('[data-r=orig]').onclick = function () {
            if (state && state.origs && state.origs.length) openOriginals(state.origs, state.origFor);
        };
        box.querySelector('[data-r=scope]').onclick = function () {
            if (!state) return;
            state.remote = !state.remote;
            renderScope();
            runQuery();
            q.focus();
        };

        window.addEventListener('resize', function () { if (isOpen()) fit(); });
        document.addEventListener('fullscreenchange', function () { if (isOpen()) setTimeout(fit, 50); });
        document.addEventListener('keydown', function (e) {
            if (!isOpen()) return;
            if (e.key === 'Escape') { close(); return; }
            if (e.target === q) return;           // 검색칸에서 친 + − 는 글자
            if (!view) return;
            if (e.key === '+' || e.key === '=') { view.zoomBy(1.5); e.preventDefault(); }
            else if (e.key === '-' || e.key === '_') { view.zoomBy(1 / 1.5); e.preventDefault(); }
            else if (e.key === '0') { view.refit(); e.preventDefault(); }
        });
    }

    function isOpen() { return box && box.style.display !== 'none'; }

    function renderScope() {
        var b = box.querySelector('[data-r=scope]');
        // 품목 도면을 연 경우에만 '이 품목 ↔ 프로젝트 전체' 를 바꾼다
        b.style.display = state.items ? '' : 'none';
        b.classList.toggle('on', !!state.remote);
        b.textContent = state.remote ? '프로젝트 전체 ✓' : '프로젝트 전체';
    }

    /* ================= 목록 · 검색 ================= */
    function runQuery(openFirst) {
        if (!state) return;
        var raw = box.querySelector('[data-r=q]').value;
        var key = norm(raw);
        if (!state.remote) {                       // 이 품목 안에서 거르기
            state.rows = state.items.filter(function (d) { return !key || norm(d.file_name).indexOf(key) >= 0; });
            renderList();
            if (openFirst && state.rows.length) show(state.rows[0].id);
            return;
        }
        if (!key && !state.projNo) {               // 전체 프로젝트를 빈 검색으로 다 끌어오지 않는다
            state.rows = [];
            renderList('도면번호를 입력하세요');
            return;
        }
        var seq = ++state.seq;
        renderList('찾는 중…');
        AjaxUtil.getAsyncData(API + '/search', {q: key, projNo: state.projNo || ''}, function (res) {
            if (!state || seq !== state.seq) return;   // 늦게 온 이전 결과는 버린다
            state.rows = latest((res && res.data) || []);
            renderList(state.rows.length ? null : '찾은 도면이 없습니다');
            if (openFirst && state.rows.length) show(state.rows[0].id);
        });
    }

    function renderList(msg) {
        var list = box.querySelector('[data-r=list]');
        var many = state.remote || state.rows.length > 1 || norm(box.querySelector('[data-r=q]').value);
        // 품목 도면이 한 장이고 검색도 안 하면 목록은 자리만 차지한다 — 숨기고 도면을 넓게
        var hide = !many && state.items && state.items.length <= 1;
        var was = list.style.display;
        list.style.display = hide ? 'none' : '';
        if (was !== list.style.display && view) setTimeout(function () { view && view.refit(); }, 0);
        if (msg) {
            list.innerHTML = '<div style="padding:22px 16px;color:#718096;font-size:16px;">' + esc(msg) + '</div>';
            return;
        }
        list.innerHTML = state.rows.map(function (d) {
            var on = d.id === state.cur;
            var sub = state.remote && d.item_name
                ? '<b style="color:#2f855a">' + esc(d.item_name) + '</b>'
                + (state.projNo ? '' : ' · ' + esc(d.proj_name || d.proj_no || '')) + ' · '
                : '';
            return '<div data-id="' + d.id + '" style="padding:14px 14px;border-bottom:1px solid #e2e8f0;cursor:pointer;'
                + 'background:' + (on ? '#dbe7ff' : 'transparent') + ';border-left:5px solid ' + (on ? '#1f4e8c' : 'transparent') + ';">'
                + '<div style="font-size:17px;font-weight:800;color:#1f2a44;">' + esc(d._no) + '</div>'
                + '<div style="font-size:13px;color:#4a5568;margin-top:2px;">' + sub
                + (d._rev != null ? '<b style="color:#1f4e8c">REV' + d._rev + '</b> · ' : '')
                + esc(d.created || '') + '</div></div>';
        }).join('') || '<div style="padding:22px 16px;color:#718096;font-size:16px;">찾은 도면이 없습니다</div>';
        Array.prototype.forEach.call(list.querySelectorAll('[data-id]'), function (n) {
            n.onclick = function () { show(+n.getAttribute('data-id')); };
        });
    }

    /* ================= 원본(DWG/DXF) 열기 =================
     * PC 에 깔린 'iljindwg:' 처리기(open_dwg.ps1)가 임시 폴더에 받아 도면 뷰어로 연다.
     * 다운로드 폴더에 복사본이 쌓이지 않고, 임시 폴더는 처리기가 하루 지난 것을 지운다.
     * 처리기에는 로그인이 없어 서버가 한 번 쓰는 표(2분)를 준다 (PartDrawingController.ticket).
     * 처리기를 안 깐 PC 는 아무 일도 안 일어나므로, 안내에 [처음이면 설치] · [내려받기] 를 잠깐 띄운다.
     *   [처음이면 설치] = 서버가 만들어 주는 .reg 하나. 열고 '예' 만 누르면 끝 (관리자 권한 불필요).
     */
    /** [원본 열기] 버튼 — 품목 파일 전부를 보고 짝을 찾는다 (검색 결과엔 원본이 빠져 있을 수 있어 품목 목록을 받아 온다) */
    function setOrig(d) {
        var ob = box.querySelector('[data-r=orig]');
        state.origs = null;
        state.origFor = d._no;
        ob.style.display = 'none';
        var apply = function (files) {
            if (!state || state.cur !== d.id) return;     // 그사이 다른 도면을 눌렀다
            var cs = findOriginals(d, files);
            state.origs = cs;
            ob.style.display = cs.length ? '' : 'none';
            ob.textContent = origText(cs);
            ob.title = cs.map(function (c) { return c.label + ' · ' + c.name; }).join('\n');
        };
        if (d.suju_id == null) { apply([d]); return; }
        state.files = state.files || {};
        if (state.files[d.suju_id]) { apply(state.files[d.suju_id]); return; }
        AjaxUtil.getAsyncData(API + '/list', {sujuId: d.suju_id}, function (res) {
            if (!state) return;
            state.files[d.suju_id] = (res && res.data) || [];
            apply(state.files[d.suju_id]);
        });
    }

    function origText(cs) {
        if (!cs.length) return '';
        if (cs.length > 1) return '원본 열기 (' + cs.length + '개) ▾';
        var c = cs[0];
        return '원본 열기 · ' + (c.kind === 'part' ? '' : c.label + ' ') + c.ext;
    }

    /**
     * 원본 후보 열기. 하나면 바로, 여럿이면 고르게.
     * @param cs     findOriginals 결과
     * @param forNo  지금 보는 도면번호 — 유닛·통파일이면 '안에서 이 장을 찾으세요' 로 알려 준다
     */
    function openOriginals(cs, forNo) {
        if (!cs || !cs.length) return;
        if (cs.length === 1) { openOriginal(cs[0].id, cs[0], forNo); return; }
        var t = toastBox();
        var bcss = 'height:44px;padding:0 14px;border:0;border-radius:8px;font-weight:800;font-size:15px;cursor:pointer;text-align:left;';
        t.innerHTML = '<div style="display:flex;flex-direction:column;gap:8px;max-height:60vh;overflow:auto">'
            + '<span style="color:#cbd5e0;font-size:14px">어느 원본을 열까요?</span>'
            + cs.map(function (c, i) {
                return '<button data-i="' + i + '" style="' + bcss + 'background:#fff;color:#1f2a44;">'
                    + '<span style="color:#1f4e8c">' + esc(c.label) + ' ' + esc(c.ext) + '</span> · ' + esc(c.name) + '</button>';
            }).join('')
            + '<button data-x style="' + bcss + 'background:#4a5568;color:#fff;text-align:center">닫기</button></div>';
        Array.prototype.forEach.call(t.querySelectorAll('[data-i]'), function (b) {
            b.onclick = function () { var c = cs[+b.getAttribute('data-i')]; openOriginal(c.id, c, forNo); };
        });
        t.querySelector('[data-x]').onclick = function () { t.style.display = 'none'; };
        clearTimeout(toast._t);
        t.style.display = 'flex';
    }

    function openOriginal(id, c, forNo) {
        AjaxUtil.getAsyncData(API + '/ticket', {id: id}, function (res) {
            if (!res || res.success === false || !res.data) {
                toast('원본을 열지 못했습니다. ' + ((res && res.message) || ''), null);
                return;
            }
            var file = location.origin + url(API + '/file?t=' + res.data.token);
            // 처리기는 링크를 작은따옴표로 받는다 → ' 도 인코딩 (encodeURIComponent 는 ' 를 그대로 둔다)
            var enc = function (v) { return encodeURIComponent(v).replace(/'/g, '%27'); };
            var link = 'iljindwg:?u=' + enc(file) + '&n=' + enc(res.data.name || 'drawing.dwg');
            window.location.href = link;
            var hint = !c || c.kind === 'part' || !forNo ? ''
                : '<br><span style="color:#ffd166">' + (c.kind === 'unit' ? '유닛' : '공정 전체') + ' 원본입니다 — 안에서 <b>'
                + esc(forNo) + '</b> 장을 찾으세요</span>';
            toast('도면 뷰어로 여는 중… (' + esc(res.data.name || '') + ')' + hint, id);
        });
    }

    function toastBox() {
        var t = document.getElementById('iljinDwgToast');
        if (!t) {
            t = document.createElement('div');
            t.id = 'iljinDwgToast';
            t.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:10002;'
                + 'background:#1f2a44;color:#fff;padding:14px 18px;border-radius:12px;font-size:16px;'
                + 'box-shadow:0 6px 20px rgba(0,0,0,.35);display:flex;gap:14px;align-items:center;max-width:90vw;';
            document.body.appendChild(t);
        }
        return t;
    }

    function toast(html, id) {
        var t = toastBox();
        var bcss = 'height:40px;padding:0 14px;border:0;border-radius:8px;font-weight:800;font-size:15px;cursor:pointer;';
        t.innerHTML = '<span>' + html + '</span>'
            + (id ? '<span style="color:#cbd5e0;font-size:13px">안 열리면</span>'
                + '<button data-inst style="' + bcss + 'background:#ffd166;color:#1f2a44;">처음이면 설치</button>'
                + '<button data-dl style="' + bcss + 'background:#4a5568;color:#fff;">내려받기</button>' : '');
        var b = t.querySelector('[data-dl]');
        if (b) b.onclick = function () { window.location.href = url(API + '/view?id=' + id + '&download=true'); t.style.display = 'none'; };
        var ins = t.querySelector('[data-inst]');
        if (ins) ins.onclick = function () {
            window.location.href = url(API + '/installer');
            clearTimeout(toast._t);
            t.innerHTML = '<span style="line-height:1.6">받은 <b>iljin_dwg_open.reg</b> 를 열고 <b>[예]</b> 를 누르세요.<br>'
                + '그다음 [원본 열기] 를 다시 누르면 됩니다. (PC 에 도면 뷰어가 깔려 있어야 합니다)</span>'
                + '<button data-x style="' + bcss + 'background:#4a5568;color:#fff;">닫기</button>';
            t.querySelector('[data-x]').onclick = function () { t.style.display = 'none'; };
        };
        t.style.display = 'flex';
        clearTimeout(toast._t);
        toast._t = setTimeout(function () { t.style.display = 'none'; }, 9000);
    }

    /* ================= 그리기 ================= */
    function show(id) {
        var d = state.rows.filter(function (x) { return x.id === id; })[0];
        if (!d) return;
        state.cur = id;
        var head = state.remote && d.item_name ? (d.proj_no ? d.proj_no + ' · ' : '') + d.item_name : state.title;
        box.querySelector('[data-r=title]').textContent =
            (head ? head + ' · ' : '') + d._no + (d._rev != null ? ' REV' + d._rev : '');

        var ext = String(d.ext || '').replace('.', '').toLowerCase();
        var src = url(API + '/view?id=' + id);
        setOrig(d);
        var pane = box.querySelector('[data-r=pane]');
        if (state.mount) state.mount.destroy();
        state.mount = null;
        view = null;
        renderList();

        if (isViewable(ext)) {
            state.mount = mountFile(pane, src, ext, function (v) { view = v; });
        } else {
            message(pane, isCad(d)
                ? 'CAD 원본(' + esc(ext.toUpperCase()) + ')만 있는 도면입니다.<br>위의 [원본 열기] 를 누르면 PC 의 도면 뷰어로 열립니다.<br>'
                + '<span style="font-size:14px;color:#cbd5e0">같은 이름의 PDF 를 같이 올리면 여기서 바로 보입니다.</span>'
                : esc(ext.toUpperCase() || '이') + ' 파일은 화면에서 볼 수 없습니다.<br>생산 지시 화면에서 내려받으세요.');
        }
    }

    var IMG_EXT = ['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp'];
    function isViewable(ext) { return ext === 'pdf' || IMG_EXT.indexOf(ext) >= 0; }

    function message(pane, html) {
        pane.innerHTML = '<div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;'
            + 'color:#fff;font-size:18px;text-align:center;line-height:1.7;padding:20px;"><div>' + html + '</div></div>';
    }

    /**
     * pane 안에 PDF·그림을 확대/이동 보기로 띄운다. 키오스크 창과 생산 지시 도면 창이 같이 쓴다.
     * @param onView  ZoomView 가 만들어질 때 불린다 (키보드 + − 0 연결용)
     * @return {destroy, refit}  다른 도면으로 바꾸거나 창을 닫을 때 destroy()
     */
    function mountFile(pane, src, ext, onView) {
        var m = {alive: true, pdf: null, page: 1, view: null};
        pane.style.position = 'relative';
        pane.style.overflow = 'hidden';
        pane.style.touchAction = 'none';
        pane.style.userSelect = 'none';
        pane.innerHTML = '';
        var setView = function (v) { m.view = v; if (onView) onView(v); };

        if (ext === 'pdf') {
            message(pane, '도면을 여는 중…');
            loadPdfJs().then(function (lib) {
                return lib.getDocument({
                    url: src,
                    cMapUrl: url(PDFJS + 'cmaps/'),
                    cMapPacked: true,
                    standardFontDataUrl: url(PDFJS + 'standard_fonts/'),
                    withCredentials: true
                }).promise;
            }).then(function (pdf) {
                if (!m.alive) { try { pdf.destroy(); } catch (e) { /* 무시 */ } return; }
                m.pdf = pdf;
                drawPdfPage(pane, m, setView);
            }).catch(function (err) {
                if (!m.alive) return;
                // 브라우저 내장 PDF 뷰어로 몰래 넘기지 않는다 — 그러면 배포 누락(pdfjs 폴더)이 안 보인다.
                // 원인을 화면에 보여 주고, 급하면 [브라우저로 보기] 로 연다.
                if (window.console) console.error('[도면] pdf.js 로 열지 못함', PDFJS, err);
                var why = !global.pdfjsLib
                    ? '<div>확대 보기 모듈(pdf.js)을 불러오지 못했습니다.</div>'
                    + '<div style="font-size:14px;color:#cbd5e0;margin-top:6px">' + esc(pdfWhy || (err && err.message) || '') + '</div>'
                    + '<div style="font-size:13px;color:#a0aec0;margin-top:4px">서버의 ' + esc(url(PDFJS))
                    + ' 폴더(pdf.min.js · pdf.worker.min.js · cmaps · standard_fonts)를 확인하세요.</div>'
                    : '<div>PDF 를 열지 못했습니다.</div><div style="font-size:14px;color:#cbd5e0;margin-top:6px">'
                    + esc(err && err.message || '') + '</div>';
                message(pane, why + '<div><button data-raw style="margin-top:14px;height:44px;padding:0 18px;border:0;border-radius:10px;'
                    + 'background:#ffd166;color:#1f2a44;font-weight:800;font-size:15px;cursor:pointer;">브라우저로 보기</button></div>');
                var rb = pane.querySelector('[data-raw]');
                if (rb) rb.onclick = function () {
                    pane.innerHTML = '<iframe src="' + src + '#view=FitH" style="width:100%;height:100%;border:0;background:#fff"></iframe>';
                };
            });
        } else if (IMG_EXT.indexOf(ext) >= 0) {
            var img = new Image();
            img.onload = function () {
                if (!m.alive) return;
                pane.innerHTML = '';
                var v = ZoomView(pane, img.naturalWidth, img.naturalHeight, null);
                img.style.cssText = 'width:100%;height:100%;display:block;pointer-events:none;';
                img.draggable = false;
                v.content.appendChild(img);
                setView(v);
            };
            img.onerror = function () { if (m.alive) message(pane, '그림을 열지 못했습니다.'); };
            img.src = src;
        }
        return {
            destroy: function () {
                m.alive = false;
                if (m.pdf) { try { m.pdf.destroy(); } catch (e) { /* 무시 */ } }
                pane.innerHTML = '';
            },
            refit: function () { if (m.view) m.view.refit(); }
        };
    }

    function drawPdfPage(pane, m, setView) {
        m.pdf.getPage(m.page).then(function (page) {
            if (!m.alive) return;
            var vp1 = page.getViewport({scale: 1});
            pane.innerHTML = '';
            var canvas = document.createElement('canvas');
            canvas.style.cssText = 'width:100%;height:100%;display:block;';
            var rendering = null, renderedPx = 0, v = null;

            function render(z) {
                if (!m.alive || !v) return;
                var s = v.size();
                var dpr = window.devicePixelRatio || 1;
                // 지금 보이는 배율에 맞는 해상도. 긴 변은 MAX_PX 까지만
                var want = Math.min(MAX_PX, Math.max(s.w, s.h) * (z || s.z) * dpr);
                if (Math.abs(want - renderedPx) < 64 && renderedPx) return;
                var scale = want / Math.max(vp1.width, vp1.height);
                var vp = page.getViewport({scale: scale});
                var off = document.createElement('canvas');   // 다 그린 뒤 바꿔 끼운다 — 그리는 동안 깜빡임 없게
                off.width = Math.round(vp.width);
                off.height = Math.round(vp.height);
                if (rendering) { try { rendering.cancel(); } catch (e) { /* 무시 */ } }
                rendering = page.render({canvasContext: off.getContext('2d'), viewport: vp, background: '#ffffff'});
                rendering.promise.then(function () {
                    rendering = null;
                    if (!m.alive) return;
                    canvas.width = off.width;
                    canvas.height = off.height;
                    canvas.getContext('2d').drawImage(off, 0, 0);
                    renderedPx = want;
                }).catch(function () { /* 취소됨 */ });
            }

            v = ZoomView(pane, vp1.width, vp1.height, render);
            v.content.appendChild(canvas);
            setView(v);
            render(1);

            // 여러 쪽 PDF 면 쪽 넘김
            if (m.pdf.numPages > 1) {
                var nav = document.createElement('div');
                nav.setAttribute('data-zbtn', '1');
                nav.style.cssText = 'position:absolute;left:50%;transform:translateX(-50%);bottom:14px;display:flex;gap:8px;align-items:center;z-index:2;';
                var bc = 'width:64px;height:64px;border:0;border-radius:14px;background:rgba(31,42,68,.88);color:#fff;font-size:26px;cursor:pointer;';
                nav.innerHTML = '<button data-p="-1" style="' + bc + '">◀</button>'
                    + '<span style="color:#fff;font-weight:800;font-size:17px;background:rgba(31,42,68,.7);border-radius:10px;padding:10px 12px;">'
                    + m.page + ' / ' + m.pdf.numPages + '</span>'
                    + '<button data-p="1" style="' + bc + '">▶</button>';
                pane.appendChild(nav);
                Array.prototype.forEach.call(nav.querySelectorAll('[data-p]'), function (b) {
                    b.onclick = function () {
                        var np = m.page + (+b.getAttribute('data-p'));
                        if (np < 1 || np > m.pdf.numPages) return;
                        m.page = np;
                        drawPdfPage(pane, m, setView);
                    };
                });
            }
        });
    }

    /**
     * 다른 화면의 칸에 확대 보기를 붙인다 (생산 지시 도면 창).
     *   var h = IljinDrawing.mount(el, '/api/production/part_drawing/view?id=3', 'pdf');
     *   h.destroy();  // 다른 도면 · 창 닫기
     * 창 크기가 바뀌면 h.refit(). 마우스 휠 · 두 손가락 · [＋][－][맞춤] 그대로.
     */
    function mount(pane, src, ext) {
        ext = String(ext || '').replace('.', '').toLowerCase();
        if (!isViewable(ext)) return null;
        return mountFile(pane, src, ext, null);
    }

    function close() {
        if (!box) return;
        box.style.display = 'none';
        view = null;
        if (state && state.mount) state.mount.destroy();
        state = null;
    }

    function begin(st) {
        if (!box) build();
        if (state && state.mount) state.mount.destroy();   // 열린 채로 다시 열 때
        state = Object.assign({rows: [], items: null, remote: false, cur: null, seq: 0, mount: null}, st);
        var q = box.querySelector('[data-r=q]');
        q.value = '';
        box.querySelector('[data-r=title]').textContent = state.title || '도면';
        box.querySelector('[data-r=pane]').innerHTML = '';
        box.style.display = 'block';
        fit();
        renderScope();
    }

    /**
     * 품목 도면 보기.
     * @param {{sujuId:number, title:string, projNo?:string, focusId?:number, onEmpty?:function}} opt
     *   focusId: 처음 띄울 도면(첨부 id). 이전 리비전이면 같은 번호의 최신을 띄운다
     */
    function open(opt) {
        if (!opt || opt.sujuId == null) return;
        AjaxUtil.getAsyncData(API + '/list', {sujuId: opt.sujuId}, function (res) {
            var rows = latest(((res && res.data) || []).map(function (d) {
                return Object.assign({}, d, {suju_id: opt.sujuId});
            }));
            if (!rows.length && !opt.projNo) {
                if (typeof opt.onEmpty === 'function') opt.onEmpty();
                else if (global.Alert) Alert.alert('', '이 공정에 올린 도면이 없습니다.');
                return;
            }
            begin({title: opt.title || '', projNo: opt.projNo || '', items: rows, rows: rows});
            state.files = {};
            state.files[opt.sujuId] = (res && res.data) || [];   // [원본 열기] 짝 찾기용 (원본은 목록에서 빠져 있다)
            if (!rows.length) {               // 이 품목엔 없음 → 프로젝트 전체에서 찾게
                state.remote = true;
                renderScope();
                renderList('이 공정엔 도면이 없습니다. 도면번호를 입력해 프로젝트 전체에서 찾으세요.');
                box.querySelector('[data-r=q]').focus();
                return;
            }
            var first = rows[0];
            if (opt.focusId != null) {
                var hit = ((res && res.data) || []).filter(function (d) { return d.id === opt.focusId; })[0];
                if (hit) {
                    var no = parse(hit.file_name).no;
                    first = rows.filter(function (r) { return r._no === no; })[0] || first;
                }
            }
            show(first.id);
        });
    }

    /**
     * 도면번호로 찾기 (품목을 모를 때 — 레이저처럼).
     * @param {{projNo?:string, title?:string}} opt  projNo 없으면 진행 중 프로젝트 전체
     */
    function search(opt) {
        opt = opt || {};
        begin({title: opt.title || (opt.projNo ? opt.projNo : '전체 프로젝트'), projNo: opt.projNo || '', remote: true});
        if (opt.projNo) runQuery();          // 프로젝트가 정해졌으면 그 프로젝트 도면부터 보여 준다
        else renderList('도면번호를 입력하세요 (예: FD10-01-03, 0103)');
        box.querySelector('[data-r=q]').focus();
    }

    global.IljinDrawing = {open: open, search: search, close: close, parse: parse, openOriginal: openOriginal, openOriginals: openOriginals,
        findOriginals: findOriginals, origKind: origKind, hiddenOriginals: hiddenOriginals, mount: mount};
})(window);