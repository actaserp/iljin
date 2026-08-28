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
 * 조립 공정
 *
 * [저장 위치]  ★ 신규 테이블 없음. 표준 MES 의 mat_produce 를 그대로 쓴다.
 *
 *   job_res(지시) → mat_produce(실적) 구조가 이미 "지시 1건에 실적 여러 행"을
 *   표현하고 있으므로 조립 실적을 담을 자리가 이미 있다.
 *   iljin_assy_result / iljin_assy_working 은 만들지 않는다.
 *
 * [조립 2단계를 표준 컬럼으로 표현]
 *   work_center 에 조립은 w06 하나뿐이라 워크센터로는 단계를 가를 수 없다.
 *   대신 mat_produce 의 공정 순서 컬럼을 원래 의미대로 쓴다.
 *
 *     ProcessOrder = 1, LastProcessYN = 'N'  →  유닛 조립 (부품 → 유닛)
 *     ProcessOrder = 2, LastProcessYN = 'Y'  →  공정 조립 (유닛 → 지그 1식)
 *
 *   마스터 데이터를 추가하지 않고, 표준 컬럼의 본래 뜻을 벗어나지도 않는다.
 *   LastProcessYN='Y' 인 행이 곧 <b>검사 대상</b>이 된다 (검사는 공정 단위).
 *
 * [진행중 상태]
 *   별도 테이블 없이 mat_produce."State" 로 표현한다.
 *     'working'  : 시작만 하고 아직 완료 안 됨 (GoodQty 0, EndTime NULL)
 *     'finished' : 완료 (GoodQty 입력, EndTime 기록)
 *   시작 시 INSERT, 완료 시 같은 행을 UPDATE 하므로 시작시각을 옮길 필요가 없다.
 *
 * [수량 축]  두 개다. 절대 섞지 않는다.
 *   조립 목표 = suju.unit_qty   (유닛 총량).  이 화면이 세는 것
 *   검사 목표 = suju."SujuQty"  (지그 대수).  공정 완료 판정용
 *   예) SujuQty 2 · unit_qty 20 → 지그 2대, 유닛 20개 (1대당 10개)
 *
 *   suju."Standard" 는 수량으로 쓰지 않는다.
 *   '1' · '2' · '1/1' · '2/2' 가 섞여 있고 '1/1' 은 한 쌍(2개)이라
 *   숫자만 뽑으면 11, 앞자리만 뽑으면 1 이 되어 어느 쪽도 맞지 않는다.
 *
 * [주의]
 *  - mat_produce."Material_id" / "Process_id" / "JobResponse_id" /
 *    "ProcessOrder" / "LastProcessYN" / "LotIndex" / "ProductionDate" 는 NOT NULL 이다.
 *    INSERT 시 반드시 채운다.
 *  - 재고를 만들지 않는다. ProductionResultService.produceInForChasu() 가
 *    mat_lot / mat_inout 을 생성하지만 <b>그 경로를 타지 않고</b> 직접 INSERT 한다.
 *  - 부품을 소모하는 공정은 없다. mat_consu 를 만들지 않는다.
 *  - 기존 "생산 실적" 화면(ProdResultListService)에 조립 실적이 함께 보인다.
 *    같은 job_res 의 실적이므로 정상 동작이다. 구분이 필요하면
 *    그 화면에서 WorkCenter_id 로 필터한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProdAssemblyService {

	private final SqlRunner sqlRunner;

	/** 조립 워크센터 코드. work_center."Code" = 'w06' */
	private static final String ASSY_WORKCENTER_CODE = "w06";

	/**
	 * ★ 조립은 <b>한 단계</b>다. ProcessOrder = 1 고정.
	 *
	 *   예전에는 두 단계였다.
	 *     PO=1 유닛 조립 (부품 → 유닛)
	 *     PO=2 공정 조립 (유닛 → 지그)
	 *   그런데 공정 조립은 현장이 따로 세지 않는 단계였고,
	 *   "S10 지그 1개 완료" 같은 입력을 요구하는 것도 부자연스러웠다.
	 *   게다가 "Standard" 가 '1/1'(한 쌍) 처럼 수량으로 쓰기 어려운 표기라
	 *   지그 대수를 목표로 잡는 것 자체가 불안정했다.
	 *
	 *   그래서 <b>공정 완료 판정을 검사가 가져간다.</b>
	 *   검사 수량이 지그 대수(suju."SujuQty")에 도달하면 그 공정은 끝난 것이다.
	 *   축이 둘(유닛·지그)로 줄어 환산이 사라졌다.
	 *
	 *   PROC_ORDER 상수만 남긴다. stage 파라미터는 화면에서 받지 않는다.
	 */
	public static final int PROC_ORDER = 1;

	/** 조립은 마지막 공정이 아니다. 검사(ProcessOrder=3)가 'Y' 를 갖는다. */
	private static final String LAST_YN = "N";

	// =================================================================
	// 마스터
	// =================================================================

	/** 진행중 프로젝트 */
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
	 * 조립 작업자.
	 *
	 * ★ rtflag 코드값 = 0:재직 / 1:퇴직 / 2:휴직 (sys_code.rtflag_type)
	 *   이전 구현의 <code>COALESCE(rtflag,'1') &lt;&gt; '2'</code> 는
	 *   휴직자를 거르고 퇴직자를 통과시키는 <b>정반대 조건</b>이었다.
	 *   재직('0')만 노출한다. rtflag 가 NULL 인 인원이 실제로 존재하므로
	 *   COALESCE 기본값은 '0'(재직)으로 둔다.
	 *
	 * 조립 워크센터(w06) 인원을 위로 올리되 막지는 않는다 (교대·대체 인력).
	 */
	public List<Map<String, Object>> getWorkerList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("wcCode", ASSY_WORKCENTER_CODE);

		return sqlRunner.getRows("""
            SELECT p.id
                 , p."Name"          AS name
                 , p."Code"          AS code
                 , p."WorkCenter_id" AS workcenter_id
                 , wc."Name"         AS workcenter_name
                 , CASE WHEN wc."Code" = :wcCode THEN 'Y' ELSE 'N' END AS own_area
            FROM person p
            LEFT JOIN work_center wc ON wc.id = p."WorkCenter_id"
            WHERE p.spjangcd = :spjangcd
              AND COALESCE(p.rtflag, '0') = '0'
            ORDER BY CASE WHEN wc."Code" = :wcCode THEN 0 ELSE 1 END
                   , wc."Name", p."Name"
            """, p);
	}

	/**
	 * 조립 워크센터 정보 (id / Process_id).
	 * mat_produce."Process_id" 가 NOT NULL 이라 INSERT 전에 반드시 필요하다.
	 * 코드로 조회한다 — id 를 상수로 박으면 사업장이 늘 때 깨진다.
	 *
	 * ★ 못 찾으면 조립 등록이 <b>NOT NULL 위반으로 실패</b>한다.
	 *   워크센터 코드('w06')는 등록 순서에 따라 달라질 수 있어
	 *   이름('조립' 포함)을 보조 수단으로 함께 본다.
	 *   조립 설비는 설비그룹(equ_grp)이 없어 검사처럼 그룹으로 찾을 수 없다 —
	 *   조립 그룹을 만들면 그쪽을 1순위로 올릴 것.
	 */
	public Map<String, Object> getAssyWorkCenter(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("wcCode", ASSY_WORKCENTER_CODE);

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT wc.id           AS workcenter_id
                 , wc."Process_id" AS process_id
            FROM work_center wc
            WHERE wc.spjangcd = :spjangcd
              AND (wc."Code" = :wcCode OR wc."Name" LIKE '%조립%')
            ORDER BY CASE WHEN wc."Code" = :wcCode THEN 0 ELSE 1 END
            LIMIT 1
            """, p);

		if (row == null) {
			log.warn("[prod_assembly] 조립 워크센터를 찾지 못했다 (spjangcd={}). "
					+ "조립 등록이 Process_id NOT NULL 로 실패한다. "
					+ "work_center 에 조립 워크센터가 있는지 확인할 것.", spjangcd);
		}
		return row;
	}

	// =================================================================
	// 조립 대상 품목
	// =================================================================

	/**
	 * 조립 대상 품목.
	 *
	 *  unit_qty / target_qty  조립 목표 = suju.unit_qty (유닛 총량)
	 *  done_qty               완료 누적 (PO=1)
	 *  wip_qty                남은 수량
	 *  set_target             지그 대수 = suju."SujuQty". 검사 목표이자 공정 완료 기준
	 *  insp_done              검사 누적 (PO=3). 공정이 끝났는지 여기서 본다
	 *  working_id             진행중인 mat_produce.id
	 *
	 * ★ 목표가 0 인 품목(unit_qty 미등록)은 화면에서 '유닛수 미등록' 으로 드러낸다.
	 *   0/0 을 완료로 처리하면 아무것도 안 했는데 끝난 것으로 보인다.
	 *
	 * 완료 누적은 mat_produce."GoodQty" 중 State='finished' 인 것만 센다.
	 * 진행중 행은 GoodQty 가 0 이지만 명시적으로 걸러 두는 편이 안전하다.
	 *
	 * 작업 지시된 품목만 노출한다 (지시 없는 조립은 관리 밖의 작업).
	 */
	public List<Map<String, Object>> getItemList(String spjangcd, String projNo) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return sqlRunner.getRows("""
            SELECT s.id                     AS suju_id
                 , s.project_id             AS proj_no
                 , s.line                   AS line_name
                 , s."Material_Name"        AS item_name
                 , s."Material_id"          AS material_id
                 , s.equip_type             AS equip_type
                 , s.make_type              AS make_type
                 , s.make_comp_name         AS make_comp
                 , s.leg_spec               AS leg_spec
                 , s.leg_cnt                AS leg_cnt
                 , j.id                     AS job_res_id
                 , j."WorkOrderNumber"      AS work_order_number

                 -- ★ 축 두 개. 절대 섞지 않는다.
                 --   unit_qty  = 유닛 총량 (조립이 세는 것).  예: 20
                 --   SujuQty   = 지그 대수 (검사가 세는 것).  예: 2  → 1대당 유닛 10개
                 --   "Standard" 는 '1/1'(한 쌍) 같은 표기가 섞여 수량으로 쓰지 않는다.
                 , COALESCE(s.unit_qty, 0)                            AS unit_qty
                 , COALESCE(s.unit_qty, 0)                            AS target_qty
                 , COALESCE(u.done_qty, 0)                            AS done_qty
                 , COALESCE(s.unit_qty, 0) - COALESCE(u.done_qty, 0)  AS wip_qty

                 -- 검사 축: 지그 대수와 검사 누적. 공정 완료 여부를 여기서 본다
                 , COALESCE(s."SujuQty", 0)                           AS set_target
                 , COALESCE(t.done_qty, 0)                            AS insp_done
                 , CASE WHEN COALESCE(s."SujuQty", 0) > 0
                         AND COALESCE(t.done_qty, 0) >= COALESCE(s."SujuQty", 0)
                        THEN 'Y' ELSE 'N' END                         AS done_yn

                 , w.id                     AS working_id
                 , w."Actor_id"             AS working_worker_id
                 , pw."Name"                AS working_worker
                 , pw."Name"                AS worker
                 , TO_CHAR(w."StartTime", 'HH24:MI') AS start_time
            FROM suju s
            JOIN job_res j
              ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS done_qty
                FROM mat_produce
                WHERE "ProcessOrder" = 1 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) u ON u."JobResponse_id" = j.id
            -- 공정 조립(PO=2)은 폐기됐다. 공정 완료는 검사(PO=3)가 판정한다
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS done_qty
                FROM mat_produce
                WHERE "ProcessOrder" = 3 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) t ON t."JobResponse_id" = j.id
            LEFT JOIN mat_produce w
              ON w."JobResponse_id" = j.id
             AND w."ProcessOrder" = 1
             AND COALESCE(w."State", '') <> 'finished'
            LEFT JOIN person pw ON pw.id = w."Actor_id"
            WHERE s.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL OR s.project_id = CAST(:projNo AS varchar))
            ORDER BY s.line, s.id
            """, p);
	}

	// =================================================================
	// 진행중
	// =================================================================

	/** 진행중인 조립 목록 */
	public List<Map<String, Object>> getWorkingList(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("procOrder", PROC_ORDER);

		return sqlRunner.getRows("""
            SELECT mp.id
                 , s.project_id      AS proj_no
                 , s.id              AS suju_id
                 , s."Material_Name" AS item_name
                 , s.line            AS line_name
                 , mp."ProcessOrder" AS process_order
                 , 'unit' AS stage
                 , '유닛'  AS stage_name
                 , mp."Actor_id"     AS worker_id
                 , pw."Name"         AS worker
                 , TO_CHAR(mp."StartTime", 'HH24:MI') AS start_time
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            JOIN suju s    ON s.id = j."SourceDataPk"
            LEFT JOIN person pw ON pw.id = mp."Actor_id"
            WHERE mp.spjangcd = :spjangcd
              AND COALESCE(mp."State", '') <> 'finished'
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
              AND (CAST(:procOrder AS smallint) IS NULL
                   OR mp."ProcessOrder" = CAST(:procOrder AS smallint))
            ORDER BY mp."StartTime"
            """, p);
	}

	/** 해당 품목·단계로 진행중인 행 (없으면 null) */
	public Map<String, Object> getWorking(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		p.addValue("procOrder", PROC_ORDER);

		return sqlRunner.getRow("""
            SELECT mp.id, mp."StartTime", mp."Actor_id", mp."LotIndex"
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            WHERE j."SourceDataPk" = :sujuId
              AND mp."ProcessOrder" = :procOrder
              AND COALESCE(mp."State", '') <> 'finished'
            ORDER BY mp.id DESC
            LIMIT 1
            """, p);
	}

	// =================================================================
	// 작업 시작 / 완료
	// =================================================================

	/**
	 * 조립 시작.
	 *
	 * mat_produce 에 GoodQty 0 / State='working' 행을 만든다.
	 * 완료 시 같은 행을 UPDATE 하므로 시작시각을 다른 테이블로 옮길 필요가 없다.
	 *
	 * 같은 품목·같은 단계로 이미 진행중이면 작업자만 갱신한다
	 * (교대 인계 시 새 행을 만들면 진행중이 두 줄로 보인다).
	 */
	@Transactional
	public void startWorking(Map<String, Object> payload, User user) {

		Integer sujuId = toInt(payload.get("sujuId"));
		String spjangcd = str(payload.get("spjangcd"));

		Map<String, Object> exist = getWorking(sujuId);
		if (exist != null) {
			MapSqlParameterSource up = new MapSqlParameterSource();
			up.addValue("id", toInt(exist.get("id")));
			up.addValue("workerId", toInt(payload.get("workerId")));
			up.addValue("userId", user.getId());
			sqlRunner.execute("""
                UPDATE mat_produce
                SET "Actor_id"     = :workerId
                  , "_modified"    = now()
                  , "_modifier_id" = :userId
                WHERE id = :id
                """, up);
			return;
		}

		Map<String, Object> item = getOrderInfo(sujuId);
		if (item == null) return;   // 지시 없는 품목 — 컨트롤러에서 이미 걸러진다

		Map<String, Object> wc = getAssyWorkCenter(spjangcd);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("jobResId", toInt(item.get("job_res_id")));
		p.addValue("materialId", toInt(item.get("material_id")));
		p.addValue("processId", wc == null ? null : toInt(wc.get("process_id")));
		p.addValue("workCenterId", wc == null ? null : toInt(wc.get("workcenter_id")));
		p.addValue("procOrder", PROC_ORDER);
		p.addValue("lastYn", LAST_YN);
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("equipmentId", toInt(payload.get("equipmentId")));   // 조립은 보통 NULL
		p.addValue("userId", user.getId());

		// LotIndex 는 NOT NULL. 같은 지시·같은 단계 안에서 1부터 증가시킨다.
		sqlRunner.execute("""
            INSERT INTO mat_produce (
                 spjangcd, "JobResponse_id", "Material_id", "Process_id", "WorkCenter_id"
               , "ProcessOrder", "LastProcessYN", "LotIndex"
               , "GoodQty", "DefectQty", "LossQty", "ScrapQty"
               , "State", "ProductionDate", "StartTime"
               , "Actor_id", "Equipment_id"
               , _status, _created, _creater_id
            )
            SELECT :spjangcd, :jobResId, :materialId, :processId, :workCenterId
                 , :procOrder, :lastYn
                 , COALESCE((SELECT MAX(m2."LotIndex") FROM mat_produce m2
                             WHERE m2."JobResponse_id" = :jobResId
                               AND m2."ProcessOrder" = :procOrder), 0) + 1
                 , 0, 0, 0, 0
                 , 'working', CURRENT_DATE, now()
                 , :workerId, :equipmentId
                 , 'a', now(), :userId
            """, p);
	}

	/**
	 * 조립 완료.
	 *
	 * 진행중 행이 있으면 그 행을 마감하고, 없으면 (시작을 안 누른 경우)
	 * 완료 상태의 행을 새로 만든다 — 현장이 시작을 건너뛰는 일이 잦다.
	 *
	 * 수량의 의미가 단계별로 다르다.
	 *   unit : 완성한 유닛 개수
	 *   set  : 완성한 공정(지그 1식) 개수. 보통 1
	 *
	 * <b>재고를 만들지 않는다.</b> mat_lot / mat_inout 을 생성하지 않으며
	 * mat_consu(투입)도 만들지 않는다 (부품을 소모하는 공정이 없다).
	 */
	@Transactional
	public void complete(Map<String, Object> payload, User user) {

		Integer sujuId = toInt(payload.get("sujuId"));

		Map<String, Object> w = getWorking(sujuId);

		if (w == null) {
			// 시작을 누르지 않고 바로 완료한 경우 — 행을 만들고 이어서 마감한다
			startWorking(payload, user);
			w = getWorking(sujuId);
			if (w == null) return;
		}

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", toInt(w.get("id")));
		p.addValue("qty", toDouble(payload.get("qty")));
		p.addValue("defectQty", toDouble(payload.get("defectQty")));
		p.addValue("prodDate", nullIfEmpty(payload.get("prodDate")));
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("description", str(payload.get("remark")));
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            UPDATE mat_produce
            SET "GoodQty"        = :qty
              , "DefectQty"      = :defectQty
              , "State"          = 'finished'
              , "EndTime"        = now()
              , "ProductionDate" = COALESCE(CAST(:prodDate AS date), "ProductionDate")
              , "Actor_id"       = COALESCE(:workerId, "Actor_id")
              , "Description"    = NULLIF(:description, '')
              , "_modified"      = now()
              , "_modifier_id"   = :userId
            WHERE id = :id
            """, p);

		syncJobResState(sujuId, user);
	}

	/**
	 * 작업중 해제 (실적 없이 취소).
	 * 진행중 행을 지운다. 이미 완료된 행은 건드리지 않는다.
	 */
	@Transactional
	public void endWorking(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		p.addValue("procOrder", PROC_ORDER);

		sqlRunner.execute("""
            DELETE FROM mat_produce
            WHERE id IN (
                SELECT mp.id FROM mat_produce mp
                JOIN job_res j ON j.id = mp."JobResponse_id"
                              AND j."SourceTableName" = 'suju'
                WHERE j."SourceDataPk" = :sujuId
                  AND mp."ProcessOrder" = :procOrder
                  AND COALESCE(mp."State", '') <> 'finished'
            )
            """, p);
	}

	/**
	 * job_res 의 누적/상태를 조립 실적에 맞춘다.
	 *
	 * job_res 는 <b>유닛 축</b>이다.
	 *   "OrderQty" = suju.unit_qty (유닛 총량)
	 *   "GoodQty"  = 조립(ProcessOrder=1) 누적
	 *
	 * ★ 완료 판정만 다른 축에서 온다.
	 *   검사(ProcessOrder=3) 누적이 지그 대수(suju."SujuQty")에 도달하면 'finished'.
	 *   공정 조립(PO=2) 단계를 없애면서 그 역할이 검사로 넘어갔다.
	 *
	 *   유닛을 다 조립했다고 공정이 끝난 것은 아니다 —
	 *   유닛을 지그에 짜맞추고 검사를 통과해야 끝이다.
	 *   그래서 GoodQty 가 OrderQty 에 닿아도 자동으로 닫지 않는다.
	 *
	 *   "SujuQty" 가 0 인 품목은 닫히지 않는다 (set_target > 0 가드).
	 *   0 을 목표로 두면 아무것도 안 했는데 완료가 된다.
	 */
	@Transactional
	public void syncJobResState(Integer sujuId, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            UPDATE job_res j
            SET "GoodQty"      = COALESCE(u.good_qty, 0)
              , "DefectQty"    = COALESCE(u.defect_qty, 0)
              , "State"        = CASE WHEN COALESCE(t.set_done, 0) >= s.set_target
                                       AND s.set_target > 0
                                      THEN 'finished' ELSE j."State" END
              , "EndTime"      = CASE WHEN COALESCE(t.set_done, 0) >= s.set_target
                                       AND s.set_target > 0
                                      THEN now() ELSE j."EndTime" END
              , "_modified"    = now()
              , "_modifier_id" = :userId
            FROM (
                SELECT su.id
                     , COALESCE(su."SujuQty", 0) AS set_target   -- 지그 대수 (검사 목표)
                FROM suju su WHERE su.id = :sujuId
            ) s
            LEFT JOIN job_res jj
                   ON jj."SourceTableName" = 'suju' AND jj."SourceDataPk" = s.id
            -- 누적은 유닛 축(PO=1). OrderQty 가 유니트수라 축이 맞는다
            LEFT JOIN (
                SELECT "JobResponse_id"
                     , SUM(COALESCE("GoodQty", 0))   AS good_qty
                     , SUM(COALESCE("DefectQty", 0)) AS defect_qty
                FROM mat_produce
                WHERE "ProcessOrder" = 1 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) u ON u."JobResponse_id" = jj.id
            -- 완료 판정은 검사(PO=3). 공정 조립 단계는 폐기됐다
            LEFT JOIN (
                SELECT "JobResponse_id", SUM(COALESCE("GoodQty", 0)) AS set_done
                FROM mat_produce
                WHERE "ProcessOrder" = 3 AND "State" = 'finished'
                GROUP BY "JobResponse_id"
            ) t ON t."JobResponse_id" = jj.id
            WHERE j.id = jj.id
            """, p);
	}

	// =================================================================
	// 실적 이력
	// =================================================================

	/**
	 * 조립 이력.
	 *
	 * 생산일은 <b>등록할 때 찍는 값</b>이지 조회 조건이 아니다.
	 * 조립은 여러 날에 걸쳐 나눠 완료되므로 날짜로 자르면
	 * "어제 완료한 것"이 화면에서 사라져 작업자가 중복 등록하게 된다.
	 * 날짜를 넘기지 않으면 프로젝트 기준 최근 100건을 그대로 보여준다.
	 */
	public List<Map<String, Object>> getResultLog(String spjangcd, String prodDate,
												  String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("prodDate", nullIfEmpty(prodDate));
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("procOrder", PROC_ORDER);

		return sqlRunner.getRows("""
            SELECT mp.id
                 , s.project_id      AS proj_no
                 , s.id              AS suju_id
                 , s."Material_Name" AS item_name
                 , s.line            AS line_name
                 , mp."ProcessOrder" AS process_order
                 , 'unit' AS stage
                 , '유닛'  AS stage_name
                 , COALESCE(mp."GoodQty", 0)   AS qty
                 , COALESCE(mp."DefectQty", 0) AS defect_qty
                 , mp."Actor_id"     AS worker_id
                 , pw."Name"         AS worker
                 , mp."Description"  AS remark
                 , mp."State"        AS state
                 , TO_CHAR(mp."ProductionDate", 'YYYY-MM-DD') AS prod_date
                 , TO_CHAR(mp."StartTime", 'HH24:MI')         AS start_time
                 , TO_CHAR(mp."EndTime", 'HH24:MI')           AS end_time
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            JOIN suju s    ON s.id = j."SourceDataPk"
            LEFT JOIN person pw ON pw.id = mp."Actor_id"
            WHERE mp.spjangcd = :spjangcd
              AND (CAST(:prodDate AS date) IS NULL
                   OR mp."ProductionDate" = CAST(:prodDate AS date))
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
              AND (CAST(:procOrder AS smallint) IS NULL
                   OR mp."ProcessOrder" = CAST(:procOrder AS smallint))
            ORDER BY mp.id DESC
            LIMIT 100
            """, p);
	}

	/** 실적 취소. 취소 후 job_res 누적을 다시 맞춘다 */
	@Transactional
	public void deleteResult(Integer id, User user) {
		MapSqlParameterSource f = new MapSqlParameterSource();
		f.addValue("id", id);

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT j."SourceDataPk" AS suju_id
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            WHERE mp.id = :id
            """, f);

		sqlRunner.execute("DELETE FROM mat_produce WHERE id = :id", f);

		if (row != null) syncJobResState(toInt(row.get("suju_id")), user);
	}

	// =================================================================
	// 지시 정보 / 검사 연계
	// =================================================================

	/** 품목의 작업지시 정보. 조립 실적을 붙일 job_res 를 찾는다 */
	public Map<String, Object> getOrderInfo(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);

		return sqlRunner.getRow("""
            SELECT j.id            AS job_res_id
                 , j."WorkOrderNumber" AS work_order_number
                 , s.id            AS suju_id
                 , s."Material_id" AS material_id
                 , s."Material_Name" AS item_name
                 , s.project_id    AS proj_no
                 , s.spjangcd      AS spjangcd
            FROM suju s
            JOIN job_res j ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            WHERE s.id = :sujuId
            LIMIT 1
            """, p);
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