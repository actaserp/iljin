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
 * 조립 공정
 *
 * [단위]
 *  조립은 <b>유닛(품목) 축</b>이다. 가공품 개수가 아니다.
 *  조립 완료 = 완제품 1개 생성. 이 누적이 곧 완제품 재고다.
 *
 * [설계 메모]
 *  - 가공품 축에는 재고를 만들지 않는다 (파기를 기록하지 않으므로 거짓이 된다).
 *    재고는 유닛 축에만 존재하고, 그 유일한 생성 지점이 이 화면이다.
 *  - 재공(WIP) = 지시 유니트수 - 조립완료 누적. 차감이 아니라 두 이벤트의 차이라 항상 참이다.
 *  - 조립에는 설비가 없다. 작업중 상태의 키는 품목(suju)이다.
 *  - 완료 시 수량을 입력받는다 (한 번에 여러 개 조립하는 경우가 있다).
 */
@Service
@RequiredArgsConstructor
public class ProdAssemblyService {

	private final SqlRunner sqlRunner;

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
            ORDER BY d.projno DESC
            """, p);
	}

	/**
	 * 조립 작업자.
	 * 조립 워크센터(w06) 인원을 위로 올리되 막지는 않는다 (교대·대체 인력).
	 */
	public List<Map<String, Object>> getWorkerList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		return sqlRunner.getRows("""
            SELECT p.id
                 , p."Name"          AS name
                 , p."Code"          AS code
                 , p."WorkCenter_id" AS workcenter_id
                 , wc."Name"         AS workcenter_name
                 , CASE WHEN wc."Code" = 'w06' THEN 'Y' ELSE 'N' END AS own_area
            FROM person p
            LEFT JOIN work_center wc ON wc.id = p."WorkCenter_id"
            WHERE p.spjangcd = :spjangcd
              AND COALESCE(p.rtflag, '1') <> '2'
            ORDER BY CASE WHEN wc."Code" = 'w06' THEN 0 ELSE 1 END
                   , wc."Name", p."Name"
            """, p);
	}

	/**
	 * 조립 대상 품목.
	 *
	 *  unit_qty   지시된 유니트수 (suju."SujuQty")
	 *  done_qty   조립 완료 누적 = <b>완제품 재고</b>
	 *  wip_qty    재공 = 지시 - 완료. 차감이 아니라 두 이벤트의 차이
	 *  working_id 진행중이면 그 id
	 *
	 * 작업 지시된 품목만 노출한다 (지시 없는 조립은 관리 밖의 작업).
	 */
	public List<Map<String, Object>> getItemList(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return sqlRunner.getRows("""
            SELECT s.id                        AS suju_id
                 , s.project_id                AS proj_no
                 , s.line                      AS line_name
                 , s."Material_Name"           AS item_name
                 , COALESCE(s."SujuQty", 0)    AS unit_qty
                 , COALESCE(r.done_qty, 0)     AS done_qty
                 , COALESCE(s."SujuQty", 0) - COALESCE(r.done_qty, 0) AS wip_qty
                 , w.id                        AS working_id
                 , w."Worker"                  AS working_worker
                 , TO_CHAR(w."StartTime", 'HH24:MI') AS start_time
            FROM suju s
            JOIN job_res j
              ON j."SourceTableName" = 'suju' AND j."SourceDataPk" = s.id
            LEFT JOIN (
                SELECT "Suju_id", SUM("Qty") AS done_qty
                FROM iljin_assy_result
                GROUP BY "Suju_id"
            ) r ON r."Suju_id" = s.id
            LEFT JOIN iljin_assy_working w ON w."Suju_id" = s.id
            WHERE s.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL OR s.project_id = CAST(:projNo AS varchar))
            GROUP BY s.id, s.project_id, s.line, s."Material_Name", s."SujuQty"
                   , r.done_qty, w.id, w."Worker", w."StartTime"
            ORDER BY s.line, s.id
            """, p);
	}

	// =================================================================
	// 작업 시작 / 완료
	// =================================================================

	/** 진행중인 조립 목록 */
	public List<Map<String, Object>> getWorkingList(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projNo", nullIfEmpty(projNo));

		return sqlRunner.getRows("""
            SELECT w.id
                 , w."Project_id"    AS proj_no
                 , w."Suju_id"       AS suju_id
                 , s."Material_Name" AS item_name
                 , w."Worker"        AS worker
                 , TO_CHAR(w."StartTime", 'HH24:MI') AS start_time
            FROM iljin_assy_working w
            LEFT JOIN suju s ON s.id = w."Suju_id"
            WHERE w.spjangcd = :spjangcd
              AND (CAST(:projNo AS varchar) IS NULL
                   OR w."Project_id" = CAST(:projNo AS varchar))
            ORDER BY w."StartTime"
            """, p);
	}

	@Transactional
	public void startWorking(Map<String, Object> payload, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", str(payload.get("spjangcd")));
		p.addValue("projNo", nullIfEmpty(payload.get("projNo")));
		p.addValue("sujuId", toInt(payload.get("sujuId")));
		p.addValue("worker", str(payload.get("worker")));
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("userId", user.getId());

		// 같은 품목을 두 번 시작하면 작업자만 갱신한다
		sqlRunner.execute("""
            INSERT INTO iljin_assy_working (
                 spjangcd, "Project_id", "Suju_id", "Worker", "Worker_id"
               , "StartTime", "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, :projNo, :sujuId, :worker, :workerId
               , now(), now(), :userId
            )
            ON CONFLICT ("Suju_id")
            DO UPDATE SET "Worker" = EXCLUDED."Worker"
                        , "Worker_id" = EXCLUDED."Worker_id"
            """, p);
	}

	/** 진행중 조회 (완료 시 시작시각을 실적에 남기기 위해) */
	public Map<String, Object> getWorking(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		return sqlRunner.getRow("""
            SELECT id, "StartTime", "Worker", "Worker_id"
            FROM iljin_assy_working WHERE "Suju_id" = :sujuId
            """, p);
	}

	/**
	 * 조립 완료.
	 * 수량을 입력받는다 — 한 번에 여러 개를 조립하는 경우가 있다.
	 * 실적을 남기고 작업중에서 내린다.
	 */
	@Transactional
	public void complete(Map<String, Object> payload, User user) {

		Integer sujuId = toInt(payload.get("sujuId"));
		Map<String, Object> w = getWorking(sujuId);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", str(payload.get("spjangcd")));
		p.addValue("prodDate", str(payload.get("prodDate")));
		p.addValue("projNo", nullIfEmpty(payload.get("projNo")));
		p.addValue("sujuId", sujuId);
		p.addValue("qty", toDouble(payload.get("qty")));
		p.addValue("worker", str(payload.get("worker")));
		p.addValue("workerId", toInt(payload.get("workerId")));
		p.addValue("startTime", w == null ? null : w.get("StartTime"));
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            INSERT INTO iljin_assy_result (
                 spjangcd, "ProdDate", "Project_id", "Suju_id", "Qty"
               , "Worker", "Worker_id", "StartTime", "EndTime"
               , "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, CAST(:prodDate AS date), :projNo, :sujuId, :qty
               , :worker, :workerId, CAST(:startTime AS timestamptz), now()
               , now(), :userId
            )
            """, p);

		endWorking(sujuId);
	}

	/** 작업중 해제 (완료 또는 취소) */
	@Transactional
	public void endWorking(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		sqlRunner.execute("DELETE FROM iljin_assy_working WHERE \"Suju_id\" = :sujuId", p);
	}

	// =================================================================
	// 실적
	// =================================================================

	/**
	 * 조립 이력.
	 *
	 * 생산일은 <b>등록할 때 찍는 값</b>이지 조회 조건이 아니다.
	 * 조립은 여러 날에 걸쳐 나눠 완료되므로 날짜로 자르면
	 * "어제 완료한 것"이 화면에서 사라져 작업자가 중복 등록하게 된다.
	 * 날짜를 넘기지 않으면 프로젝트 기준 최근 100건을 그대로 보여준다.
	 */
	public List<Map<String, Object>> getResultLog(String spjangcd, String prodDate, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("prodDate", nullIfEmpty(prodDate));
		p.addValue("projNo", nullIfEmpty(projNo));

		return sqlRunner.getRows("""
            SELECT r.id
                 , r."Project_id"    AS proj_no
                 , r."Suju_id"       AS suju_id
                 , s."Material_Name" AS item_name
                 , r."Qty"           AS qty
                 , r."Worker"        AS worker
                 , TO_CHAR(r."ProdDate", 'YYYY-MM-DD')  AS prod_date
                 , TO_CHAR(r."StartTime", 'HH24:MI')    AS start_time
                 , TO_CHAR(r."EndTime", 'HH24:MI')      AS end_time
            FROM iljin_assy_result r
            LEFT JOIN suju s ON s.id = r."Suju_id"
            WHERE r.spjangcd = :spjangcd
              AND (CAST(:prodDate AS date) IS NULL
                   OR r."ProdDate" = CAST(:prodDate AS date))
              AND (CAST(:projNo AS varchar) IS NULL
                   OR r."Project_id" = CAST(:projNo AS varchar))
            ORDER BY r.id DESC
            LIMIT 100
            """, p);
	}

	@Transactional
	public void deleteResult(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		sqlRunner.execute("DELETE FROM iljin_assy_result WHERE id = :id", p);
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