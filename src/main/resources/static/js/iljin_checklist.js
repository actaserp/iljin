/*
 * 조립 전자 체크리스트 — 인수인계 · 배선/I/O · 시운전 준비
 *   조립 공정(PC)과 조립 키오스크가 같이 쓴다. API: /api/production/asm_check
 *
 *   IljinChecklist.open({ sujuId, title, worker, workerId, onChange(done, total) })
 *     - 항목을 누르면 바로 저장 (체크 ↔ 해제). 체크한 사람 · 시각이 남는다
 *     - [항목 관리] : 항목 추가 · 이름 바꾸기 · 빼기 (전 품목 공통)
 *   IljinChecklist.summary([sujuId…]) → Promise<{sujuId: {done,total}}>
 *
 *   AjaxUtil(common.js) 로 통신 — CSRF 포함.
 */
(function (w) {
    'use strict';
    const API = '/api/production/asm_check';

    const get = (url, data) => new Promise(res => AjaxUtil.getAsyncData(url, data,
        r => res(r || {success: false}), () => res({success: false})));
    const post = (url, data) => new Promise(res => AjaxUtil.postJsonData(url, data,
        r => res(r || {success: false}), () => res({success: false, message: '저장하지 못했습니다.'})));
    const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c =>
        ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

    const CSS = `
.ick-dim{position:fixed;inset:0;background:rgba(15,23,42,.55);z-index:10050;display:flex;align-items:center;justify-content:center}
.ick-box{background:#fff;border-radius:14px;width:min(760px,94vw);max-height:90vh;display:flex;flex-direction:column;box-shadow:0 20px 50px rgba(0,0,0,.3);font-size:15px;color:#1e293b}
.ick-head{display:flex;align-items:center;gap:10px;padding:16px 20px;border-bottom:1px solid #e2e8f0}
.ick-title{font-size:18px;font-weight:700;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ick-prog{font-weight:700;color:#2563eb;white-space:nowrap}
.ick-prog.full{color:#16a34a}
.ick-bar{height:6px;background:#e2e8f0}
.ick-bar i{display:block;height:100%;background:#2563eb;transition:width .2s}
.ick-bar i.full{background:#16a34a}
.ick-body{overflow:auto;padding:8px 20px 16px}
.ick-grp{margin-top:14px;font-weight:700;color:#475569;display:flex;justify-content:space-between}
.ick-grp small{font-weight:600;color:#94a3b8}
.ick-row{display:flex;align-items:center;gap:12px;padding:12px 14px;margin-top:8px;border:1px solid #e2e8f0;border-radius:10px;cursor:pointer;user-select:none;min-height:52px}
.ick-row:hover{background:#f8fafc}
.ick-row.on{background:#f0fdf4;border-color:#86efac}
.ick-box2{width:26px;height:26px;border:2px solid #94a3b8;border-radius:6px;flex:none;display:flex;align-items:center;justify-content:center;font-weight:900;color:#fff}
.ick-row.on .ick-box2{background:#16a34a;border-color:#16a34a}
.ick-name{flex:1}
.ick-who{font-size:13px;color:#64748b;white-space:nowrap}
.ick-row.busy{opacity:.5;pointer-events:none}
.ick-foot{display:flex;gap:8px;justify-content:flex-end;padding:12px 20px;border-top:1px solid #e2e8f0}
.ick-btn{border:1px solid #cbd5e1;background:#fff;border-radius:8px;padding:10px 18px;font-size:15px;cursor:pointer}
.ick-btn.pri{background:#2563eb;border-color:#2563eb;color:#fff}
.ick-btn.del{color:#dc2626;border-color:#fecaca}
.ick-btn.sm{padding:6px 12px;font-size:14px}
.ick-edit{display:flex;gap:8px;align-items:center;margin-top:8px}
.ick-edit input,.ick-edit select{border:1px solid #cbd5e1;border-radius:8px;padding:8px 10px;font-size:15px}
.ick-edit input.nm{flex:1}
.ick-empty{padding:30px 0;text-align:center;color:#94a3b8}
.ick-msg{color:#dc2626;font-size:14px;margin-right:auto;align-self:center}
`;
    let cssDone = false;
    const injectCss = () => {
        if (cssDone) return;
        const s = document.createElement('style');
        s.textContent = CSS;
        document.head.appendChild(s);
        cssDone = true;
    };

    function open(opt) {
        opt = opt || {};
        if (!opt.sujuId) { alert('품목을 먼저 고르세요.'); return; }
        injectCss();
        let rows = [], manage = false;

        const dim = document.createElement('div');
        dim.className = 'ick-dim';
        dim.innerHTML = '<div class="ick-box"></div>';
        document.body.appendChild(dim);
        const box = dim.firstChild;
        const close = () => { dim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = e => { if (e.key === 'Escape') close(); };
        document.addEventListener('keydown', onKey);
        dim.addEventListener('click', e => { if (e.target === dim) close(); });

        const counts = () => ({done: rows.filter(r => r.checked).length, total: rows.length});
        const notify = () => { if (typeof opt.onChange === 'function') { const c = counts(); opt.onChange(c.done, c.total); } };
        const groups = () => {
            const g = [];
            rows.forEach(r => { if (g.indexOf(r.grp) < 0) g.push(r.grp); });
            return g;
        };

        function render(msg) {
            const c = counts(), full = c.total > 0 && c.done === c.total;
            const pct = c.total ? Math.round(c.done * 100 / c.total) : 0;
            let body = '';
            if (!rows.length && !manage) body = '<div class="ick-empty">체크 항목이 없습니다. [항목 관리]에서 추가하세요.</div>';
            groups().forEach(g => {
                const gr = rows.filter(r => r.grp === g);
                body += `<div class="ick-grp"><span>${esc(g)}</span><small>${gr.filter(r => r.checked).length}/${gr.length}</small></div>`;
                gr.forEach(r => {
                    if (manage) {
                        body += `<div class="ick-edit" data-id="${r.tpl_id}">
                            <input class="nm" value="${esc(r.name)}">
                            <button class="ick-btn sm" data-act="rename">저장</button>
                            <button class="ick-btn sm del" data-act="del">빼기</button></div>`;
                    } else {
                        body += `<div class="ick-row${r.checked ? ' on' : ''}" data-id="${r.tpl_id}">
                            <span class="ick-box2">${r.checked ? '✓' : ''}</span>
                            <span class="ick-name">${esc(r.name)}</span>
                            <span class="ick-who">${r.checked ? esc((r.checker || '') + ' ' + (r.checked_at || '')) : ''}</span></div>`;
                    }
                });
            });
            if (manage) {
                const gs = groups();
                if (!gs.length) gs.push('인수인계', '배선·I/O', '시운전 준비');
                body += `<div class="ick-grp"><span>항목 추가</span></div>
                    <div class="ick-edit">
                      <select class="grp">${gs.map(g => `<option>${esc(g)}</option>`).join('')}<option value="">새 구분…</option></select>
                      <input class="nm" placeholder="항목 이름">
                      <button class="ick-btn sm pri" data-act="add">추가</button></div>`;
            }
            box.innerHTML = `
                <div class="ick-head">
                  <div class="ick-title">${manage ? '체크 항목 관리' : '조립 체크리스트'}${opt.title ? ' · ' + esc(opt.title) : ''}</div>
                  ${manage ? '' : `<div class="ick-prog${full ? ' full' : ''}">${c.done} / ${c.total}${full ? ' 완료' : ''}</div>`}
                </div>
                ${manage ? '' : `<div class="ick-bar"><i class="${full ? 'full' : ''}" style="width:${pct}%"></i></div>`}
                <div class="ick-body">${body}</div>
                <div class="ick-foot">
                  <span class="ick-msg">${esc(msg || '')}</span>
                  <button class="ick-btn" data-act="manage">${manage ? '체크로 돌아가기' : '항목 관리'}</button>
                  <button class="ick-btn pri" data-act="close">닫기</button>
                </div>`;
        }

        async function load() {
            const r = await get(API + '/list', {sujuId: opt.sujuId});
            rows = (r && r.success && r.data) || [];
            render(r && r.success ? '' : '불러오지 못했습니다.');
            notify();
        }

        box.addEventListener('click', async e => {
            const act = e.target.closest('[data-act]');
            if (act) {
                const a = act.dataset.act;
                if (a === 'close') return close();
                if (a === 'manage') { manage = !manage; return render(); }
                const ed = act.closest('.ick-edit');
                if (a === 'add') {
                    let grp = ed.querySelector('.grp').value;
                    if (!grp) grp = (prompt('새 구분 이름') || '').trim();
                    const name = ed.querySelector('.nm').value.trim();
                    if (!grp || !name) return render('구분과 항목 이름을 넣으세요.');
                    const r = await post(API + '/tpl/save', {grp, name});
                    if (!r.success) return render(r.message || '저장하지 못했습니다.');
                    return load();
                }
                const id = Number(ed.dataset.id), row = rows.find(x => x.tpl_id === id);
                if (a === 'rename') {
                    const name = ed.querySelector('.nm').value.trim();
                    if (!name) return render('항목 이름을 넣으세요.');
                    const r = await post(API + '/tpl/save', {id, grp: row.grp, name});
                    if (!r.success) return render(r.message || '저장하지 못했습니다.');
                    return load();
                }
                if (a === 'del') {
                    if (!confirm(`'${row.name}' 항목을 뺄까요?\n지난 품목의 체크 기록은 남습니다.`)) return;
                    const r = await post(API + '/tpl/delete', {id});
                    if (!r.success) return render(r.message || '빼지 못했습니다.');
                    return load();
                }
                return;
            }
            const rowEl = e.target.closest('.ick-row');
            if (!rowEl) return;
            const id = Number(rowEl.dataset.id), row = rows.find(x => x.tpl_id === id);
            if (!row) return;
            rowEl.classList.add('busy');
            const want = !row.checked;
            const r = await post(API + '/check', {
                sujuId: opt.sujuId, tplId: id, checked: want,
                checker: opt.worker || '', checkerId: opt.workerId || null
            });
            if (!r.success) { rowEl.classList.remove('busy'); return render(r.message || '저장하지 못했습니다.'); }
            // 작업자를 안 골랐으면 서버가 로그인 사용자 이름으로 남긴다 — 다시 읽어 그 이름을 보여준다
            if (want && !opt.worker) return load();
            const d = new Date(), p = n => String(n).padStart(2, '0');
            row.checked = want;
            row.checker = want ? (opt.worker || '') : null;
            row.checked_at = want ? `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}` : null;
            render();
            notify();
        });

        box.innerHTML = '<div class="ick-empty">불러오는 중…</div>';
        load();
    }

    async function summary(ids) {
        ids = (ids || []).filter(Boolean);
        if (!ids.length) return {};
        const r = await get(API + '/summary', {sujuIds: ids.join(',')});
        const m = {};
        ((r && r.data) || []).forEach(x => { m[x.suju_id] = {done: Number(x.done), total: Number(x.total)}; });
        return m;
    }

    w.IljinChecklist = {open, summary};
})(window);