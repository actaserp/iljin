/**
 * 일진 품목 도면 보기 (키오스크 · 현장 모니터용)
 *
 * 생산 지시 화면에서 올린 품목 도면(attach_file, TableName='suju_drawing')을 크게 띄운다.
 * 장갑 낀 손으로 누르는 화면이라 목록 줄과 버튼을 크게 둔다.
 *
 * [필요]  common.js 의 AjaxUtil, withCtx
 *   <script th:src="@{/js/iljin_drawing.js}"></script>
 *
 * [사용]
 *   IljinDrawing.open({sujuId: 123, title: 'FD10'});
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
    var box = null, state = null;

    function url(u) { return (typeof withCtx === 'function') ? withCtx(u) : u; }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

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

    /** 도면번호별 최신 1건. 번호 순 (조립도 -00-00 이 먼저) */
    function latest(rows) {
        var map = {};
        rows.forEach(function (d) {
            var p = parse(d.file_name);
            var cur = map[p.no];
            var r = p.rev == null ? -1 : p.rev;
            if (!cur || r > cur._r || (r === cur._r && d.id > cur.id)) {
                map[p.no] = Object.assign({}, d, {_no: p.no, _rev: p.rev, _r: r});
            }
        });
        return Object.keys(map).map(function (k) { return map[k]; })
            .sort(function (a, b) { return a._no.localeCompare(b._no, 'ko', {numeric: true}); });
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
    }

    function build() {
        box = document.createElement('div');
        box.id = 'iljinDrawing';
        box.style.cssText = 'display:none;position:fixed;left:0;top:0;right:0;z-index:10000;'
            + 'background:rgba(0,0,0,.6);padding:10px;box-sizing:border-box;';
        box.innerHTML = ''
            + '<div style="background:#fff;border-radius:12px;height:100%;display:flex;flex-direction:column;overflow:hidden;">'
            + '  <div style="display:flex;align-items:center;gap:12px;padding:10px 14px;background:#1f2a44;color:#fff;">'
            + '    <b data-r="title" style="font-size:18px;flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;"></b>'
            + '    <button data-r="close" style="flex:none;min-width:110px;height:52px;border:0;border-radius:10px;'
            + '            background:#e74c3c;color:#fff;font-size:18px;font-weight:800;cursor:pointer;">닫기 ✕</button>'
            + '  </div>'
            + '  <div style="flex:1;display:flex;min-height:0;">'
            + '    <div data-r="list" style="width:260px;flex:none;overflow:auto;border-right:1px solid #e2e8f0;background:#f7f9fc;"></div>'
            + '    <div data-r="pane" style="flex:1;min-width:0;background:#525659;display:flex;align-items:center;justify-content:center;"></div>'
            + '  </div>'
            + '</div>';
        document.body.appendChild(box);
        box.querySelector('[data-r=close]').onclick = close;
        window.addEventListener('resize', function () { if (box.style.display !== 'none') fit(); });
        document.addEventListener('fullscreenchange', function () { if (box.style.display !== 'none') fit(); });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && box.style.display !== 'none') close();
        });
    }

    function renderList() {
        var list = box.querySelector('[data-r=list]');
        // 도면이 하나뿐이면 목록은 자리만 차지한다 — 숨기고 도면을 넓게
        list.style.display = state.rows.length > 1 ? '' : 'none';
        list.innerHTML = state.rows.map(function (d) {
            var on = d.id === state.cur;
            return '<div data-id="' + d.id + '" style="padding:14px 14px;border-bottom:1px solid #e2e8f0;cursor:pointer;'
                + 'background:' + (on ? '#dbe7ff' : 'transparent') + ';border-left:5px solid ' + (on ? '#1f4e8c' : 'transparent') + ';">'
                + '<div style="font-size:17px;font-weight:800;color:#1f2a44;">' + esc(d._no) + '</div>'
                + '<div style="font-size:13px;color:#4a5568;margin-top:2px;">'
                + (d._rev != null ? '<b style="color:#1f4e8c">REV' + d._rev + '</b> · ' : '')
                + esc(d.created || '') + '</div></div>';
        }).join('');
        Array.prototype.forEach.call(list.querySelectorAll('[data-id]'), function (n) {
            n.onclick = function () { show(+n.getAttribute('data-id')); };
        });
    }

    function show(id) {
        var d = state.rows.filter(function (x) { return x.id === id; })[0];
        if (!d) return;
        state.cur = id;
        box.querySelector('[data-r=title]').textContent =
            state.title + ' · ' + d._no + (d._rev != null ? ' REV' + d._rev : '');

        var ext = String(d.ext || '').replace('.', '').toLowerCase();
        var src = url(API + '/view?id=' + id);
        var pane = box.querySelector('[data-r=pane]');
        if (ext === 'pdf') {
            // #view=FitH : 폭에 맞춰 연다 (작은 글씨가 많은 조립도라 처음부터 크게)
            pane.innerHTML = '<iframe src="' + src + '#view=FitH" style="width:100%;height:100%;border:0;background:#fff"></iframe>';
        } else if (['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp'].indexOf(ext) >= 0) {
            pane.innerHTML = '<img src="' + src + '" alt="" style="max-width:100%;max-height:100%;object-fit:contain;background:#fff">';
        } else {
            pane.innerHTML = '<div style="color:#fff;font-size:18px;text-align:center;line-height:1.7;">'
                + esc(ext.toUpperCase() || '이') + ' 파일은 화면에서 볼 수 없습니다.<br>'
                + '생산 지시 화면에서 내려받으세요.</div>';
        }
        renderList();
    }

    function close() {
        if (!box) return;
        box.style.display = 'none';
        box.querySelector('[data-r=pane]').innerHTML = '';
    }

    /**
     * @param {{sujuId:number, title:string, onEmpty?:function}} opt
     */
    function open(opt) {
        if (!opt || opt.sujuId == null) return;
        if (!box) build();
        AjaxUtil.getAsyncData(API + '/list', {sujuId: opt.sujuId}, function (res) {
            var rows = latest((res && res.data) || []);
            if (!rows.length) {
                if (typeof opt.onEmpty === 'function') opt.onEmpty();
                else if (global.Alert) Alert.alert('', '이 공정에 올린 도면이 없습니다.');
                return;
            }
            state = {rows: rows, title: opt.title || '', cur: null};
            box.style.display = 'block';
            fit();
            show(rows[0].id);
        });
    }

    global.IljinDrawing = {open: open, close: close, parse: parse};
})(window);