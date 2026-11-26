/* ============================================================
 * EditTable v1.8 (2026-11-24)
 *  일진 : EditTable.alert · EditTable.confirm 자리 추가 · hitTest(e) · getColumn(key) · EditTable.getControl(el) · opts.onEditEnd · opts.reselectOnCellClick · dispose() (위즈모 호환) (alert/confirm 대신 프로젝트 공통 창, edit-table-custom.js 에서 지정)
 *  v1.8 : 표 설정을 서버(et_table_setting)에 저장 — 어느 PC · 어느 사용자에게나 같은 설정 (회사 공통 하나)
 *         슈퍼유저(auth_user.is_superuser) : 머리글 · 키 · 형식 · 칸 추가 · 삭제까지 / 그 밖 : 머리글 · 보이기 · 순서 · 너비 · 정렬 · 집계 · 행 높이 · 줄 번호 · 고정 칸 · 묶기
 *         EditTable.settingUrl = null 이면 예전처럼 이 브라우저(localStorage)에만
 *  v1.7 : 표 설정 모달 — 머리글 오른쪽 위 ⚙ 버튼 (또는 오른쪽 클릭 › 표 설정 · grid.openSettings())
 *         칸 보이기/숨기기 · 순서 · 너비 · 정렬 · 집계(합계 · 평균 · 개수 · 최대 · 최소)
 *         · 행 높이 · 줄 번호 · 고정 칸 수 · 묶기(그룹, 묶음 줄에 소계)
 *         이 브라우저에 화면 · 표마다 저장 (localStorage 'et.cfg.' + 주소 + 표 id, opts.saveKey 가 있으면 그걸로)
 *         opts.settings:false 면 버튼 없음. getSettings() · resetSettings()
 *  v1.6 : 공통 기본값 · 화면별 덮어쓰기 (ax5commUtil.js 의 gridBuilder 기본 설정과 같은 구조)
 *         EditTable.defaults            — 모든 표의 기본 옵션 (공통 설정 파일 edit-table-custom.js 에서 정함)
 *         EditTable.setDefaults({...})  — 깊은 병합. 화면 <script> 맨 위에서 부르면 그 화면의 표에만 적용
 *         new EditTable(el, {...})      — 표마다 준 옵션이 맨 마지막으로 이긴다
 *         EditTable.formatter['이름'] = function (v, row) { ... }   (ax5 처럼 this.value · this.item 도 된다)
 *           → 칸에 format: '이름' 또는 formatter: '이름' (ax5 이름 그대로)
 *         opts.headerHeight · opts.rowHeight (px, ax5 header/body.columnHeight) · opts.className · opts.column (모든 칸 공통값)
 *  v1.5 : moveRow(row, ±1) · 위즈모 이름 호환 hostElement · selectionChanged.addHandler
 *  v1.4 : 트리 표 (opts.childItemsPath : 자식 배열 필드, 예 'items') · expandAll · collapseAll · collapseToLevel(n)
 *         · opts.treeColumn : ▾ ▸ 를 붙일 칸 (기본 첫 칸) · 칸 onClick · jQuery 요소도 받음
 *         · moveRow(row, -1|+1) 순서 옮기기 · hostElement (= el, 위즈모 이름)
 *  v1.3 : 칸 고정(frozenColumns) · 버튼 칸(type:'button', buttonText, onClick) · render(HTML) · refreshCell
 *  v1.2 : 행 체크(rowCheck · getChecked · onCheck) · 행 번호(rowNumbers) · 그룹(groupBy · collapseAll) · cellClass · rowClass
 *         · 날짜 칸(type:'date') · onClick · onBeginEdit · selectFirst:false · updateColumn · getPlain · 합계 함수(sum: fn) · footerLabelKey
 *  v1.1 : 표 전체 보기전용(readOnly) · 숨김 칸(visible:false) · 숫자 형식(format:'n0') · 셀 병합(merge)
 *         · 합계 줄(sum) · 행 더블클릭(onDblClick) · 오른쪽 클릭 메뉴 · .xlsx 내보내기 · selectById
 * edit-table.js — 공통 편집 표 (위즈모 FlexGrid 대체, 라이브러리 없음)
 * 짝 CSS : edit-table.css (+ 화면 틀 ms-page.css)
 *
 * 사용 예)
 *   const grid = new EditTable('#acGrid', {
 *     columns: [
 *       {key:'acccd', header:'계정코드', width:80,  readOnly:true, align:'center'},
 *       {key:'accnm', header:'계정명칭', width:'1.4*', maxLength:100},
 *       {key:'drcr',  header:'차대', width:80, type:'select', options:[{v:'1',n:'차변'},{v:'2',n:'대변'}]},
 *       {key:'spyn',  header:'전표', width:60, type:'check'},
 *       {key:'useyn', header:'사용여부', width:170, type:'radio', options:[{v:'1',n:'예'},{v:'0',n:'아니오'}]},
 *       {key:'seq',   header:'순서', width:80, type:'number'}
 *     ],
 *     onChange: (row, key, value, grid) => {},   // 칸을 고친 직후
 *     onSelect: (row, grid) => {},               // 현재 행이 바뀐 직후
 *     countEl : '#acCount',                       // 건수 표시할 곳 (선택)
 *     saveKey : 'acc',                            // 주면 칸 너비 · 쪽당 행 수를 브라우저에 기억 (선택)
 *     pageSize: 50                                // 주면 페이징 (선택, 아래 '페이징' 참고)
 *   });
 *   grid.setData(list);      grid.getChanged();     grid.addRow({...}, {after: grid.current()});
 *   grid.removeRow(row);     grid.setFilter(fn);    grid.exportCsv('파일명');
 *
 * 칸 속성
 *   key, header, width(px 숫자 또는 '1*' 비율), align('left'|'center'|'right')
 *   type     : 'text'(기본) | 'number' | 'check' | 'select' | 'radio'
 *   readOnly : true 또는 함수(row) → 보기 전용 (예: 새 행만 코드 입력 → row => !row._new)
 *   options  : select / radio 선택지 [{v, n}]
 *   on, off  : check 의 값 (기본 '1' / '0')
 *   maxLength, placeholder, wrap(true 면 긴 글자 줄바꿈), format(값→표시글자, 보기전용 칸)
 *   sortable : false 면 그 칸은 정렬 안 함
 *   resizable: false 면 그 칸은 너비 조절 안 함 (표 전체는 opts.resizable)
 *   minWidth : 줄일 수 있는 최소 너비 (기본 40)
 *   visible  : false 면 그 칸은 안 보이고 내보내기에도 안 나감
 *   format   : 함수(값, 행) 또는 'n0' · 'n2' (천 단위 쉼표 + 소수 자리, 오른쪽 정렬이 기본)
 *   merge    : true 면 위 행과 값이 같을 때 한 칸처럼 보임 (왼쪽 병합 칸이 이어질 때만)
 *   sum      : true 면 아래 합계 줄에 그 칸 합계 (글자는 opts.footerLabel, 기본 '합계')
 *
 * 표 옵션 (v1.1 추가)
 *   readOnly : true 면 모든 칸 보기 전용
 *   onDblClick(row, grid) : 행을 두 번 눌렀을 때
 *   contextMenu : false 면 오른쪽 클릭 메뉴(엑셀 · CSV · 정렬 풀기 · 칸 너비) 끔
 *   fileName : 내려받을 파일 이름 (확장자 빼고)
 *   footerLabel : 합계 줄 글자 (기본 '합계'), footerLabelKey : 그 글자를 넣을 칸 (기본 합계 아닌 첫 칸)
 *
 * 표 옵션 (v1.2 추가)
 *   rowCheck : true 면 맨 앞에 행 체크 칸 (머리글 체크 = 모두). getChecked() · clearChecked() · onCheck(체크된 행들, 바뀐 행)
 *   rowNumbers : true 면 맨 앞에 행 번호 칸
 *   selectFirst : false 면 setData 뒤 아무 행도 고르지 않음 (기본은 첫 행)
 *   onClick(row) : 행을 누를 때마다 (이미 고른 행을 다시 눌러도). onSelect 는 고른 행이 바뀔 때만
 *   onBeginEdit(row, key) : 고칠 수 있는 칸에 들어갈 때
 *   rowClass(row) : 행 tr 에 붙일 class
 *   groupBy : 묶을 필드. groupText(값, 행들) · groupClass(값, 행들). collapseAll() · expandAll() · setGroup(key)
 * 칸 속성 (v1.2 추가)
 *   type: 'date' (YYYY-MM-DD), cellClass : 글자 또는 함수(row) (기본 제공 : et-hl 노란 입력칸 · et-red · et-mute · et-bold)
 *   sum : 함수(보이는 행들) 이면 그 결과를 합계 칸에 (숫자면 format 적용)
 * v1.3
 *   opts.frozenColumns : 왼쪽에서 n칸 고정 (행 체크 · 행 번호 칸은 따로 세지 않음)
 *   type:'button' : buttonText (글자 또는 함수(row) → null 이면 noButtonText 글자), onClick(row, grid)
 *   render(값, row) : 그 칸에 HTML 을 그린다 (format 은 글자 · 내보내기용)
 *   refreshCell(row, key) : 한 칸만 다시 그리기
 *   칸 onClick(row, grid, e) : 버튼 칸이 아니어도 그 칸을 누르면 불린다
 *
 * 행 상태 : 새 행은 row._new = true, 고친 행은 row._dirty = true (getChanged 결과에는 빠진다)
 * ============================================================ */
(function (global) {
 'use strict';

 const esc = v => String(v == null ? '' : v).replace(/[&<>"']/g,
  c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
 let seq = 0;

 class EditTable {
  constructor(el, opts) {
   if (el && el.jquery) el = el[0];   // jQuery 객체도 받는다 ($content.find('#grid'))
   this.el = typeof el === 'string' ? document.querySelector(el) : el;
   if (!this.el) throw new Error('EditTable: 표를 넣을 요소가 없습니다. ' + el);
   this.id = 'et' + (++seq);
   this.opts = deepMerge(deepMerge({}, EditTable.defaults), opts || {});
   if (this.opts.childItemsPath) this.opts.sortable = false;   // 트리 표는 정렬하지 않는다
   this.columns = [];
   this.data = [];          // 원본 순서
   this.view = [];          // 필터 · 정렬 뒤 전체 행
   this.pv = [];            // 지금 페이지에 그려진 행 (페이징 안 하면 view 와 같다)
   this.page = 1;
   this.total = 0;          // 서버 페이징일 때 전체 건수
   this.sorts = [];         // [{key, dir: 1|-1}]
   this.filter = null;
   this.cur = null;         // 현재 행 (자료 객체)

   this.el.classList.add('et');
   this.el.editTable = this;   // EditTable.getControl(요소 · 선택자) 로 다시 찾기 (위즈모 Control.getControl 대신)
   this._opt0 = {rowHeight: this.opts.rowHeight, rowNumbers: this.opts.rowNumbers, frozenColumns: this.opts.frozenColumns, groupBy: this.opts.groupBy};
   // 표 설정 키 : 화면 주소 + '#' + 표 id (서버 et_table_setting.setting_key). 서버를 못 쓰면 이 브라우저(localStorage 'et.cfg.' + 키)
   this._srvKey = location.pathname + '#' + (this.opts.saveKey || this.el.id || ('n' + [...document.querySelectorAll('.et')].indexOf(this.el)));
   this._cfgKey = 'et.cfg.' + this._srvKey;
   this._cfg = this._loadCfg();
   if (!EditTable._srv.ready) EditTable._srvWait(() => {   // 서버 설정이 늦게 오면 받은 뒤 다시 그린다
    const c = this._loadCfg();
    if (JSON.stringify(c) !== JSON.stringify(this._cfg) || this._mine().rowHeight || this._mine().fontSize) { this._cfg = c; this._applyCfg(); }
   });
   this._cfgOpts();
   if (this.opts.className) String(this.opts.className).split(/\s+/).filter(Boolean).forEach(c => this.el.classList.add(c));
   if (this.opts.headerHeight) this.el.style.setProperty('--et-head-h', this.opts.headerHeight + 'px');
   if (this.opts.rowHeight) this.el.style.setProperty('--et-row-h', this.opts.rowHeight + 'px');
   this.el.tabIndex = this.el.tabIndex >= 0 ? this.el.tabIndex : 0;
   this.el.innerHTML = (this.opts.settings === false ? '' : '<div class="et-cfg-dock"></div>')
    + '<table class="et-t"><colgroup></colgroup><thead><tr></tr></thead><tbody></tbody><tfoot></tfoot></table>';
   this._dock = this.el.querySelector('.et-cfg-dock');
   if (this._dock) {
    // ⚙ 는 표 바깥 오른쪽 위 (표 바로 앞에 크기 0 인 자리를 두고 띄움). 그 자리에 다른 버튼 · 글자가 있으면 머리글 안으로
    const prev = this.el.previousElementSibling;
    if (prev && prev.classList.contains('et-cfg-bar')) prev.remove();   // 같은 자리에 표를 다시 만든 경우
    this._bar = document.createElement('div');
    this._bar.className = 'et-cfg-bar';
    if (this.el.parentNode) this.el.parentNode.insertBefore(this._bar, this.el);
    const cb = this._cfgBtn = document.createElement('button');
    cb.type = 'button'; cb.className = 'et-cfg-btn'; cb.title = '표 설정'; cb.innerHTML = '&#9881;';
    cb.addEventListener('mousedown', e => e.stopPropagation());
    cb.addEventListener('click', e => { e.preventDefault(); e.stopPropagation(); this.openSettings(); });
    this._bar.appendChild(cb);
    setTimeout(() => this._placeCfg(), 400);   // 팝업 여는 애니메이션(0.3초) 뒤 겹침 다시 확인
   }
   this.table = this.el.querySelector('table');
   this.colgroup = this.table.querySelector('colgroup');
   this.headRow = this.table.querySelector('thead tr');
   this.tbody = this.table.querySelector('tbody');
   this.tfoot = this.table.querySelector('tfoot');
   this._mg = null;         // 병합 표시 [{cont:Set, next:Set}] (pv 행마다)

   this._bind();
   this._initPager();
   this.setColumns(this.opts.columns || []);
   if ('ResizeObserver' in global) {
    this._ro = new ResizeObserver(() => this._sizeCols());
    this._ro.observe(this.el);
   } else {
    global.addEventListener('resize', () => this._sizeCols());
   }
  }

  /* ───────── 칸 ───────── */
  setColumns(cols) {
   const saved = this._loadWidths();
   this._defs = cols;
   const eff = this._cfgCols(cols);
   const pre = [];
   if (this.opts.rowCheck) pre.push({key: '_chk', header: '', type: '_rowcheck', width: 34, align: 'center', sortable: false, resizable: false});
   if (this.opts.rowNumbers) pre.push({key: '_no', header: '', type: '_rownum', width: 44, align: 'center', sortable: false, resizable: false});
   this.columns = pre.concat(eff.filter(c => c.visible !== false)).map(c => {
    if (c.formatter != null && c.format == null) c = Object.assign({}, c, {format: c.formatter});   // ax5 이름 그대로 써도 된다
    const fmtRight = typeof c.format === 'string' && (/^n\d+$/.test(c.format) || (EditTable.formatter[c.format] && EditTable.formatter[c.format].align === 'right'));
    const al = c.type === 'check' ? 'center' : (c.type === 'number' || fmtRight) ? 'right' : 'left';
    const o = Object.assign({type: 'text', align: al, sortable: true}, this._isSys(c) ? {} : (this.opts.column || {}), c);
    if (c.align == null && al !== 'left') o.align = al;   // 숫자 · 체크 칸 정렬은 공통값보다 앞선다
    o._w0 = c._dw !== undefined ? c._dw : c.width;    // 처음 너비 (두 번 누르면 되돌림)
    if (saved && typeof saved[c.key] === 'number') o.width = saved[c.key];
    return o;
   });
   this.sorts = this.sorts.filter(s => this.columns.some(c => c.key === s.key));
   this._renderHead();
   this._renderBody(true);
  }

  /** 칸 하나 바꾸기 : updateColumn('need_qty', {header: '투입수량'}) · updateColumn('bom_ratio', {visible: false}) */
  updateColumn(key, props) {
   const d = (this._defs || []).find(c => c.key === key);
   if (!d) return;
   Object.assign(d, props);
   this.setColumns(this._defs);
  }
  _isSys(c) { return c.type === '_rowcheck' || c.type === '_rownum'; }
  _exportCols(cols) { return (cols || this.columns).filter(c => !this._isSys(c) && c.type !== 'button'); }

  _isRO(col, row) {
   if (this.opts.readOnly) return true;
   return typeof col.readOnly === 'function' ? !!col.readOnly(row) : !!col.readOnly;
  }

  /** 보이는 글자 : format 이 함수면 그 결과, 'n0' · 'n2' 같은 글자면 천 단위 쉼표 + 소수 자리 */
  _fmt(c, v, r) {
   if (typeof c.format === 'function') return c.format(v, r);
   if (typeof c.format === 'string' && EditTable.formatter[c.format]) {
    const f = EditTable.formatter[c.format];
    return f.call({value: v, item: r, key: c.key, column: c}, v, r);
   }
   const m = typeof c.format === 'string' && /^n(\d+)$/.exec(c.format);
   if (m) {
    if (v == null || v === '' || isNaN(Number(v))) return v == null ? '' : v;
    const d = +m[1];
    return Number(v).toLocaleString('ko-KR', {minimumFractionDigits: d, maximumFractionDigits: d});
   }
   return v;
  }

  // 선택 · 라디오 · 체크 칸은 이름으로, 그 밖은 형식대로 (인쇄 · 내보내기용)
  _text(c, r) {
   const v = r[c.key];
   if (c.type === 'check') return String(v) === String(c.on == null ? '1' : c.on) || v === true ? '예' : '';
   if (c.options) { const o = c.options.find(o => String(o.v) === String(v)); return o ? o.n : (v == null ? '' : v); }
   if (c.type === 'date' && v != null && /^\d{4}-\d{2}-\d{2}/.test(String(v))) return String(v).slice(0, 10);
   const t = this._fmt(c, v, r);
   return t == null ? '' : t;
  }

  _renderHead() {
   this.colgroup.innerHTML = this.columns.map(() => '<col>').join('');
   this.headRow.innerHTML = this.columns.map((c, i) => {
    const s = this.sorts.findIndex(x => x.key === c.key);
    const mark = s < 0 ? '' : '<span class="et-sort">' + (this.sorts[s].dir > 0 ? '▲' : '▼') + (this.sorts.length > 1 ? (s + 1) : '') + '</span>';
    if (c.type === '_rowcheck') {
     const all = this.view && this.view.length && this.view.every(r => r._chk);
     return '<th data-c="' + i + '" class="et-chk"><input type="checkbox" class="et-native et-chkall" title="모두 선택"' + (all ? ' checked' : '') + '/></th>';
    }
    if (c.type === '_rownum') return '<th data-c="' + i + '"></th>';
    const cls = [(this.opts.readOnly || c.readOnly === true ? '' : 'et-edit'), (this.opts.sortable && c.sortable ? 'et-sortable' : '')].join(' ');
    const rz = this.opts.resizable && c.resizable !== false ? '<span class="et-rz" title="끌어서 칸 너비 조절"></span>' : '';
    return '<th data-c="' + i + '" class="' + cls + '" title="' + esc(c.header) + '">' + esc(c.header) + mark + rz + '</th>';
   }).join('');
   this._sizeCols();
  }

  // 숫자 폭은 그대로, '1*' 같은 비율 폭은 남는 폭을 나눠 갖는다
  _sizeCols() {
   if (this._dock) { const w = this.el.clientWidth; this._dock.style.width = w + 'px'; this._dock.style.marginRight = (-w) + 'px'; }   // ⚙ 자리 (flex 부모여도 자리 차지 안 함)
   this._placeCfg();
   const cols = this.colgroup.children;
   if (!cols.length) return;
   const total = this.el.clientWidth || 0;
   let fixed = 0, stars = 0, sum = 0;
   this.columns.forEach(c => {
    if (typeof c.width === 'number') fixed += c.width;
    else stars += parseFloat(String(c.width || '1*')) || 1;
   });
   const rest = Math.max(0, total - fixed - 2);
   this.columns.forEach((c, i) => {
    const w = typeof c.width === 'number' ? c.width
     : Math.max(c.minWidth || 60, Math.floor(rest * ((parseFloat(String(c.width || '1*')) || 1) / (stars || 1))));
    cols[i].style.width = w + 'px';
    sum += w;
   });
   // 칸 합이 화면보다 넓으면 표를 그만큼 넓혀 가로 스크롤, 아니면 화면 폭
   this.table.style.width = sum > total ? sum + 'px' : '100%';
   this._freeze();
  }

  /* ───────── 칸 고정 (opts.frozenColumns = 왼쪽에서 몇 칸) ─────────
   * 가로로 넘겨도 왼쪽 칸이 남는다. 행 체크 · 행 번호 칸은 따로 세지 않고 같이 고정된다. */
  _freeze() {
   const n = +this.opts.frozenColumns || 0;
   const fc = n ? this.columns.filter(c => this._isSys(c)).length + n : 0;
   if (!fc) { if (this._fz) this._fz.textContent = ''; return; }
   if (!this._fz) { this._fz = document.createElement('style'); this.el.appendChild(this._fz); this.el.setAttribute('data-etid', this.id); }
   const sel = '[data-etid="' + this.id + '"] > table > * > tr > ';
   let left = 0, css = '';
   const cols = this.colgroup.children;
   for (let i = 0; i < Math.min(fc, cols.length); i++) {
    const k = ':nth-child(' + (i + 1) + ')';
    css += sel + 'td' + k + '{position:sticky;left:' + left + 'px;z-index:1}'
     + sel + 'th' + k + '{left:' + left + 'px;z-index:4}';
    left += parseFloat(cols[i].style.width) || 0;
   }
   const last = ':nth-child(' + Math.min(fc, cols.length) + ')';
   css += sel + 'td' + last + ',' + sel + 'th' + last + '{box-shadow:inset -2px 0 0 rgba(27,36,48,.18)}'
    + '[data-etid="' + this.id + '"] > table > tfoot > tr > td:nth-child(-n+' + fc + '){z-index:3}';
   this._fz.textContent = css;
  }

  /* ───────── 칸 너비 조절 (머리글 오른쪽 끝을 끌기 · 두 번 누르면 처음 너비) ───────── */
  _startResize(e, ci) {
   e.preventDefault(); e.stopPropagation();
   const col = this.columns[ci];
   const startX = e.clientX;
   const startW = this.colgroup.children[ci].getBoundingClientRect().width;
   const min = col.minWidth || 40;
   this.el.classList.add('et-resizing');
   const move = ev => {
    col.width = Math.max(min, Math.round(startW + ev.clientX - startX));
    this._sizeCols();
   };
   const up = () => {
    document.removeEventListener('mousemove', move);
    document.removeEventListener('mouseup', up);
    this.el.classList.remove('et-resizing');
    this._saveWidths();
   };
   document.addEventListener('mousemove', move);
   document.addEventListener('mouseup', up);
  }

  /** 칸 너비를 처음 정의대로 되돌림 (저장된 너비도 지움) */
  resetWidths() {
   this.columns.forEach(c => { c.width = c._w0; });
   try { if (this.opts.saveKey) localStorage.removeItem('et.w.' + this.opts.saveKey); } catch (e) { /* 저장소 못 씀 */ }
   this._sizeCols();
  }

  // saveKey 를 주면 이 브라우저에 칸 너비를 기억한다 (사용자별 · 브라우저별)
  _saveWidths() {
   if (!this.opts.saveKey) return;
   const o = {};
   this.columns.forEach(c => { if (typeof c.width === 'number' && c.width !== c._w0) o[c.key] = c.width; });
   try { localStorage.setItem('et.w.' + this.opts.saveKey, JSON.stringify(o)); } catch (e) { /* 저장소 못 씀 */ }
  }
  _loadWidths() {
   if (!this.opts.saveKey) return null;
   try { return JSON.parse(localStorage.getItem('et.w.' + this.opts.saveKey) || 'null'); } catch (e) { return null; }
  }

  /* ───────── 자료 ───────── */
  /**
   * 자료 넣기
   *  - 일반 : setData(list)  → 1쪽으로
   *  - 서버 페이징 : setData(그 쪽 자료, {total: 전체건수, page: 쪽번호})
   */
  setData(list, info) {
   const prev = this.cur;
   this.data = this.opts.childItemsPath ? this._flatTree(list || []) : (list || []).slice();
   this.sorts = [];
   this._collapsed = new Set();
   this._tclosed = new Set();
   this.cur = this.opts.selectFirst === false ? null : (this.data[0] || null);
   this._curCol = -1;   // 자료를 새로 넣으면 아직 누른 칸 없음 (reselectOnCellClick)
   if (this.opts.serverPaging) {
    this.total = info && info.total != null ? +info.total : this.data.length;
    this.page = info && info.page ? +info.page : 1;
   } else if (!info || !info.keepPage) {
    this.page = 1;
   }
   this._renderHead();
   this._renderBody();
   // 위즈모처럼 자료를 넣어 고른 행이 바뀌면 onSelect 를 부른다 (첫 행 · 또는 null)
   if (this.cur !== prev) this._fireSelect();
  }
  getData() { return this.data; }
  /* ───────── 트리 (childItemsPath) ─────────
   * 자식 배열을 앞 순서대로 한 줄씩 펼친다. 행마다 _lvl(깊이) · _par(부모 행) · _kids(자식 수) 를 붙인다.
   * 접은 부모(_tclosed)의 자손은 그리지 않는다 (view 에는 남아 내보내기 · 검색은 전체). */
  _flatTree(list) {
   const p = this.opts.childItemsPath, out = [];
   const walk = (arr, lvl, par) => (arr || []).forEach(n => {
    const k = n[p];
    n._lvl = lvl; n._par = par; n._kids = Array.isArray(k) ? k.length : 0;
    out.push(n);
    if (n._kids) walk(k, lvl + 1, n);
   });
   walk(list, 0, null);
   return out;
  }
  _treeHidden(r) { for (let q = r._par; q; q = q._par) if (this._tclosed.has(q)) return true; return false; }
  _treeCol() {
   const k = this.opts.treeColumn;
   const i = k ? this.columns.findIndex(c => c.key === k) : this.columns.findIndex(c => !this._isSys(c));
   return i < 0 ? 0 : i;
  }
  toggleNode(row) { if (!row || !row._kids) return; if (this._tclosed.has(row)) this._tclosed.delete(row); else this._tclosed.add(row); this._renderBody(true); }
  /** 트리 : n 단계부터 접는다 (0 = 모두 접기, 99 = 모두 펼치기) */
  collapseToLevel(n) { this._tclosed = new Set(this.data.filter(r => r._kids && r._lvl >= n)); this._renderBody(true); }
  /** '_' 로 시작하는 화면용 필드를 뺀 복사본 (서버로 보낼 때) */
  getPlain(list) {
   return (list || this.data).map(r => { const o = {}; Object.keys(r).forEach(k => { if (k.charAt(0) !== '_') o[k] = r[k]; }); return o; });
  }
  /** 위즈모에서 옮길 때 편하도록 : grid.selectedItems = [현재 행], grid.itemsSource = 배열 */
  get selectedItems() { return this.cur ? [this.cur] : []; }
  get hostElement() { return this.el; }
  /** 고른 행이 바뀌면 opts.onSelect(row, grid) 와 selectionChanged 처리기들(grid, {row}) 을 부른다 */
  _fireSelect() {
   if (this.opts.onSelect) this.opts.onSelect(this.cur, this);
   (this._selH || []).forEach(f => f(this, {row: this.cur}));
  }
  /** 위즈모처럼 grid.selectionChanged.addHandler(fn) — fn(grid) 안에서는 grid.current() 로 고른 행 */
  get selectionChanged() {
   const g = this;
   return {addHandler: f => { (g._selH = g._selH || []).push(f); }, removeHandler: f => { g._selH = (g._selH || []).filter(x => x !== f); }};
  }   // 위즈모처럼 grid.hostElement.addEventListener(...) 가 되도록
  set itemsSource(v) { this.setData(Array.isArray(v) ? v : (v && v.sourceCollection) || []); }
  /** 위즈모 hitTest 대신 (일진, 2026-11-24) : 마우스 이벤트가 난 칸.
   *  {row: 지금 쪽 행 번호(getPageRows 기준, 없으면 -1), col: 칸 번호(없으면 -1), item: 행 자료, key: 칸 key,
   *   cellType: 'cell' | 'header' | 'footer' | 'group' | 'none'} */
  hitTest(e) {
   const t = e && e.target && e.target.closest ? e.target : null;
   const r = {row: -1, col: -1, item: null, key: null, cellType: 'none'};
   if (!t || !this.el.contains(t)) return r;
   const cell = t.closest('td[data-c], th[data-c]');
   if (cell) { r.col = +cell.dataset.c; r.key = (this.columns[r.col] || {}).key || null; }
   if (t.closest('thead')) { r.cellType = 'header'; return r; }
   if (t.closest('tfoot')) { r.cellType = 'footer'; return r; }
   if (t.closest('tr.et-grp')) { r.cellType = 'group'; return r; }
   const item = this._rowOf(t);
   if (item) { r.item = item; r.row = this.pv.indexOf(item); r.cellType = 'cell'; }
   return r;
  }
  /** 숨겨 두었던 표가 보일 때 칸 너비 다시 계산 */
  invalidate() { this._sizeCols(); }

  /* ───────── 행 체크 (opts.rowCheck) ───────── */
  getChecked() { return this.view.filter(r => r._chk); }
  clearChecked() { this.data.forEach(r => { delete r._chk; }); this._syncChecks(); }
  setChecked(rows, on) { (rows || []).forEach(r => { if (on === false) delete r._chk; else r._chk = true; }); this._syncChecks(); }
  _syncChecks() {
   this.tbody.querySelectorAll('input[data-k="_chk"]').forEach(inp => {
    const r = this._rowOf(inp); inp.checked = !!(r && r._chk);
    const tr = inp.closest('tr'); if (tr && r) tr.className = this._rowCls(r);
   });
   const all = this.headRow.querySelector('.et-chkall');
   if (all) all.checked = !!(this.view.length && this.view.every(r => r._chk));
  }
  _fireCheck(row) { if (this.opts.onCheck) this.opts.onCheck(this.getChecked(), row || null, this); }

  /* ───────── 그룹 (opts.groupBy = 필드 이름) ─────────
   * 같은 값끼리 묶어 머리 줄을 넣는다. 머리 줄을 누르면 접고 펼친다.
   * opts.groupText(값, 행들) 로 머리 글자, opts.groupClass(값, 행들) 로 머리 줄 class */
  setGroup(key) { this.opts.groupBy = key || null; this._collapsed = new Set(); this._renderBody(); }
  collapseAll() { if (this.opts.childItemsPath) return this.collapseToLevel(0); this._collapsed = new Set((this._groups || []).map(g => g.key)); this._renderBody(true); }
  expandAll() { this._tclosed = new Set(); this._collapsed = new Set(); this._renderBody(true); }
  toggleGroup(key) { if (this._collapsed.has(key)) this._collapsed.delete(key); else this._collapsed.add(key); this._renderBody(true); }
  getView() { return this.view; }        // 필터 · 정렬 뒤 전체 행 (인쇄 · 엑셀용)
  getPageRows() { return this.pv; }      // 지금 쪽에 보이는 행
  current() { return this.cur; }
  getChanged() {
   return this.data.filter(r => r._new || r._dirty).map(r => {
    const o = {};
    Object.keys(r).forEach(k => { if (k.charAt(0) !== '_') o[k] = r[k]; });
    if (r._new) o._new = '1';
    return o;
   });
  }
  hasChanges() { return this.data.some(r => r._new || r._dirty); }
  markClean() { this.data.forEach(r => { delete r._new; delete r._dirty; }); this._renderBody(); }

  /** 새 행 : opts.after 가 있으면 그 행 바로 아래, 없으면 맨 아래 */
  addRow(row, opts) {
   row = Object.assign({}, row, {_new: true});
   const after = opts && opts.after;
   const at = after ? this.data.indexOf(after) : -1;
   if (at >= 0) this.data.splice(at + 1, 0, row); else this.data.push(row);
   this.sorts = [];            // 새 행이 놓인 자리에 보이도록 정렬을 푼다
   this._renderHead();
   this.cur = row;
   this._renderBody(true);
   if (opts && opts.focus === false) this.select(row); else this.focusCell(row, opts && opts.focus);   // focus:false 면 커서는 그대로
   return row;
  }

  removeRow(row) {
   const i = this.data.indexOf(row);
   if (i < 0) return;
   const vi = this.view.indexOf(row);
   this.data.splice(i, 1);
   this._renderBody();
   if (this.cur === row) this.select(this.view[Math.min(vi, this.view.length - 1)] || null);
  }

  /** 값 넣기 (코드에서) — 화면도 바뀐다 */
  /** 행 순서 옮기기 (GridUtil.changeOrder 대체) : d = -1 위로, +1 아래로. 끝이라 못 옮기면 false */
  moveRow(row, d) {
   const i = this.data.indexOf(row), j = i + d;
   if (i < 0 || j < 0 || j >= this.data.length) return false;
   this.data.splice(i, 1); this.data.splice(j, 0, row);
   this.sorts = []; this._renderHead(); this._renderBody(true); this.select(row);
   return true;
  }
  setValue(row, key, value, markDirty) {
   row[key] = value;
   if (markDirty !== false && !row._new) row._dirty = true;
   this.refreshRow(row);
  }

  setFilter(fn) { this.filter = fn || null; this.page = 1; this._renderBody(); }
  refresh() { this._renderBody(true); }

  /* ───────── 정렬 ───────── */
  sortBy(key, multi) {
   if (this.opts.childItemsPath) return;
   const s = this.sorts.find(x => x.key === key);
   if (multi) {
    if (!s) this.sorts.push({key, dir: 1});
    else if (s.dir > 0) s.dir = -1;
    else this.sorts = this.sorts.filter(x => x !== s);
   } else {
    this.sorts = [{key, dir: s && this.sorts.length === 1 && s.dir > 0 ? -1 : 1}];
   }
   this.page = 1;          // 정렬을 바꾸면 첫 쪽부터
   this._renderHead();
   this._renderBody();
  }
  clearSort() { this.sorts = []; this.page = 1; this._renderHead(); this._renderBody(); }

  _cmp(a, b) {
   for (const s of this.sorts) {
    let x = a[s.key], y = b[s.key];
    x = x == null ? '' : x; y = y == null ? '' : y;
    const nx = Number(x), ny = Number(y);
    const r = (x !== '' && y !== '' && !isNaN(nx) && !isNaN(ny)) ? nx - ny : String(x).localeCompare(String(y), 'ko');
    if (r) return r * s.dir;
   }
   return 0;
  }

  /* ───────── 그리기 ───────── */
  _renderBody(keepScroll) {
   const st = this.el.scrollTop;
   let v = this.filter ? this.data.filter(this.filter) : this.data.slice();
   const tree = !!this.opts.childItemsPath;
   if (this.sorts.length && !tree) v = v.map((r, i) => [r, i]).sort((p, q) => this._cmp(p[0], q[0]) || p[1] - q[1]).map(p => p[0]);
   this._groups = null;
   if (this.opts.groupBy) {   // 처음 나온 순서대로 묶는다
    const gk = this.opts.groupBy, map = new Map();
    v.forEach(r => { const k = r[gk] == null ? '' : String(r[gk]); if (!map.has(k)) map.set(k, []); map.get(k).push(r); });
    this._groups = [...map].map(([key, rows]) => ({key, rows}));
    v = [].concat(...this._groups.map(g => g.rows));
    if (!this._collapsed) this._collapsed = new Set();
   }
   this.view = v;
   // 페이징 : 서버 페이징이면 받은 자료가 곧 한 쪽, 아니면 여기서 자른다
   const size = this._size();
   if (size && !this.opts.serverPaging && !tree) {
    const pages = Math.max(1, Math.ceil(v.length / size));
    this.page = Math.min(Math.max(1, this.page), pages);
    this.pv = v.slice((this.page - 1) * size, this.page * size);
   } else {
    this.pv = tree ? v.filter(r => !this._treeHidden(r)) : this._groups ? v.filter(r => !this._collapsed.has(String(r[this.opts.groupBy] == null ? '' : r[this.opts.groupBy]))) : v;
   }
   this._calcMerge();
   if (this._groups && this._groups.length) {
    let i = 0, html = '';
    this._groups.forEach((g, gi) => {
     const closed = this._collapsed.has(g.key);
     const txt = this.opts.groupText ? this.opts.groupText(g.key, g.rows) : g.key;
     const gc = this.opts.groupClass ? (this.opts.groupClass(g.key, g.rows) || '') : '';
     const head = '<span class="et-grp-t">' + (closed ? '▸' : '▾') + '</span> ' + esc(txt) + ' <span class="et-grp-n">(' + g.rows.length + ')</span>';
     const fa = this.columns.findIndex(c => c.sum);   // 집계 칸이 있으면 묶음 줄에 소계
     if (fa > 0) {
      html += '<tr class="et-grp ' + gc + '" data-g="' + gi + '"><td colspan="' + fa + '">' + head + '</td>' + this.columns.slice(fa).map(c => {
       if (!c.sum) return '<td></td>';
       const v = this._agg(c, g.rows);
       return '<td class="et-grp-s et-' + (c.align === 'center' ? 'c' : c.align === 'left' ? 'l' : 'r') + '">' + esc(typeof v === 'number' ? this._fmt(c, v, {}) : v) + '</td>';
      }).join('') + '</tr>';
     } else {
      html += '<tr class="et-grp ' + gc + '" data-g="' + gi + '"><td colspan="' + this.columns.length + '">' + head + '</td></tr>';
     }
     if (!closed) g.rows.forEach(r => { html += this._rowHtml(r, i++); });
    });
    this.tbody.innerHTML = html;
   } else if (!this.pv.length) {
    this.tbody.innerHTML = '<tr class="et-empty"><td colspan="' + (this.columns.length || 1) + '">' + esc(this.opts.emptyText) + '</td></tr>';
   } else {
    this.tbody.innerHTML = this.pv.map((r, i) => this._rowHtml(r, i)).join('');
   }
   if (this.cur && v.indexOf(this.cur) < 0) this.cur = null;
   this.el.scrollTop = keepScroll ? st : 0;
   const all = this.headRow.querySelector('.et-chkall');
   if (all) all.checked = !!(v.length && v.every(r => r._chk));
   this._renderFoot();
   this._count();
   this._renderPager();
  }

  /* ───────── 셀 병합 (merge: true 칸) ─────────
   * 위 행과 값이 같으면 글자를 숨기고 사이 괘선을 지워 한 칸처럼 보인다.
   * 왼쪽에 병합 칸이 있으면 그 칸도 이어질 때만 이어진다 (제품그룹 → 제품코드 → 제품명).
   * 행 구조는 그대로라 행 선택 · 정렬 · 내보내기는 행마다 동작한다. */
  _calcMerge() {
   const mcols = [];
   this.columns.forEach((c, ci) => { if (c.merge) mcols.push(ci); });
   if (!mcols.length || this.pv.length < 2) { this._mg = null; return; }
   const mg = this.pv.map(() => ({cont: new Set(), next: new Set()}));
   for (let i = 1; i < this.pv.length; i++) {
    let prev = true;
    for (const ci of mcols) {
     const k = this.columns[ci].key;
     const a = this.pv[i][k], b = this.pv[i - 1][k];
     const same = prev && a != null && a !== '' && String(a) === String(b);
     if (same) { mg[i].cont.add(ci); mg[i - 1].next.add(ci); }
     prev = same;
    }
   }
   this._mg = mg;
  }

  /* ───────── 합계 줄 (sum: true 칸) ─────────
   * 필터 · 정렬 뒤 전체 행의 합. 글자는 opts.footerLabel (기본 '합계') 를 첫 칸에 */
  _hasFoot() { return this.columns.some(c => c.sum); }
  // sum: true 면 합, 함수면 그 결과 (예: 통화가 하나일 때만 외화 합계)
  _sums(cols, rows) {
   const o = {};
   (cols || this.columns).forEach(c => { if (c.sum) o[c.key] = this._agg(c, rows || this.view); });
   return o;
  }
  // 집계 : sum true|'sum' 합 · 'avg' 평균 · 'count' 개수 · 'max' · 'min' · 함수(rows)
  _agg(c, rows) {
   const a = c.sum;
   if (typeof a === 'function') return a(rows);
   if (a === 'count') return rows.filter(r => r[c.key] != null && r[c.key] !== '').length;
   const ns = rows.map(r => r[c.key]).filter(v => v != null && v !== '' && !isNaN(Number(v))).map(Number);
   if (a === 'avg') return ns.length ? Math.round(ns.reduce((x, y) => x + y, 0) / ns.length * 100) / 100 : '';
   if (a === 'max') return ns.length ? Math.max(...ns) : '';
   if (a === 'min') return ns.length ? Math.min(...ns) : '';
   return ns.reduce((x, y) => x + y, 0);
  }
  _labelCol(cols) {
   const k = this.opts.footerLabelKey;
   const i = k ? cols.findIndex(c => c.key === k) : -1;
   return i >= 0 ? i : cols.findIndex(c => !this._isSys(c) && !c.sum);
  }
  _renderFoot() {
   if (!this._hasFoot()) { this.tfoot.innerHTML = ''; return; }
   const s = this._sums();
   const label = this.opts.footerLabel == null ? (this.columns.some(c => c.sum && c.sum !== true && c.sum !== 'sum' && typeof c.sum !== 'function') ? '집계' : '합계') : this.opts.footerLabel;
   const li = this._labelCol(this.columns);
   this.tfoot.innerHTML = '<tr>' + this.columns.map((c, i) => {
    const al = 'et-' + (c.align === 'center' ? 'c' : c.align === 'right' ? 'r' : 'l');
    if (c.sum) { const v = s[c.key]; return '<td class="' + al + '">' + esc(typeof v === 'number' ? this._fmt(c, v, s) : (v == null ? '' : v)) + '</td>'; }
    return '<td class="et-c">' + (i === li ? esc(label) : '') + '</td>';
   }).join('') + '</tr>';
  }

  _rowCls(r) {
   return (r === this.cur ? 'et-sel ' : '') + (r._new ? 'et-new ' : '') + (!r._new && r._dirty ? 'et-dirty ' : '') + (r._chk ? 'et-checked ' : '')
    + (this.opts.rowClass ? (this.opts.rowClass(r) || '') : '');
  }

  _rowHtml(r, i) {
   const tc = this.opts.childItemsPath ? this._treeCol() : -1;
   return '<tr data-r="' + i + '" class="' + this._rowCls(r) + '">' + this.columns.map((c, ci) => {
    const h = this._cellHtml(c, r, ci, i);
    if (ci !== tc) return h;
    // 트리 칸 : 깊이만큼 들여 쓰고, 자식이 있으면 ▾ (펼침) / ▸ (접힘)
    const t = '<span class="et-tree-i" style="width:' + ((r._lvl || 0) * 16) + 'px"></span>'
     + (r._kids ? '<span class="et-tree-t">' + (this._tclosed.has(r) ? '▸' : '▾') + '</span>' : '<span class="et-tree-t et-tree-leaf"></span>');
    return h.replace(/^(<td[^>]*>)/, '$1' + t);
   }).join('') + '</tr>';
  }

  _cellHtml(c, r, ci, ri) {
   const val = r[c.key];
   const al = 'et-' + (c.align === 'center' ? 'c' : c.align === 'right' ? 'r' : 'l');
   const at = ' data-c="' + ci + '"';
   // cellClass : 글자 또는 함수(row) → 그 칸 td 에 붙는 class (예: 'et-hl' 노란 입력칸, 'et-red' 빨간 글자)
   const xc = c.cellClass ? ' ' + ((typeof c.cellClass === 'function' ? c.cellClass(r) : c.cellClass) || '') : '';

   if (c.type === '_rowcheck') {
    return '<td class="et-chk et-rowchk"' + at + '><input type="checkbox" class="et-native" data-k="_chk"' + (r._chk ? ' checked' : '') + '/></td>';
   }
   if (c.type === '_rownum') {
    const size = this._size(), off = size && !this.opts.serverPaging ? (this.page - 1) * size : 0;
    return '<td class="et-ro et-c et-no"' + at + '>' + (off + ri + 1) + '</td>';
   }
   // 버튼 칸 : buttonText (글자 또는 함수(row), null 이면 noButtonText 글자), onClick(row, grid)
   if (c.type === 'button') {
    const t = typeof c.buttonText === 'function' ? c.buttonText(r) : (c.buttonText || c.header || '');
    if (t == null) return '<td class="et-ro et-c' + xc + '"' + at + '>' + esc(c.noButtonText || '') + '</td>';
    return '<td class="et-btncell' + xc + '"' + at + '><button type="button" class="et-btn">' + esc(t) + '</button></td>';
   }
   // render(값, 행) : 화면에서 만든 HTML 을 그대로 넣는다 (글자는 format 으로, 내보내기도 format)
   if (c.render) {
    return '<td class="et-ro ' + al + xc + '"' + at + '>' + c.render(val, r) + '</td>';
   }
   const ro = this._isRO(c, r);
   if (c.type === 'check') {
    const on = c.on == null ? '1' : c.on;
    const chk = String(val) === String(on) || val === true;
    return '<td class="et-chk' + xc + '"' + at + '><input type="checkbox" class="et-native" data-k="' + esc(c.key) + '"'
     + (chk ? ' checked' : '') + (ro ? ' disabled' : '') + '/></td>';
   }
   if (c.type === 'radio') {
    const name = this.id + '_' + ri + '_' + ci;
    return '<td class="et-radio' + xc + '"' + at + '>' + (c.options || []).map(o =>
     '<label><input type="radio" class="et-native" name="' + name + '" data-k="' + esc(c.key) + '" value="' + esc(o.v) + '"'
     + (String(val) === String(o.v) ? ' checked' : '') + (ro ? ' disabled' : '') + '/>' + esc(o.n) + '</label>').join('') + '</td>';
   }
   const day = v => { const t = v == null ? '' : String(v); return /^\d{4}-\d{2}-\d{2}/.test(t) ? t.slice(0, 10) : t; };
   if (ro) {
    let txt = val;
    if (c.format) txt = this._fmt(c, val, r);
    else if (c.type === 'select') { const o = (c.options || []).find(o => String(o.v) === String(val)); txt = o ? o.n : val; }
    else if (c.type === 'date') txt = day(val);
    const m = this._mg && this._mg[ri];
    const mc = m ? (m.cont.has(ci) ? ' et-mgc' : '') + (m.next.has(ci) ? ' et-mgn' : '') : '';
    return '<td class="et-ro ' + al + (c.wrap ? ' et-wrap' : '') + mc + xc + '"' + at + ' title="' + esc(txt) + '">' + esc(txt) + '</td>';
   }
   if (c.type === 'select') {
    const has = (c.options || []).some(o => String(o.v) === String(val));
    return '<td class="' + xc.trim() + '"' + at + '><select class="et-sel ' + al + '" data-k="' + esc(c.key) + '">'
     + (has ? '' : '<option value=""' + (val == null || val === '' ? ' selected' : '') + '></option>')
     + (c.options || []).map(o => '<option value="' + esc(o.v) + '"' + (String(val) === String(o.v) ? ' selected' : '') + '>' + esc(o.n) + '</option>').join('')
     + '</select></td>';
   }
   if (c.type === 'date') {
    return '<td class="' + xc.trim() + '"' + at + '><input type="date" class="et-in et-date ' + al + '" data-k="' + esc(c.key) + '" value="' + esc(day(val)) + '"/></td>';
   }
   return '<td class="' + xc.trim() + '"' + at + '><input type="text" class="et-in ' + al + '" data-k="' + esc(c.key) + '"'
    + (c.type === 'number' ? ' inputmode="decimal"' : '')
    + (c.maxLength ? ' maxlength="' + c.maxLength + '"' : '')
    + (c.placeholder ? ' placeholder="' + esc(c.placeholder) + '"' : '')
    + ' value="' + esc(val) + '"/></td>';
  }

  /** 한 칸만 다시 그리기 : 입력 중인 칸은 그대로 두고 계산된 칸(예: 차이)만 바꿀 때 */
  refreshCell(row, key) {
   const i = this.pv.indexOf(row), ci = this.columns.findIndex(c => c.key === key);
   if (i < 0 || ci < 0) return;
   const td = this.tbody.querySelector('tr[data-r="' + i + '"] > td[data-c="' + ci + '"]');
   if (td) td.outerHTML = this._cellHtml(this.columns[ci], row, ci, i);
   if (this._hasFoot()) this._renderFoot();
  }

  /** 한 행만 다시 그리기 (다른 칸 값이 바뀌었을 때) */
  refreshRow(row) {
   const i = this.pv.indexOf(row);
   if (i < 0) return;
   const tr = this.tbody.querySelector('tr[data-r="' + i + '"]');
   if (!tr) return;
   const focusKey = document.activeElement && tr.contains(document.activeElement) ? document.activeElement.dataset.k : null;
   tr.className = this._rowCls(row);
   tr.innerHTML = this.columns.map((c, ci) => this._cellHtml(c, row, ci, i)).join('');
   if (focusKey) { const e = tr.querySelector('[data-k="' + focusKey + '"]'); if (e) e.focus(); }
  }

  _count() {
   if (!this.opts.countEl) return;
   const el = typeof this.opts.countEl === 'string' ? document.querySelector(this.opts.countEl) : this.opts.countEl;
   if (el) el.textContent = '전체 ' + this._totalCount().toLocaleString() + '건';
  }

  _totalCount() { return this.opts.serverPaging ? this.total : this.view.length; }

  /* ───────── 페이징 ─────────
   * opts.pageSize : 한 쪽 행 수 (0 또는 없음 = 페이징 안 함)
   * opts.pageSizes: 고를 수 있는 행 수 (기본 [20, 50, 100, 200, 0], 0 = 전체)
   * opts.pager    : 쪽 번호를 넣을 곳 (선택자/요소). 없으면 표 바로 아래에 만든다
   * opts.serverPaging + opts.onPage(page, size) : 쪽을 바꿀 때마다 서버에서 그 쪽만 받아 setData(list, {total, page})
   */
  _size() {
   return this.opts.pageSize ? +this.opts.pageSize : 0;
  }

  _initPager() {
   const o = this.opts;
   if (!o.pageSize && !o.serverPaging) { this.pagerEl = null; return; }
   if (o.saveKey) {   // 고른 행 수 기억
    try { const ps = localStorage.getItem('et.ps.' + o.saveKey); if (ps != null && ps !== '') o.pageSize = +ps; } catch (e) { /* 저장소 못 씀 */ }
   }
   let el = o.pager ? (typeof o.pager === 'string' ? document.querySelector(o.pager) : o.pager) : null;
   if (!el) {
    el = document.createElement('div');
    this.el.parentNode.insertBefore(el, this.el.nextSibling);
   }
   el.classList.add('et-pager');
   this.pagerEl = el;
   el.addEventListener('click', e => {
    const b = e.target.closest('button[data-p]');
    if (b && !b.disabled) this.goPage(+b.dataset.p);
   });
   el.addEventListener('change', e => {
    if (e.target.classList.contains('et-ps')) this.setPageSize(+e.target.value);
   });
  }

  _renderPager() {
   const el = this.pagerEl;
   if (!el) return;
   const size = this._size(), total = this._totalCount();
   const pages = size ? Math.max(1, Math.ceil(total / size)) : 1;
   const p = Math.min(this.page, pages);
   const sizes = this.opts.pageSizes || [20, 50, 100, 200, 0];
   // 쪽 번호는 10개씩 묶어서
   const from = Math.floor((p - 1) / 10) * 10 + 1, to = Math.min(pages, from + 9);
   const btn = (n, label, dis, on, title) => '<button type="button" data-p="' + n + '"' + (dis ? ' disabled' : '')
    + (on ? ' class="on"' : '') + (title ? ' title="' + title + '"' : '') + '>' + label + '</button>';
   let nums = '';
   for (let n = from; n <= to; n++) nums += btn(n, n, false, n === p);
   el.innerHTML = '<div class="et-pg">'
    + btn(1, '«', p <= 1, false, '처음') + btn(p - 1, '‹', p <= 1, false, '이전')
    + nums
    + btn(p + 1, '›', p >= pages, false, '다음') + btn(pages, '»', p >= pages, false, '마지막')
    + '</div><div class="et-pg-r"><span class="et-pg-info">' + p + ' / ' + pages + ' 쪽</span>'
    + '<select class="et-ps" title="한 쪽에 보일 행 수">'
    + sizes.map(n => '<option value="' + n + '"' + (n === size ? ' selected' : '') + '>' + (n ? n + '개씩' : '전체') + '</option>').join('')
    + '</select></div>';
  }

  goPage(n) {
   const size = this._size(), total = this._totalCount();
   const pages = size ? Math.max(1, Math.ceil(total / size)) : 1;
   n = Math.min(Math.max(1, n), pages);
   if (this.opts.serverPaging) {
    if (this.opts.onPage) this.opts.onPage(n, size, this);   // 페이지에서 서버 조회 → setData(list, {total, page: n})
    return;
   }
   if (n === this.page) return;
   this.page = n;
   this.cur = null;
   this._renderBody();
  }

  setPageSize(n) {
   this.opts.pageSize = n;
   try { if (this.opts.saveKey) localStorage.setItem('et.ps.' + this.opts.saveKey, String(n)); } catch (e) { /* 저장소 못 씀 */ }
   if (this.opts.serverPaging) { if (this.opts.onPage) this.opts.onPage(1, n, this); return; }
   const keep = this.cur;
   this.page = 1;
   if (keep) { this._ensurePage(keep, true); } else this._renderBody();
  }

  /** 그 행이 있는 쪽으로 넘어간다 (클라이언트 페이징) */
  _ensurePage(row, force) {
   const size = this._size();
   if (!size || this.opts.serverPaging || !row) { if (force) this._renderBody(); return; }
   const i = this.view.indexOf(row);
   if (i < 0) { if (force) this._renderBody(); return; }
   const p = Math.floor(i / size) + 1;
   if (p !== this.page || force) { this.page = p; this._renderBody(); }
  }

  /* ───────── 선택 · 포커스 ───────── */
  select(row, scroll) {
   const prev = this.cur;
   if (row && this.pv.indexOf(row) < 0) this._ensurePage(row);   // 다른 쪽에 있는 행이면 그 쪽으로
   this.cur = row || null;
   [prev, this.cur].forEach(r => {
    const i = this.pv.indexOf(r);
    const tr = i < 0 ? null : this.tbody.querySelector('tr[data-r="' + i + '"]');
    if (tr) tr.className = this._rowCls(r);
   });
   if (scroll !== false && this.cur) {
    const tr = this.tbody.querySelector('tr[data-r="' + this.pv.indexOf(this.cur) + '"]');
    if (tr) tr.scrollIntoView({block: 'nearest'});
   }
   if (prev !== this.cur) this._fireSelect();
  }

  /** 그 행으로 가서 key 칸(없으면 처음 고칠 수 있는 칸)에 커서 */
  focusCell(row, key) {
   this.select(row);
   const i = this.pv.indexOf(row);
   const tr = i < 0 ? null : this.tbody.querySelector('tr[data-r="' + i + '"]');
   if (!tr) return;
   const e = key ? tr.querySelector('[data-k="' + key + '"]:not(:disabled)') : tr.querySelector('input:not(:disabled),select:not(:disabled)');
   if (e) e.focus();
  }

  findRow(fn) { return this.data.find(fn) || null; }

  /** 그 id(기본 필드 'id')를 가진 행을 고른다. 없으면 아무것도 안 함. 고른 행을 돌려준다 */
  selectById(id, field) {
   if (id == null || id === '') return null;
   const f = field || 'id';
   const row = this.findRow(r => String(r[f]) === String(id));
   if (row && this.view.indexOf(row) >= 0) this.select(row);
   return row;
  }

  /* ───────── 이벤트 ───────── */
  _rowOf(node) {
   const tr = node.closest && node.closest('tr[data-r]');
   return tr && this.tbody.contains(tr) ? this.pv[+tr.dataset.r] : null;
  }

  _commit(input) {
   const row = this._rowOf(input);
   if (!row) return;
   const col = this.columns.find(c => c.key === input.dataset.k);
   if (!col) return;
   if (col.type === '_rowcheck') {
    if (input.checked) row._chk = true; else delete row._chk;
    const tr = input.closest('tr'); if (tr) tr.className = this._rowCls(row);
    const all = this.headRow.querySelector('.et-chkall');
    if (all) all.checked = !!(this.view.length && this.view.every(r => r._chk));
    this._fireCheck(row);
    return;
   }
   let v;
   if (input.type === 'checkbox') v = input.checked ? (col.on == null ? '1' : col.on) : (col.off == null ? '0' : col.off);
   else if (input.type === 'radio') { if (!input.checked) return; v = input.value; }
   else v = input.value;
   if (col.type === 'number') {   // 숫자 칸 : 숫자 · 소수점 · 마이너스만 받고, 값은 숫자로 (비거나 '-' 뿐이면 null)
    const n = v.replace(/[^0-9.\-]/g, '');
    if (n !== v) input.value = n;
    v = n === '' || isNaN(Number(n)) ? null : Number(n);
   }
   if (String(row[col.key] == null ? '' : row[col.key]) === String(v == null ? '' : v)) return;
   row[col.key] = v;
   if (!row._new) row._dirty = true;
   if (this._hasFoot()) this._renderFoot();
   const tr = input.closest('tr');
   if (tr) tr.className = this._rowCls(row);
   if (this.opts.onChange) this.opts.onChange(row, col.key, v, this);
  }

  _bind() {
   // 글자칸은 칠 때마다, 체크 · 라디오 · 콤보는 바뀔 때
   this.el.addEventListener('input', e => { if (e.target.classList.contains('et-in')) this._commit(e.target); });
   this.el.addEventListener('change', e => {
    if (e.target.classList.contains('et-chkall')) {   // 머리글 체크 = 보이는 행 모두
     const on = e.target.checked;
     this.view.forEach(r => { if (on) r._chk = true; else delete r._chk; });
     this._syncChecks();
     this._fireCheck(null);
     return;
    }
    if (e.target.dataset && e.target.dataset.k) {
     this._commit(e.target);
     // opts.onEditEnd(row, key, value, grid) : 칸 고치기를 마쳤을 때 한 번 (글자칸은 칸을 벗어날 때, 체크 · 선택은 바로) — 위즈모 cellEditEnded 대신 (일진)
     const k = e.target.dataset.k, row = this._rowOf(e.target);
     if (this.opts.onEditEnd && row && k !== '_chk') this.opts.onEditEnd(row, k, row[k], this);
    }
   });
   // 행을 누르면 opts.onClick(row) (이미 고른 행을 다시 눌러도 불린다. 행 체크 칸은 제외)
   this.el.addEventListener('click', e => {
    const tt = e.target.closest('.et-tree-t');
    if (tt && this.tbody.contains(tt)) { this.toggleNode(this._rowOf(tt)); return; }
    const g = e.target.closest('tr.et-grp');
    if (g && this.tbody.contains(g)) { const grp = this._groups && this._groups[+g.dataset.g]; if (grp) this.toggleGroup(grp.key); return; }
    const btn = e.target.closest('button.et-btn');
    if (btn && this.tbody.contains(btn)) {
     const row = this._rowOf(btn), td = btn.closest('td[data-c]'), col = td && this.columns[+td.dataset.c];
     if (row && col && col.onClick) col.onClick(row, this, e);
     return;
    }
    // 칸 onClick(row, grid, e) : 버튼이 아닌 칸도 누르면 불린다 (예 : O ↔ X 바꾸기)
    const ctd = e.target.closest('td[data-c]');
    if (ctd && this.tbody.contains(ctd)) {
     const cc = this.columns[+ctd.dataset.c], crow = cc && cc.onClick && cc.type !== 'button' ? this._rowOf(ctd) : null;
     if (crow) cc.onClick(crow, this, e);
    }
    if (!this.opts.onClick || e.target.closest('td.et-rowchk')) return;
    const row = this._rowOf(e.target);
    if (row) this.opts.onClick(row, this, e);
   });

   this.el.addEventListener('mousedown', e => {
    const th = e.target.closest('th[data-c]');
    if (th && e.target.classList.contains('et-rz')) { this._startResize(e, +th.dataset.c); return; }
    if (th && e.target.classList.contains('et-chkall')) return;
    if (th) {
     const c = this.columns[+th.dataset.c];
     if (this.opts.sortable && c && c.sortable) { e.preventDefault(); this.sortBy(c.key, e.ctrlKey || e.metaKey); }
     return;
    }
    const row = this._rowOf(e.target);
    // opts.reselectOnCellClick : 위즈모처럼 지금 행의 다른 칸을 눌러도 선택 이벤트(onSelect · selectionChanged)를 다시 낸다 (일진)
    const ctd = e.target.closest && e.target.closest('td[data-c]'), cci = ctd ? +ctd.dataset.c : -1;
    if (row && row !== this.cur) { this._curCol = cci; this.select(row, false); }
    else if (row && this.opts.reselectOnCellClick && cci !== this._curCol && !e.target.closest('td.et-rowchk')) { this._curCol = cci; this._fireSelect(); }
   });
   this.el.addEventListener('focusin', e => {
    const row = this._rowOf(e.target);
    if (row && row !== this.cur) this.select(row, false);
    // 고칠 수 있는 칸에 들어갈 때 opts.onBeginEdit(row, key) (예: 빈 칸이면 남은 수량을 미리 채움)
    const k = e.target.dataset && e.target.dataset.k;
    if (row && k && k !== '_chk' && this.opts.onBeginEdit && (e.target.classList.contains('et-in') || e.target.tagName === 'SELECT')) this.opts.onBeginEdit(row, k, this);
   });

   // 손잡이를 두 번 누르면 그 칸만 처음 너비로 · 행을 두 번 누르면 opts.onDblClick(row)
   this.el.addEventListener('dblclick', e => {
    if (e.target.classList.contains('et-rz')) {
     const c = this.columns[+e.target.closest('th').dataset.c];
     c.width = c._w0; this._sizeCols(); this._saveWidths();
     return;
    }
    const row = this._rowOf(e.target);
    if (row && this.opts.onDblClick) this.opts.onDblClick(row, this);
   });

   // 오른쪽 클릭 메뉴 (opts.contextMenu === false 면 끔)
   this.el.addEventListener('contextmenu', e => {
    if (this.opts.contextMenu === false) return;
    if (e.target.closest('input, select')) return;   // 입력칸에서는 브라우저 메뉴 (붙여넣기 등)
    e.preventDefault();
    const row = this._rowOf(e.target);
    if (row && row !== this.cur) this.select(row, false);
    this._showMenu(e.clientX, e.clientY);
   });

   // 위 · 아래 화살표 : 같은 칸의 위 · 아래 행으로 (콤보 안에서는 콤보가 쓴다)
   this.el.addEventListener('keydown', e => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    if (e.target.tagName === 'SELECT') return;
    const list = this.opts.serverPaging || this._groups ? this.pv : this.view;   // 클라이언트 페이징은 쪽을 넘어 이어서 이동 (그룹은 펼친 행만)
    const i = this.cur ? list.indexOf(this.cur) : -1;
    const j = i + (e.key === 'ArrowDown' ? 1 : -1);
    if (j < 0 || j >= list.length) return;
    e.preventDefault();
    const key = e.target.dataset && e.target.dataset.k;
    if (key) this.focusCell(list[j], key); else this.select(list[j]);
   });
  }

  /* ───────── 엑셀(CSV) ───────── */
  /** 보이는 행을 CSV 로 받는다. 선택 · 라디오 칸은 이름으로, 체크 칸은 '예' / '' 로 */
  exportCsv(fileName, cols) {
   cols = this._exportCols(cols);
   // = + - @ 로 시작하는 글자는 엑셀이 수식으로 읽으므로 앞에 ' 를 붙인다
   const q = v => { let s = String(v == null ? '' : v); if (/^[=+\-@]/.test(s) && isNaN(Number(s))) s = "'" + s; return '"' + s.replace(/"/g, '""') + '"'; };
   const head = cols.map(c => q(c.header));
   const lines = [head.join(',')].concat(this.view.map(r => cols.map(c => q(this._text(c, r))).join(',')));
   EditTable._download(new Blob(['﻿' + lines.join('\r\n')], {type: 'text/csv;charset=utf-8'}), (this._fileName(fileName)) + '.csv');
  }

  /* ───────── 엑셀(.xlsx) ─────────
   * 라이브러리 없이 진짜 .xlsx 를 만든다 (zip 무압축 + 시트 XML).
   * 숫자 칸(type number · format n0/n2 · 합계 칸)은 숫자로 들어가 엑셀에서 합계 · 정렬이 된다.
   * 글자 칸은 글자로 들어가 '001' 같은 코드의 앞 0 이 지워지지 않는다. 합계 줄이 있으면 맨 아래에 넣는다. */
  exportXlsx(fileName, cols) {
   const allCols = this.columns;
   cols = this._exportCols(cols);
   const xe = s => String(s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]))
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
   const ref = (ci, ri) => { let s = '', n = ci + 1; while (n) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = (n - m - 1) / 26; } return s + ri; };
   // 숫자 형식 : n0 → 내장 3(#,##0), nD → 사용자 형식 164+
   const fmts = [], styleOf = {};
   const numStyle = c => {
    const m = typeof c.format === 'string' && /^n(\d+)$/.exec(c.format);
    const d = m ? +m[1] : (c.type === 'number' || c.sum ? -1 : null);
    if (d == null) return null;
    const key = String(d);
    if (!(key in styleOf)) {
     styleOf[key] = 2 + Object.keys(styleOf).length;   // 0 = 기본, 1 = 머리글(굵게)
     if (d > 0) fmts.push({id: 164 + fmts.length, code: '#,##0.' + '0'.repeat(d), key});
    }
    return styleOf[key];
   };
   const colStyle = cols.map(numStyle);
   const cell = (ci, ri, c, r, bold) => {
    const v = r == null ? '' : (c ? r[c.key] : '');
    const st = colStyle[ci];
    if (!bold && c && st != null && v !== '' && v != null && isFinite(Number(v)) && c.type !== 'check' && !c.options) {
     return '<c r="' + ref(ci, ri) + '" s="' + st + '"><v>' + Number(v) + '</v></c>';
    }
    const t = bold === true ? (c ? c.header : '') : (typeof bold === 'string' ? bold : this._text(c, r));
    if (t === '' || t == null) return '';
    return '<c r="' + ref(ci, ri) + '" t="inlineStr"' + (bold === true ? ' s="1"' : '') + '><is><t xml:space="preserve">' + xe(t) + '</t></is></c>';
   };
   const rows = [];
   rows.push('<row r="1">' + cols.map((c, ci) => cell(ci, 1, c, null, true)).join('') + '</row>');
   this.view.forEach((r, i) => rows.push('<row r="' + (i + 2) + '">' + cols.map((c, ci) => cell(ci, i + 2, c, r)).join('') + '</row>'));
   if (cols.some(c => c.sum)) {
    const s = this._sums(cols);
    const ri = this.view.length + 2;
    const label = this.opts.footerLabel == null ? '합계' : this.opts.footerLabel;
    const li = this._labelCol(cols);
    rows.push('<row r="' + ri + '">' + cols.map((c, ci) => c.sum ? cell(ci, ri, c, s) : (ci === li ? cell(ci, ri, c, {}, label) : '')).join('') + '</row>');
   }
   const widths = cols.map((c, i) => {
    const gi = allCols.indexOf(c);
    const px = gi >= 0 && this.colgroup.children[gi] ? parseFloat(this.colgroup.children[gi].style.width) : (typeof c.width === 'number' ? c.width : 120);
    return '<col min="' + (i + 1) + '" max="' + (i + 1) + '" width="' + Math.max(6, Math.round((px || 120) / 7)) + '" customWidth="1"/>';
   }).join('');
   const sheet = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
    + '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
    + '<cols>' + widths + '</cols><sheetData>' + rows.join('') + '</sheetData></worksheet>';
   const xfs = ['<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>', '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>'];
   Object.keys(styleOf).sort((a, b) => styleOf[a] - styleOf[b]).forEach(k => {
    const d = +k, f = fmts.find(x => x.key === k);
    const id = d > 0 ? f.id : (d === 0 ? 3 : 0);   // -1(형식 없음) = 일반
    xfs.push('<xf numFmtId="' + id + '" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>');
   });
   const styles = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
    + (fmts.length ? '<numFmts count="' + fmts.length + '">' + fmts.map(f => '<numFmt numFmtId="' + f.id + '" formatCode="' + f.code + '"/>').join('') + '</numFmts>' : '')
    + '<fonts count="2"><font><sz val="11"/><name val="맑은 고딕"/></font><font><b/><sz val="11"/><name val="맑은 고딕"/></font></fonts>'
    + '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
    + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    + '<cellXfs count="' + xfs.length + '">' + xfs.join('') + '</cellXfs>'
    + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>';
   const files = {
    '[Content_Types].xml': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
     + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
     + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
     + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
     + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>',
    '_rels/.rels': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
     + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    'xl/workbook.xml': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
     + '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>',
    'xl/_rels/workbook.xml.rels': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
     + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
     + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
    'xl/worksheets/sheet1.xml': sheet,
    'xl/styles.xml': styles
   };
   EditTable._download(new Blob([EditTable._zip(files)], {type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}), this._fileName(fileName) + '.xlsx');
  }

  _fileName(n) { return n || this.opts.fileName || 'data'; }

  /* ───────── 표 설정 (v1.8 : 서버 저장 · 권한) ─────────
   * 저장 모양 : {cols:[{id, key?, header?, hide?, width?, align?, sum?, fmt?, add?, del?}] (보이는 순서), rowHeight?, rowNumbers?, frozenColumns?, groupBy?}
   *   id  = 화면 코드가 정한 칸의 처음 key (슈퍼유저가 추가한 칸은 '+' 로 시작)
   *   key = 슈퍼유저가 바꾼 자료 필드 · add = 슈퍼유저가 추가한 칸 · del = 슈퍼유저가 지운 칸 · fmt = 슈퍼유저가 정한 형식
   * 서버(et_table_setting)에 회사 공통 하나로 저장 → 어느 PC · 어느 사용자에게나 같다.
   *   슈퍼유저(auth_user.is_superuser) : 전부 / 그 밖 : 머리글 · 보이기 · 순서 · 너비 · 정렬 · 집계 · 행 높이 · 줄 번호 · 고정 칸 · 묶기
   * 화면 코드가 정한 칸 정의(_defs)는 그대로 두고, 그릴 때만 덮어쓴다 → '처음대로' 하면 화면 정의로 돌아감 */
  _loadCfg() {
   const s = EditTable._srv;
   if (s.ready && !s.failed) { const c = s.items[this._srvKey]; return c ? JSON.parse(JSON.stringify(c)) : null; }
   if (!s.ready) return null;   // 서버 설정을 받는 중
   try { return JSON.parse(localStorage.getItem(this._cfgKey) || 'null'); } catch (e) { return null; }   // 서버를 못 쓸 때만 이 브라우저
  }
  /** 설정 저장 : 서버, 안 되면 이 브라우저. 끝나면 done(서버가 돌려준 설정 — 일반 계정이면 슈퍼유저 칸 구조가 붙어 있음) */
  _saveCfg(cfg, done, reset) {
   const s = EditTable._srv;
   const local = () => {
    try { if (reset) localStorage.removeItem(this._cfgKey); else localStorage.setItem(this._cfgKey, JSON.stringify(cfg)); } catch (e) { /* 저장소 못 씀 */ }
    done(reset ? null : cfg);
   };
   if (s.failed) return local();
   fetch(EditTable.settingUrl + (reset ? '/reset' : '/save'), {
    method: 'POST', credentials: 'same-origin',
    headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': EditTable._csrf()},
    body: JSON.stringify(reset ? {key: this._srvKey} : {key: this._srvKey, cfg})
   }).then(r => r.ok ? r.json() : Promise.reject(r.status)).then(j => {
    if (j && j.success === false) { EditTable.alert(j.message || '표 설정을 저장하지 못했습니다.'); return; }
    const c = j ? j.data : null;
    if (c) s.items[this._srvKey] = c; else delete s.items[this._srvKey];
    done(c ? JSON.parse(JSON.stringify(c)) : null);
   }).catch(() => { EditTable.alert('표 설정을 서버에 저장하지 못해 이 브라우저에만 저장합니다.'); local(); });
  }
  _cfgOpts() {
   const c = this._cfg || {}, o0 = this._opt0;
   ['rowHeight', 'rowNumbers', 'frozenColumns', 'groupBy'].forEach(k => { this.opts[k] = c[k] !== undefined ? c[k] : o0[k]; });
   const me = this._mine();   // 계정별 행 높이 · 글자 크기가 회사 공통보다 앞
   if (me.rowHeight) this.opts.rowHeight = me.rowHeight;
   if (this.opts.rowHeight) this.el.style.setProperty('--et-row-h', this.opts.rowHeight + 'px');
   else this.el.style.removeProperty('--et-row-h');
   if (me.fontSize) this.el.style.setProperty('--et-fs', me.fontSize + 'px');
   else this.el.style.removeProperty('--et-fs');
  }
  /** 이 계정의 행 높이 · 글자 크기 : 이 표 > 모든 표('*'). {rowHeight?, fontSize?, own?, all?} */
  _mine() {
   const m = EditTable._srv.mine || {}, t = m[this._srvKey] || {}, a = m['*'] || {};
   return {rowHeight: t.rowHeight || a.rowHeight || null, fontSize: t.fontSize || a.fontSize || null,
    own: !!(t.rowHeight || t.fontSize), all: !!(a.rowHeight || a.fontSize)};
  }
  /** 계정별 행 높이 · 글자 크기 저장. all 이면 모든 표('*')에 넣고 이 표 것은 지움 */
  _saveMine(rh, fs, all, done) {
   const s = EditTable._srv, me = this._mine();
   s.mine = s.mine || {};
   const post = (key, v) => {
    if (v) s.mine[key] = v; else delete s.mine[key];
    if (s.failed) return Promise.resolve();
    return fetch(EditTable.settingUrl + '/mine', {
     method: 'POST', credentials: 'same-origin',
     headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': EditTable._csrf()},
     body: JSON.stringify({key, rowHeight: v ? v.rowHeight || null : null, fontSize: v ? v.fontSize || null : null})
    }).then(r => r.ok ? r.json() : Promise.reject(r.status)).then(j => { if (j && j.success === false) EditTable.alert(j.message || '내 표 설정을 저장하지 못했습니다.'); })
     .catch(() => EditTable.alert('내 행 높이 · 글자 크기를 서버에 저장하지 못했습니다.'));
   };
   const v = (rh || fs) ? Object.assign(rh ? {rowHeight: rh} : {}, fs ? {fontSize: fs} : {}) : null;
   const jobs = [];
   if (all) { jobs.push(post('*', v)); if (me.own) jobs.push(post(this._srvKey, null)); }
   else {
    if (me.all && !me.own) jobs.push(post('*', null));   // '모든 표에' 를 끔
    if (v) jobs.push(post(this._srvKey, v)); else if (me.own) jobs.push(post(this._srvKey, null));
   }
   Promise.all(jobs).then(() => { done && done(); });
  }
  // 화면 칸 정의 + 저장된 설정 → 그릴 칸 (순서 · 숨김 · 너비 · 정렬 · 집계 · 머리글 · 키 · 추가 · 삭제 · 형식)
  _cfgCols(cols) {
   const base = cols.map((c, j) => Object.assign({}, c, {_id: c._id || c.key, _dw: c.width, _dh: c.header, _dk: c.key, _j: j}));
   const cc = this._cfg && this._cfg.cols;
   if (!cc || !cc.length) return base;
   const m = new Map(cc.map((x, i) => [x.id != null ? x.id : x.key, Object.assign({i}, x)]));   // 예전 설정(id 없음)은 key 가 id
   const out = base.map(o => {
    const x = m.get(o._id);
    o._ord = x ? x.i : null;
    if (!x) return o;
    if (x.id != null && x.key && x.key !== o._dk) o.key = x.key;
    if (x.header != null && x.header !== '') o.header = x.header;
    if (x.hide) o.visible = false;
    if (x.del) { o.visible = false; o._del = true; }
    if (typeof x.width === 'number') o.width = x.width;
    if (x.align) o.align = x.align;
    if (x.sum !== undefined && typeof o.sum !== 'function') o.sum = x.sum || false;
    if (x.fmt) { o.format = x.fmt; delete o.formatter; }
    return o;
   });
   cc.forEach((x, i) => {   // 슈퍼유저가 추가한 칸
    if (!x.add || x.id == null || base.some(b => b._id === x.id)) return;
    const o = {key: x.key || x.id, header: x.header || x.key || '', width: typeof x.width === 'number' ? x.width : 100,
     _id: x.id, _dw: 100, _dh: '', _dk: '', _add: true, _ord: i, _j: base.length + i};
    if (x.align) o.align = x.align;
    if (x.fmt) o.format = x.fmt;
    if (x.sum) o.sum = x.sum;
    if (x.hide) o.visible = false;
    if (x.del) { o.visible = false; o._del = true; }
    out.push(o);
   });
   // 저장된 순서대로, 저장에 없는 (새로 생긴) 칸은 원래 앞 칸 바로 뒤에
   let last = -1;
   out.forEach(o => { if (o._ord == null) o._ord = last + 0.001 * (o._j + 1); else last = o._ord; });
   return out.sort((a, b) => a._ord - b._ord);
  }
  /** 회사 공통 · 화면 기본 행 높이 (계정별 값 빼고) */
  _baseRowH() { const c = this._cfg || {}; return c.rowHeight || this._opt0.rowHeight || EditTable.defaults.rowHeight || ''; }
  /** 지금 표 설정 (저장된 값) */
  getSettings() { return this._cfg ? JSON.parse(JSON.stringify(this._cfg)) : null; }
  /** 표 설정 지우고 화면 정의대로 */
  resetSettings() {
   if (this._mine().own) this._saveMine(null, null, false, () => this._applyCfg());   // 이 표의 내 줄 높이 · 글자 크기도 지움
   this._saveCfg(null, c => { this._cfg = c; this._applyCfg(); }, true);   // 일반 계정이면 서버가 슈퍼유저 칸 구조는 남겨 돌려준다
  }
  _applyCfg() {
   this._cfgOpts();
   if (!this.opts.groupBy) this._groups = null;
   this._collapsed = new Set();
   this.setColumns(this._defs || []);
  }
  // ⚙ 위치 : 표 바깥 오른쪽 위. 가려지거나 다른 버튼 · 글자와 겹치면 머리글 안 (opts.settingsInside:true 면 늘 안)
  _placeCfg() {
   const b = this._cfgBtn, bar = this._bar;
   if (!b || !bar) return;
   const er = this.el.getBoundingClientRect();
   if (!er.width || !bar.isConnected) { b.style.visibility = 'hidden'; return; }
   b.style.visibility = '';
   const inside = () => { if (b.parentNode !== this._dock) { this._dock.appendChild(b); } b.classList.add('et-cfg-in'); b.style.left = b.style.top = ''; };
   if (this.opts.settingsInside) return inside();
   if (b.parentNode !== bar) bar.appendChild(b);
   b.classList.remove('et-cfg-in');
   const w = b.offsetWidth || 24, h = b.offsetHeight || 22;
   // offset 으로 계산 (팝업이 열릴 때 scale 애니메이션 중이어도 틀어지지 않게. 자리와 표는 같은 부모 안 형제)
   b.style.left = (this.el.offsetLeft + this.el.offsetWidth - bar.offsetLeft - w) + 'px';
   b.style.top = (this.el.offsetTop - bar.offsetTop - h - 4) + 'px';
   const r = b.getBoundingClientRect();
   const busy = el => el && (/^(A|BUTTON|INPUT|SELECT|TEXTAREA|LABEL|IMG|SVG|I)$/i.test(el.tagName)
    || [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim()));
   const hit = [[r.left + 2, r.top + 2], [r.right - 2, r.top + 2], [r.left + w / 2, r.top + h / 2], [r.left + 2, r.bottom - 2], [r.right - 2, r.bottom - 2]].some(([x, y]) => {
    if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) return true;
    const st = document.elementsFromPoint(x, y);
    if (!st.length || (st[0] !== b && !b.contains(st[0]))) return true;   // 잘리거나 다른 것에 가려짐
    const under = st.find(el => el !== b && !b.contains(el) && el !== bar);
    if (busy(under)) return true;
    // 표를 감싼 틀(조상)이 아닌, 바탕색 · 테두리가 있는 상자(검색 패널 등) 위면 겹친 것으로 본다
    if (under && !under.contains(this.el)) {
     const cs = getComputedStyle(under);
     const bg = cs.backgroundColor, hasBg = bg && bg !== 'transparent' && !/rgba\([^)]*,\s*0\)$/.test(bg);
     if (hasBg || parseFloat(cs.borderTopWidth) > 0 || parseFloat(cs.borderBottomWidth) > 0) return true;
    }
    return false;
   });
   if (hit) inside();
  }
  /** 표 설정 모달 열기 (슈퍼유저는 키 · 형식 · 칸 추가 · 삭제까지) */
  openSettings() {
   EditTable._closeMenu();
   const old = document.querySelector('.et-cfg-ov'); if (old) old.remove();
   const su = !!EditTable._srv.superuser;
   const isSys = c => c.type === '_rowcheck' || c.type === '_rownum';
   const plain = h => String(h == null ? '' : h).replace(/<[^>]*>/g, '');
   const cur = new Map(this.columns.map(c => [c._id || c.key, c]));
   const cfgById = new Map(((this._cfg && this._cfg.cols) || []).map(x => [x.id != null ? x.id : x.key, x]));
   // 모달에 보일 칸 : 지금 그리는 순서 + 숨긴 칸 (슈퍼유저가 지운 칸은 슈퍼유저에게만)
   const eff = this._cfgCols(this._defs || []);
   const rows = eff.filter(c => !isSys(c) && (su || !c._del)).map(c => {
    const live = cur.get(c._id);
    const def = (this._defs || []).find(d => d.key === c._id);
    const x = cfgById.get(c._id) || {};
    return {id: c._id, key: c.key, dkey: c._dk || '', header: plain(c.header == null ? c.key : c.header), dheader: plain(c._dh),
     show: c.visible !== false || !!c._del, lock: !!(def && def.visible === false), add: !!c._add, del: !!c._del,
     width: live && typeof live.width === 'number' ? live.width : (typeof c.width === 'number' ? c.width : ''),
     align: c.align || '', sum: typeof c.sum === 'function' ? 'fn' : (c.sum === true ? 'sum' : (c.sum || '')),
     fmt: x.fmt || '', btn: c.type === 'button'};
   });
   const ov = document.createElement('div');
   ov.className = 'et-cfg-ov';
   const aggOpt = [['', '-'], ['sum', '합계'], ['avg', '평균'], ['count', '개수'], ['max', '최대'], ['min', '최소']];
   const alOpt = [['', '기본'], ['left', '왼쪽'], ['center', '가운데'], ['right', '오른쪽']];
   const fmtOpt = [['', '(화면대로)'], ['n0', '숫자 1,234'], ['n2', '숫자 1,234.00'], ['money', '금액'], ['date', '날짜'], ['datetime', '날짜 시간'], ['percent', '%']];
   const sel = (name, opts, v, dis) => '<select data-f="' + name + '"' + (dis ? ' disabled' : '') + '>' + opts.map(o => '<option value="' + o[0] + '"' + (String(v) === o[0] ? ' selected' : '') + '>' + o[1] + '</option>').join('') + '</select>';
   const rowHtml = r => '<tr draggable="true" data-id="' + esc(r.id) + '" data-add="' + (r.add ? 1 : 0) + '"'
    + ' class="' + (r.lock ? 'et-cfg-lock ' : '') + (r.del ? 'et-cfg-del' : '') + '"' + (r.lock ? ' title="화면에서 숨긴 칸"' : '') + '>'
    + '<td class="et-cfg-h">&#8942;&#8942;</td>'
    + '<td><input type="checkbox" data-f="show"' + (r.show ? ' checked' : '') + (r.lock ? ' disabled' : '') + '></td>'
    + '<td class="et-cfg-nm"><input type="text" data-f="header" maxlength="60" value="' + esc(r.header) + '" placeholder="' + esc(r.dheader || r.key) + '" title="머리글 이름"></td>'
    + (su ? '<td><input type="text" data-f="key" list="et-cfg-keys" maxlength="80" value="' + esc(r.key) + '" placeholder="자료 필드" title="이 칸이 보여 줄 자료 필드(키)' + (r.dkey && r.dkey !== r.key ? ' · 화면 정의 : ' + esc(r.dkey) : '') + '"></td>' : '')
    + '<td><button type="button" data-mv="-1">&#9650;</button><button type="button" data-mv="1">&#9660;</button></td>'
    + '<td><input type="number" data-f="width" min="30" max="1000" step="10" value="' + esc(r.width) + '"></td>'
    + '<td>' + sel('align', alOpt, r.align) + '</td>'
    + '<td>' + (r.sum === 'fn' ? '<span class="et-cfg-fix">화면 지정</span>' : r.btn ? '' : sel('sum', aggOpt, r.sum)) + '</td>'
    + (su ? '<td>' + (r.btn ? '' : sel('fmt', fmtOpt, r.fmt)) + '</td><td><button type="button" class="et-cfg-delbtn" data-del title="칸 삭제 · 되살리기 (모든 사용자에게서 빠짐)">&#10005;</button></td>' : '')
    + '</tr>';
   const o = this.opts, me = this._mine();
   const grpOpt = [['', '(묶지 않음)']].concat(rows.filter(r => !r.del).map(r => [r.key, r.header]));
   // 칸 추가용 자료 필드 목록 (지금 받은 자료의 이름)
   const fields = su ? [...new Set([].concat(...(this.data || []).slice(0, 50).map(r => Object.keys(r || {}))))].filter(k => k.charAt(0) !== '_').sort() : [];
   ov.innerHTML = '<div class="et-cfg' + (su ? ' et-cfg-su' : '') + '" role="dialog" aria-label="표 설정">'
    + '<div class="et-cfg-hd"><b>표 설정</b>' + (su ? '<span class="et-cfg-badge">관리자</span>' : '') + '<button type="button" class="et-cfg-x" data-act="cancel" title="닫기">&#10005;</button></div>'
    + '<div class="et-cfg-bd">'
    + '<div class="et-cfg-opts">'
    + '<label>줄 높이 <input type="number" data-o="rowHeight" min="18" max="80" step="1" placeholder="' + esc(this._baseRowH() || '기본') + '" value="' + esc(me.rowHeight || '') + '"> px</label>'
    + '<label>글자 크기 <input type="number" data-o="fontSize" min="10" max="20" step="0.5" placeholder="13" value="' + esc(me.fontSize || '') + '"> px</label>'
    + '<label title="체크하면 모든 화면의 표에 이 줄 높이 · 글자 크기를 씁니다"><input type="checkbox" data-o="mineAll"' + (me.all && !me.own ? ' checked' : '') + '> 모든 표에</label>'
    + '<span class="et-cfg-mine">내 계정만</span>'
    + '</div><div class="et-cfg-opts">'
    + '<label><input type="checkbox" data-o="rowNumbers"' + (o.rowNumbers ? ' checked' : '') + '> 줄 번호</label>'
    + '<label>고정 칸 <input type="number" data-o="frozenColumns" min="0" max="20" value="' + esc(o.frozenColumns || 0) + '"></label>'
    + (this.opts.childItemsPath ? '' : '<label>묶기 ' + sel('groupBy', grpOpt, o.groupBy || '').replace('data-f=', 'data-o=') + '</label>')
    + '</div>'
    + '<div class="et-cfg-tw"><table class="et-cfg-t"><thead><tr><th></th><th><input type="checkbox" data-all title="모두 보이기/숨기기"></th><th>칸 (머리글)</th>'
    + (su ? '<th>키</th>' : '') + '<th>순서</th><th>너비</th><th>정렬</th><th>집계</th>' + (su ? '<th>형식</th><th></th>' : '') + '</tr></thead><tbody>'
    + rows.map(rowHtml).join('') + '</tbody></table></div>'
    + (su ? '<datalist id="et-cfg-keys">' + fields.map(k => '<option value="' + esc(k) + '">').join('') + '</datalist>' : '')
    + '<div class="et-cfg-note">끌어서 순서를 바꿀 수 있습니다. 줄 높이 · 글자 크기는 내 계정에만, 나머지 설정은 모든 사용자 · 모든 PC 에 같이 적용됩니다.'
    + (su ? ' 관리자는 키 · 형식을 바꾸고 칸을 추가 · 삭제할 수 있습니다.' : '') + '</div>'
    + '</div>'
    + '<div class="et-cfg-ft"><button type="button" data-act="reset">처음대로</button>' + (su ? '<button type="button" data-act="add">+ 칸 추가</button>' : '') + '<span></span>'
    + '<button type="button" data-act="cancel">취소</button><button type="button" class="et-cfg-ok" data-act="ok">적용</button></div></div>';
   document.body.appendChild(ov);
   const tb = ov.querySelector('tbody');
   const all = ov.querySelector('[data-all]');
   const syncAll = () => { const bs = [...tb.querySelectorAll('[data-f="show"]:not(:disabled)')]; all.checked = bs.length && bs.every(b => b.checked); all.indeterminate = !all.checked && bs.some(b => b.checked); };
   syncAll();
   all.addEventListener('change', () => { tb.querySelectorAll('[data-f="show"]:not(:disabled)').forEach(b => { b.checked = all.checked; }); });
   tb.addEventListener('change', e => { if (e.target.dataset.f === 'show') syncAll(); });
   tb.addEventListener('click', e => {
    const del = e.target.closest('[data-del]');
    if (del) { const tr = del.closest('tr'); if (tr.dataset.add === '1' && !tr.dataset.saved) tr.remove(); else tr.classList.toggle('et-cfg-del'); return; }
    const b = e.target.closest('[data-mv]'); if (!b) return;
    const tr = b.closest('tr'), d = +b.dataset.mv;
    if (d < 0 && tr.previousElementSibling) tb.insertBefore(tr, tr.previousElementSibling);
    if (d > 0 && tr.nextElementSibling) tb.insertBefore(tr.nextElementSibling, tr);
   });
   // 입력칸에서는 끌기 대신 글자 고르기
   tb.addEventListener('mousedown', e => { const tr = e.target.closest('tr'); if (tr) tr.draggable = !e.target.closest('input, select'); });
   tb.querySelectorAll('tr[data-add="1"]').forEach(tr => { tr.dataset.saved = '1'; });
   let drag = null;   // 끌어서 순서 바꾸기
   tb.addEventListener('dragstart', e => { drag = e.target.closest('tr'); if (drag) { drag.classList.add('et-cfg-drag'); e.dataTransfer.effectAllowed = 'move'; try { e.dataTransfer.setData('text/plain', ''); } catch (x) { /* IE */ } } });
   tb.addEventListener('dragover', e => {
    if (!drag) return; e.preventDefault();
    const tr = e.target.closest('tr'); if (!tr || tr === drag) return;
    const r = tr.getBoundingClientRect();
    tb.insertBefore(drag, e.clientY > r.top + r.height / 2 ? tr.nextSibling : tr);
   });
   tb.addEventListener('dragend', () => { if (drag) drag.classList.remove('et-cfg-drag'); drag = null; });
   const close = () => { ov.remove(); document.removeEventListener('keydown', key, true); this.el.focus && this.el.focus(); };
   const key = e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } };
   document.addEventListener('keydown', key, true);
   ov.addEventListener('mousedown', e => { if (e.target === ov) close(); });
   ov.querySelector('.et-cfg-ft').addEventListener('click', e => { if (e.target.dataset.act === 'cancel') close(); });
   ov.querySelector('.et-cfg-hd').addEventListener('click', e => { if (e.target.closest('[data-act="cancel"]')) close(); });
   ov.querySelector('[data-act="reset"]').addEventListener('click', () => {
    EditTable.confirm(su ? '이 표의 설정을 모두 지우고 화면 정의대로 되돌릴까요? (모든 사용자에게 적용)' : '이 표의 설정을 처음대로 되돌릴까요? (모든 사용자에게 적용, 관리자가 정한 칸 구성은 남음)', () => { this.resetSettings(); close(); });
   });
   const addBtn = ov.querySelector('[data-act="add"]');
   if (addBtn) addBtn.addEventListener('click', () => {
    const id = '+' + Date.now().toString(36);
    tb.insertAdjacentHTML('beforeend', rowHtml({id, key: '', dkey: '', header: '', dheader: '새 칸', show: true, lock: false, add: true, del: false, width: 100, align: '', sum: '', fmt: '', btn: false}));
    const tr = tb.lastElementChild; tr.scrollIntoView({block: 'nearest'});
    tr.querySelector('[data-f="key"]').focus();
   });
   ov.querySelector('[data-act="ok"]').addEventListener('click', () => {
    const base = new Map((this._defs || []).map(c => [c.key, c]));
    let bad = null;
    const cols = [...tb.querySelectorAll('tr')].map(tr => {
     const id = tr.dataset.id, add = tr.dataset.add === '1', d = base.get(id) || {}, x = {id};
     const hd = tr.querySelector('[data-f="header"]').value.trim();
     if (add) x.add = true;
     if (hd && (add || hd !== plain(d.header == null ? d.key : d.header))) x.header = hd;
     const sh = tr.querySelector('[data-f="show"]');
     if (!sh.disabled && !sh.checked) x.hide = true;
     const w = parseInt(tr.querySelector('[data-f="width"]').value, 10);
     if (w > 0 && (add || w !== d.width)) x.width = Math.max(30, w);
     const al = tr.querySelector('[data-f="align"]').value;
     if (al && (add || al !== (d.align || ''))) x.align = al;
     const sm = tr.querySelector('[data-f="sum"]');
     if (sm) { const v = sm.value, dv = d.sum === true ? 'sum' : (d.sum || ''); if (add ? v : v !== dv) x.sum = v || false; }
     if (su) {
      const k = tr.querySelector('[data-f="key"]').value.trim();
      if (add) { if (!k && !tr.classList.contains('et-cfg-del')) bad = bad || '추가한 칸의 키(자료 필드)를 넣으세요.'; x.key = k; if (!x.header) x.header = k; }
      else if (k && k !== d.key) x.key = k;
      const f = tr.querySelector('[data-f="fmt"]'); if (f && f.value) x.fmt = f.value;
      if (tr.classList.contains('et-cfg-del')) x.del = true;
     }
     return x;
    }).filter(x => !(x.add && x.del && !base.has(x.id) && !cfgById.has(x.id)));   // 추가했다가 바로 지운 칸은 버림
    if (bad) { EditTable.alert(bad); return; }
    const num = (k, lo, hi) => { const v = parseInt(ov.querySelector('[data-o="' + k + '"]').value, 10); return isNaN(v) ? null : Math.min(hi, Math.max(lo, v)); };
    const cfg = {cols};
    if (this._cfg && this._cfg.rowHeight) cfg.rowHeight = this._cfg.rowHeight;   // 예전 회사 공통 행 높이는 그대로 (이제 줄 높이는 계정별)
    const myRh = num('rowHeight', 18, 80);
    const myFs = (v => isNaN(v) ? null : Math.round(Math.min(20, Math.max(10, v)) * 2) / 2)(parseFloat(ov.querySelector('[data-o="fontSize"]').value));
    const myAll = ov.querySelector('[data-o="mineAll"]').checked;
    const rn = ov.querySelector('[data-o="rowNumbers"]').checked; if (rn !== !!this._opt0.rowNumbers) cfg.rowNumbers = rn;
    const fz = num('frozenColumns', 0, 20) || 0; if (fz !== (+this._opt0.frozenColumns || 0)) cfg.frozenColumns = fz;
    const gb = ov.querySelector('[data-o="groupBy"]');
    if (gb && (gb.value || null) !== (this._opt0.groupBy || null)) cfg.groupBy = gb.value || null;
    const okBtn = ov.querySelector('[data-act="ok"]'); okBtn.disabled = true;
    this._saveCfg(cfg, c => {
     this._cfg = c;
     this._saveMine(myRh, myFs, myAll, () => this._applyCfg());
     // 드래그로 바꾼 너비 기억(saveKey)은 설정 너비가 우선하도록 비움
     try { if (this.opts.saveKey) localStorage.removeItem('et.w.' + this.opts.saveKey); } catch (e) { /* 저장소 못 씀 */ }
     this._applyCfg();
     close();
     if (typeof this.opts.onSettings === 'function') this.opts.onSettings(this.getSettings());
    });
    setTimeout(() => { if (okBtn.isConnected) okBtn.disabled = false; }, 3000);
   });
  }

  /* ───────── 오른쪽 클릭 메뉴 ───────── */
  _showMenu(x, y) {
   EditTable._closeMenu();
   const items = [['xlsx', '엑셀 내려받기 (.xlsx)'], ['csv', 'CSV 내려받기']];
   if (this.sorts.length) items.push(['-'], ['unsort', '정렬 풀기']);
   if (this.opts.resizable) items.push(['-'], ['width', '칸 너비 처음대로']);
   if (this.opts.settings !== false) items.push(['-'], ['cfg', '표 설정…']);
   const m = document.createElement('div');
   m.className = 'et-menu';
   m.innerHTML = items.map(it => it[0] === '-' ? '<div class="et-menu-sep"></div>' : '<div class="et-menu-item" data-cmd="' + it[0] + '">' + esc(it[1]) + '</div>').join('');
   document.body.appendChild(m);
   const w = m.offsetWidth, h = m.offsetHeight;
   m.style.left = Math.min(x, window.innerWidth - w - 4) + 'px';
   m.style.top = Math.min(y, window.innerHeight - h - 4) + 'px';
   m.addEventListener('mousedown', e => e.stopPropagation());
   m.addEventListener('click', e => {
    const it = e.target.closest('[data-cmd]');
    if (!it) return;
    EditTable._closeMenu();
    const cmd = it.dataset.cmd;
    if (cmd === 'xlsx') this.exportXlsx();
    else if (cmd === 'csv') this.exportCsv();
    else if (cmd === 'unsort') this.clearSort();
    else if (cmd === 'width') this.resetWidths();
    else if (cmd === 'cfg') this.openSettings();
   });
   EditTable._menu = m;
  }

  /** 위즈모 dispose() 대신 (일진) : 표를 없앤다. 요소를 새 빈 요소로 바꿔 이 표의 이벤트까지 떼어 내므로, 같은 자리에 new EditTable 을 다시 만들 수 있다 */
  dispose() {
   if (this._ro) this._ro.disconnect();
   if (this._bar) this._bar.remove();
   if (this.pagerEl && !this.opts.pager) this.pagerEl.remove();
   const n = this.el.cloneNode(false);
   n.classList.remove('et'); n.removeAttribute('data-etid'); n.innerHTML = '';
   if (this.el.parentNode) this.el.parentNode.replaceChild(n, this.el);
   delete this.el.editTable;
   this.el = n;
  }

  /** 위즈모 getColumn('key') 대신 : 그 칸 정의 (없으면 null) */
  getColumn(key) { return this.columns.find(c => c.key === key) || null; }

  /** 요소(또는 선택자 · jQuery)에 만든 표 (위즈모 wijmo.Control.getControl 대신, 일진) */
  static getControl(el) {
   if (el && el.jquery) el = el[0];
   if (typeof el === 'string') el = document.querySelector(el);
   return el && el.editTable || null;
  }

  static _closeMenu() { if (EditTable._menu) { EditTable._menu.remove(); EditTable._menu = null; } }

  static _download(blob, name) {
   const a = document.createElement('a');
   a.href = URL.createObjectURL(blob);
   a.download = name;
   document.body.appendChild(a); a.click(); a.remove();
   setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  // zip (무압축 STORE) : {경로: 글자} → Uint8Array
  static _zip(files) {
   const enc = new TextEncoder();
   if (!EditTable._crcT) {
    EditTable._crcT = new Uint32Array(256);
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; EditTable._crcT[n] = c >>> 0; }
   }
   const crc32 = b => { let c = 0xFFFFFFFF; for (let i = 0; i < b.length; i++) c = EditTable._crcT[(c ^ b[i]) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; };
   const parts = [], central = [];
   let off = 0;
   const u16 = (a, p, v) => { a[p] = v & 255; a[p + 1] = (v >>> 8) & 255; };
   const u32 = (a, p, v) => { u16(a, p, v & 0xFFFF); u16(a, p + 2, (v >>> 16) & 0xFFFF); };
   Object.keys(files).forEach(name => {
    const nb = enc.encode(name), data = enc.encode(files[name]), crc = crc32(data);
    const lh = new Uint8Array(30 + nb.length);
    u32(lh, 0, 0x04034b50); u16(lh, 4, 20); u16(lh, 6, 0x0800); u16(lh, 8, 0); u16(lh, 10, 0); u16(lh, 12, 0x21);
    u32(lh, 14, crc); u32(lh, 18, data.length); u32(lh, 22, data.length); u16(lh, 26, nb.length); u16(lh, 28, 0); lh.set(nb, 30);
    const ch = new Uint8Array(46 + nb.length);
    u32(ch, 0, 0x02014b50); u16(ch, 4, 20); u16(ch, 6, 20); u16(ch, 8, 0x0800); u16(ch, 10, 0); u16(ch, 12, 0); u16(ch, 14, 0x21);
    u32(ch, 16, crc); u32(ch, 20, data.length); u32(ch, 24, data.length); u16(ch, 28, nb.length);
    u32(ch, 42, off); ch.set(nb, 46);
    parts.push(lh, data); central.push(ch);
    off += lh.length + data.length;
   });
   const cdSize = central.reduce((a, c) => a + c.length, 0);
   const end = new Uint8Array(22);
   u32(end, 0, 0x06054b50); u16(end, 8, central.length); u16(end, 10, central.length); u32(end, 12, cdSize); u32(end, 16, off);
   const all = parts.concat(central, [end]);
   const out = new Uint8Array(all.reduce((a, p) => a + p.length, 0));
   let p = 0; all.forEach(b => { out.set(b, p); p += b.length; });
   return out;
  }
 }

 // 메뉴 밖을 누르거나 Esc · 스크롤 · 창 크기 변경이면 메뉴를 닫는다
 document.addEventListener('mousedown', () => EditTable._closeMenu());
 document.addEventListener('keydown', e => { if (e.key === 'Escape') EditTable._closeMenu(); });
 global.addEventListener('blur', () => EditTable._closeMenu());
 global.addEventListener('resize', () => EditTable._closeMenu());

 /* ───────── 공통 기본값 · 포맷터 (v1.6) ───────── */
 function isPlain(o) { return o && typeof o === 'object' && !Array.isArray(o) && Object.getPrototypeOf(o) === Object.prototype; }
 /** 깊은 병합 ($.extend(true, …) 와 같음). 배열 · 함수 · 요소는 통째로 바꾼다 */
 function deepMerge(t, s) {
  Object.keys(s || {}).forEach(k => {
   const v = s[k];
   if (isPlain(v)) t[k] = deepMerge(isPlain(t[k]) ? t[k] : {}, v);
   else if (v !== undefined) t[k] = v;
  });
  return t;
 }
 EditTable.defaults = {sortable: true, resizable: true, emptyText: '자료가 없습니다.'};

 /* ───────── 표 설정 서버 저장 (v1.8, 2026-11-24) ─────────
  * 화면을 열면 그 화면 표 설정 전부와 슈퍼유저 여부를 한 번에 받는다 (GET settingUrl/page?path=화면주소).
  * 받기 전에 만든 표는 받은 뒤 다시 그린다. 서버를 못 쓰면(오류 · 다른 프로젝트) 예전처럼 이 브라우저(localStorage)에 저장 */
 EditTable.settingUrl = '/api/system/table_setting';
 EditTable._srv = {ready: false, failed: false, superuser: false, items: {}, mine: {}, waits: []};
 EditTable._srvWait = function (f) { const s = EditTable._srv; if (s.ready) f(); else s.waits.push(f); };
 EditTable._csrf = function () { const e = document.querySelector('input[name="_csrf"]'); return e ? e.value : ''; };
 EditTable.loadServerSettings = function () {
  const s = EditTable._srv;
  const done = () => { s.ready = true; const w = s.waits; s.waits = []; w.forEach(f => { try { f(); } catch (e) { console.warn('표 설정', e); } }); };
  if (!EditTable.settingUrl || typeof fetch !== 'function') { s.failed = true; done(); return; }
  fetch(EditTable.settingUrl + '/page?path=' + encodeURIComponent(location.pathname), {credentials: 'same-origin'})
   .then(r => r.ok ? r.json() : Promise.reject(r.status))
   .then(j => { const d = (j && j.data) || {}; s.superuser = !!d.superuser; s.items = d.items || {}; s.mine = d.mine || {}; })
   .catch(() => { s.failed = true; })
   .then(done);
 };
 EditTable.loadServerSettings();
 /** 알림 · 확인 창 : 기본은 브라우저 alert / confirm. 프로젝트 공통 창을 쓰려면 edit-table-custom.js 에서 바꾼다 (일진 : Alert.alert · Alert.confirmAsync) */
 EditTable.alert = function (msg) { alert(msg); };
 EditTable.confirm = function (msg, ok) { if (confirm(msg)) ok(); };
 EditTable.setDefaults = function (o) { deepMerge(EditTable.defaults, o || {}); return EditTable.defaults; };
 /** 이름 붙인 포맷터 : format: 'money' 처럼 쓴다. 함수에 align: 'right' 를 붙이면 그 칸 기본 정렬이 오른쪽 */
 EditTable.formatter = {
  money: Object.assign(function (v) { return (v == null || v === '' || isNaN(Number(v))) ? (v == null ? '' : v) : Number(v).toLocaleString('ko-KR', {maximumFractionDigits: 0}); }, {align: 'right'})
 };
 global.EditTable = EditTable;
})(window);
