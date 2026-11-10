/**
 * 일진 라벨 출력 (Zebra ZD421T · 유포 100×50mm)
 *
 * 입고 라벨(수불전표등록)과 지그 라벨(검사 공정)이 같은 양식·같은 크기를 쓴다.
 * 프린터가 1대라 롤을 갈아 끼우지 않게 하려는 것.
 *
 * [필요]  QR 라이브러리를 먼저 불러야 한다.
 *   <script th:src="@{/resource/qrCode/qrcode.min.js}"></script>
 *   <script th:src="@{/js/iljin_label.js}"></script>
 *
 * [사용]
 *   IljinLabel.print([{
 *       qr:    'LOT 번호',            // QR 에 들어갈 값 = PDA 가 읽는 값. 반드시 LOT 번호 그대로
 *       title: 'HD20',               // 크게 찍을 한 줄 (품목)
 *       sub:   'HOOD',               // 제목 옆 작은 글씨 (라인 등). 생략 가능
 *       rows:  [['프로젝트', '2026-001'], ['발주처', '대우공업']],   // 최대 4줄 권장
 *       seq:   '1/2'                 // 오른쪽 아래. 생략 가능
 *   }]);
 *
 * [인쇄]  숨은 iframe 으로 인쇄한다. window.open 은 비동기 콜백(저장 후 확인창 등)
 *   안에서 부르면 팝업 차단에 걸린다.
 *   용지 크기는 @page 로 100×50mm 를 지정하지만, Zebra 드라이버의 용지 크기도
 *   100×50mm 로 맞춰 두어야 한 장씩 정확히 끊긴다 (드라이버 설정이 우선하는 브라우저가 있다).
 */
(function (global) {
    'use strict';

    var W = 100, H = 50;   // mm

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /** QR → data URL. 라이브러리가 그린 canvas 를 그대로 쓴다 */
    function qrDataUrl(text) {
        if (typeof QRCode === 'undefined') {
            throw new Error('QRCode 라이브러리가 없습니다 (/resource/qrCode/qrcode.min.js)');
        }
        var box = document.createElement('div');
        box.style.cssText = 'position:absolute;left:-9999px;top:-9999px;';
        document.body.appendChild(box);
        try {
            /* 인쇄 해상도(203dpi)에서 28mm ≈ 224dot. 넉넉히 그려서 축소한다.
               오류 보정은 M — 철판에 붙어 긁혀도 읽히되, 코드가 너무 촘촘해지지 않게 */
            new QRCode(box, {
                text: String(text), width: 256, height: 256,
                colorDark: '#000000', colorLight: '#ffffff',
                correctLevel: QRCode.CorrectLevel.M
            });
            var cv = box.querySelector('canvas');
            if (cv) return cv.toDataURL('image/png');
            var img = box.querySelector('img');
            return img ? img.src : '';
        } finally {
            box.parentNode.removeChild(box);
        }
    }

    /* 품목명 길이에 따라 글자 크기를 줄인다. 구매품은 'CLAMP CYL(SMC) CKG1A50-100YAZ' 처럼
       길고, 잘리면 라벨의 핵심 정보가 사라진다. 긴 이름은 두 줄까지 감는다 */
    function titleSize(t) {
        var n = String(t || '').length;
        return n <= 10 ? 't-l' : (n <= 18 ? 't-m' : 't-s');
    }

    function labelHtml(l) {
        var rows = (l.rows || []).slice(0, 5).map(function (r) {
            return '<tr><th>' + esc(r[0]) + '</th><td>' + esc(r[1]) + '</td></tr>';
        }).join('');

        return ''
            + '<div class="lb">'
            + '  <div class="top">'
            + '    <img class="qr" src="' + qrDataUrl(l.qr) + '" alt="">'
            + '    <div class="info">'
            + '      <div class="ttl ' + titleSize(l.title) + '">' + esc(l.title)
            + (l.sub ? ' <span class="sub">' + esc(l.sub) + '</span>' : '') + '</div>'
            + '      <table>' + rows + '</table>'
            + '    </div>'
            + '  </div>'
            + '  <div class="bot">'
            + '    <span class="code">' + esc(l.qr) + '</span>'
            + (l.seq ? '<span class="seq">' + esc(l.seq) + '</span>' : '')
            + '  </div>'
            + '</div>';
    }

    var CSS = ''
        + '@page { size: ' + W + 'mm ' + H + 'mm; margin: 0; }'
        + '* { box-sizing: border-box; margin: 0; padding: 0; }'
        + 'html, body { width: ' + W + 'mm; background: #fff; color: #000;'
        + '  font-family: "Malgun Gothic", "맑은 고딕", "Apple SD Gothic Neo", sans-serif; }'
        + '.lb { width: ' + W + 'mm; height: ' + H + 'mm; padding: 2.5mm 3mm 2mm;'
        + '  display: flex; flex-direction: column; overflow: hidden;'
        + '  page-break-after: always; break-after: page; }'
        + '.lb:last-child { page-break-after: auto; break-after: auto; }'
        + '.top { flex: 1; display: flex; gap: 3mm; min-height: 0; }'
        + '.qr { width: 30mm; height: 30mm; flex: none; image-rendering: pixelated; }'
        + '.info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1.2mm; }'
        + '.ttl { font-weight: 800; line-height: 1.15; overflow: hidden; word-break: break-all;'
        + '  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }'
        + '.t-l { font-size: 6mm; } .t-m { font-size: 4.6mm; } .t-s { font-size: 3.8mm; }'
        + '.sub { font-size: 3.4mm; font-weight: 700; }'
        + 'table { border-collapse: collapse; width: 100%; table-layout: fixed; }'
        + 'th, td { font-size: 3mm; line-height: 1.35; text-align: left; vertical-align: top;'
        + '  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }'
        + 'th { width: 13mm; font-weight: 600; color: #333; }'
        + 'td { font-weight: 700; }'
        + '.bot { display: flex; justify-content: space-between; align-items: flex-end;'
        + '  border-top: 0.3mm solid #000; padding-top: 1mm; margin-top: 1mm; }'
        + '.code { font-family: Consolas, "Courier New", monospace; font-size: 3mm; font-weight: 700;'
        + '  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }'
        + '.seq { font-size: 3.6mm; font-weight: 800; margin-left: 2mm; }';

    /** 인쇄할 문서 전체 HTML. 미리보기·점검용으로도 쓴다 */
    function build(labels) {
        labels = (labels || []).filter(function (l) { return l && l.qr; });
        if (!labels.length) return '';
        return '<!doctype html><html><head><meta charset="utf-8"><title>라벨</title>'
            + '<style>' + CSS + '</style></head><body>'
            + labels.map(labelHtml).join('') + '</body></html>';
    }

    function print(labels) {
        var html = build(labels);
        if (!html) return false;

        var frame = document.createElement('iframe');
        frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
        document.body.appendChild(frame);

        var doc = frame.contentWindow.document;
        doc.open();
        doc.write(html);
        doc.close();

        // QR 이미지가 그려진 뒤에 인쇄한다
        var done = false;
        function go() {
            if (done) return;
            done = true;
            frame.contentWindow.focus();
            frame.contentWindow.print();
            setTimeout(function () {
                if (frame.parentNode) frame.parentNode.removeChild(frame);
            }, 60000);
        }
        frame.onload = go;
        setTimeout(go, 400);
        return true;
    }

    global.IljinLabel = {print: print, build: build, qrDataUrl: qrDataUrl};
})(window);