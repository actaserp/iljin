package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 생산 실적 입력 (가공 키오스크)
 *
 * [기록 단위] 가공품(부품) 개수. 유닛(지그 세트) 개수가 아니다.
 * [축] 프로젝트 × 품목(suju) × 유형 × 가공공정 × 수량
 *      가공공정은 설비에서 자동 결정. 라우팅 없음.
 * [재고 없음] 차감/귀속/파기 없음. 누적 생산량만 쌓는다.
 *      필요량은 iljin_suju_bom 이 공급하고 화면은 둘을 나란히 보여줄 뿐이다.
 */
@Service
@RequiredArgsConstructor
public class ProdResultService {

	/**
	 * 가공이 <b>아닌</b> 설비그룹 코드.
	 *
	 * equ_grp 에 등록된 것 중 여기 없는 그룹은 전부 가공공정으로 본다.
	 * 검사 설비(3차원측정)를 이 목록으로 뺀다.
	 * 조립 작업대는 설비그룹이 없어 JOIN 에서 자동으로 빠진다 —
	 * 나중에 조립 그룹을 만들면 그 코드를 여기 추가할 것.
	 */
	private static final java.util.List<String> NON_MACHINING_GROUPS =
			java.util.List.of("CMM");

	private final SqlRunner sqlRunner;

	// =================================================================
	// 마스터
	// =================================================================

	/**
	 * 가공공정 목록 (키오스크 선택용).
	 *
	 * <b>가공공정 = 워크센터다.</b> work_center 가 이미
	 * 레이저 모형절단 / 머시닝센터 / 와이어커팅 / N/C 선반·밀링 단위로 등록돼 있으므로
	 * 설비 마스터에 공정 문자열을 따로 두지 않는다 (신규 iljin_equipment 는 폐기).
	 * 설비는 기존 equ 를 그대로 쓰고, equ."WorkCenter_id" 가 공정을 결정한다.
	 *
	 * 설비가 한 대도 없는 워크센터는 나오지 않는다 —
	 * "공정을 늘리려면 설비만 추가" 가 그대로 성립한다.
	 *
	 * 단, 워크센터에는 가공이 아닌 것도 섞여 있다 (3차원측정 검사, 조립).
	 *
	 * ★ 가공 여부는 <b>설비그룹(equ_grp)</b>으로 가른다.
	 *   이전에는 wc."Code" IN ('w01'..'w04') 로 박아 두었는데,
	 *   설비를 한 대 늘릴 때마다 코드를 고쳐 배포해야 해서
	 *   SPEC 5-2 의 "공정을 늘리려면 설비만 추가" 와 어긋났다.
	 *
	 *   판별은 <b>제외 목록</b>으로 한다 (포함 목록이 아니라).
	 *   새 가공설비를 사면 그룹만 지정하면 자동으로 공정이 늘고,
	 *   검사·조립 설비는 그룹이 없거나 NON_MACHINING 에 걸려 빠진다.
	 *   포함 목록이면 새 설비가 조용히 누락되는데, 그쪽이 더 위험하다.
	 */
	public List<Map<String, Object>> getOperationList(String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("nonMachining", NON_MACHINING_GROUPS);

		String sql = """
            SELECT wc."Name"   AS operation
                 , wc.id      AS workcenter_id
                 , wc."Name"  AS workcenter_name
                 , COUNT(e.id) AS equip_cnt
                 , wc."Code"  AS sort
            FROM work_center wc
            JOIN equ e ON e."WorkCenter_id" = wc.id
                      AND e.spjangcd = wc.spjangcd
                      AND e."DisposalDate" IS NULL
            -- 설비그룹이 없는 설비(조립 작업대 등)는 INNER JOIN 에서 빠진다
            JOIN equ_grp g ON g.id = e."EquipmentGroup_id"
                          AND g."Code" NOT IN (:nonMachining)
            WHERE wc.spjangcd = :spjangcd
            GROUP BY wc.id, wc."Name", wc."Code"
            ORDER BY wc."Code"
            """;

		return sqlRunner.getRows(sql, p);
	}

	/** 설비 목록 (equ). 공정(=워크센터명)이 지정되면 그 공정 설비만 */
	public List<Map<String, Object>> getEquipmentList(String spjangcd, String operation) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("operation", nullIfEmpty(operation));
		p.addValue("nonMachining", NON_MACHINING_GROUPS);

		String sql = """
            SELECT e.id
                 , e."Code"          AS code
                 , e."Name"          AS name
                 , wc."Name"         AS operation
                 , e."WorkCenter_id" AS workcenter_id
            FROM equ e
            JOIN work_center wc ON wc.id = e."WorkCenter_id"
            -- 공정을 안 넘기면 전체가 나오므로 여기서도 검사·조립 설비를 뺀다
            JOIN equ_grp g ON g.id = e."EquipmentGroup_id"
                          AND g."Code" NOT IN (:nonMachining)
            WHERE e.spjangcd = :spjangcd
              AND e."DisposalDate" IS NULL
              AND (CAST(:operation AS varchar) IS NULL
                   OR wc."Name" = CAST(:operation AS varchar))
            ORDER BY wc."Code", e."Code"
            """;

		return sqlRunner.getRows(sql, p);
	}

	/**
	 * 작업자 목록. 직원 정보는 person 테이블이 원본이다.
	 *
	 * 워크센터(WorkCenter_id)로 담당을 가른다 — 부서는 조직도라 총무·영업까지 섞이지만
	 * 워크센터는 작업 단위라 가공공정과 그대로 대응된다.
	 *
	 * 담당 워크센터 인원을 위로 올리되 막지는 않는다 (교대·대체 인력 대응).
	 *
	 * ★ rtflag 실제 코드값 = 0:재직 / 1:퇴직 / 2:휴직 (sys_code.rtflag_type 확인 결과)
	 *   이전 조건 COALESCE(rtflag,'1') <> '2' 는 <b>휴직자를 거르고 퇴직자를 통과</b>시키는
	 *   정반대 조건이었다. 재직('0')만 노출한다.
	 *   rtflag 가 NULL 인 인원이 실제로 있으므로 COALESCE 기본값은 '0'(재직)으로 둔다.
	 */
	public List<Map<String, Object>> getWorkerList(String spjangcd, Integer workCenterId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("workCenterId", workCenterId);

		String sql = """
            SELECT p.id
                 , p."Name"          AS name
                 , p."Code"          AS code
                 , p."WorkCenter_id" AS workcenter_id
                 , wc."Name"         AS workcenter_name
                 , CASE WHEN p."WorkCenter_id" = CAST(:workCenterId AS integer)
                        THEN 'Y' ELSE 'N' END AS own_area
            FROM person p
            LEFT JOIN work_center wc ON wc.id = p."WorkCenter_id"
            WHERE p.spjangcd = :spjangcd
              AND COALESCE(p.rtflag, '0') = '0'
            ORDER BY CASE WHEN p."WorkCenter_id" = CAST(:workCenterId AS integer) THEN 0 ELSE 1 END
                   , wc."Name", p."Name"
            """;

		return sqlRunner.getRows(sql, p);
	}

	/** 진행중 프로젝트 */
	public List<Map<String, Object>> getProjectList(String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		String sql = """
            SELECT d.projno AS proj_no
                 , d.projnm AS proj_name
            FROM tb_da003 d
            WHERE d.spjangcd = :spjangcd
              AND COALESCE(d.endflag, '0') <> '1'
            ORDER BY d.eddate, d.projno
            """;

		return sqlRunner.getRows(sql, p);
	}

	/**
	 * 품목 목록.
	 * 작업 지시된 품목만 보여준다 — 지시 없이 실적이 찍히면 관리 밖의 작업이 된다.
	 * 부품(BOM)이 등록된 품목만 유형 타일을 그릴 수 있으므로 부품 수도 같이 준다.
	 */
	public List<Map<String, Object>> getItemList(String spjangcd, String projNo) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", projNo);

		String sql = """
            SELECT s.id                    AS suju_id
                 , s.line                  AS line_name
                 , s."Material_Name"       AS item_name
                 , COALESCE(s.unit_qty, 0) AS unit_qty
                 , COALESCE(b.part_cnt, 0) AS part_cnt
            FROM suju s
            JOIN job_res j
              ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            LEFT JOIN (
                SELECT "Suju_id", COUNT(*) AS part_cnt
                FROM iljin_suju_bom
                WHERE "Gubun" = '제작품'
                GROUP BY "Suju_id"
            ) b ON b."Suju_id" = s.id
            WHERE s.spjangcd = :spjangcd
              AND s.project_id = :projNo
            GROUP BY s.id, s.line, s."Material_Name", s.unit_qty, b.part_cnt
            ORDER BY s.line, s.id
            """;

		return sqlRunner.getRows(sql, p);
	}

	// =================================================================
	// 유형 타일 - 필요량 vs 누적 생산량
	//
	//   여기가 생산지시(BOM)와 만나는 지점이다.
	//   need_qty : iljin_suju_bom 합계 (이 품목에 필요한 가공품 수)
	//              ★ 유니트수를 곱하지 않는다. 부품표의 수량이 이미 전체 총량이다.
	//   done_qty : iljin_prod_result 누적 (지금까지 만든 수)
	//   재고가 아니므로 차감하지 않는다. 두 숫자를 나란히 보여줄 뿐이다.
	//
	//   ★ operation 을 넘기면 <b>그 공정의 실적만</b> 센다.
	//     한 부품은 절단 → 가공 → 와이어커팅을 거치며 공정마다 다시 세어지므로,
	//     공정을 합치면 실물보다 큰 수가 나온다
	//     (plate 를 340개 자르고 120개를 가공했는데 460 으로 표시되는 식).
	//     키오스크는 공정 단위로 서 있고, 절단 작업자가 보고 싶은 것도
	//     "내가 오늘 몇 개 잘랐나" 이지 총합이 아니다.
	//     operation 을 비우면 종전대로 전 공정 합계가 나오므로, 호출부가 반드시 넘길 것.
	// =================================================================
	public List<Map<String, Object>> getKindTiles(String spjangcd, String projNo,
												  Integer sujuId, String operation) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		// 빈 문자열이 오면 "전체" 로 보고 NULL 로 정규화한다
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("sujuId", sujuId);
		p.addValue("operation", nullIfEmpty(operation));

		String sql = """
            WITH need AS (
                SELECT b."Kind" AS kind
                     -- ★ "Qty" 는 공정(품목) 전체 총량이다. 유니트수를 곱하지 않는다.
                     --  이 계산식은 ProdDesignService 의 kind_summary / kind_need,
                     --  DashProjectService 의 getKinds / getProjectKinds 와
                     --  반드시 같아야 한다. 갈리면 생산지시 화면과 키오스크가
                     --  서로 다른 필요량을 보여준다.
                     , SUM(b."Qty") AS need_qty
                FROM iljin_suju_bom b
                JOIN suju s ON s.id = b."Suju_id"
                WHERE s.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL OR s.project_id = CAST(:projNo AS varchar))
                  AND (CAST(:sujuId AS integer) IS NULL OR b."Suju_id" = CAST(:sujuId AS integer))
                  AND b."Gubun" = '제작품'
                GROUP BY b."Kind"
            ), done AS (
                SELECT r."Kind" AS kind, SUM(r."GoodQty") AS done_qty
                FROM iljin_prod_result r
                WHERE r.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL OR r."Project_id" = CAST(:projNo AS varchar))
                  AND (CAST(:sujuId AS integer) IS NULL OR r."Suju_id" = CAST(:sujuId AS integer))
                  AND (CAST(:operation AS varchar) IS NULL
                       OR r."Operation" = CAST(:operation AS varchar))
                GROUP BY r."Kind"
            )
            SELECT COALESCE(n.kind, d.kind)     AS kind
                 , COALESCE(n.need_qty, 0)      AS need_qty
                 , COALESCE(d.done_qty, 0)      AS done_qty
            FROM need n
            FULL OUTER JOIN done d ON d.kind = n.kind
            ORDER BY COALESCE(n.need_qty, 0) DESC, COALESCE(n.kind, d.kind)
            """;

		return sqlRunner.getRows(sql, p);
	}

	// =================================================================
	// 실적 등록 / 취소
	// =================================================================
	@Transactional
	public void saveResult(Map<String, Object> payload, String operation, User user) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", str(payload.get("spjangcd")));
		p.addValue("prodDate", str(payload.get("prodDate")));
		p.addValue("projNo", nullIfEmpty(payload.get("projNo")));
		p.addValue("sujuId", toInt(payload.get("sujuId")));
		// 유형 미선택은 '기타(etc)' 로 정규화한다.
		// (회의록: 구분이 안 되면 전부 기타로 묶어 총 집계)
		p.addValue("kind", defaultIfEmpty(payload.get("kind"), "etc"));
		p.addValue("operation", operation);
		p.addValue("equipment", str(payload.get("equipment")));
		p.addValue("worker", str(payload.get("worker")));
		// person.id. 이름만 남기면 동명이인·개명 시 추적이 끊긴다
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("goodQty", toDouble(payload.get("goodQty")));
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            INSERT INTO iljin_prod_result (
                 spjangcd, "ProdDate", "Project_id", "Suju_id", "Kind"
               , "Operation", "Equipment", "Worker", "Worker_id", "GoodQty"
               , "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, CAST(:prodDate AS date), :projNo, :sujuId, :kind
               , :operation, :equipment, :worker, :workerId, :goodQty
               , now(), :userId
            )
            """, p);
	}

	@Transactional
	public void deleteResult(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		sqlRunner.execute("DELETE FROM iljin_prod_result WHERE id = :id", p);
	}

	/** 오늘(또는 지정일) 이 키오스크에서 등록한 내역 */
	public List<Map<String, Object>> getResultLog(String spjangcd, String prodDate,
												  String equipment, String operation) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("prodDate", prodDate);
		p.addValue("equipment", nullIfEmpty(equipment));
		// ★ 키오스크는 설비 옆에 서 있다. 설비를 아직 안 골랐어도
		//   다른 공정의 실적이 섞여 보이면 안 된다.
		p.addValue("operation", nullIfEmpty(operation));

		String sql = """
            SELECT r.id
                 , r."Project_id"    AS proj_no
                 , s."Material_Name" AS item_name
                 , r."Kind"          AS kind
                 , r."Operation"     AS operation
                 , r."Equipment"     AS equipment
                 , r."Worker"        AS worker
                 , r."GoodQty"       AS good_qty
                 , TO_CHAR(r."_created", 'HH24:MI') AS reg_time
            FROM iljin_prod_result r
            LEFT JOIN suju s ON s.id = r."Suju_id"
            WHERE r.spjangcd = :spjangcd
              AND r."ProdDate" = CAST(:prodDate AS date)
              AND (CAST(:equipment AS varchar) IS NULL OR r."Equipment" = CAST(:equipment AS varchar))
              AND (CAST(:operation AS varchar) IS NULL OR r."Operation" = CAST(:operation AS varchar))
            ORDER BY r.id DESC
            LIMIT 50
            """;

		return sqlRunner.getRows(sql, p);
	}

	// =================================================================
	// 작업중 상태
	//   대시보드의 "현재 진행중인 공정 / 작업자" 를 채운다.
	// =================================================================
	public List<Map<String, Object>> getWorkingList(String spjangcd, String equipment,
													String operation, String projNo) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("equipment", nullIfEmpty(equipment));
		// 키오스크는 자기 공정만 본다. 대시보드는 프로젝트로 걸러 본다.
		p.addValue("operation", nullIfEmpty(operation));
		p.addValue("projNo", nullIfEmpty(projNo));

		String sql = """
            SELECT w.id
                 , w."Equipment"     AS equipment
                 , w."Operation"     AS operation
                 , w."Project_id"    AS proj_no
                 , w."Suju_id"       AS suju_id
                 , s."Material_Name" AS item_name
                 , w."Kind"          AS kind
                 , w."Worker"        AS worker
                 , TO_CHAR(w."StartTime", 'HH24:MI') AS start_time
            FROM iljin_prod_working w
            LEFT JOIN suju s ON s.id = w."Suju_id"
            WHERE w.spjangcd = :spjangcd
              AND (CAST(:equipment AS varchar) IS NULL OR w."Equipment" = CAST(:equipment AS varchar))
              AND (CAST(:operation AS varchar) IS NULL OR w."Operation" = CAST(:operation AS varchar))
              AND (CAST(:projNo AS varchar) IS NULL OR w."Project_id" = CAST(:projNo AS varchar))
            ORDER BY w."StartTime" DESC
            """;

		return sqlRunner.getRows(sql, p);
	}

	@Transactional
	public void startWorking(Map<String, Object> payload, String operation, User user) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", str(payload.get("spjangcd")));
		p.addValue("equipment", str(payload.get("equipment")));
		p.addValue("operation", operation);
		p.addValue("projNo", nullIfEmpty(payload.get("projNo")));
		p.addValue("sujuId", toInt(payload.get("sujuId")));
		p.addValue("kind", nullIfEmpty(payload.get("kind")));
		p.addValue("worker", str(payload.get("worker")));
		p.addValue("userId", user.getId());

		// 같은 설비 × 프로젝트 × 품목이면 갱신 (중복 작업중 방지)
		sqlRunner.execute("""
            INSERT INTO iljin_prod_working (
                 spjangcd, "Equipment", "Operation", "Project_id", "Suju_id"
               , "Kind", "Worker", "StartTime", "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, :equipment, :operation, :projNo, :sujuId
               , :kind, :worker, now(), now(), :userId
            )
            ON CONFLICT ("Equipment", "Project_id", "Suju_id")
            DO UPDATE SET "Kind" = EXCLUDED."Kind"
                        , "Worker" = EXCLUDED."Worker"
                        , "Operation" = EXCLUDED."Operation"
            """, p);
	}

	@Transactional
	public void endWorking(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		sqlRunner.execute("DELETE FROM iljin_prod_working WHERE id = :id", p);
	}

	/** 설비 코드로 가공공정 결정 (실적의 공정은 화면이 아니라 설비가 정한다) */
	public String operationOf(String spjangcd, String equipment) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("code", equipment);

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT wc."Name" AS operation
            FROM equ e
            JOIN work_center wc ON wc.id = e."WorkCenter_id"
            WHERE e.spjangcd = :spjangcd AND e."Code" = :code
            """, p);

		return row == null ? "" : str(row.get("operation"));
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

	private String defaultIfEmpty(Object o, String def) {
		String s = str(o);
		return s.isEmpty() ? def : s;
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