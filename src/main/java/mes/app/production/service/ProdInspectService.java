package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 검사 공정
 *
 * [원칙]  ★ <b>내작·외작 구분 없이 모든 공정(품목)이 검사 대상이다.</b>
 *   검사는 유닛이 아니라 공정 단위이므로 대상의 키는 suju.id 다.
 *
 * [검사 면제]
 *   외작 중 <b>업체가 검사를 마치고 보낸 품목</b>은 우리가 다시 검사하지 않는다.
 *   그 목록이 mat_inout_inspect 다.
 *
 *     mat_inout_inspect."MatInout_id"  NOT NULL  — 입고 1건에 종속
 *     mat_inout_inspect."InspectYN"    NOT NULL  — 'Y' 면 검사 완료 상태로 입고됨
 *
 *   이 테이블은 <b>읽기 전용 참조</b>다. 검사 실적을 여기 쓰지 않는다.
 *   입고에 종속된 구조라 내작(입고가 없다)을 표현할 수 없기 때문이다.
 *   외작 입고 시 수주/발주 쪽에서 채워진다.
 *
 * [검사 실적]  mat_produce 에 ProcessOrder=3 으로 남긴다.
 *
 *     ProcessOrder 1 = 유닛 조립 (부품 → 유닛)
 *     ProcessOrder 2 = 공정 조립 (유닛 → 지그 1식)
 *     ProcessOrder 3 = 검사              ← 이 서비스
 *
 *   신규 테이블이 필요 없고, 조립과 같은 job_res 아래 순서대로 쌓인다.
 *   검사 워크센터는 w05(3차원 자동측정 검사), 설비는 EQ-CMM 이다.
 *
 * [상태 판정]
 *     exempt : 외작 + mat_inout_inspect."InspectYN"='Y'  → 검사 불필요
 *     done   : ProcessOrder=3 실적 있음                   → 검사 완료
 *     wait   : 그 외                                      → 미검사
 *
 *   "검사를 안 한 외작"에 별도 플래그가 필요 없다.
 *   면제 목록에 없고 검사 실적도 없으면 그대로 미검사다.
 *
 * [설계 메모]
 *  - 재고를 만들지 않는다. 검사 결과로 mat_inout / mat_lot 을 생성하지 않는다.
 *  - rtflag = 0:재직 / 1:퇴직 / 2:휴직. 재직만 노출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProdInspectService {

	private final SqlRunner sqlRunner;

	/** 검사 단계의 ProcessOrder. 조립(1, 2) 다음이다. */
	public static final int PROCESS_ORDER_INSPECT = 3;

	/** 검사 워크센터 코드 — 3차원 자동측정 검사 */
	private static final String INSPECT_WORKCENTER_CODE = "w05";

	/**
	 * 검사 설비그룹 코드 (equ_grp."Code").
	 *
	 * 워크센터 코드보다 이쪽이 믿을 만하다 — 실제 DB 에서 확인된 값이다.
	 * 워크센터 코드('w05')는 등록 순서에 따라 달라질 수 있어 보조 수단으로만 쓴다.
	 */
	private static final String INSPECT_EQUIP_GROUP_CODE = "CMM";

	// =================================================================
	// 마스터
	// =================================================================

	public List<Map<String, Object>> getProjectList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		return sqlRunner.getRows("""
            SELECT d.projno AS proj_no
                 , d.projnm AS proj_name
            FROM tb_da003 d
            WHERE d.spjangcd = :spjangcd
              AND COALESCE(d.endflag, '0') <> '1'
            ORDER BY d.projno DESC
            """, p);
	}

	/**
	 * 검사자 목록.
	 * 검사 워크센터(w05) 인원을 위로 올리되 막지 않는다 (교대·대체 인력).
	 */
	public List<Map<String, Object>> getWorkerList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("wcCode", INSPECT_WORKCENTER_CODE);

		return sqlRunner.getRows("""
            SELECT p.id
                 , p."Name"  AS name
                 , p."Code"  AS code
                 , wc."Name" AS workcenter_name
                 , CASE WHEN wc."Code" = :wcCode THEN 'Y' ELSE 'N' END AS own_area
            FROM person p
            LEFT JOIN work_center wc ON wc.id = p."WorkCenter_id"
            WHERE p.spjangcd = :spjangcd
              AND COALESCE(p.rtflag, '0') = '0'
            ORDER BY CASE WHEN wc."Code" = :wcCode THEN 0 ELSE 1 END
                   , wc."Name", p."Name"
            """, p);
	}

	/** 검사 설비 (3차원 측정기 등). 없으면 빈 목록 — 설비 없이도 검사 등록은 된다 */
	public List<Map<String, Object>> getEquipmentList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("wcCode", INSPECT_WORKCENTER_CODE);
		p.addValue("grpCode", INSPECT_EQUIP_GROUP_CODE);

		// 설비그룹(CMM)으로 찾는다. 그룹이 안 붙은 설비를 대비해 워크센터 코드도 함께 본다.
		return sqlRunner.getRows("""
            SELECT e.id        AS equipment_id
                 , e."Code"    AS equipment_code
                 , e."Name"    AS equipment_name
                 , wc.id       AS workcenter_id
            FROM equ e
            JOIN work_center wc ON wc.id = e."WorkCenter_id"
            LEFT JOIN equ_grp g ON g.id = e."EquipmentGroup_id"
            WHERE e.spjangcd = :spjangcd
              AND e."DisposalDate" IS NULL
              AND (g."Code" = :grpCode OR wc."Code" = :wcCode)
            ORDER BY e."Code"
            """, p);
	}

	/**
	 * 검사 워크센터 정보. mat_produce."Process_id" 가 NOT NULL 이라 반드시 필요하다.
	 *
	 * ★ 못 찾으면 검사 등록이 <b>NOT NULL 위반으로 실패</b>한다.
	 *   워크센터 코드 하나에 의존하면 코드값이 다른 순간 조용히 깨지므로
	 *   세 갈래로 찾고 우선순위를 준다.
	 *     1) 검사 설비그룹(CMM) 설비가 붙어 있는 워크센터  — 가장 확실
	 *     2) 워크센터 코드 = w05
	 *     3) 워크센터명에 '검사' 포함                      — 최후 수단
	 */
	public Map<String, Object> getInspectWorkCenter(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("wcCode", INSPECT_WORKCENTER_CODE);
		p.addValue("grpCode", INSPECT_EQUIP_GROUP_CODE);

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT wc.id           AS workcenter_id
                 , wc."Process_id" AS process_id
                 , CASE WHEN g."Code" = :grpCode  THEN 0
                        WHEN wc."Code" = :wcCode  THEN 1
                        ELSE 2 END AS pri
            FROM work_center wc
            LEFT JOIN equ e     ON e."WorkCenter_id" = wc.id
                               AND e.spjangcd = wc.spjangcd
                               AND e."DisposalDate" IS NULL
            LEFT JOIN equ_grp g ON g.id = e."EquipmentGroup_id"
            WHERE wc.spjangcd = :spjangcd
              AND (g."Code" = :grpCode
                   OR wc."Code" = :wcCode
                   OR wc."Name" LIKE '%검사%')
            ORDER BY pri
            LIMIT 1
            """, p);

		if (row == null) {
			log.warn("[prod_inspect] 검사 워크센터를 찾지 못했다 (spjangcd={}). "
							+ "검사 등록이 Process_id NOT NULL 로 실패한다. "
							+ "work_center 에 검사 워크센터가 있는지, EQ-CMM 의 설비그룹이 {} 인지 확인할 것.",
					spjangcd, INSPECT_EQUIP_GROUP_CODE);
		}
		return row;
	}

	// =================================================================
	// 검사 대상  ★ 모든 공정(품목)이 대상. 면제만 걸러낸다
	// =================================================================

	/**
	 * 검사 대상 목록.
	 *
	 * @param state 'wait' 미검사 / 'done' 검사완료 / 'exempt' 검사면제 / 그 외(null) 전체
	 *
	 * 작업 지시된 품목 전체가 대상이다. 내작·외작을 가리지 않는다.
	 *
	 *   exempt_yn  외작 + mat_inout_inspect."InspectYN"='Y' → 업체 검사 완료분
	 *   ready_qty  <b>조립(ProcessOrder=1) 완료 유닛수</b>.
	 *              검사는 이 값을 보지 않고 막지도 않는다 — 실물을 보고 하는 일이라
	 *              시스템이 앞서 판단할 근거가 없다. 다만 화면에 같이 띄워
	 *              "조립 3/20 인데 검사 완료" 같은 오입력이 눈에 띄게 한다.
	 *   insp_qty   검사(ProcessOrder=3) 완료 누적
	 *
	 * 외작은 조립 실적이 없을 수 있으므로 ready_qty 가 0 이어도 검사할 수 있다.
	 * 막지 않는다 — 현장이 조립 실적을 건너뛰는 경우가 있다.
	 *
	 * ★ 검사 대상은 <b>작업지시와 무관하다.</b>
	 *   예전에는 job_res 를 INNER JOIN 해서 지시된 품목만 검사할 수 있었다.
	 *   그런데 외작은 우리가 지시할 대상이 아니라 지시가 없다.
	 *   그 탓에 외작을 검사하려고 생산 지시 화면에서 억지로 지시를 넣는 일이 생겼다.
	 *
	 *   내작·외작 가릴 것 없이 <b>수주에 있는 공정은 전부 검사 대상</b>이고,
	 *   외작 중 업체가 검사를 마치고 보낸 것만 mat_inout_inspect 로 면제된다.
	 *   job_res 는 실적을 매다는 컨테이너일 뿐이라 LEFT JOIN 으로 바꾸고,
	 *   없으면 검사 등록 시점에 만든다(ensureOrder).
	 */
	public List<Map<String, Object>> getTargetList(String spjangcd, String projNo, String state) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("state", nullIfEmpty(state));
		p.addValue("inspOrder", PROCESS_ORDER_INSPECT);

		return sqlRunner.getRows("""
            SELECT s.id                      AS suju_id
                 , s.project_id              AS proj_no
                 , s.line                    AS line_name
                 , s."Material_Name"         AS item_name
                 , s.equip_type              AS equip_type
                 , COALESCE(s.make_type, 'inhouse') AS make_type
                 , s.make_comp_name          AS make_comp
                 , j.id                      AS job_res_id
                 -- ★ 검사 목표 = 지그 대수 = suju."SujuQty".
                 --   "Standard" 로 대수를 뽑던 것을 버렸다 —
                 --   '1'·'2'·'1/1'·'2/2' 가 섞여 있고 '1/1' 은 한 쌍(2개)이라
                 --   숫자만 뽑으면 11, 앞자리만 뽑으면 1 이 되어 어느 쪽도 맞지 않는다.
                 --
                 --   ★ 검사가 <b>공정 완료 판정</b>을 갖는다.
                 --   검사 누적이 대수에 도달하면 그 공정(품목)은 끝난 것이다.
                 --   공정 조립(PO=2) 단계를 없앤 뒤 그 역할이 여기로 왔다.
                 , COALESCE(s."SujuQty", 0) AS set_target
                 , COALESCE(a.ready_qty, 0)  AS ready_qty
                 , COALESCE(i.insp_qty, 0)   AS insp_qty
                 , COALESCE(i.defect_qty, 0) AS insp_defect_qty
                 , COALESCE(i.insp_cnt, 0)   AS insp_cnt
                 , TO_CHAR(i.last_date, 'YYYY-MM-DD') AS last_inspect_date
                 , CASE WHEN x.exempt_yn = 'Y' THEN 'Y' ELSE 'N' END AS exempt_yn
                 , CASE WHEN x.exempt_yn = 'Y'         THEN 'exempt'
                        WHEN COALESCE(i.insp_cnt, 0) > 0 THEN 'done'
                        ELSE 'wait' END      AS insp_state
                 -- 수주가 여럿이면 같은 품목명이 여러 번 나온다. 수주일·업체로 구분한다
                 , s."SujuHead_id"             AS suju_head_id
                 , COALESCE(sh.suju_name, '')  AS suju_name
                 , TO_CHAR(sh."JumunDate", 'MM-DD') AS jumun_date
                 , COALESCE(c."Name", '')      AS company
                 , COALESCE(sh."SujuType", '') AS suju_type
            FROM suju s
            LEFT JOIN suju_head sh ON sh.id = s."SujuHead_id"
            LEFT JOIN company c    ON c.id = sh."Company_id"
            LEFT JOIN job_res j
              ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            -- 공정 조립 완료 = 검사 가능 수량
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS ready_qty
                FROM mat_produce
                WHERE "ProcessOrder" = 1 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) a ON a."JobResponse_id" = j.id
            -- 검사 실적
            LEFT JOIN (
                SELECT "JobResponse_id"
                     , COUNT(*)                        AS insp_cnt
                     , SUM(COALESCE("GoodQty", 0))     AS insp_qty
                     , SUM(COALESCE("DefectQty", 0))   AS defect_qty
                     , MAX("ProductionDate")           AS last_date
                FROM mat_produce
                WHERE "ProcessOrder" = :inspOrder AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) i ON i."JobResponse_id" = j.id
            -- 검사 면제: 외작 입고분 중 업체 검사 완료로 등록된 것
            --   suju.id → balju."PlanDataPk" → mat_inout → mat_inout_inspect
            LEFT JOIN (
                SELECT b."PlanDataPk" AS suju_id, 'Y' AS exempt_yn
                FROM balju b
                JOIN mat_inout mi         ON mi."SourceTableName" = 'balju'
                                         AND mi."SourceDataPk" = b.id
                JOIN mat_inout_inspect ii ON ii."MatInout_id" = mi.id
                WHERE b."PlanTableName" = 'suju'
                  AND ii."InspectYN" = 'Y'
                GROUP BY b."PlanDataPk"
            ) x ON x.suju_id = s.id
            WHERE s.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL OR s.project_id = CAST(:projNo AS varchar))
              AND (CAST(:state AS varchar) IS NULL
                   OR (CAST(:state AS varchar) = 'exempt' AND x.exempt_yn = 'Y')
                   OR (CAST(:state AS varchar) = 'done'
                       AND COALESCE(x.exempt_yn, 'N') <> 'Y'
                       AND COALESCE(i.insp_cnt, 0) > 0)
                   OR (CAST(:state AS varchar) = 'wait'
                       AND COALESCE(x.exempt_yn, 'N') <> 'Y'
                       AND COALESCE(i.insp_cnt, 0) = 0))
            ORDER BY CASE WHEN x.exempt_yn = 'Y' THEN 2
                          WHEN COALESCE(i.insp_cnt, 0) > 0 THEN 1
                          ELSE 0 END
                   , s.line, s.id
            """, p);
	}

	/** 상태별 건수 (탭 뱃지용) */
	public Map<String, Object> getStateCount(String spjangcd, String projNo) {
		List<Map<String, Object>> all = getTargetList(spjangcd, projNo, null);

		int wait = 0, done = 0, exempt = 0;
		for (Map<String, Object> r : all) {
			String st = str(r.get("insp_state"));
			if ("exempt".equals(st)) exempt++;
			else if ("done".equals(st)) done++;
			else wait++;
		}
		return Map.of("wait_cnt", wait, "done_cnt", done,
				"exempt_cnt", exempt, "total_cnt", all.size());
	}

	// =================================================================
	// 검사 등록 — mat_produce ProcessOrder=3
	// =================================================================

	/**
	 * 검사 등록.
	 *
	 * 한 품목을 여러 번 검사할 수 있다 (나눠 입고되는 외작, 재검사).
	 * 행을 계속 추가하며 UPDATE 하지 않는다.
	 *
	 * <b>재고를 만들지 않는다.</b> mat_lot / mat_inout 을 생성하지 않으며
	 * mat_consu(투입)도 만들지 않는다.
	 *
	 * @return 저장된 mat_produce.id
	 */
	@Transactional
	public Integer save(Map<String, Object> payload, User user) {

		Integer sujuId = toInt(payload.get("sujuId"));
		String spjangcd = str(payload.get("spjangcd"));

		Map<String, Object> item = getOrderInfo(sujuId);
		if (item == null) return null;   // 수주에 없는 품목 — 컨트롤러에서 검증됨

		// 작업지시가 없으면 여기서 만든다 (외작은 지시를 하지 않는다)
		Integer jobResId = ensureOrder(item, user);
		if (jobResId == null) return null;

		Map<String, Object> wc = getInspectWorkCenter(spjangcd);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("jobResId", jobResId);
		p.addValue("materialId", toInt(item.get("material_id")));
		p.addValue("processId", wc == null ? null : toInt(wc.get("process_id")));
		p.addValue("workCenterId", wc == null ? null : toInt(wc.get("workcenter_id")));
		p.addValue("procOrder", PROCESS_ORDER_INSPECT);
		p.addValue("inspectDate", nullIfEmpty(payload.get("inspectDate")));
		p.addValue("goodQty", toDouble(payload.get("goodQty")));
		p.addValue("defectQty", toDouble(payload.get("defectQty")));
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("equipmentId", toInt(payload.get("equipmentId")));
		p.addValue("description", str(payload.get("remark")));
		p.addValue("userId", user.getId());

		// LotIndex 는 NOT NULL. 같은 지시의 검사 단계 안에서 1부터 증가시킨다.
		// LastProcessYN='Y' — 검사가 마지막 공정이다.
		return sqlRunner.queryForObject("""
            INSERT INTO mat_produce (
                 spjangcd, "JobResponse_id", "Material_id", "Process_id", "WorkCenter_id"
               , "ProcessOrder", "LastProcessYN", "LotIndex"
               , "GoodQty", "DefectQty", "LossQty", "ScrapQty"
               , "State", "ProductionDate", "StartTime", "EndTime"
               , "Actor_id", "Equipment_id", "Description"
               , _status, _created, _creater_id
            )
            SELECT :spjangcd, :jobResId, :materialId, :processId, :workCenterId
                 , :procOrder, 'Y'
                 , COALESCE((SELECT MAX(m2."LotIndex") FROM mat_produce m2
                             WHERE m2."JobResponse_id" = :jobResId
                               AND m2."ProcessOrder" = :procOrder), 0) + 1
                 , :goodQty, :defectQty, 0, 0
                 , 'finished'
                 , COALESCE(CAST(:inspectDate AS date), CURRENT_DATE)
                 , now(), now()
                 , :workerId, :equipmentId, NULLIF(:description, '')
                 , 'a', now(), :userId
            RETURNING id
            """, p, (rs, rowNum) -> rs.getInt("id"));
	}

	/** 검사 실적 id → 그 실적이 달린 품목(suju.id). 취소 후 상태 재계산에 필요하다 */
	public Integer sujuIdOf(Integer matProduceId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", matProduceId);

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT j."SourceDataPk" AS suju_id
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            WHERE mp.id = :id
            """, p);
		return row == null ? null : toInt(row.get("suju_id"));
	}

	/**
	 * 검사 실적에 맞춰 job_res 상태를 갱신한다.
	 *
	 * ★ 검사가 <b>공정 완료 판정</b>을 갖는다.
	 *   검사 누적이 지그 대수(suju."SujuQty")에 도달하면 그 공정은 끝이다.
	 *   공정 조립(ProcessOrder=2) 단계를 없애면서 그 역할이 여기로 왔다.
	 *
	 *   "GoodQty"(유닛 축 누적)는 건드리지 않는다 — 조립이 채우는 값이다.
	 *   여기서는 State / EndTime 만 본다.
	 *
	 *   "SujuQty" 가 0 이면 닫지 않는다. 0 을 목표로 두면 즉시 완료가 된다.
	 *   검사를 취소해 누적이 내려가면 다시 열린다.
	 */
	@Transactional
	public void syncJobResState(Integer sujuId, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		p.addValue("inspOrder", PROCESS_ORDER_INSPECT);
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            UPDATE job_res j
            SET "State"        = CASE WHEN s.set_target > 0
                                       AND COALESCE(t.insp_done, 0) >= s.set_target
                                      THEN 'finished' ELSE 'working' END
              , "EndTime"      = CASE WHEN s.set_target > 0
                                       AND COALESCE(t.insp_done, 0) >= s.set_target
                                      THEN now() ELSE NULL END
              , "_modified"    = now()
              , "_modifier_id" = :userId
            FROM (
                SELECT su.id
                     , COALESCE(su."SujuQty", 0) AS set_target   -- 지그 대수
                FROM suju su WHERE su.id = :sujuId
            ) s
            LEFT JOIN job_res jj
                   ON jj."SourceTableName" = 'suju' AND jj."SourceDataPk" = s.id
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS insp_done
                FROM mat_produce
                WHERE "ProcessOrder" = :inspOrder AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) t ON t."JobResponse_id" = jj.id
            WHERE j.id = jj.id
            """, p);
	}

	/** 검사 이력 */
	public List<Map<String, Object>> getLog(String spjangcd, String projNo, Integer sujuId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("sujuId", sujuId);
		p.addValue("inspOrder", PROCESS_ORDER_INSPECT);

		return sqlRunner.getRows("""
            SELECT mp.id
                 , s.project_id      AS proj_no
                 , s.id              AS suju_id
                 , s.line            AS line_name
                 , s."Material_Name" AS item_name
                 , COALESCE(s.make_type, 'inhouse') AS make_type
                 , COALESCE(mp."GoodQty", 0)   AS good_qty
                 , COALESCE(mp."DefectQty", 0) AS defect_qty
                 , CASE WHEN COALESCE(mp."DefectQty", 0) > 0
                        THEN 'fail' ELSE 'pass' END AS result
                 , mp."Actor_id"     AS worker_id
                 , pw."Name"         AS worker
                 , e."Name"          AS equipment_name
                 , mp."Description"  AS remark
                 , TO_CHAR(mp."ProductionDate", 'YYYY-MM-DD') AS inspect_date
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            JOIN suju s    ON s.id = j."SourceDataPk"
            LEFT JOIN person pw ON pw.id = mp."Actor_id"
            LEFT JOIN equ e     ON e.id = mp."Equipment_id"
            WHERE mp.spjangcd = :spjangcd
              AND mp."ProcessOrder" = :inspOrder
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
              AND (CAST(:sujuId AS integer) IS NULL
                   OR s.id = CAST(:sujuId AS integer))
            ORDER BY mp.id DESC
            LIMIT 100
            """, p);
	}

	@Transactional
	public void delete(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		p.addValue("inspOrder", PROCESS_ORDER_INSPECT);
		// 검사 행만 지운다. 실수로 조립 실적이 지워지지 않게 ProcessOrder 를 함께 건다.
		sqlRunner.execute("""
            DELETE FROM mat_produce
            WHERE id = :id AND "ProcessOrder" = :inspOrder
            """, p);
	}

	/** 품목의 작업지시 정보. 검사 실적을 붙일 job_res 를 찾는다 */
	/**
	 * 검사 대상 품목 정보. <b>작업지시가 없어도 돌려준다.</b>
	 * job_res_id 가 null 이면 아직 컨테이너가 없다는 뜻이고, ensureOrder 가 만든다.
	 */
	public Map<String, Object> getOrderInfo(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);

		return sqlRunner.getRow("""
            SELECT j.id            AS job_res_id
                 , s.id            AS suju_id
                 , s."Material_id" AS material_id
                 , s."Material_Name" AS item_name
                 , s.project_id    AS proj_no
                 , s.spjangcd      AS spjangcd
                 , COALESCE(s."SujuQty", 0) AS jig_qty     -- 지그 대수 (검사 목표)
                 , COALESCE(s.unit_qty, 0)  AS unit_qty    -- 유닛 총량 (job_res.OrderQty)
                 , s.make_type     AS make_type
            FROM suju s
            LEFT JOIN job_res j ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            WHERE s.id = :sujuId
            LIMIT 1
            """, p);
	}

	/**
	 * 검사 실적을 매달 job_res 를 확보한다. 없으면 만든다.
	 *
	 * mat_produce."JobResponse_id" 가 NOT NULL 이라 컨테이너 없이는 검사를 저장할 수 없다.
	 * 외작은 생산 지시를 하지 않으므로 <b>검사가 이 품목의 첫 접점</b>이 된다.
	 * 사용자에게 "먼저 작업 지시를 하세요" 라고 요구하지 않는다 —
	 * 외작에 가공 지시를 넣는 것은 사실과 다르고, 실제로 그 요구 때문에
	 * 생산 지시 화면에서 외작을 지시해 버리는 일이 있었다.
	 *
	 * SourceTableName='suju' 규약은 그대로 지킨다. 대시보드가 이 규약으로 붙는다.
	 */
	@Transactional
	public Integer ensureOrder(Map<String, Object> item, User user) {
		Integer jobResId = toInt(item.get("job_res_id"));
		if (jobResId != null) return jobResId;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("workOrderNumber", "INSP-" + str(item.get("suju_id")));
		p.addValue("sujuId", toInt(item.get("suju_id")));
		p.addValue("materialId", toInt(item.get("material_id")));
		p.addValue("orderQty", toDouble(item.get("unit_qty")));
		p.addValue("spjangcd", str(item.get("spjangcd")));
		p.addValue("description", str(item.get("proj_no")) + " / " + str(item.get("item_name"))
				+ " (검사)");
		p.addValue("userId", user.getId());

		return sqlRunner.queryForObject("""
            INSERT INTO job_res (
                 "WorkOrderNumber", "SourceTableName", "SourceDataPk"
               , "Material_id", "OrderQty", "State"
               , "ProductionDate", "ProductionPlanDate", "Description", spjangcd
               , "GoodQty", "DefectQty"
               , "_created", "_creater_id"
            ) VALUES (
                 :workOrderNumber, 'suju', :sujuId
               , :materialId, :orderQty, 'working'
               , now(), now(), :description, :spjangcd
               , 0, 0
               , now(), :userId
            )
            RETURNING id
            """, p, (rs, rowNum) -> rs.getInt("id"));
	}

	// =================================================================
	// helper
	// =================================================================
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

	private Double toDouble(Object o) {
		if (o == null || o.toString().isBlank()) return 0d;
		try {
			return Double.parseDouble(o.toString().trim());
		} catch (NumberFormatException e) {
			return 0d;
		}
	}
}