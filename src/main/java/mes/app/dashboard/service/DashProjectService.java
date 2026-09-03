package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 프로젝트 현황 대시보드 (읽기 전용)
 *
 *  화면: dash_project.html / dash_project_kind.html — 두 화면이 <b>같은 구조</b>를 쓴다.
 *
 * [응답 구조]  화면이 기대하는 모양 그대로 조립해 내려준다.
 * <pre>
 *  [{ name, owner, due, ship, outd,
 *     dsteps: [{n, s}],                       // 설계 단계. s = done|cur|wait
 *     lines: [{ name,
 *       items: [{ code, need, made, asm, insp, out, type, vendor, rcv,
 *                 parts: { kinds: {유형: {t, d, ops:{공정:수량}}}, procs: {공정: 수량} },
 *                 working: {stage, machine, worker} | null }] }],
 *     kinds: {유형: {t, d, ops:{공정:수량}}},  // 프로젝트 단위. 품목 미지정 실적 포함
 *     wbs: [{step_no, step_name, seq, task_name, pl_stdate, pl_eddate,
 *            ac_stdate, ac_eddate, progress, milestone_yn, charge_name,
 *            state, delay_yn}],
 *     milestones: [{n, step, pl, ac, s, delay}],   // wbs 중 milestone_yn='Y'
 *     delayCnt: 0
 *  }]
 * </pre>
 *
 * [단위 환산에 대한 정직한 설명]  ★ 이 화면은 축이 서로 다른 값을 한 줄에 세운다.
 *
 *   need  지그 대수 (suju."SujuQty")  — 조립·검사 축
 *   unit  구성 유닛 수 (suju.unit_qty) — 참고 표시. 실적 없음
 *   made  <b>가공 진척을 유닛으로 환산한 추정값</b>      — 원래는 가공품 축
 *   asm   유닛 조립 완료 (ProcessOrder=1)                — 유닛 축. 참값
 *   insp  검사 진척을 유닛으로 환산                      — 원래는 공정(세트) 축
 *
 *   made 는 <b>참값이 아니다.</b> 재고·귀속·파기를 기록하지 않으므로
 *   "plate 200개가 유닛 몇 개분인가"는 원리상 알 수 없다.
 *   그래서 유형별 (생산합 ÷ 필요합) 비율에 유닛수를 곱해 환산한다.
 *   못 쓸 부품이 섞여 있어도 시스템은 모르므로 <b>실제보다 낙관적</b>이다.
 *   설계 결함이 아니라 파기를 입력하지 않기로 한 선택의 결과다.
 *   정확한 숫자가 필요하면 parts.kinds 의 t/d 원값을 보면 된다 — 그쪽은 참값이다.
 *
 *   외작은 수량 개념이 없다. 공정 통째로 입고되므로
 *   화면이 rcv(입고완료) 여부로 need 만큼 또는 0 으로 계산한다.
 *
 * [설계 메모]
 *  - 신규 테이블 없음. 기존 집계만 조합한다.
 *  - 프로젝트 수가 많지 않으므로 쿼리 5개를 각각 돌리고 Java 에서 조립한다.
 *    한 방 쿼리로 만들면 중첩 구조 때문에 읽을 수 없어진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashProjectService {

	private final SqlRunner sqlRunner;

	/**
	 * 대시보드 전체 데이터.
	 *
	 * @param projNo 특정 프로젝트만 볼 때. 비우면 진행중 전체
	 */
	public List<Map<String, Object>> getDashboard(String spjangcd, String projNo) {

		List<Map<String, Object>> projects = getProjects(spjangcd, projNo);
		if (projects.isEmpty()) return new ArrayList<>();   // rows() 가 null 을 걸러주므로 NPE 없음

		List<Map<String, Object>> items = getItems(spjangcd, projNo);

		// 품목별 부수 정보를 미리 묶어둔다 (N+1 방지)
		Map<Integer, Map<String, Object>> kindsByItem = groupKinds(getKinds(spjangcd, projNo));
		Map<Integer, Map<String, Object>> procsByItem = groupProcs(getProcs(spjangcd, projNo));
		Map<Integer, Map<String, Object>> workingByItem = groupWorking(getWorking(spjangcd, projNo));

		// 프로젝트 단위 유형 집계 — 품목 미지정 실적까지 포함한다
		Map<String, Map<String, Object>> kindsByProj = groupProjectKinds(getProjectKinds(spjangcd, projNo));

		// WBS 계획 / 마일스톤 / 지연
		Map<String, List<Map<String, Object>>> wbsByProj = groupWbs(getWbs(spjangcd, projNo));

		// 가공 작업중 — 품목 미지정 건이 많아 프로젝트 단위로 따로 받는다
		Map<String, List<Map<String, Object>>> mworkByProj =
				groupProjectWorking(getProjectWorking(spjangcd, projNo));

		// 날짜별 가공 실적 (최근 14일). 작업량 지표이며 진척률에는 안 쓴다
		Map<String, List<Map<String, Object>>> dailyByProj =
				groupDaily(getDailyResults(spjangcd, projNo));

		// 프로젝트 → 라인 → 품목 으로 접는다
		Map<String, List<Map<String, Object>>> itemsByProj = new LinkedHashMap<>();
		for (Map<String, Object> it : items) {
			itemsByProj.computeIfAbsent(str(it.get("proj_no")), k -> new ArrayList<>()).add(it);
		}

		List<Map<String, Object>> out = new ArrayList<>();

		for (Map<String, Object> p : projects) {
			String pno = str(p.get("proj_no"));
			List<Map<String, Object>> rows = itemsByProj.getOrDefault(pno, new ArrayList<>());

			Map<String, List<Map<String, Object>>> byLine = new LinkedHashMap<>();
			for (Map<String, Object> r : rows) {
				String line = str(r.get("line_name"));
				if (line.isEmpty()) line = "MAIN";   // 라인이 비면 한 덩이로
				byLine.computeIfAbsent(line, k -> new ArrayList<>()).add(r);
			}

			List<Map<String, Object>> lines = new ArrayList<>();
			for (Map.Entry<String, List<Map<String, Object>>> e : byLine.entrySet()) {
				List<Map<String, Object>> itemList = new ArrayList<>();
				for (Map<String, Object> r : e.getValue()) {
					itemList.add(buildItem(r, kindsByItem, procsByItem, workingByItem));
				}
				Map<String, Object> line = new LinkedHashMap<>();
				line.put("name", e.getKey());
				line.put("items", itemList);
				lines.add(line);
			}

			Map<String, Object> proj = new LinkedHashMap<>();
			proj.put("name", str(p.get("proj_name")).isEmpty() ? pno : str(p.get("proj_name")));
			proj.put("projNo", pno);
			proj.put("owner", str(p.get("owner")));
			proj.put("due", str(p.get("due")));
			proj.put("ship", false);   // 출하 관리는 이 범위 밖. 항상 false
			proj.put("outd", false);   // 출고 관리는 이 범위 밖
			proj.put("dsteps", buildDesignSteps(rows));
			// 가공품 축 요약. 품목별 합계보다 클 수 있다 (품목 미지정 실적이 여기만 잡힘)
			proj.put("kinds", kindsByProj.getOrDefault(pno, new LinkedHashMap<>()));

			List<Map<String, Object>> wbs = wbsByProj.getOrDefault(pno, new ArrayList<>());
			proj.put("wbs", wbs);
			proj.put("milestones", milestonesOf(wbs));
			/*
			 * ★ 종료를 두 단계로 나눈다.
			 *   shipped   포장·출하 완료 → <b>더 만들 것이 없다.</b> KPI 합계에서 뺀다
			 *   closed    납품 완료     → 인수까지 끝났다. 목록에서도 뺀다
			 *
			 *   그 사이 구간(출하했지만 납품 확인 전)은 목록에 남아야 한다.
			 *   만들 게 없다고 프로젝트가 끝난 것은 아니기 때문이다.
			 *
			 *   WBS 가 없는 프로젝트는 두 단계를 찾을 수 없다.
			 *   그때는 화면이 검사 완료로 떨어뜨린다 (isAllDone).
			 */
			proj.put("shipped", wbsDone(wbs, "포장·출하"));
			proj.put("closed", wbsDone(wbs, "납품 완료"));
			proj.put("hasWbs", !wbs.isEmpty());
			proj.put("delayCnt", wbs.stream().filter(w -> "Y".equals(str(w.get("delay_yn")))).count());
			proj.put("mwork", mworkByProj.getOrDefault(pno, new ArrayList<>()));
			proj.put("daily", dailyByProj.getOrDefault(pno, new ArrayList<>()));
			proj.put("lines", lines);
			out.add(proj);
		}

		return out;
	}

	// =================================================================
	// 품목 1건 조립
	// =================================================================

	private Map<String, Object> buildItem(Map<String, Object> r,
										  Map<Integer, Map<String, Object>> kindsByItem,
										  Map<Integer, Map<String, Object>> procsByItem,
										  Map<Integer, Map<String, Object>> workingByItem) {

		Integer sujuId = toInt(r.get("suju_id"));
		// ★ need 는 <b>지그 대수</b>다. 조립·검사가 그 축으로 세기 때문이다.
		//   유닛(unit_qty)은 실적을 기록하지 않는 구성 정보라 분모가 될 수 없다.
		double need = toDouble(r.get("jig_qty"));
		boolean isOut = isOutsource(r.get("make_type"));

		@SuppressWarnings("unchecked")
		Map<String, Object> kinds = (Map<String, Object>)
				kindsByItem.getOrDefault(sujuId, new LinkedHashMap<>());
		@SuppressWarnings("unchecked")
		Map<String, Object> procs = (Map<String, Object>)
				procsByItem.getOrDefault(sujuId, new LinkedHashMap<>());

		Map<String, Object> parts = new LinkedHashMap<>();
		parts.put("kinds", kinds);
		parts.put("procs", procs);

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("code", str(r.get("item_name")));
		item.put("etype", str(r.get("equip_type")));   // 설비타입 (표시용)
		item.put("sujuId", sujuId);
		item.put("need", need);                                // 지그 대수 (조립·검사 목표)
		item.put("unit", toDouble(r.get("unit_qty")));         // 구성 유닛 수
		// ★ 유닛 조립 실적. 환산이 아니라 참값이다 (mat_produce PO=1).
		//   지그 완료(0 또는 1)만으로는 몇 달간 진척이 0 이라, 그 사이를 이 값이 메운다.
		item.put("unitDone", toDouble(r.get("unit_done")));
		// ★ 지시된 수량. 작업지시가 없으면 0 이다.
		//   need 는 수주에 등록된 양이고 ordered 는 실제로 지시가 나간 양이라
		//   둘의 차이가 곧 "아직 지시 안 한 물량" 이다.
		boolean ordered = toInt(r.get("job_res_id")) != null;
		item.put("ordered", ordered ? need : 0d);
		// ★ made 는 가공 실적으로 만들지 않는다. 아래 madeOf 주석 참조.
		item.put("made", madeOf(r, need, isOut));
		item.put("asm", toDouble(r.get("asm_qty")));           // 유닛 조립. 참값
		item.put("insp", estimateInsp(r, need));
		item.put("out", r.get("draw_date") != null);           // 2D 출도 여부
		// 미지정은 내작과 구분해 표시한다. 아래 typeOf 주석 참조.
		item.put("type", typeOf(r.get("make_type")));
		item.put("vendor", str(r.get("make_comp")));
		item.put("rcv", "Y".equals(str(r.get("rcv_yn"))));     // 외작 입고 완료
		item.put("parts", parts);
		item.put("working", workingByItem.get(sujuId));        // 없으면 null
		return item;
	}

	/**
	 * 제작 진척(유닛 축).
	 *
	 * ★ 가공 실적으로 계산하지 않는다. 예전에는 그렇게 했다가 걷어냈다.
	 *
	 *   유형별 (생산합 ÷ 필요합) 에 유닛수를 곱하는 방식이었는데,
	 *   분자와 분모의 <b>단위가 다르다.</b>
	 *     필요량(BOM 340) = 부품 개수
	 *     생산량          = 설비가 처리한 횟수
	 *   같은 plate 가 절단·가공·와이어를 거치면 공정마다 다시 세어진다.
	 *   어느 부품이 어느 공정을 거치는지 모르므로(라우팅 없음 — SPEC 3-2)
	 *   그 둘로 완성 개수를 만들 수 없다.
	 *   최댓값(kindDone)으로 바꿔 봐도 "몇 개가 절단됐다" 이지 완성이 아니다.
	 *
	 *   게다가 현장은 가공 수량을 세는 습관 자체가 없어 값이 기억에 의존한다.
	 *   추정을 추정으로 나눈 비율이라 진척률로 쓸 수 없었다.
	 *
	 *   그래서 제작 진척은 <b>조립(유닛 실적)</b>이 대신한다. 그쪽은 참값이다.
	 *   가공 실적은 kinds / procs 에 공정별로 그대로 실려 나가며,
	 *   "오늘 얼마나 했나" 를 보는 작업량 지표로만 쓴다.
	 *   비어 있는 가공 구간은 WBS 계획일이 메운다.
	 *
	 *   외작은 공정을 통째로 입고하므로 화면이 rcv 로 계산한다. 여기서는 0.
	 */
	private double madeOf(Map<String, Object> r, double need, boolean isOutsource) {
		if (isOutsource || need <= 0) return 0d;
		return Math.min(need, toDouble(r.get("asm_qty")));
	}

	/**
	 * 외작 판정.
	 *
	 * suju.make_type 실제 분포 (2026-08 확인):
	 *   'outsource' 136 · 'self' 81 · <b>빈 값 55</b>
	 *
	 * ★ 빈 값 55건은 내작으로 본다.
	 *   외작은 업체·입고 정보가 따라붙어야 하는데 그게 없는 행이므로,
	 *   외작으로 잡으면 "업체 미지정 · 영원히 미입고" 상태가 만들어져
	 *   외주 현황과 납기 경보가 전부 오염된다.
	 *   내작으로 두면 가공 실적이 안 붙어 진척이 0 으로 남을 뿐이라
	 *   틀렸을 때 눈에 띈다. <b>조용히 틀리지 않는 쪽</b>을 고른 것이다.
	 *
	 *   다만 55건은 수주 등록에서 구분이 빠진 데이터다. 화면 문제가 아니라
	 *   입력 문제이므로 수주 쪽에서 채워야 한다.
	 */
	private boolean isOutsource(Object makeType) {
		return "outsource".equalsIgnoreCase(str(makeType));
	}

	/**
	 * 화면 표기: 내작 / 외작 / <b>미지정</b>.
	 *
	 * ★ 미지정을 내작으로 뭉개지 않는다.
	 *   집계에서는 여전히 내작으로 취급한다 (외작으로 잡으면 업체·입고가 없어
	 *   "영원히 미입고" 가 만들어지고 외주 현황과 납기 경보가 오염된다).
	 *   하지만 배지까지 '내작' 으로 붙이면 <b>누가 지정한 것처럼 보인다.</b>
	 *   실제로 2026-002 는 52건 전부가 구분 없이 들어와 있는데
	 *   화면만 보면 전부 내작으로 정해진 것으로 읽힌다.
	 *
	 *   수주 등록에서 채워야 할 값이므로, 화면은 비어 있다는 사실만 정직하게 보인다.
	 */
	private String typeOf(Object makeType) {
		if (str(makeType).isEmpty()) return "미지정";
		return isOutsource(makeType) ? "외작" : "내작";
	}

	/**
	 * 검사 진척을 유닛으로 환산.
	 *
	 * 검사는 공정(세트) 단위라 유닛 축과 다르다.
	 * 세트 목표 대비 검사 완료 비율에 유닛수를 곱한다.
	 * 검사 면제(업체 검사 완료로 입고된 외작)는 전량 검사된 것으로 본다.
	 */
	private double estimateInsp(Map<String, Object> r, double need) {
		if ("Y".equals(str(r.get("exempt_yn")))) return need;

		double target = toDouble(r.get("set_target"));
		double done = toDouble(r.get("insp_qty"));
		if (need <= 0 || target <= 0 || done <= 0) return 0d;

		return Math.floor(need * Math.min(1d, done / target));
	}

	/**
	 * 프로젝트 WBS (계획/마일스톤/지연).
	 *
	 * wbs_plan 의 <b>프로젝트 레벨 행</b>만 읽는다 (suju_head_id IS NULL).
	 *   수주 확정분(suju_head_id NOT NULL)은 수주 단위로 복제된 것이라
	 *   프로젝트 카드에 세우면 같은 단계가 수주 수만큼 중복된다.
	 *
	 * ★ 지연은 <b>저장하지 않고 조회 시 판정</b>한다.
	 *   저장하면 날짜가 바뀔 때마다 갱신해야 하고 반드시 어긋난다.
	 *   판정식: pl_eddate &lt; 오늘 AND ac_eddate IS NULL
	 *
	 * ★ 진척률(progress)은 담당자가 입력하는 값이다.
	 *   가공 실적에서 자동 계산하지 않는다 — SPEC 3-1 이 인정한 낙관 편향이
	 *   그대로 WBS 진척률로 올라가면 지연 경보가 울려야 할 때 안 울린다.
	 *   가공 진척은 kinds 표에 별도로 보이므로 둘을 나란히 두는 편이 정직하다.
	 *
	 * 날짜는 varchar('yyyymmdd') 다. TO_CHAR 를 쓰면 함수가 없다는 오류가 난다.
	 */
	private List<Map<String, Object>> getWbs(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("wbs", """
            SELECT w.projno
                 , w.step_no
                 , w.step_name
                 , w.seq
                 , w.task_name
                 , w.pl_stdate
                 , w.pl_eddate
                 , w.ac_stdate
                 , w.ac_eddate
                 , COALESCE(w.progress, 0)              AS progress
                 -- ★ 표기를 'Y'/'N' 으로 정규화한다.
                 --   DB 에는 '1'/'0' 이 들어 있는데 화면·서버가 'Y' 로 비교해
                 --   마일스톤이 하나도 안 잡혔다 (◆ 표시도, 칩 줄도 비어 있었다).
                 --   저장 형식을 바꾸는 것은 프로젝트 등록 쪽이라 조회에서 맞춘다.
                 , CASE WHEN COALESCE(w.milestone_yn, '') IN ('Y', 'y', '1', 'T', 'true')
                        THEN 'Y' ELSE 'N' END          AS milestone_yn
                 , pc."Name"                            AS charge_name
                 , CASE WHEN w.ac_eddate IS NOT NULL AND w.ac_eddate <> '' THEN 'done'
                        WHEN w.ac_stdate IS NOT NULL AND w.ac_stdate <> '' THEN 'cur'
                        ELSE 'wait' END                 AS state
                 -- 지연: 완료 예정일이 지났는데 완료 실적이 없다
                 , CASE WHEN COALESCE(w.ac_eddate, '') = ''
                         AND COALESCE(w.pl_eddate, '') <> ''
                         AND w.pl_eddate < TO_CHAR(now(), 'YYYYMMDD')
                        THEN 'Y' ELSE 'N' END           AS delay_yn
            FROM wbs_plan w
            LEFT JOIN person pc ON pc.id = w.charge_id
            WHERE w.spjangcd = :spjangcd
              AND w.suju_head_id IS NULL
              AND (CAST(:projNo AS varchar) IS NULL
                   OR w.projno = CAST(:projNo AS varchar))
            ORDER BY w.projno, w.step_no, w.seq
            """, p);
	}

	/**
	 * 마일스톤만 추린다 (milestone_yn='Y').
	 *
	 * 게이트 판정은 <b>진척률이 아니라 완료 여부</b>다.
	 *   가공 단계 진척률은 낙관 편향이 있어 임계값으로 쓰면 경보가 늦게 울린다.
	 *   마일스톤은 실적일(ac_eddate)이 찍혔는지만 본다.
	 */
	/**
	 * WBS 특정 단계가 완료됐는지.
	 *
	 * 단계 이름으로 찾는다. 템플릿이 통일돼 있어 가능한 방식이고,
	 * 이름이 바뀌면 여기도 같이 바꿔야 한다 —
	 *   SELECT DISTINCT step_name, task_name FROM wbs_plan;
	 *
	 * 실적일(ac_eddate)이 찍혔는지만 본다. 진척률은 담당자 입력값이라
	 * 100% 로 적어 두고 실제로는 안 끝난 경우가 있다.
	 */
	private boolean wbsDone(List<Map<String, Object>> wbs, String taskName) {
		for (Map<String, Object> w : wbs) {
			if (!taskName.equals(str(w.get("task_name")))) continue;
			return !str(w.get("ac_eddate")).isEmpty();
		}
		return false;
	}

	private List<Map<String, Object>> milestonesOf(List<Map<String, Object>> wbs) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> w : wbs) {
			if (!"Y".equals(str(w.get("milestone_yn")))) continue;

			Map<String, Object> m = new LinkedHashMap<>();
			m.put("n", str(w.get("task_name")));
			m.put("step", str(w.get("step_name")));
			m.put("pl", str(w.get("pl_eddate")));
			m.put("ac", str(w.get("ac_eddate")));
			// 화면 계약: done / cur / wait — dsteps 와 같은 값이라 같은 렌더러를 쓴다
			m.put("s", str(w.get("state")));
			m.put("delay", "Y".equals(str(w.get("delay_yn"))));
			out.add(m);
		}
		return out;
	}

	/**
	 * 날짜별 · 공정별 가공 실적 (최근 14일).
	 *
	 * ★ 이 숫자는 <b>그 설비가 처리한 횟수</b>다. 완성 개수가 아니다.
	 *   한 부품이 절단·가공을 거치면 공정마다 다시 세어지므로
	 *   가로로 더하면 실물보다 커진다. 화면이 공정별로 갈라서 보여준다.
	 *
	 *   진척률에는 쓰지 않는다. "오늘 얼마나 돌았나" 를 보는 작업량 지표다.
	 */
	private List<Map<String, Object>> getDailyResults(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("daily", """
            SELECT r."Project_id"                        AS proj_no
                 , TO_CHAR(r."ProdDate", 'MM-DD')        AS d
                 , COALESCE(r."Operation", '')           AS operation
                 , SUM(COALESCE(r."GoodQty", 0))         AS qty
                 , COUNT(DISTINCT r."Worker")            AS worker_cnt
            FROM iljin_prod_result r
            WHERE r.spjangcd = :spjangcd
              AND r."ProdDate" >= CURRENT_DATE - 13
              AND (CAST(:projNo AS varchar) IS NULL
                   OR r."Project_id" = CAST(:projNo AS varchar))
            GROUP BY r."Project_id", r."ProdDate", r."Operation"
            ORDER BY r."ProdDate" DESC, r."Operation"
            """, p);
	}

	/** projno → 날짜별 실적 */
	private Map<String, List<Map<String, Object>>> groupDaily(List<Map<String, Object>> rows) {
		Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			String pno = str(r.get("proj_no"));
			if (pno.isEmpty()) continue;
			out.computeIfAbsent(pno, k -> new ArrayList<>()).add(r);
		}
		return out;
	}

	/**
	 * 프로젝트 단위 <b>가공 작업중</b> 목록.
	 *
	 * getWorking() 은 suju 를 JOIN 하므로 <b>품목 미지정 작업이 통째로 빠진다.</b>
	 *   가공 키오스크는 품목 선택이 선택 항목이라 실제로 자주 비어 있고,
	 *   그러면 설비가 돌고 있는데 대시보드에는 아무것도 안 보인다.
	 *
	 * 여기서는 "Project_id" 로만 묶어 그 프로젝트에 걸린 설비를 전부 보여준다.
	 */
	private List<Map<String, Object>> getProjectWorking(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("projectWorking", """
            SELECT w."Project_id"    AS proj_no
                 , w."Operation"     AS stage
                 , w."Equipment"     AS machine
                 , w."Worker"        AS worker
                 , w."Kind"          AS kind
                 , s."Material_Name" AS item_name
                 , TO_CHAR(w."StartTime", 'HH24:MI') AS start_time
            FROM iljin_prod_working w
            LEFT JOIN suju s ON s.id = w."Suju_id"
            WHERE w.spjangcd = :spjangcd
              AND COALESCE(w."Project_id", '') <> ''
              AND (CAST(:projNo AS varchar) IS NULL
                   OR w."Project_id" = CAST(:projNo AS varchar))
            ORDER BY w."StartTime"
            """, p);
	}

	/** projno → 가공 작업중 목록 */
	private Map<String, List<Map<String, Object>>> groupProjectWorking(List<Map<String, Object>> rows) {
		Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			String pno = str(r.get("proj_no"));
			if (pno.isEmpty()) continue;
			out.computeIfAbsent(pno, k -> new ArrayList<>()).add(r);
		}
		return out;
	}

	/** projno → WBS 행 목록 */
	private Map<String, List<Map<String, Object>>> groupWbs(List<Map<String, Object>> rows) {
		Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			String pno = str(r.get("projno"));
			if (pno.isEmpty()) continue;
			out.computeIfAbsent(pno, k -> new ArrayList<>()).add(r);
		}
		return out;
	}

	/**
	 * 설계 단계.
	 *
	 * ★ 도면 이력(dwg_drawing)의 실제 컬럼을 확인하지 못해
	 *   지금은 suju.draw_date(도면 출도일)만으로 최소 구성한다.
	 *   검도 차수를 표시하려면 dwg_drawing 스키마가 필요하다.
	 *
	 * 전체 품목 중 출도된 비율로 상태를 정한다.
	 */
	private List<Map<String, Object>> buildDesignSteps(List<Map<String, Object>> rows) {
		long total = rows.size();
		long done = rows.stream().filter(r -> r.get("draw_date") != null).count();

		String state = (total > 0 && done >= total) ? "done" : (done > 0 ? "cur" : "wait");

		Map<String, Object> step = new LinkedHashMap<>();
		step.put("n", "출도");
		step.put("s", state);
		return List.of(step);
	}

	// =================================================================
	// 쿼리
	// =================================================================

	private List<Map<String, Object>> getProjects(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("projects", """
            SELECT d.projno   AS proj_no
                 , d.projnm   AS proj_name
                 , d.balcltnm AS owner
                 -- ★ eddate 는 date 가 아니라 varchar('yyyymmdd') 다.
                 --   TO_CHAR(varchar, unknown) 은 존재하지 않는 함수라 첫 조회에서 터졌다.
                 --   문자열을 그대로 잘라 붙인다. 8자리가 아니면 원문을 준다
                 --   (빈 값 · 이미 포맷된 값 대비).
                 , CASE WHEN d.eddate ~ '^[0-9]{8}$'
                        THEN SUBSTRING(d.eddate, 1, 4) || '-' ||
                             SUBSTRING(d.eddate, 5, 2) || '-' ||
                             SUBSTRING(d.eddate, 7, 2)
                        ELSE COALESCE(d.eddate, '') END AS due
            FROM tb_da003 d
            WHERE d.spjangcd = :spjangcd
              AND COALESCE(d.endflag, '0') <> '1'
              AND (CAST(:projNo AS varchar) IS NULL OR d.projno = CAST(:projNo AS varchar))
            ORDER BY d.eddate, d.projno
            """, p);
	}

	/**
	 * 품목 + 조립/검사 집계.
	 *
	 * 작업 지시된 품목만 대상이다 (지시 없는 것은 관리 밖).
	 *   asm_qty   유닛 조립 완료 (ProcessOrder=1)
	 *   insp_qty  검사 완료      (ProcessOrder=3)
	 *   rcv_yn    외작 입고 완료 여부
	 *   exempt_yn 업체 검사 완료로 입고된 외작
	 */
	private List<Map<String, Object>> getItems(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("items", """
            SELECT s.id                       AS suju_id
                 , s.project_id               AS proj_no
                 , s.line                     AS line_name
                 , s."Material_Name"          AS item_name
                 -- 설비타입(고정지그·턴테이블…). 품목명만으로는 뭘 만드는지 알기 어렵다
                 , COALESCE(s.equip_type, '')  AS equip_type
                 -- ★ 화면의 need 는 <b>지그 대수</b>다. 공정 조립·검사가 그 축으로 센다.
                 --   unit_qty 는 그 지그를 이루는 유닛 수이며 별도 축으로 표시한다.
                 , COALESCE(s.unit_qty, 0)    AS unit_qty
                 , COALESCE(s."SujuQty", 0)   AS jig_qty
                 -- ★ 'inhouse' 로 채우지 않는다. 미지정을 내작과 구분해 표시하려면
                 --   빈 값이 그대로 올라와야 한다 (typeOf 참조).
                 , COALESCE(s.make_type, '')        AS make_type
                 , s.make_comp_name           AS make_comp
                 , s.draw_date                AS draw_date
                 -- 검사 목표 = 지그 대수. "Standard" 는 '1/1'(한 쌍) 표기가 섞여 쓰지 않는다
                 , COALESCE(s."SujuQty", 0) AS set_target
                 , j.id                       AS job_res_id
                 , COALESCE(u.qty, 0)         AS asm_qty     -- 공정 조립 (지그)
                 , COALESCE(uu.qty, 0)        AS unit_done   -- 유닛 조립 (참값)
                 , COALESCE(i.qty, 0)         AS insp_qty
                 , CASE WHEN rc.suju_id IS NOT NULL THEN 'Y' ELSE 'N' END AS rcv_yn
                 , CASE WHEN ex.suju_id IS NOT NULL THEN 'Y' ELSE 'N' END AS exempt_yn
            FROM suju s
            -- ★ LEFT JOIN 이어야 한다.
            --   INNER 면 <b>작업지시가 난 품목만</b> 보인다. 그러면
            --   ① "수주 12,000 / 지시 8,000" 의 차이를 화면이 표현할 수 없고
            --   ② 외작은 생산 지시를 하지 않으므로 대시보드에서 통째로 사라진다.
            LEFT JOIN job_res j
              ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            -- ★ 조립은 두 단계다. 축이 달라 따로 센다.
            --   PO=2 공정 조립 → asm_qty   (분모 "SujuQty"). 화면의 need 가 이쪽이다
            --   PO=1 유닛 조립 → unit_done (분모 unit_qty). 참값이며 진척의 빈 구간을 메운다
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS qty
                FROM mat_produce WHERE "ProcessOrder" = 2 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) u ON u."JobResponse_id" = j.id
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS qty
                FROM mat_produce WHERE "ProcessOrder" = 1 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) uu ON uu."JobResponse_id" = j.id
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS qty
                FROM mat_produce WHERE "ProcessOrder" = 3 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) i ON i."JobResponse_id" = j.id
            -- 외작 입고 여부
            LEFT JOIN (
                SELECT DISTINCT b."PlanDataPk" AS suju_id
                FROM balju b
                JOIN mat_inout mi ON mi."SourceTableName" = 'balju'
                                 AND mi."SourceDataPk" = b.id
                                 AND mi."InOut" = 'in'
                WHERE b."PlanTableName" = 'suju'
            ) rc ON rc.suju_id = s.id
            -- 업체 검사 완료로 입고된 외작 (검사 면제)
            LEFT JOIN (
                SELECT DISTINCT b."PlanDataPk" AS suju_id
                FROM balju b
                JOIN mat_inout mi         ON mi."SourceTableName" = 'balju'
                                         AND mi."SourceDataPk" = b.id
                JOIN mat_inout_inspect ii ON ii."MatInout_id" = mi.id
                WHERE b."PlanTableName" = 'suju' AND ii."InspectYN" = 'Y'
            ) ex ON ex.suju_id = s.id
            WHERE s.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
            ORDER BY s.project_id, s.line, s.id
            """, p);
	}

	/**
	 * 품목 × 유형 × <b>가공공정</b>별 필요량 / 생산량.
	 *
	 * ★ 필요량은 부품표 "Qty" 의 단순 합이다. 유니트수를 곱하지 않는다.
	 *   현장이 부품표에 적는 것은 "이 지그에 브라켓 300개" 라는 전체 총량이다.
	 *
	 * ★ 생산량을 <b>공정별로 쪼개는 이유</b>
	 *   한 부품은 절단 → 가공 → 와이어커팅을 거치며 <b>공정마다 다시 세어진다.</b>
	 *   plate 를 340개 자르고 그중 120개를 가공했다면 실물은 340개인데
	 *   공정을 합치면 460 이 된다. 이전 버전이 SUM 으로 합쳐서,
	 *   절단만 끝나도 진척이 100%(min 으로 잘려서)로 보였다.
	 *   자세한 계산은 kindDone() 참조.
	 *
	 * 생산량은 품목에 귀속된 실적만 센다.
	 * 품목 미지정 실적은 여기 들어오지 않는다 — 프로젝트 레벨(getProjectKinds)이 받는다.
	 */
	private List<Map<String, Object>> getKinds(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("kinds", """
            SELECT t.suju_id
                 , t.kind
                 , t.operation
                 , SUM(t.need_qty) AS need_qty
                 , SUM(t.done_qty) AS done_qty
            FROM (
                -- 필요량: 공정을 모른다. BOM 은 라우팅을 갖지 않는다 (SPEC 3-2)
                --         수량은 유닛당이 아니라 공정(품목) 전체 총량이다
                SELECT b."Suju_id" AS suju_id
                     , b."Kind"    AS kind
                     , ''          AS operation
                     , b."Qty" AS need_qty
                     , 0 AS done_qty
                FROM iljin_suju_bom b
                JOIN suju s ON s.id = b."Suju_id"
                WHERE b."Gubun" = '제작품'
                  AND s.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL
                       OR s.project_id = CAST(:projNo AS varchar))

                UNION ALL

                -- 생산량: 공정별로 따로 남긴다
                SELECT r."Suju_id"
                     , COALESCE(r."Kind", 'etc')
                     , COALESCE(r."Operation", '')
                     , 0
                     , COALESCE(r."GoodQty", 0)
                FROM iljin_prod_result r
                JOIN suju s ON s.id = r."Suju_id"
                WHERE r.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL
                       OR s.project_id = CAST(:projNo AS varchar))
            ) t
            WHERE t.suju_id IS NOT NULL
            GROUP BY t.suju_id, t.kind, t.operation
            ORDER BY t.suju_id, t.kind, t.operation
            """, p);
	}

	/**
	 * <b>프로젝트 단위</b> 유형별 필요량 / 공정별 생산량.
	 *
	 * ★ 품목별 집계(getKinds)와 별개로 존재하는 이유
	 *   가공 키오스크는 품목 선택이 <b>선택 항목</b>이다.
	 *   현장이 절단할 때 그 plate 가 S10 것인지 RS01 것인지 모르는 경우가 많고,
	 *   억지로 고르게 하면 아무거나 찍는다 (SPEC 3-1 이 파기 미기록을 인정한 것과 같은 이유).
	 *   그래서 실적은 프로젝트만 붙어 오는 경우가 있고,
	 *   그 실적은 품목 축 집계에서 <b>사라진다.</b>
	 *
	 *   여기서는 "Project_id" 로만 묶으므로 품목 미지정 실적도 전부 잡힌다.
	 *   현장이 알고 싶어하는 <b>"이 프로젝트에서 가공품을 얼마나 만들어야 하나"</b>
	 *   의 답은 이쪽이다.
	 *
	 *   필요량은 프로젝트에 속한 모든 품목의 BOM 합계다 — 실적과 무관하게 항상 계산된다.
	 *   부품이 하나도 등록되지 않았으면 0 이 나오고, 화면은 그것을
	 *   "생산 지시에서 부품을 먼저 등록하세요" 로 읽으면 된다 (SPEC 5-3).
	 */
	private List<Map<String, Object>> getProjectKinds(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("projectKinds", """
            SELECT t.proj_no
                 , t.kind
                 , t.operation
                 , SUM(t.need_qty) AS need_qty
                 , SUM(t.done_qty) AS done_qty
            FROM (
                -- 필요량 : 프로젝트의 모든 품목 BOM 합계 (제작품만).
                --          부품 수량이 이미 전체 총량이므로 유니트수를 곱하지 않는다.
                SELECT COALESCE(s.project_id, b."Project_id") AS proj_no
                     , b."Kind"    AS kind
                     , ''          AS operation
                     , b."Qty" AS need_qty
                     , 0 AS done_qty
                FROM iljin_suju_bom b
                -- ★ 프로젝트 공통 부품(Suju_id IS NULL)도 센다.
                --   2D 도면 전 추정 물량이라 품목에 안 붙지만 필요량에는 들어간다.
                --   JOIN 이면 그 행이 통째로 빠져 화면에서 사라진다.
                LEFT JOIN suju s ON s.id = b."Suju_id"
                WHERE b."Gubun" = '제작품'
                  AND b.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL
                       OR COALESCE(s.project_id, b."Project_id") = CAST(:projNo AS varchar))

                UNION ALL

                -- 생산량 : suju 를 거치지 않는다. 품목 미지정 실적도 잡기 위함
                SELECT r."Project_id"
                     , COALESCE(r."Kind", 'etc')
                     , COALESCE(r."Operation", '')
                     , 0
                     , COALESCE(r."GoodQty", 0)
                FROM iljin_prod_result r
                WHERE r.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL
                       OR r."Project_id" = CAST(:projNo AS varchar))
            ) t
            WHERE t.proj_no IS NOT NULL AND t.proj_no <> ''
            GROUP BY t.proj_no, t.kind, t.operation
            ORDER BY t.proj_no, t.kind, t.operation
            """, p);
	}

	/** proj_no → {유형: {t, d, ops}} — groupKinds 와 같은 모양 */
	private Map<String, Map<String, Object>> groupProjectKinds(List<Map<String, Object>> rows) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();

		for (Map<String, Object> r : rows) {
			String pno = str(r.get("proj_no"));
			if (pno.isEmpty()) continue;

			String kind = str(r.get("kind")).isEmpty() ? "기타" : str(r.get("kind"));
			String op   = str(r.get("operation"));

			Map<String, Object> byKind = out.computeIfAbsent(pno, k -> new LinkedHashMap<>());

			@SuppressWarnings("unchecked")
			Map<String, Object> e = (Map<String, Object>) byKind.computeIfAbsent(kind, k -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("t", 0d);
				m.put("d", 0d);
				m.put("ops", new LinkedHashMap<String, Object>());
				return m;
			});

			e.put("t", toDouble(e.get("t")) + toDouble(r.get("need_qty")));

			double done = toDouble(r.get("done_qty"));
			if (!op.isEmpty() && done != 0d) {
				@SuppressWarnings("unchecked")
				Map<String, Object> ops = (Map<String, Object>) e.get("ops");
				ops.put(op, toDouble(ops.get(op)) + done);
			}
		}

		for (Map<String, Object> byKind : out.values()) {
			for (Object v : byKind.values()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> e = (Map<String, Object>) v;
				@SuppressWarnings("unchecked")
				Map<String, Object> ops = (Map<String, Object>) e.get("ops");
				e.put("d", kindDone(ops));
			}
		}
		return out;
	}

	/**
	 * 가공공정별 실적.
	 * 공정명은 work_center."Name" 이다 — 설비가 공정을 결정하므로 별도 마스터가 없다.
	 */
	private List<Map<String, Object>> getProcs(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("procs", """
            SELECT r."Suju_id"  AS suju_id
                 , r."Operation" AS operation
                 , SUM(COALESCE(r."GoodQty", 0)) AS qty
            FROM iljin_prod_result r
            JOIN suju s ON s.id = r."Suju_id"
            WHERE r.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
            GROUP BY r."Suju_id", r."Operation"
            ORDER BY r."Suju_id", r."Operation"
            """, p);
	}

	/**
	 * 진행중인 <b>조립·검사</b>. 품목(suju)에 붙는다.
	 *
	 * 품목당 1건만 보여주면 되므로 우선순위를 둔다: 검사(3) &gt; 조립(1,2).
	 * 뒤 공정이 돌고 있으면 그게 현재 상태이기 때문이다.
	 *
	 * ★ 가공은 여기 없다. getProjectWorking() 이 맡는다.
	 *   가공 작업중은 품목이 비는 경우가 흔해 프로젝트 단위로 읽어야 하고,
	 *   양쪽에 두면 같은 작업이 두 줄로 나온다.
	 */
	private List<Map<String, Object>> getWorking(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return rows("working", """
            SELECT w.suju_id, w.stage, w.machine, w.worker, w.pri
            FROM (
                -- 조립 / 검사 (mat_produce 진행중)
                SELECT j."SourceDataPk" AS suju_id
                     , CASE mp."ProcessOrder" WHEN 3 THEN '검사' ELSE '조립' END AS stage
                     , COALESCE(e."Code", wc."Name") AS machine
                     , pw."Name"        AS worker
                     , CASE mp."ProcessOrder" WHEN 3 THEN 1 ELSE 2 END AS pri
                FROM mat_produce mp
                JOIN job_res j ON j.id = mp."JobResponse_id"
                              AND j."SourceTableName" = 'suju'
                JOIN suju s    ON s.id = j."SourceDataPk"
                LEFT JOIN equ e          ON e.id = mp."Equipment_id"
                LEFT JOIN work_center wc ON wc.id = mp."WorkCenter_id"
                LEFT JOIN person pw      ON pw.id = mp."Actor_id"
                WHERE COALESCE(mp."State", '') <> 'finished'
                  AND mp.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL
                       OR s.project_id = CAST(:projNo AS varchar))

                -- ★ 가공(iljin_prod_working)은 여기서 빼야 한다.
                --   getProjectWorking() 이 같은 테이블을 프로젝트 단위로 읽고,
                --   화면은 그 둘을 합쳐 보여준다.
                --   여기에도 두면 품목을 골라 시작한 가공이 <b>두 줄로 나온다</b>
                --   ('머시닝센터' 와 '가공' 이 같은 작업인데 따로 보였다).
                --   이 조회는 <b>조립·검사</b>만 맡는다.
            ) w
            WHERE w.suju_id IS NOT NULL
            ORDER BY w.suju_id, w.pri
            """, p);
	}

	// =================================================================
	// 그룹핑
	// =================================================================

	/**
	 * suju_id → {유형: {t: 필요, d: 생산, ops: {공정: 수량}}}
	 *
	 * 행이 유형 × 공정으로 쪼개져 오므로 유형 단위로 다시 접는다.
	 *   t  = 필요량 (공정이 없는 행에서만 온다)
	 *   ops= 공정별 생산량. <b>화면이 실제로 보여줄 값은 이쪽이다</b>
	 *   d  = kindDone(ops). 합이 아니다 — 아래 참조
	 */
	private Map<Integer, Map<String, Object>> groupKinds(List<Map<String, Object>> rows) {
		Map<Integer, Map<String, Object>> out = new HashMap<>();

		for (Map<String, Object> r : rows) {
			Integer id = toInt(r.get("suju_id"));
			if (id == null) continue;

			String kind = str(r.get("kind")).isEmpty() ? "기타" : str(r.get("kind"));
			String op   = str(r.get("operation"));

			Map<String, Object> byKind = out.computeIfAbsent(id, k -> new LinkedHashMap<>());

			@SuppressWarnings("unchecked")
			Map<String, Object> e = (Map<String, Object>) byKind.computeIfAbsent(kind, k -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("t", 0d);
				m.put("d", 0d);
				m.put("ops", new LinkedHashMap<String, Object>());
				return m;
			});

			e.put("t", toDouble(e.get("t")) + toDouble(r.get("need_qty")));

			double done = toDouble(r.get("done_qty"));
			if (!op.isEmpty() && done != 0d) {
				@SuppressWarnings("unchecked")
				Map<String, Object> ops = (Map<String, Object>) e.get("ops");
				ops.put(op, toDouble(ops.get(op)) + done);
			}
		}

		// ops 가 다 모인 뒤에 d 를 확정한다
		for (Map<String, Object> byKind : out.values()) {
			for (Object v : byKind.values()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> e = (Map<String, Object>) v;
				@SuppressWarnings("unchecked")
				Map<String, Object> ops = (Map<String, Object>) e.get("ops");
				e.put("d", kindDone(ops));
			}
		}
		return out;
	}

	/**
	 * 공정별 실적에서 <b>실물이 몇 개나 존재하는지</b> 추정한다.
	 *
	 * ★ 합이 아니라 <b>최댓값</b>이다.
	 *   같은 plate 가 절단에서 한 번, 가공에서 또 한 번 세어지므로
	 *   더하면 실물보다 커진다 (절단 340 + 가공 120 = 460 &gt; 실물 340).
	 *   한 부품이 같은 공정을 두 번 거치지 않는다고 보면,
	 *   가장 많이 처리한 공정의 수량이 곧 <b>존재하는 부품 수</b>다.
	 *
	 *   이것도 참값은 아니다. 340개가 절단됐다는 것이지 340개가 완성됐다는 뜻은 아니다.
	 *   즉 여전히 낙관적이지만, 합산(460)처럼 <b>실물보다 큰 수</b>는 나오지 않는다.
	 *   정확한 숫자가 필요하면 ops 를 공정별로 그대로 보면 된다 — 그쪽은 참값이다.
	 */
	private double kindDone(Map<String, Object> ops) {
		double max = 0d;
		for (Object v : ops.values()) max = Math.max(max, toDouble(v));
		return max;
	}

	/** suju_id → {공정명: 수량} */
	private Map<Integer, Map<String, Object>> groupProcs(List<Map<String, Object>> rows) {
		Map<Integer, Map<String, Object>> out = new HashMap<>();
		for (Map<String, Object> r : rows) {
			Integer id = toInt(r.get("suju_id"));
			if (id == null) continue;
			out.computeIfAbsent(id, k -> new LinkedHashMap<>())
					.put(str(r.get("operation")), toDouble(r.get("qty")));
		}
		return out;
	}

	/** suju_id → {stage, machine, worker}. 우선순위가 높은 첫 건만 남긴다 */
	private Map<Integer, Map<String, Object>> groupWorking(List<Map<String, Object>> rows) {
		Map<Integer, Map<String, Object>> out = new HashMap<>();
		for (Map<String, Object> r : rows) {
			Integer id = toInt(r.get("suju_id"));
			if (id == null || out.containsKey(id)) continue;   // 정렬돼 있으므로 첫 건이 우선

			Map<String, Object> w = new LinkedHashMap<>();
			w.put("stage", str(r.get("stage")));
			w.put("machine", str(r.get("machine")));
			w.put("worker", str(r.get("worker")));
			out.put(id, w);
		}
		return out;
	}

	// =================================================================
	// helper
	// =================================================================

	/**
	 * SqlRunner 는 SQL 오류를 삼키고 <b>null 을 반환</b>한다.
	 * 그대로 쓰면 호출부에서 NullPointerException 이 나고,
	 * 로그에는 NPE 가 먼저 찍혀 <b>진짜 원인인 SQL 오류가 뒤로 밀린다.</b>
	 * (실제로 TO_CHAR 오류를 찾는 데 이 때문에 시간이 걸렸다.)
	 *
	 * 대시보드는 벽걸이 모니터라 500 보다 빈 화면이 낫다.
	 * null 은 빈 목록으로 바꾸되 <b>어느 쿼리가 실패했는지 반드시 남긴다.</b>
	 */
	private List<Map<String, Object>> rows(String tag, String sql, MapSqlParameterSource p) {
		List<Map<String, Object>> r = sqlRunner.getRows(sql, p);
		if (r == null) {
			log.warn("[dash_project] {} 쿼리 실패(null 반환). 앞선 SQL 오류 로그를 확인할 것.", tag);
			return new ArrayList<>();
		}
		return r;
	}

	private String str(Object o) {
		return o == null ? "" : o.toString().trim();
	}

	private String nullIfEmpty(Object o) {
		String s = str(o);
		return s.isEmpty() ? null : s;
	}

	private Integer toInt(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		try {
			return Integer.parseInt(o.toString().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private double toDouble(Object o) {
		if (o == null || o.toString().isBlank()) return 0d;
		try {
			return Double.parseDouble(o.toString().trim());
		} catch (NumberFormatException e) {
			return 0d;
		}
	}
}