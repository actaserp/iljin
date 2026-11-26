/* ============================================================
 * edit-table-custom.js — EditTable 공통 설정 (ax5commUtil.js 의 Ax5Grid.init · gridBuilder 기본 설정 자리)
 * layout_page 에서 edit-table.js 바로 뒤에 불러온다. (일진 MES, 2026-11-24)
 *
 * 우선순위 (뒤가 이긴다)
 *   1) 이 파일의 EditTable.setDefaults(...)          — 모든 화면 공통
 *   2) 화면 <script> 맨 위의 EditTable.setDefaults(...) — 그 화면의 표에만 (표를 만들기 전에 부를 것)
 *   3) new EditTable('#grid', {...}) 에 준 옵션          — 그 표 하나만
 *
 * 화면에서 쓰는 예)
 *   EditTable.setDefaults({rowNumbers: true, rowHeight: 30, column: {align: 'center'}});
 *   EditTable.formatter['strUnit'] = function (v, row) { return v === 'EA' ? '개' : v; };
 *   new EditTable('#theGrid', {columns: [{key: 'use_yn', header: '사용', formatter: 'strBool'}]});
 * ============================================================ */
(function () {
    'use strict';
    if (!window.EditTable) return;

    /* ── 공통 기본값 (모든 표) ──
     *  지금 화면들이 쓰는 모양과 같게 둔다. 바꾸면 모든 표에 바로 반영된다.
     *   sortable · resizable · emptyText · headerHeight · rowHeight · className · column(모든 칸 공통값) · contextMenu …
     */
    EditTable.setDefaults({
        sortable: true,                 // 머리글 눌러 정렬
        resizable: true,                // 칸 너비 손잡이
        emptyText: '자료가 없습니다.',
        settings: true,                 // 머리글 오른쪽 위 ⚙ 표 설정 버튼 (false 면 숨김, 표마다 settings:false 로도 끔)
        rowHeight: 24,                  // 행 높이 (px) 기본 24 (2026-11-25, 20 → 24). 화면 · 표에서 rowHeight 로 바꿀 수 있음
        reselectOnCellClick: true       // 일진 : 위즈모처럼 지금 행의 다른 칸을 눌러도 선택 이벤트를 다시 냄 (검색 뒤 첫 행을 눌러 아래 표를 불러오는 화면들)
        // headerHeight: 34,            // 머리글 높이 (px)
        // column: {align: 'left'}      // 모든 칸 공통값 (칸에 적은 값이 이긴다)
    });

    /* ── 공통 포맷터 (ax5commUtil.js 의 ax5.ui.grid.formatter 와 같은 이름) ──
     *  칸에 format: '이름' 또는 formatter: '이름'. 함수는 (값, 행) 을 받고, this.value · this.item 도 된다.
     *  함수에 .align = 'right' 를 붙이면 그 칸의 기본 정렬이 오른쪽.
     */
    /* ── 알림 · 확인 창 : 일진은 alert / confirm 대신 Alert (ax5commUtil.js) ── */
    EditTable.alert = function (msg) { if (window.Alert && Alert.alert) Alert.alert('', msg); else alert(msg); };
    EditTable.confirm = function (msg, ok) {
        if (window.Alert && Alert.confirmAsync) Alert.confirmAsync('', msg).then(function (r) { if (r) ok(); });
        else if (confirm(msg)) ok();
    };

    const F = EditTable.formatter;
    F.strAttend = function (v) { return v === 'Y' ? '참석' : ''; };
    F.strManagerYn = function (v) { return v === 'Y' ? '관리자' : '일반'; };
    F.strBool = function (v) { return v === 'Y' ? '사용' : '미사용'; };
    F.strObjType = function (v) { return v === 'M' ? '메뉴' : (v === 'S' ? '서브화면' : (v === 'A' ? 'Ajax' : 'TAB')); };
    F.capital = function (v) { return v == null ? '' : String(v).toUpperCase(); };
    // 자주 쓰는 것 추가
    F.yn = function (v) { return (v === true || v === 1 || v === '1' || v === 'Y' || v === 'y') ? 'Y' : ((v == null || v === '') ? '' : 'N'); };
    F.date = function (v) { return v == null ? '' : String(v).slice(0, 10); };              // 'YYYY-MM-DD hh:mm' → 'YYYY-MM-DD'
    F.datetime = function (v) { return v == null ? '' : String(v).replace('T', ' ').slice(0, 16); };
    F.bizNo = function (v) { const s = String(v == null ? '' : v).replace(/\D/g, ''); return s.length === 10 ? s.replace(/(\d{3})(\d{2})(\d{5})/, '$1-$2-$3') : (v == null ? '' : v); };
    F.percent = Object.assign(function (v) { return (v == null || v === '' || isNaN(Number(v))) ? (v == null ? '' : v) : Number(v).toLocaleString('ko-KR', {maximumFractionDigits: 1}) + '%'; }, {align: 'right'});
})();
