package mes.app.sales.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.balju.service.BaljuOrderService;
import mes.app.definition.service.BomService;
import mes.domain.entity.*;
import mes.domain.repository.BalJuHeadRepository;
import mes.domain.repository.BujuRepository;
import mes.domain.repository.MaterialRepository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;

/**
 * 수주 저장 → 라인 BOM / 외작 발주 동기화.
 *
 * <p>엑셀 업로드(/excel_save)와 수동 등록(/manual_save_project)이 <b>같은 경로</b>를 타게 한다.
 * 엑셀은 항상 신규 헤더라 결과적으로 전부 INSERT 가 되고, 수동은 저장할 때마다 diff 가 돈다.
 *
 * <h3>설계 전제 (바꾸기 전에 반드시 읽을 것)</h3>
 * <ul>
 *   <li><b>BOM 행은 재생성하지 않는다.</b> bom 에 UNIQUE("Material_id","BOMType","Version") 와
 *       UNIQUE("Material_id","BOMType","StartDate") 가 걸려 있어, 같은 모품목으로 다시 INSERT 하면
 *       충돌한다. 수정은 bom_comp 만 교체한다. 매핑은 suju_line_bom 이 갖는다.</li>
 *   <li><b>발주 라인은 삭제하지 않는다.</b> 업체로 나가는 문서이므로 되돌리기는 State='canceled'
 *       상태전이로만 한다. BaljuOrderService.balju_stop() 과 같은 규약이다.</li>
 *   <li><b>단가는 절대 덮어쓰지 않는다.</b> 발주 화면이 소유한다. 여기서는 수량이 바뀌었을 때
 *       기존 단가로 금액만 다시 계산한다.</li>
 *   <li><b>입고 누계가 하한선이다.</b> 무조건 차단이 아니라, 이미 입고된 수량 미만으로만 못 줄인다.
 *       3/10 입고 상태에서 10→12 증량이나 10→5 감량은 허용하고, 10→2 만 거부한다.
 *       (작업지시 수정 화면 prod_order_edit 의 edit_guard 와 같은 규칙)</li>
 *   <li><b>입고가 시작된 발주는 잠기지만, 수주 저장을 막지는 않는다.</b> 제작업체·설계업체·
 *       도면출도일·비고는 수주 문서의 정보이므로 언제든 고칠 수 있어야 한다.
 *       그런 행은 발주 동기화만 건너뛰고 발주는 나간 그대로 둔다 (skipped 로 집계).
 *       막는 것은 딱 둘 — 입고량 미만으로 수량을 줄이는 것, 그리고 행 삭제.</li>
 *   <li><b>막을 때는 조용히 넘기지 않고 저장 자체를 되돌린다.</b> 화면은 저장 전에
 *       {@link #getBaljuGuard(Integer)} 로 미리 물어 사유를 보여준다.</li>
 * </ul>
 */
@Slf4j
@Service
public class SujuSyncService {

	@Autowired SqlRunner sqlRunner;
	@Autowired BomService bomService;
	@Autowired MaterialRepository materialRepository;
	@Autowired BalJuHeadRepository balJuHeadRepository;
	@Autowired BujuRepository bujuRepository;
	@Autowired BaljuOrderService baljuOrderService;
	@Autowired SujuService sujuService;

	/** 라인 BOM 의 종류 / 버전 (SujuController 와 동일해야 한다) */
	private static final String BOM_TYPE    = "manufacturing";
	private static final String BOM_VERSION = "1.0";

	/** LINE(BOM 모품목) 품목그룹 (mat_grp."Code") */
	private static final String LINE_MATERIAL_GROUP_CODE = "FG";

	/** 발주구분 (sys_code."CodeType" = 'Balju_type') */
	private static final String BALJU_TYPE_OUTSOURCE = "outsource";

	/**
	 * 수주 헤더를 지울 때, 그 행들이 쓰던 품목까지 정리할지 여부.
	 *  판정은 isMaterialReferenced (6곳 참조 확인). 운영에서 품목 삭제 자체를
	 *  원하지 않으면 false. BOM · 발주 정리는 이 값과 무관하게 동작한다.
	 */
	private static final boolean CLEANUP_ROW_MATERIALS = true;

	/** 발주 라인 상태값. BaljuOrderService.balju_stop() 과 같은 집합. */
	private static final String ST_DRAFT    = "draft";
	private static final String ST_PARTIAL  = "partial";
	private static final String ST_RECEIVED = "received";
	private static final String ST_CANCELED = "canceled";

	/**
	 * 입고 누계와 발주 수량으로 발주 라인 상태를 판정한다.
	 *  BaljuOrderService.balju_stop() 의 '중지 취소' 분기와 같은 규칙이라,
	 *  수량을 고치면 received ↔ partial 이 자동으로 따라간다.
	 */
	private static String resolveState(double inQty, double orderQty) {
		if (inQty <= 0)      return ST_DRAFT;
		if (inQty < orderQty) return ST_PARTIAL;
		return ST_RECEIVED;
	}

	// ===================================================================
	//  결과 객체
	// ===================================================================

	/** 동기화 결과. ok=false 면 호출부가 롤백하고 message 를 그대로 보여준다. */
	public static class SyncResult {
		public boolean ok = true;
		public String  message;

		public int bomCreated;      // 새로 만든 BOM 헤더
		public int bomUpdated;      // 구성품을 갈아끼운 BOM 헤더
		public int bomRemoved;      // 라인이 사라져 지운 BOM 헤더

		public int baljuHeadCreated;
		public int baljuInserted;
		public int baljuUpdated;
		public int baljuCanceled;
		/** 입고가 있어 발주를 손대지 않고 넘어간 행. 수주는 저장된다. */
		public int baljuSkipped;

		static SyncResult fail(String msg) {
			SyncResult r = new SyncResult();
			r.ok = false;
			r.message = msg;
			return r;
		}

		/** 화면 메시지용 요약. 변화가 없으면 빈 문자열. */
		public String summary() {
			List<String> parts = new ArrayList<>();
			int bom = bomCreated + bomUpdated + bomRemoved;
			if (bom > 0) parts.add("BOM " + bom + "건");
			int balju = baljuInserted + baljuUpdated + baljuCanceled;
			if (balju > 0) parts.add("발주 " + balju + "건");
			if (baljuSkipped > 0) parts.add("발주 " + baljuSkipped + "건은 입고가 있어 유지");
			return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
		}
	}

	/** 수주 1행의 동기화 입력값 (suju 테이블에서 다시 읽어온 것) */
	private static class SujuRow {
		Integer id;
		String  line;
		Integer materialId;
		String  materialName;
		Double  qty;
		String  standard;
		String  makeType;
		Integer makeCompId;
		String  makeCompName;
		// 수주행의 비고. 발주 라인 비고로 내려보낸다.
		//  ★ 원본은 suju.item_remark(varchar 255) 이다. suju."Description" 이 아니다 —
		//    두 컬럼이 모두 존재하고 이름도 헷갈리지만,
		//    SujuController 의 setItemRemark(1583 수기 / 1898 엑셀) 가 넣는 곳은 item_remark 이고
		//    "Description" 은 실제 데이터가 전부 비어 있다 (확인됨).
		String  itemRemark;
	}

	/** 그 수주행에 걸려 있는 살아있는 발주 라인 */
	private static class BaljuRow {
		Integer id;
		Integer headId;
		Integer companyId;
		String  state;
		Double  unitPrice;
		Double  qty;
		String  inVatYn;
		double  inQty;      // 입고 누계
	}

	// ===================================================================
	//  1. 라인 BOM 동기화
	// ===================================================================

	/**
	 * 수주 헤더에 속한 라인들의 BOM 을 현재 수주 내용에 맞춘다.
	 *  - 유지되는 라인 : bom_comp 전체 삭제 후 재삽입 (bom 행은 그대로)
	 *  - 사라진 라인   : bom_comp → bom → LINE 모품목 → 매핑 순으로 삭제
	 *  - 새 라인       : LINE 모품목 채번 → bom → bom_comp → 매핑
	 */
	public SyncResult syncLineBoms(Integer sujuHeadId, String spjangcd, User user) {
		SyncResult r = new SyncResult();
		if (sujuHeadId == null) return r;

		// 현재 수주 내용 (라인명이 있는 행만)
		Map<String, List<SujuRow>> current = new LinkedHashMap<>();
		for (SujuRow s : loadSujuRows(sujuHeadId)) {
			if (s.line == null || s.line.trim().isEmpty()) continue;
			if (s.materialId == null) continue;
			current.computeIfAbsent(s.line.trim(), k -> new ArrayList<>()).add(s);
		}

		// 기존 매핑
		Map<String, int[]> mapped = loadLineBomMap(sujuHeadId);   // line → [bomId, lineMaterialId]

		// ── 사라진 라인 제거 ──
		for (Map.Entry<String, int[]> e : new ArrayList<>(mapped.entrySet())) {
			if (current.containsKey(e.getKey())) continue;
			removeLineBom(sujuHeadId, e.getKey(), e.getValue()[0], e.getValue()[1]);
			mapped.remove(e.getKey());
			r.bomRemoved++;
			log.info("[sync] BOM 삭제: head={} line=[{}] bom={}", sujuHeadId, e.getKey(), e.getValue()[0]);
		}

		// ── 유지 / 신규 ──
		for (Map.Entry<String, List<SujuRow>> e : current.entrySet()) {
			String lineName = e.getKey();
			List<SujuRow> comps = e.getValue();

			int[] m = mapped.get(lineName);
			Integer bomId;

			if (m == null) {
				// 신규: LINE 모품목 채번 → bom 생성 → 매핑 기록
				Integer lineMatId = createLineMaterial(lineName, spjangcd, user);
				if (lineMatId == null) continue;

				Bom bom = new Bom();
				bom.setName(lineName);
				bom.setMaterialId(lineMatId);
				bom.setOutputAmount(1F);
				bom.setBomType(BOM_TYPE);
				bom.setVersion(BOM_VERSION);
				bom.setStartDate(Timestamp.valueOf(java.time.LocalDate.now().toString() + " 00:00:00"));
				bom.setEndDate(Timestamp.valueOf("2100-12-31 00:00:00"));
				bom.setSpjangcd(spjangcd);
				bom.set_audit(user);
				bomId = bomService.saveBom(bom).getId();

				insertLineBomMap(sujuHeadId, lineName, bomId, lineMatId, spjangcd, user);
				r.bomCreated++;
				log.info("[sync] BOM 생성: head={} line=[{}] bom={} 모품목={}",
					sujuHeadId, lineName, bomId, lineMatId);
			} else {
				// 유지: 구성품만 교체. ★ bom 행은 UNIQUE 제약 때문에 절대 재생성하지 않는다.
				bomId = m[0];
				deleteBomComponents(bomId);
				r.bomUpdated++;
			}

			// 구성품 삽입
			//  bom_comp 에 UNIQUE("BOM_id","Material_id") 가 있으므로 같은 품목은 수량을 합쳐 1행으로.
			Map<Integer, Double> merged = new LinkedHashMap<>();
			for (SujuRow c : comps) {
				merged.merge(c.materialId, c.qty == null ? 0d : c.qty, Double::sum);
			}
			int order = 1;
			for (Map.Entry<Integer, Double> c : merged.entrySet()) {
				BomComponent bc = new BomComponent();
				bc.setBomId(bomId);
				bc.setMaterialId(c.getKey());
				bc.setAmount(c.getValue().floatValue());
				bc.set_order(order++);
				bc.setDescription(null);
				bc.setSpjangcd(spjangcd);
				bc.set_audit(user);
				bomService.saveBomComponent(bc);
			}
		}

		return r;
	}

	// ===================================================================
	//  2. 외작 발주 동기화
	// ===================================================================

	/**
	 * 수주 헤더에 속한 외작 행들의 발주를 현재 수주 내용에 맞춘다.
	 *
	 * <pre>
	 *  want = (make_type='outsource' AND make_comp_id IS NOT NULL)
	 *  have = balju."PlanTableName"='suju' AND "PlanDataPk"=suju.id AND State<>'canceled'
	 *
	 *  !want !have  → 없음
	 *  !want  have  → 취소 (입고 있으면 오류)
	 *   want !have  → 신규 (업체 헤더 재사용 or 생성)
	 *   want  have  → 업체 같음 : 변경분 UPDATE (수량 변경인데 입고 있으면 오류)
	 *                 업체 다름 : 취소 + 신규 (입고 있으면 오류)
	 * </pre>
	 *
	 * @param sourceMemo balju_head."Description" 에 남길 출처 문구
	 */
	public SyncResult syncBalju(Integer sujuHeadId, Date jumunDate, Date dueDate,
															String spjangcd, User user, String sourceMemo) {
		SyncResult r = new SyncResult();
		if (sujuHeadId == null) return r;

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Set<Integer> touchedHeads = new LinkedHashSet<>();
		Map<Integer, Integer> headByCompany = new HashMap<>();   // 업체id → 이 수주의 발주헤더id

		for (SujuRow s : loadSujuRows(sujuHeadId)) {
			boolean want = "outsource".equals(s.makeType) && s.makeCompId != null && s.materialId != null;
			BaljuRow b = findLiveBalju(s.id);

			if (!want && b == null) continue;

			// ── 외작이 아니게 됨 → 취소 ──
			//  ★ 입고가 있으면 발주를 없앨 수 없다 (입고 이력이 뜬다).
			//    그렇다고 수주 저장을 막지는 않는다. 발주는 나간 그대로 두고 넘어간다.
			if (!want) {
				if (b.inQty > 0) {
					log.info("[sync] 입고 {} 로 발주 유지 (수주는 내작으로 변경됨): suju={} balju={}",
						fmt(b.inQty), s.id, b.id);
					r.baljuSkipped++;
					continue;
				}
				cancelBaljuLine(b.id, user, "내작으로 변경되어 취소됨");
				touchedHeads.add(b.headId);
				r.baljuCanceled++;
				continue;
			}

			// ── 업체가 바뀜 → 옛 라인 취소 후 신규 ──
			//  ★ 입고가 있으면 옛 업체 발주를 취소할 수 없다. 발주는 그대로 두고
			//    수주의 제작업체만 바뀐 상태로 넘어간다 (화면이 불일치를 표시한다).
			if (b != null && !Objects.equals(b.companyId, s.makeCompId)) {
				if (b.inQty > 0) {
					log.info("[sync] 입고 {} 로 발주 유지 (수주 제작업체만 변경됨): suju={} balju={} 발주업체={}",
						fmt(b.inQty), s.id, b.id, b.companyId);
					r.baljuSkipped++;
					continue;
				}
				cancelBaljuLine(b.id, user,
					"제작업체 변경으로 취소됨 (→ " + (s.makeCompName == null ? "" : s.makeCompName) + ")");
				touchedHeads.add(b.headId);
				r.baljuCanceled++;
				b = null;
			}

			// ── 신규 ──
			if (b == null) {
				Integer headId = headByCompany.get(s.makeCompId);
				if (headId == null) {
					headId = findBaljuHeadOfSuju(sujuHeadId, s.makeCompId);
					if (headId == null) {
						headId = createBaljuHead(s.makeCompId, jumunDate, dueDate, spjangcd, user, sourceMemo, now);
						r.baljuHeadCreated++;
					}
					headByCompany.put(s.makeCompId, headId);
				}
				insertBaljuLine(headId, s, jumunDate, dueDate, spjangcd, user, now);
				touchedHeads.add(headId);
				r.baljuInserted++;
				continue;
			}

			// ── 기존 라인 갱신 ──
			headByCompany.putIfAbsent(s.makeCompId, b.headId);
			double newQty = s.qty == null ? 0d : s.qty;

			// ★ 하한선은 입고 누계. 증량과 (입고량까지의) 감량은 허용한다.
			//   무조건 차단하면 3/10 입고 상태에서 10→12 증량까지 막혀 현장이 못 쓴다.
			//   이것과 행 삭제만이 저장을 되돌리는 유일한 두 경우다.
			if (newQty < b.inQty) {
				return SyncResult.fail(lockMsg(s,
					"이미 입고된 수량이 " + fmt(b.inQty) + " 이라 유니트를 그 미만으로 줄일 수 없습니다"));
			}

			if (updateBaljuLine(b, s, newQty, jumunDate, dueDate, user)) {
				touchedHeads.add(b.headId);
				r.baljuUpdated++;
			}
		}

		// 헤더 금액 / 상태 정리
		for (Integer h : touchedHeads) recalcBaljuHead(h, user);

		return r;
	}

	/**
	 * 수주행을 삭제하기 전에 호출한다. 발주가 걸려 있으면 취소로 정리하고,
	 * 입고가 있으면 false 를 돌려 삭제를 막는다.
	 *
	 * @return null 이면 삭제 가능. 문자열이면 그것이 차단 사유.
	 */
	public String blockOrCancelBaljuBeforeDelete(Integer sujuId, String materialName, User user) {
		BaljuRow b = findLiveBalju(sujuId);
		if (b == null) return null;

		if (b.inQty > 0) {
			return "[" + (materialName == null ? sujuId : materialName)
							 + "] 은(는) 입고 " + fmt(b.inQty) + " 가 있는 발주가 걸려 있어 삭제할 수 없습니다."
							 + " 발주 화면에서 먼저 처리하세요.";
		}
		cancelBaljuLine(b.id, user, "수주 삭제로 취소됨");
		recalcBaljuHead(b.headId, user);
		log.info("[sync] 수주행 삭제로 발주 취소: suju={} balju={}", sujuId, b.id);
		return null;
	}

	// ===================================================================
	//  3. 저장 전 사전조회 (edit_guard)
	// ===================================================================

	/**
	 * 저장 전에 화면이 물어보는 발주 잠금 정보.
	 *
	 * <p>"저장 눌렀더니 안 된다" 가 아니라 "왜 안 되는지" 를 먼저 보여주기 위한 것.
	 * 작업지시 수정 화면(prod_order_edit)의 <code>/edit_guard</code> 와 같은 역할이다.
	 *
	 * <p>반환하는 행은 <b>발주가 걸려 있는 수주행만</b>이다. 화면은 이 목록으로
	 * 수량 입력칸에 <code>min</code> 을 걸고, 업체 변경을 막고, 사유를 띄운다.
	 * 서버는 저장 시 같은 판정을 다시 하므로 화면 값을 믿지 않는다.
	 *
	 * <pre>
	 *  suju_id        수주행 id
	 *  material_name  품목명 (메시지용)
	 *  balju_id       발주 라인 id
	 *  jumun_number   발주번호 (화면에서 발주 화면으로 링크)
	 *  state          draft / partial / received
	 *  balju_qty      현재 발주 수량
	 *  in_qty         입고 누계  ← 이것이 하한선
	 *  min_qty        = in_qty. 유니트를 이 미만으로 내릴 수 없다 (저장이 되돌려진다)
	 *  qty_editable   false 면 수량 자체를 못 고침 (현재는 항상 true)
	 *  balju_locked   true 면 이 행의 발주가 잠겼다.
	 *                 제작업체·설계업체·도면출도일·비고는 <b>고칠 수 있다.</b>
	 *                 다만 그 변경이 이미 나간 발주에는 반영되지 않는다.
	 *  delete_locked  true 면 행 삭제 불가 (발주가 고아가 된다)
	 *  reason         화면에 그대로 띄울 문구. 잠금이 없으면 null
	 * </pre>
	 */
	public List<Map<String, Object>> getBaljuGuard(Integer sujuHeadId) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (sujuHeadId == null) return out;

		String sql = """
      SELECT s.id            AS suju_id,
             s."Material_Name" AS material_name,
             b.id            AS balju_id,
             b."JumunNumber" AS jumun_number,
             b."Company_id"  AS company_id,
             b."CompanyName" AS company_name,
             COALESCE(b."State", 'draft') AS state,
             b."SujuQty"     AS balju_qty,
             COALESCE(mi.in_qty, 0) AS in_qty
        FROM suju s
        JOIN balju b
          ON b."PlanTableName" = 'suju'
         AND b."PlanDataPk" = s.id
         AND COALESCE(b."State", 'draft') <> 'canceled'
        LEFT JOIN (
              SELECT "SourceDataPk", SUM("InputQty") AS in_qty
                FROM mat_inout
               WHERE "SourceTableName" = 'balju' AND COALESCE("_status", 'a') = 'a'
               GROUP BY "SourceDataPk"
             ) mi ON mi."SourceDataPk" = b.id
       WHERE s."SujuHead_id" = :headId
       ORDER BY s.id
      """;

		for (Map<String, Object> m : sqlRunner.getRows(sql,
			new MapSqlParameterSource().addValue("headId", sujuHeadId))) {

			Double inD = toDbl(m.get("in_qty"));
			double inQty = inD == null ? 0d : inD;

			Map<String, Object> g = new LinkedHashMap<>(m);
			g.put("min_qty", inQty);
			g.put("qty_editable", true);
			g.put("balju_locked", inQty > 0);
			g.put("delete_locked", inQty > 0);
			g.put("reason", inQty > 0
												? "발주 " + m.get("jumun_number") + " (" + m.get("company_name") + ") 에 입고 "
														+ fmt(inQty) + " 가 있습니다. 유니트를 " + fmt(inQty) + " 미만으로 줄일 수 없고,"
														+ " 제작업체를 바꿔도 이 발주에는 반영되지 않습니다."
												: null);
			out.add(g);
		}
		return out;
	}

	// ===================================================================
	//  4. 수주 헤더 삭제 지원
	//
	//   ★ 새 로직을 만들지 말 것. 아래 넷은 전부 기존 private 헬퍼를 재사용한다.
	//     헤더 삭제와 행 삭제(manual_save_project)가 서로 다른 규칙을 갖게 되면
	//     한쪽만 고쳐지고 곧 갈라진다.
	// ===================================================================

	/**
	 * 헤더 삭제 전 검사. 막히는 사유를 <b>전부</b> 모아 돌려준다.
	 *
	 * <p>{@link #blockOrCancelBaljuBeforeDelete} 를 루프로 돌리면 첫 행에서 멈춰
	 * 사용자가 문제를 하나씩 발견하게 된다. 여기서는 사유를 한 번에 보여준다.
	 *
	 * <p>막는 것은 둘이고, 규칙이 같다 — <b>이미 실체가 생긴 것은 되돌리지 않는다.</b>
	 * <ul>
	 *   <li>발주에 입고가 있는 행 ({@link #getBaljuGuard} 재사용)</li>
	 *   <li>작업지시에 생산실적이 있는 행 (job_res."SourceTableName"='suju')</li>
	 * </ul>
	 *
	 * @return 빈 목록이면 삭제 가능. 아니면 각 문자열이 차단 사유.
	 */
	public List<String> checkHeadDeletable(Integer sujuHeadId) {
		List<String> blockers = new ArrayList<>();
		if (sujuHeadId == null) return blockers;

		// ── 발주 입고 ──
		for (Map<String, Object> g : getBaljuGuard(sujuHeadId)) {
			if (!Boolean.TRUE.equals(g.get("delete_locked"))) continue;
			Double in = toDbl(g.get("in_qty"));
			blockers.add("[" + g.get("material_name") + "] 발주 " + g.get("jumun_number")
										 + " (" + g.get("company_name") + ") 에 입고 " + fmt(in == null ? 0d : in) + " 가 있습니다.");
		}

		// ── 작업지시 실적 ──
		//  실적 판정은 수량으로만 한다. job_res."State" 의 코드값을 확인하지 못했으므로
		//  상태를 조건에 넣지 않는다 (넣으면 코드값이 다를 때 조용히 통과한다).
		String sql = """
      SELECT s."Material_Name" AS material_name,
             j."WorkOrderNumber" AS work_order,
             COALESCE(j."GoodQty", 0) + COALESCE(j."DefectQty", 0) AS done_qty
        FROM job_res j
        JOIN suju s ON s.id = j."SourceDataPk"
       WHERE j."SourceTableName" = 'suju'
         AND s."SujuHead_id" = :headId
         AND COALESCE(j."GoodQty", 0) + COALESCE(j."DefectQty", 0) > 0
       ORDER BY s.id
      """;
		for (Map<String, Object> m : sqlRunner.getRows(sql,
			new MapSqlParameterSource().addValue("headId", sujuHeadId))) {
			Double q = toDbl(m.get("done_qty"));
			blockers.add("[" + m.get("material_name") + "] 작업지시 " + m.get("work_order")
										 + " 에 생산실적 " + fmt(q == null ? 0d : q) + " 가 있습니다.");
		}

		return blockers;
	}

	/**
	 * 헤더에 걸린 작업지시를 정리한다.
	 *
	 * <p>실적이 있는 것은 {@link #checkHeadDeletable} 이 이미 삭제를 막았으므로
	 * 여기 도달하는 것은 빈 지시뿐이다. 그래도 조건을 다시 건다 — 판정을 한 곳에만
	 * 두면 호출 순서가 바뀌었을 때 조용히 실적이 사라진다.
	 *
	 * <p>★ suju 를 지우기 <b>전에</b> 불러야 한다. suju 를 조인해서 찾기 때문이다.
	 */
	public int removeWorkOrders(Integer sujuHeadId) {
		if (sujuHeadId == null) return 0;
		int n = sqlRunner.execute("""
      DELETE FROM job_res j
       WHERE j."SourceTableName" = 'suju'
         AND COALESCE(j."GoodQty", 0) + COALESCE(j."DefectQty", 0) = 0
         AND j."SourceDataPk" IN (SELECT s.id FROM suju s WHERE s."SujuHead_id" = :headId)
      """, new MapSqlParameterSource().addValue("headId", sujuHeadId));
		if (n > 0) log.info("[delete] 작업지시 {}건 정리: head={}", n, sujuHeadId);
		return n;
	}

	/**
	 * 헤더에 걸린 라인 BOM 을 전부 정리한다.
	 *  구성품 → BOM → LINE 모품목 → 매핑 순서는 {@link #removeLineBom} 이 그대로 갖고 있다.
	 */
	public SyncResult removeAllLineBoms(Integer sujuHeadId) {
		SyncResult r = new SyncResult();
		if (sujuHeadId == null) return r;

		for (Map.Entry<String, int[]> e : loadLineBomMap(sujuHeadId).entrySet()) {
			removeLineBom(sujuHeadId, e.getKey(), e.getValue()[0], e.getValue()[1]);
			r.bomRemoved++;
			log.info("[delete] BOM 정리: head={} line=[{}] bom={}",
				sujuHeadId, e.getKey(), e.getValue()[0]);
		}
		return r;
	}

	/**
	 * 수주행이 물고 있는 품목 id 목록.
	 *
	 * <p>★ suju 를 지우기 <b>전에</b> 불러야 한다. 지운 뒤에는 읽을 수 없다.
	 */
	public List<Integer> collectRowMaterialIds(Integer sujuHeadId) {
		List<Integer> out = new ArrayList<>();
		if (sujuHeadId == null) return out;

		String sql = """
      SELECT DISTINCT s."Material_id" AS mid
        FROM suju s
       WHERE s."SujuHead_id" = :headId
         AND s."Material_id" IS NOT NULL
      """;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("headId", sujuHeadId);
		for (Map<String, Object> m : sqlRunner.getRows(sql, p)) {
			Integer mid = toInt(m.get("mid"));
			if (mid != null) out.add(mid);
		}
		return out;
	}

	/**
	 * 아무 곳에서도 참조되지 않는 품목만 지운다.
	 *
	 * <p>★ suju / bom_comp 삭제가 <b>끝난 뒤</b> 불러야 한다. 순서가 뒤바뀌면
	 * 아직 참조가 살아 있어 한 건도 지워지지 않는다.
	 *
	 * <p>판정은 {@link #isMaterialReferenced} 를 그대로 쓴다
	 * (suju / balju / bom / bom_comp / mat_inout / job_res 6곳).
	 * 한 곳이라도 걸리면 건너뛴다. 품목이 남는 것은 사고가 아니지만
	 * 쓰이는 품목을 지우는 것은 사고이므로, 판정은 보수적으로 둔다.
	 * 통째로 끄고 싶으면 {@link #CLEANUP_ROW_MATERIALS} 를 false 로.
	 *
	 * @return 실제로 지운 건수
	 */
	public int deleteUnreferencedMaterials(List<Integer> materialIds) {
		if (!CLEANUP_ROW_MATERIALS) return 0;
		if (materialIds == null || materialIds.isEmpty()) return 0;

		int n = 0;
		for (Integer mid : materialIds) {
			if (mid == null || isMaterialReferenced(mid)) continue;
			sqlRunner.execute("DELETE FROM material WHERE id = :id",
				new MapSqlParameterSource().addValue("id", mid));
			n++;
		}
		if (n > 0) log.info("[delete] 미참조 품목 {}건 정리", n);
		return n;
	}

	// ===================================================================
	//  조회 헬퍼
	// ===================================================================

	private List<SujuRow> loadSujuRows(Integer sujuHeadId) {
		String sql = """
      SELECT s.id, s.line, s."Material_id", s."Material_Name", s."SujuQty", s."Standard",
             s.make_type, s.make_comp_id, s.make_comp_name, s.item_remark
        FROM suju s
       WHERE s."SujuHead_id" = :headId
       ORDER BY s.id
      """;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("headId", sujuHeadId);
		return sqlRunner.getRows(sql, p).stream().map(m -> {
			SujuRow s = new SujuRow();
			s.id           = toInt(m.get("id"));
			s.line         = (String) m.get("line");
			s.materialId   = toInt(m.get("Material_id"));
			s.materialName = (String) m.get("Material_Name");
			s.qty          = toDbl(m.get("SujuQty"));
			s.standard     = (String) m.get("Standard");
			s.makeType     = (String) m.get("make_type");
			s.makeCompId   = toInt(m.get("make_comp_id"));
			s.makeCompName = (String) m.get("make_comp_name");
			s.itemRemark   = (String) m.get("item_remark");
			return s;
		}).toList();
	}

	private Map<String, int[]> loadLineBomMap(Integer sujuHeadId) {
		String sql = """
      SELECT line, bom_id, line_material_id
        FROM suju_line_bom
       WHERE suju_head_id = :headId
      """;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("headId", sujuHeadId);
		Map<String, int[]> out = new LinkedHashMap<>();
		for (Map<String, Object> m : sqlRunner.getRows(sql, p)) {
			out.put((String) m.get("line"),
				new int[]{ toInt(m.get("bom_id")), toInt(m.get("line_material_id")) });
		}
		return out;
	}

	/**
	 * 그 수주행에 걸린 살아있는 발주 라인 1건.
	 *  입고 누계는 BaljuOrderService.balju_stop() 과 같은 방식으로 mat_inout 에서 집계한다.
	 */
	private BaljuRow findLiveBalju(Integer sujuId) {
		if (sujuId == null) return null;
		String sql = """
      SELECT b.id, b."BaljuHead_id", b."Company_id", b."State",
             b."UnitPrice", b."SujuQty", b."InVatYN",
             COALESCE(mi.in_qty, 0) AS in_qty
        FROM balju b
        LEFT JOIN (
              SELECT "SourceDataPk", SUM("InputQty") AS in_qty
                FROM mat_inout
               WHERE "SourceTableName" = 'balju' AND COALESCE("_status", 'a') = 'a'
               GROUP BY "SourceDataPk"
             ) mi ON mi."SourceDataPk" = b.id
       WHERE b."PlanTableName" = 'suju'
         AND b."PlanDataPk" = :sujuId
         AND COALESCE(b."State", 'draft') <> 'canceled'
       ORDER BY b.id
       LIMIT 1
      """;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("sujuId", sujuId);
		List<Map<String, Object>> rows = sqlRunner.getRows(sql, p);
		if (rows == null || rows.isEmpty()) return null;

		Map<String, Object> m = rows.get(0);
		BaljuRow b = new BaljuRow();
		b.id        = toInt(m.get("id"));
		b.headId    = toInt(m.get("BaljuHead_id"));
		b.companyId = toInt(m.get("Company_id"));
		b.state     = (String) m.get("State");
		b.unitPrice = toDbl(m.get("UnitPrice"));
		b.qty       = toDbl(m.get("SujuQty"));
		b.inVatYn   = (String) m.get("InVatYN");
		Double in   = toDbl(m.get("in_qty"));
		b.inQty     = in == null ? 0d : in;
		return b;
	}

	/**
	 * 이 수주 헤더에서 이미 만든 그 업체의 발주 헤더.
	 *  balju_head 에는 수주를 가리키는 컬럼이 없으므로 라인에서 거슬러 올라간다.
	 *    suju."SujuHead_id" → suju.id → balju."PlanDataPk" → balju."BaljuHead_id"
	 */
	private Integer findBaljuHeadOfSuju(Integer sujuHeadId, Integer companyId) {
		String sql = """
      SELECT b."BaljuHead_id"
        FROM balju b
       WHERE b."PlanTableName" = 'suju'
         AND b."Company_id" = :compId
         AND b."BaljuHead_id" IS NOT NULL
         AND b."PlanDataPk" IN (SELECT s.id FROM suju s WHERE s."SujuHead_id" = :headId)
       ORDER BY b."BaljuHead_id"
       LIMIT 1
      """;
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("headId", sujuHeadId)
																.addValue("compId", companyId);
		try {
			return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
		} catch (Exception e) {
			return null;
		}
	}

	// ===================================================================
	//  변경 헬퍼 — BOM
	// ===================================================================

	private void deleteBomComponents(Integer bomId) {
		sqlRunner.execute("DELETE FROM bom_comp WHERE \"BOM_id\" = :bomId",
			new MapSqlParameterSource().addValue("bomId", bomId));
	}

	/** 라인이 사라졌을 때: 구성품 → BOM → LINE 모품목 → 매핑 순으로 정리 */
	private void removeLineBom(Integer sujuHeadId, String line, Integer bomId, Integer lineMatId) {
		deleteBomComponents(bomId);
		sqlRunner.execute("DELETE FROM bom WHERE id = :id",
			new MapSqlParameterSource().addValue("id", bomId));

		// LINE 모품목은 이 BOM 전용이지만, 다른 곳에서 참조하면 남긴다.
		// FK 가 없어 DB 가 막아주지 않으므로 직접 확인한다.
		if (lineMatId != null && !isMaterialReferenced(lineMatId)) {
			sqlRunner.execute("DELETE FROM material WHERE id = :id",
				new MapSqlParameterSource().addValue("id", lineMatId));
		}

		sqlRunner.execute(
			"DELETE FROM suju_line_bom WHERE suju_head_id = :headId AND line = :line",
			new MapSqlParameterSource().addValue("headId", sujuHeadId).addValue("line", line));
	}

	/**
	 * 품목이 다른 곳에서 쓰이고 있는지. FK 가 하나도 없어 직접 본다.
	 *
	 * <p>여섯 곳을 본다. mat_inout / job_res 는 각각 입출고 이력과 작업지시·실적이라
	 * 여기 걸린 품목을 지우면 <b>이력이 가리키는 대상이 사라진다.</b>
	 * 둘 다 "Material_id" 가 NOT NULL 이고 인덱스도 있어 조회 비용은 문제되지 않는다.
	 */
	private boolean isMaterialReferenced(Integer materialId) {
		String sql = """
      SELECT 1 WHERE EXISTS (SELECT 1 FROM suju      WHERE "Material_id" = :id)
                  OR EXISTS (SELECT 1 FROM balju     WHERE "Material_id" = :id)
                  OR EXISTS (SELECT 1 FROM bom_comp  WHERE "Material_id" = :id)
                  OR EXISTS (SELECT 1 FROM bom       WHERE "Material_id" = :id)
                  OR EXISTS (SELECT 1 FROM mat_inout WHERE "Material_id" = :id)
                  OR EXISTS (SELECT 1 FROM job_res   WHERE "Material_id" = :id)
      """;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", materialId);
		try {
			return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1)) != null;
		} catch (Exception e) {
			return false;
		}
	}

	private void insertLineBomMap(Integer sujuHeadId, String line, Integer bomId,
																Integer lineMatId, String spjangcd, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("headId", sujuHeadId)
																.addValue("line", line)
																.addValue("bomId", bomId)
																.addValue("matId", lineMatId)
																.addValue("spjangcd", spjangcd)
																.addValue("userId", user == null ? null : user.getId());
		sqlRunner.execute("""
      INSERT INTO suju_line_bom
             (suju_head_id, line, bom_id, line_material_id, spjangcd, _status, _created, _creater_id)
      VALUES (:headId, :line, :bomId, :matId, CAST(:spjangcd AS varchar), 'manual', now(), :userId)
      """, p);
	}

	/** LINE 모품목 신규 채번 (SujuController.createMaterial 과 같은 규칙) */
	private Integer createLineMaterial(String name, String spjangcd, User user) {
		if (name == null || name.trim().isEmpty()) return null;

		Integer groupId = findMaterialGroupIdByCode(LINE_MATERIAL_GROUP_CODE);
		if (groupId == null) {
			throw new IllegalStateException(
				"품목그룹을 찾을 수 없습니다. mat_grp 에 Code='" + LINE_MATERIAL_GROUP_CODE + "' 인 행이 필요합니다.");
		}

		Material m = new Material();
		m.setCode(sujuService.getNextMatCode());
		m.setName(name.trim());
		m.setMaterialGroupId(groupId);
		m.setFactory_id(1);
		m.setSpjangcd(spjangcd);
		m.setUseyn("0");
		m.setStoreHouseId(3);
		m.setPurchaseOrderStandard("mrp");
		m.setValidDays(1);
		m.set_audit(user);
		return materialRepository.save(m).getId();
	}

	private Integer findMaterialGroupIdByCode(String code) {
		if (code == null || code.isEmpty()) return null;
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("code", code);
		try {
			return sqlRunner.queryForObject(
				"SELECT id FROM mat_grp WHERE \"Code\" = :code LIMIT 1", p, (rs, n) -> rs.getInt(1));
		} catch (Exception e) {
			return null;
		}
	}

	// ===================================================================
	//  변경 헬퍼 — 발주
	// ===================================================================

	private Integer createBaljuHead(Integer companyId, Date jumunDate, Date dueDate,
																	String spjangcd, User user, String sourceMemo, Timestamp now) {
		BaljuHead head = new BaljuHead();
		head.setCreated(now);
		head.setCreaterId(user.getId());
		head.set_status("manual");
		head.setJumunNumber(baljuOrderService.makeJumunNumber(jumunDate));
		head.setJumunDate(jumunDate);
		head.setDeliveryDate(dueDate);
		head.setCompanyId(companyId);
		head.setSpjangcd(spjangcd);
		head.setSujuType(BALJU_TYPE_OUTSOURCE);
		head.setTotalPrice(0d);
		head.setDescription(sourceMemo);
		balJuHeadRepository.save(head);
		log.info("[sync] 발주 헤더 생성: 업체={} head={} 비고=[{}]", companyId, head.getId(), sourceMemo);
		return head.getId();
	}

	private void insertBaljuLine(Integer baljuHeadId, SujuRow s, Date jumunDate, Date dueDate,
															 String spjangcd, User user, Timestamp now) {
		String jumunNumber = queryString(
			"SELECT \"JumunNumber\" FROM balju_head WHERE id = :id",
			new MapSqlParameterSource().addValue("id", baljuHeadId));

		Balju d = new Balju();
		d._created = now;
		d._creater_id = user.getId();
		d.setBaljuHeadId(baljuHeadId);
		d.setJumunNumber(jumunNumber);
		d.setMaterialId(s.materialId);
		d.setCompanyId(s.makeCompId);
		d.setCompanyName(s.makeCompName);
		d.setSujuQty(s.qty == null ? 0d : s.qty);
		d.setSujuQty2(0d);
		d.setUnitPrice(0d);          // 단가 미정 → 발주 화면에서 입력
		d.setPrice(0d);
		d.setVat(0d);
		d.setTotalAmount(0d);
		d.setStandard(s.standard);
		// 발주 라인 비고 = 수주행의 비고.
		//  예전에는 품목명(공정명)을 넣었는데, 품목명은 "Standard"/품목 컬럼으로 이미 보이므로
		//  비고 칸은 현장이 수주에 적어둔 내용을 그대로 전달하는 데 쓴다.
		d.setDescription(s.itemRemark);
		d.setJumunDate(jumunDate);
		d.setDueDate(dueDate);
		d.setSpjangcd(spjangcd);
		d.setInVatYN("N");
		d.setSujuType(BALJU_TYPE_OUTSOURCE);
		d.setState(ST_DRAFT);
		d.set_status("manual");
		Balju saved = bujuRepository.save(d);

		// 역추적 고리. Balju 엔티티에 필드가 없어 직접 채운다.
		sqlRunner.execute("""
      UPDATE balju SET "PlanTableName" = 'suju', "PlanDataPk" = :sujuId WHERE id = :id
      """, new MapSqlParameterSource().addValue("id", saved.getId()).addValue("sujuId", s.id));
	}

	/**
	 * 기존 발주 라인 갱신.
	 *  ★ "UnitPrice" 는 발주 화면 소유이므로 절대 덮어쓰지 않는다.
	 *    수량이 바뀌면 <b>기존 단가로</b> 금액만 다시 계산한다.
	 * @return 실제로 바뀐 게 있으면 true
	 */
	/**
	 * 발주 금액 계산. <b>balju_order.html 의 {@code calculateAmount()} 자동계산과 같은 규칙.</b>
	 *
	 * <p>전에는 {@code InVatYN='Y'} 를 "부가세를 잡지 않는다" 로 해석해
	 * {@code Price=단가×수량, Vat=0} 을 넣었는데, 발주 화면은 반대로
	 * <b>단가×수량을 합계로 보고 거기서 공급가를 역산</b>한다. 그래서 수주에서 수량만
	 * 고쳤는데도 부가세 포함 발주의 공급가·부가세가 사라져 세금계산서 기준 금액이 틀어졌다.
	 *
	 * <pre>
	 *  부가세 포함(Y) : 합계 = 단가×수량,  공급가 = 반올림(합계×10/11),  부가세 = 합계 − 공급가
	 *  부가세 별도(N) : 공급가 = 단가×수량, 부가세 = 반올림(공급가×0.1),  합계 = 공급가 + 부가세
	 * </pre>
	 *
	 * <p>반올림 위치도 화면과 맞춘다. 별도일 때 공급가는 반올림하지 않는다.
	 *
	 * <p>면세는 다루지 않는다. 발주 화면에 면세 분기가 있지만
	 * {@code /api/popup/search_Comp} 가 {@code VatExemptionYN} 을 내려주지 않아
	 * ({@code company} 에 그 컴럼 자체가 없다) 화면에서도 도달하지 않는 경로다.
	 * 면세를 실제로 쓰게 되면 컴럼 추가부터 필요하고, 그때 여기도 같이 손봐야 한다.
	 *
	 * @return {@code [공급가, 부가세, 합계]}
	 */
	private double[] computeBaljuAmount(double unitPrice, double qty, String inVatYn) {
		if ("Y".equalsIgnoreCase(inVatYn)) {
			double total  = unitPrice * qty;
			double supply = Math.round(total * 10d / 11d);
			return new double[]{supply, total - supply, total};
		}
		double supply = unitPrice * qty;
		double vat    = Math.round(supply * 0.1d);
		return new double[]{supply, vat, supply + vat};
	}

	private boolean updateBaljuLine(BaljuRow b, SujuRow s, double newQty,
																	Date jumunDate, Date dueDate, User user) {
		double unitPrice = b.unitPrice == null ? 0d : b.unitPrice;

		// 단가는 발주 화면이 소유한다. 여기서 덮어쓰지 않고 금액만 다시 계산한다.
		double[] amt = computeBaljuAmount(unitPrice, newQty, b.inVatYn);
		double price = amt[0];
		double vat   = amt[1];
		double total = amt[2];

		// 수량이 바뀌면 입고 진행 상태도 따라 바뀐다 (10 중 3 입고 → 3 으로 줄이면 received)
		String newState = resolveState(b.inQty, newQty);

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("id", b.id)
																.addValue("state", newState)
																.addValue("qty", newQty)
																.addValue("price", price)
																.addValue("vat", vat)
																.addValue("total", total)
																.addValue("standard", s.standard)
																.addValue("desc", s.itemRemark)
																.addValue("matId", s.materialId)
																.addValue("compName", s.makeCompName)
																.addValue("jumunDate", jumunDate)
																.addValue("dueDate", dueDate)
																.addValue("userId", user == null ? null : user.getId());

		int n = sqlRunner.execute("""
      UPDATE balju
         SET "SujuQty"     = :qty,
             "State"       = CAST(:state AS varchar),
             "Price"       = :price,
             "Vat"         = :vat,
             "TotalAmount" = :total,
             "Standard"    = CAST(:standard AS varchar),
             "Description" = CAST(:desc AS varchar),
             "Material_id" = :matId,
             "CompanyName" = CAST(:compName AS varchar),
             "JumunDate"   = :jumunDate,
             "DueDate"     = :dueDate,
             "_modified"   = now(),
             "_modifier_id"= :userId
       WHERE id = :id
         AND ( "SujuQty" IS DISTINCT FROM :qty
            OR "State" IS DISTINCT FROM CAST(:state AS varchar)
            OR "Standard" IS DISTINCT FROM CAST(:standard AS varchar)
            OR "Description" IS DISTINCT FROM CAST(:desc AS varchar)
            OR "Material_id" IS DISTINCT FROM :matId
            OR "DueDate" IS DISTINCT FROM :dueDate
            OR "Price" IS DISTINCT FROM :price
            OR "Vat" IS DISTINCT FROM :vat
            OR "TotalAmount" IS DISTINCT FROM :total )
      """, p);
		return n > 0;
	}

	/**
	 * 발주 라인 취소.
	 *
	 * <p>물리삭제하지 않는다 — 업체로 이미 나간 문서다.
	 * 대신 <b>비고에 사유를 남긴다.</b> 발주 화면만 보는 사람은 "중지" 라는 상태만
	 * 보이고 <b>왜 중지됐는지를 알 길이 없다.</b> 원본 수주행은 이미 지워져
	 * PlanDataPk 로 역추적해도 아무것도 나오지 않는다.
	 *
	 * <p>취소된 라인은 {@link #findLiveBalju} 가 제외하므로
	 * {@link #updateBaljuLine} 이 다시 덮어쓰지 않는다. 사유는 그대로 남는다.
	 *
	 * @param reason 비고에 남길 취소 사유. null 이면 비고를 건드리지 않는다.
	 */
	private void cancelBaljuLine(Integer baljuId, User user, String reason) {
		sqlRunner.execute("""
      UPDATE balju
         SET "State" = 'canceled',
             "Description" = CASE
                 WHEN CAST(:reason AS varchar) IS NULL THEN "Description"
                 ELSE CAST(:reason AS varchar) END,
             "_modified" = now(), "_modifier_id" = :userId
       WHERE id = :id
      """, new MapSqlParameterSource().addValue("id", baljuId)
																	 .addValue("reason", reason)
																	 .addValue("userId", user == null ? null : user.getId()));
	}

	/**
	 * 발주 헤더 합계 재계산.
	 *  살아있는 라인이 하나도 없으면 헤더도 canceled 로 내린다.
	 *  (헤더 자체는 삭제하지 않는다 — 발주번호가 이미 나갔을 수 있다)
	 */
	private void recalcBaljuHead(Integer baljuHeadId, User user) {
		if (baljuHeadId == null) return;
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("headId", baljuHeadId)
																.addValue("userId", user == null ? null : user.getId());
		sqlRunner.execute("""
      UPDATE balju_head h
         SET "TotalPrice" = COALESCE((
               SELECT SUM(b."TotalAmount") FROM balju b
                WHERE b."BaljuHead_id" = h.id
                  AND COALESCE(b."State", 'draft') <> 'canceled'), 0),
             -- 살아있는 라인이 없으면 헤더도 중지.
             -- 반대로 라인이 다시 생기면 중지 상태를 풀어야 한다.
             --  외작→내작→외작 으로 되돌리면 findLiveBalju 가 canceled 를 제외해
             --  라인을 새로 만드는데, findBaljuHeadOfSuju 는 상태 조건이 없어
             --  죽은 헤더를 그대로 재사용한다. 예전에는 h."State" 를 그대로 둔 탓에
             --  "중지된 발주 안에 살아있는 라인이 들어있는" 상태가 남았다.
             --  입고가 진행된 건은 건드리지 않고 draft(미입고) 로만 되돌린다.
             "State" = CASE
                 WHEN NOT EXISTS (
                   SELECT 1 FROM balju b
                    WHERE b."BaljuHead_id" = h.id
                      AND COALESCE(b."State", 'draft') <> 'canceled')
                 THEN 'canceled'
                 WHEN COALESCE(h."State", '') = 'canceled'
                 THEN 'draft'
                 ELSE h."State" END,
             "_modified" = now(),
             "_modifier_id" = :userId
       WHERE h.id = :headId
      """, p);
	}

	// ===================================================================
	//  유틸
	// ===================================================================

	private String lockMsg(SujuRow s, String reason) {
		String nm = (s.materialName == null || s.materialName.isEmpty())
									? String.valueOf(s.id) : s.materialName;
		return "[" + nm + "] " + reason + ". 발주 화면에서 먼저 처리하세요.";
	}

	private String queryString(String sql, MapSqlParameterSource p) {
		try {
			return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getString(1));
		} catch (Exception e) {
			return null;
		}
	}

	/** 수량 표기: 정수면 소수점을 떼고 보여준다 (3.0 → 3) */
	private static String fmt(double v) {
		return (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(v);
	}

	private static Integer toInt(Object v) {
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).intValue();
		try { return Integer.valueOf(v.toString().trim()); } catch (Exception e) { return null; }
	}

	private static Double toDbl(Object v) {
		if (v == null) return null;
		if (v instanceof Number) return ((Number) v).doubleValue();
		try { return Double.valueOf(v.toString().trim()); } catch (Exception e) { return null; }
	}
}