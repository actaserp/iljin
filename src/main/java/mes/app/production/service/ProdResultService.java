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
	 * 수주 목록 (프로젝트 하위).
	 *
	 * ★ 프로젝트 하나에 수주가 여러 건이다. 실제로 2026-004 는 5건이다.
	 *   수주가 다르면 같은 품목명(S10)이 여러 번 나타나므로,
	 *   수주를 안 거치면 키오스크에서 어느 쪽을 고를지 알 수 없다.
	 *
	 * 계획(SujuType='plan')도 포함한다 —
	 * 도면이 나왔으면 확정 전이라도 현장이 작업을 시작한다.
	 */
	public List<Map<String, Object>> getSujuHeadList(String spjangcd, String projNo) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		String sql = """
            SELECT sh.id                       AS suju_head_id
                 -- ★ 화면에 보여줄 것은 <b>수주명</b>이다.
                 --   업체명은 프로젝트 안에서 대부분 같아 구분에 도움이 안 된다
                 --   (2026-004 는 5건 중 3건이 대우공업).
                 , COALESCE(sh.suju_name, '')   AS suju_name
                 , TO_CHAR(sh."JumunDate", 'YYYY-MM-DD') AS jumun_date
                 , COALESCE(c."Name", '')      AS company
                 , COALESCE(sh."SujuType", '') AS suju_type
                 , COUNT(s.id)                 AS item_cnt
            FROM suju_head sh
            JOIN suju s ON s."SujuHead_id" = sh.id
            LEFT JOIN company c ON c.id = sh."Company_id"
            WHERE sh.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
            GROUP BY sh.id, sh.suju_name, sh."JumunDate", c."Name", sh."SujuType"
            ORDER BY sh."JumunDate", sh.id
            """;

		return sqlRunner.getRows(sql, p);
	}

	/**
	 * 품목 목록.
	 * 작업 지시된 품목만 보여준다 — 지시 없이 실적이 찍히면 관리 밖의 작업이 된다.
	 * 부품(BOM)이 등록된 품목만 유형 타일을 그릴 수 있으므로 부품 수도 같이 준다.
	 */
	public List<Map<String, Object>> getItemList(String spjangcd, String projNo,
												 Integer sujuHeadId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		// ★ 프로젝트·수주 모두 선택 항목이다.
		//   둘 다 비면 전체가 나오고, 품목을 고르는 순간 서버가 역으로 채운다(headOf).
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("sujuHeadId", sujuHeadId);

		String sql = """
            SELECT s.id                    AS suju_id
                 , s.line                  AS line_name
                 , s."Material_Name"       AS item_name
                 , COALESCE(s.unit_qty, 0) AS unit_qty
                 , COALESCE(b.part_cnt, 0) AS part_cnt
                 -- 수주가 여럿이면 같은 품목명이 여러 번 나온다. 구분할 정보를 같이 준다
                 , s."SujuHead_id"         AS suju_head_id
                 , s.project_id            AS proj_no
                 , COALESCE(sh.suju_name, '') AS suju_name
                 , TO_CHAR(sh."JumunDate", 'MM-DD') AS jumun_date
                 , COALESCE(c."Name", '')  AS company
            FROM suju s
            JOIN job_res j
              ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            LEFT JOIN suju_head sh ON sh.id = s."SujuHead_id"
            LEFT JOIN company c    ON c.id = sh."Company_id"
            LEFT JOIN (
                SELECT "Suju_id", COUNT(*) AS part_cnt
                FROM iljin_suju_bom
                WHERE "Gubun" = '제작품'
                GROUP BY "Suju_id"
            ) b ON b."Suju_id" = s.id
            WHERE s.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL
                   OR s.project_id = CAST(:projNo AS varchar))
              AND (CAST(:sujuHeadId AS integer) IS NULL
                   OR s."SujuHead_id" = CAST(:sujuHeadId AS integer))
            GROUP BY s.id, s.line, s."Material_Name", s.unit_qty, b.part_cnt
                   , s."SujuHead_id", s.project_id, sh.suju_name, sh."JumunDate", c."Name"
            ORDER BY sh."JumunDate", s.line, s.id
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
												  Integer sujuId, String operation,
												  Integer sujuHeadId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		// 빈 문자열이 오면 "전체" 로 보고 NULL 로 정규화한다
		p.addValue("projNo", nullIfEmpty(projNo));
		p.addValue("sujuId", sujuId);
		p.addValue("operation", nullIfEmpty(operation));
		// 수주까지만 고른 상태에서도 유형 타일이 나와야 한다 (품목은 선택 항목)
		p.addValue("sujuHeadId", sujuHeadId);

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
                -- 프로젝트 공통 부품(Suju_id IS NULL)도 필요량에 포함한다
                LEFT JOIN suju s ON s.id = b."Suju_id"
                WHERE b.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL
                       OR COALESCE(s.project_id, b."Project_id") = CAST(:projNo AS varchar))
                  AND (CAST(:sujuId AS integer) IS NULL OR b."Suju_id" = CAST(:sujuId AS integer))
                  AND (CAST(:sujuHeadId AS integer) IS NULL
                       OR s."SujuHead_id" = CAST(:sujuHeadId AS integer))
                  AND b."Gubun" = '제작품'
                GROUP BY b."Kind"
            ), done AS (
                SELECT r."Kind" AS kind, SUM(r."GoodQty") AS done_qty
                FROM iljin_prod_result r
                WHERE r.spjangcd = :spjangcd
                  AND (CAST(:projNo AS varchar) IS NULL OR r."Project_id" = CAST(:projNo AS varchar))
                  AND (CAST(:sujuId AS integer) IS NULL OR r."Suju_id" = CAST(:sujuId AS integer))
                  AND (CAST(:sujuHeadId AS integer) IS NULL
                       OR r."SujuHead_id" = CAST(:sujuHeadId AS integer))
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
	/**
	 * 품목(suju.id) 하나로 상위 계층을 역으로 확정한다.
	 *
	 * ★ 계층이 프로젝트 &gt; 수주 &gt; 공정(품목) 이므로 <b>아래를 고르면 위가 정해진다.</b>
	 *   suju 한 행이 project_id 와 "SujuHead_id" 를 모두 갖고 있어 조회 한 번이면 끝이다.
	 *
	 *   그래서 키오스크에서 프로젝트·수주를 <b>건너뛰어도 된다.</b>
	 *   그 둘은 목록을 좁히는 필터일 뿐이고, 품목만 고르면 서버가 채운다.
	 *   화면이 보내온 projNo 를 믿지 않는 이유도 같다 —
	 *   목록을 좁힌 뒤 다른 프로젝트로 바꾸면 화면 값과 품목이 어긋난다.
	 *
	 *   품목을 안 고른 경우(품목 미지정)에는 화면이 보낸 projNo 를 쓴다.
	 *   그때는 수주를 알 수 없으므로 "SujuHead_id" 가 비고, 그게 사실이다.
	 */
	private Map<String, Object> headOf(Integer sujuId) {
		if (sujuId == null) return null;
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		return sqlRunner.getRow("""
            SELECT s.project_id AS proj_no, s."SujuHead_id" AS suju_head_id
            FROM suju s WHERE s.id = :sujuId
            """, p);
	}

	/** 실적/작업중 저장 전에 프로젝트·수주를 확정해 파라미터에 넣는다 */
	private void bindOwner(MapSqlParameterSource p, Map<String, Object> payload) {
		Integer sujuId = toInt(payload.get("sujuId"));
		Map<String, Object> h = headOf(sujuId);

		p.addValue("sujuId", sujuId);
		p.addValue("projNo", h != null ? str(h.get("proj_no"))
				: nullIfEmpty(payload.get("projNo")));
		p.addValue("sujuHeadId", h != null ? toInt(h.get("suju_head_id")) : null);
	}

	@Transactional
	public void saveResult(Map<String, Object> payload, String operation, User user) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", str(payload.get("spjangcd")));
		p.addValue("prodDate", str(payload.get("prodDate")));
		bindOwner(p, payload);
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
                 spjangcd, "ProdDate", "Project_id", "SujuHead_id", "Suju_id", "Kind"
               , "Operation", "Equipment", "Worker", "Worker_id", "GoodQty"
               , "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, CAST(:prodDate AS date), :projNo, :sujuHeadId, :sujuId, :kind
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
		bindOwner(p, payload);   // 품목으로 프로젝트·수주를 역으로 확정
		p.addValue("kind", nullIfEmpty(payload.get("kind")));
		p.addValue("worker", str(payload.get("worker")));
		// 이름만 남기면 동명이인·개명 시 추적이 끊긴다. 종료 시 실적으로 그대로 옮긴다
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("userId", user.getId());

		// 같은 설비 × 프로젝트 × 품목이면 갱신 (중복 작업중 방지)
		sqlRunner.execute("""
            INSERT INTO iljin_prod_working (
                 spjangcd, "Equipment", "Operation", "Project_id", "SujuHead_id", "Suju_id"
               , "Kind", "Worker", "Worker_id", "StartTime", "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, :equipment, :operation, :projNo, :sujuHeadId, :sujuId
               , :kind, :worker, :workerId, now(), now(), :userId
            )
            ON CONFLICT ("Equipment", "Project_id", "Suju_id")
            DO UPDATE SET "Kind" = EXCLUDED."Kind"
                        , "Worker" = EXCLUDED."Worker"
                        , "Operation" = EXCLUDED."Operation"
                        , "SujuHead_id" = EXCLUDED."SujuHead_id"
                        , "Worker_id" = EXCLUDED."Worker_id"
            """, p);
	}

	@Transactional
	/** 작업중 1건 (종료 시 실적으로 옮기기 위해 선언 내용을 읽는다) */
	public Map<String, Object> getWorking(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		return sqlRunner.getRow("""
            SELECT w.id
                 , w.spjangcd
                 , w."Equipment"    AS equipment
                 , w."Operation"    AS operation
                 , w."Project_id"   AS proj_no
                 , w."SujuHead_id"  AS suju_head_id
                 , w."Suju_id"      AS suju_id
                 , w."Kind"         AS kind
                 , w."Worker"       AS worker
                 , w."Worker_id"    AS worker_id
                 , w."StartTime"    AS start_time
            FROM iljin_prod_working w WHERE w.id = :id
            """, p);
	}

	/**
	 * 작업 종료.
	 *
	 * ★ 수량을 주면 <b>실적을 만들고</b> 종료한다. 0 이거나 없으면 그냥 종료한다.
	 *
	 *   예전에는 무조건 DELETE 였다. 그러면 시작~종료 사이에 실제로 만든 것이
	 *   아무 기록도 남기지 않고 사라진다. 작업자는 종료를 눌렀으니 입력했다고
	 *   생각하는데 실적은 0 인 상태가 된다.
	 *
	 *   실적은 <b>작업중 행의 선언</b>을 그대로 쓴다 (설비·공정·프로젝트·수주·품목·유형).
	 *   화면이 다시 보내온 값을 쓰지 않는 이유는, 시작한 뒤 화면에서 프로젝트를
	 *   바꿔 놓았을 수 있기 때문이다. 만든 물건은 시작 시점의 선언에 속한다.
	 *
	 *   나중에 PLC 가 붙으면 이 자리에 누적 카운트가 들어온다.
	 *   그때도 작업자가 수량을 고칠 수 있어야 하므로(초물·재작업) 인자는 그대로 둔다.
	 */
	@Transactional
	public void endWorking(Integer id, Double goodQty, User user) {

		Map<String, Object> w = getWorking(id);

		if (w != null && goodQty != null && goodQty > 0) {
			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("spjangcd", str(w.get("spjangcd")));
			p.addValue("projNo", nullIfEmpty(w.get("proj_no")));
			p.addValue("sujuHeadId", toInt(w.get("suju_head_id")));
			p.addValue("sujuId", toInt(w.get("suju_id")));
			p.addValue("kind", defaultIfEmpty(w.get("kind"), "etc"));
			p.addValue("operation", str(w.get("operation")));
			p.addValue("equipment", str(w.get("equipment")));
			p.addValue("worker", str(w.get("worker")));
			p.addValue("workerId", toInt(w.get("worker_id")));
			p.addValue("goodQty", goodQty);
			p.addValue("userId", user == null ? null : user.getId());

			sqlRunner.execute("""
                INSERT INTO iljin_prod_result (
                     spjangcd, "ProdDate", "Project_id", "SujuHead_id", "Suju_id", "Kind"
                   , "Operation", "Equipment", "Worker", "Worker_id", "GoodQty"
                   , "_created", "_creater_id"
                ) VALUES (
                     :spjangcd, CURRENT_DATE, :projNo, :sujuHeadId, :sujuId, :kind
                   , :operation, :equipment, :worker, :workerId, :goodQty
                   , now(), :userId
                )
                """, p);
		}

		MapSqlParameterSource d = new MapSqlParameterSource();
		d.addValue("id", id);
		sqlRunner.execute("DELETE FROM iljin_prod_working WHERE id = :id", d);
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