package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 생산 지시(설계)
 *
 * [용어]
 *  - 품목(item)          : suju 1행. S10, RS01 등 지그 세트. 작업지시 단위.
 *  - 부품(part)          : 품목 아래 BOM 행. JOINT PLATE, LOC BLOCK 등.
 *  - 유형(kind)          : plate / block / shim / etc. 가공품의 종류이자 실적 집계 단위.
 *
 * [설계 메모]
 *  - 가공공정(레이저절단/선반밀링/와이어커팅)은 여기서 관리하지 않는다.
 *    라우팅(공정 경로) 개념 없음 — 키오스크에서 설비를 고르면 공정이 자동 결정되고,
 *    어떤 유형이든 어떤 공정에서든 실적이 찍힌다. 공정은 실적에만 존재하고 목표가 없다.
 *  - 이 화면이 만드는 유형별 소요량은 가공 키오스크에 그대로 공급된다.
 *    (품목 선택 시 유형 타일 + "소요 N" 표시)
 *  - JPA 엔티티/레포지토리 사용 안 함. 전부 SqlRunner(JDBC) 직접 쿼리.
 *    dirty checking 이 없으므로 INSERT/UPDATE 시 컬럼을 전부 나열한다.
 *    컬럼 추가 시 양쪽을 같이 고칠 것.
 *  - 재고/귀속/파기 개념 없음. 이 화면은 "필요량"까지만 책임진다.
 *  - 2D 출도 여부는 수주관리(dwg_drawing)가 단독 소유.
 */
@Service
@RequiredArgsConstructor
public class ProdDesignService {

	private final SqlRunner sqlRunner;

	// =================================================================
	// 프로젝트 콤보 (미완료 프로젝트만)
	// =================================================================
	public List<Map<String, Object>> getProjectList(String spjangcd) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);

		String sql = """
            SELECT d.projno      AS proj_no
                 , d.projnm      AS proj_name
                 , d.balcltnm    AS order_company
                 , d.stdate      AS start_date
                 , d.eddate      AS end_date
            FROM tb_da003 d
            WHERE d.spjangcd = :spjangcd
              AND COALESCE(d.endflag, '0') <> '1'
            ORDER BY d.eddate, d.projno
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// 품목(suju) 목록 - 화면 왼쪽 표
	//   부품수 / 필요량 / 작업지시 상태를 함께 내려준다.
	// =================================================================
	public List<Map<String, Object>> getItemList(String spjangcd, String projNo) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("projNo", projNo);

		String sql = """
            SELECT s.id                       AS suju_id
                 , s.project_id               AS proj_no
                 , s.line                     AS line_name
                 , s."Material_Name"          AS item_name      -- 품목명 (S10 등)
                 , s."Material_id"            AS material_id
                 , m."Code"                   AS item_code
                 , s.equip_type               AS equip_type
                 , s."SujuQty"                AS unit_qty       -- 유니트 수
                 , s."Standard"               AS set_cnt
                 , s.make_type                AS make_type      -- 내작/외작
                 , s.make_comp_name           AS make_comp
                 , s.spjangcd                 AS spjangcd
                 , TO_CHAR(s.draw_date, 'YYYY-MM-DD') AS draw_date
                 , COALESCE(b.part_cnt, 0)    AS part_cnt       -- 등록된 부품 수
                 , COALESCE(b.need_qty, 0)    AS need_qty       -- 필요량 합계
                 , CASE WHEN j.cnt > 0 THEN 'Y' ELSE 'N' END AS ordered_yn
                 , j.work_order_number        AS work_order_number
            FROM suju s
            LEFT JOIN material m ON m.id = s."Material_id"
            LEFT JOIN (
                SELECT "Suju_id"
                     , COUNT(*)   AS part_cnt
                     , SUM("Qty") AS need_qty
                FROM iljin_suju_bom
                GROUP BY "Suju_id"
            ) b ON b."Suju_id" = s.id
            LEFT JOIN (
                SELECT "SourceDataPk"
                     , COUNT(*)               AS cnt
                     , MIN("WorkOrderNumber") AS work_order_number
                FROM job_res
                WHERE "SourceTableName" = 'suju'
                GROUP BY "SourceDataPk"
            ) j ON j."SourceDataPk" = s.id
            WHERE s.spjangcd = :spjangcd
              AND s.project_id = :projNo
            ORDER BY s.line, s.id
            """;

		return sqlRunner.getRows(sql, params);
	}

	/** 품목 단건 (작업지시 생성 시 필요) */
	public Map<String, Object> getItem(Integer sujuId) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sujuId", sujuId);

		String sql = """
            SELECT s.id
                 , s."Material_id"   AS material_id
                 , s."Material_Name" AS item_name
                 , s."SujuQty"       AS unit_qty
                 , s.project_id      AS proj_no
                 , s.spjangcd        AS spjangcd
            FROM suju s
            WHERE s.id = :sujuId
            """;

		return sqlRunner.getRow(sql, params);
	}

	// =================================================================
	// 부품(BOM) 목록 - 화면 오른쪽 표
	// =================================================================
	public List<Map<String, Object>> getPartList(Integer sujuId) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sujuId", sujuId);

		String sql = """
            SELECT b.id
                 , b."Suju_id"       AS suju_id
                 , b."PartNo"        AS part_no
                 , b."PartName"      AS part_name
                 , b."Gubun"         AS gubun
                 , b."Material_id"   AS material_id
                 , m."Code"          AS material_code
                 , m."Name"          AS material_name
                 , b."Kind"          AS kind
                 , b."Qty"           AS qty
                 , b."State"         AS state
                 , b."AttachFile_id" AS attach_file_id
                 , b."Remark"        AS remark
                 , b."_order"        AS sort_order
            FROM iljin_suju_bom b
            LEFT JOIN material m ON m.id = b."Material_id"
            WHERE b."Suju_id" = :sujuId
            ORDER BY b."_order", b.id
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// 부품 저장 (INSERT / UPDATE)
	// =================================================================
	@Transactional
	public void savePart(Map<String, Object> item, String spjangcd, Integer sujuId,
						 Integer order, String kind, Integer materialId, User user) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("sujuId", sujuId);
		p.addValue("partNo", str(item.get("partNo")));
		p.addValue("partName", str(item.get("partName")));
		p.addValue("gubun", str(item.get("gubun")));
		p.addValue("materialId", materialId);
		p.addValue("kind", kind);
		p.addValue("qty", toDouble(item.get("qty")));
		p.addValue("state", str(item.get("state")));
		p.addValue("remark", str(item.get("remark")));
		p.addValue("order", order);
		p.addValue("userId", user.getId());

		Integer id = toInt(item.get("id"));

		if (id == null) {
			sqlRunner.execute("""
                INSERT INTO iljin_suju_bom (
                     spjangcd, "Suju_id", "PartNo", "PartName", "Gubun"
                   , "Material_id", "Kind", "Qty", "State"
                   , "Remark", "_order", "_created", "_creater_id"
                ) VALUES (
                     :spjangcd, :sujuId, :partNo, :partName, :gubun
                   , :materialId, :kind, :qty, :state
                   , :remark, :order, now(), :userId
                )
                """, p);

		} else {
			p.addValue("id", id);
			sqlRunner.execute("""
                UPDATE iljin_suju_bom
                SET "PartNo"       = :partNo
                  , "PartName"     = :partName
                  , "Gubun"        = :gubun
                  , "Material_id"  = :materialId
                  , "Kind"         = :kind
                  , "Qty"          = :qty
                  , "State"        = :state
                  , "Remark"       = :remark
                  , "_order"       = :order
                  , "_modified"    = now()
                  , "_modifier_id" = :userId
                WHERE id = :id
                """, p);
		}
	}

	@Transactional
	public void deletePart(Integer id) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		sqlRunner.execute("DELETE FROM iljin_suju_bom WHERE id = :id", p);
	}

	// =================================================================
	// 유형(분류) 마스터 + 별칭
	// =================================================================
	public List<Map<String, Object>> getKindList(String spjangcd) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);

		String sql = """
            SELECT k.id
                 , k."Canon" AS canon
                 , k."Sort"  AS sort
                 , (SELECT STRING_AGG(a."Alias", ', ' ORDER BY a.id)
                    FROM iljin_part_kind_alias a WHERE a."Kind_id" = k.id) AS aliases
            FROM iljin_part_kind k
            WHERE k.spjangcd = :spjangcd
              AND k."UseYn" = 'Y'
            ORDER BY k."Sort", k."Canon"
            """;

		return sqlRunner.getRows(sql, params);
	}

	/** 유형 + 별칭 전체 교체 저장 (별칭은 FK CASCADE 로 함께 삭제) */
	@Transactional
	public void saveKinds(String spjangcd, List<Map<String, Object>> kinds) {

		MapSqlParameterSource del = new MapSqlParameterSource();
		del.addValue("spjangcd", spjangcd);
		sqlRunner.execute("DELETE FROM iljin_part_kind WHERE spjangcd = :spjangcd", del);

		int sort = 0;
		for (Map<String, Object> k : kinds) {
			String canon = str(k.get("canon"));
			if (canon.isEmpty()) continue;

			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("spjangcd", spjangcd);
			p.addValue("canon", canon);
			p.addValue("sort", sort += 10);

			// RETURNING 으로 신규 id 회수 (프로젝트 기존 패턴)
			Integer kindId = sqlRunner.queryForObject("""
                INSERT INTO iljin_part_kind (spjangcd, "Canon", "Sort", "UseYn")
                VALUES (:spjangcd, :canon, :sort, 'Y')
                RETURNING id
                """, p, (rs, rowNum) -> rs.getInt("id"));

			for (String alias : str(k.get("aliases")).split(",")) {
				String a = alias.trim();
				if (a.isEmpty()) continue;

				MapSqlParameterSource ap = new MapSqlParameterSource();
				ap.addValue("kindId", kindId);
				ap.addValue("alias", a);
				sqlRunner.execute("""
                    INSERT INTO iljin_part_kind_alias ("Kind_id", "Alias")
                    VALUES (:kindId, :alias)
                    """, ap);
			}

		}
	}

	/**
	 * 부품명으로 유형 추정.
	 * Sort 오름차순으로 먼저 매칭되는 것을 채택한다.
	 * (PIN BRKT -> plate, PIN BLOCK -> block 처럼 구체적인 것을 앞에 두어 충돌 해결)
	 */
	public String guessKind(String spjangcd, String partName) {
		if (partName == null || partName.isBlank()) return "etc";

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("name", partName.toUpperCase());

		String sql = """
            SELECT k."Canon" AS canon
            FROM iljin_part_kind k
            JOIN iljin_part_kind_alias a ON a."Kind_id" = k.id
            WHERE k.spjangcd = :spjangcd
              AND k."UseYn" = 'Y'
              AND POSITION(UPPER(a."Alias") IN :name) > 0
            ORDER BY k."Sort"
            LIMIT 1
            """;

		Map<String, Object> row = sqlRunner.getRow(sql, params);
		return row == null ? "etc" : (String) row.get("canon");
	}

	// =================================================================
	// 작업지시 (job_res)
	//   SourceTableName='suju', SourceDataPk=suju.id 규약은
	//   기존 DashboardProjectService 가 이미 사용 중이므로 그대로 따른다.
	// =================================================================

	/** 이미 지시된 품목인지 */
	public int countOrder(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		return sqlRunner.queryForCount("""
            SELECT COUNT(*) FROM job_res
            WHERE "SourceTableName" = 'suju' AND "SourceDataPk" = :sujuId
            """, p);
	}

	/** 실적이 이미 발생했는지 (지시 취소 가능 여부 판정) */
	public int countProduced(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		return sqlRunner.queryForCount("""
            SELECT COUNT(*) FROM job_res
            WHERE "SourceTableName" = 'suju' AND "SourceDataPk" = :sujuId
              AND COALESCE("GoodQty", 0) > 0
            """, p);
	}

	@Transactional
	public String createOrder(Map<String, Object> item, User user) {

		String workOrderNumber = generateWorkOrderNumber();

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("workOrderNumber", workOrderNumber);
		p.addValue("sujuId", toInt(item.get("id")));
		p.addValue("materialId", toInt(item.get("material_id")));
		p.addValue("orderQty", toDouble(item.get("unit_qty")));
		p.addValue("spjangcd", str(item.get("spjangcd")));
		p.addValue("description", str(item.get("proj_no")) + " / " + str(item.get("item_name")));
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            INSERT INTO job_res (
                 "WorkOrderNumber", "SourceTableName", "SourceDataPk"
               , "Material_id", "OrderQty", "State"
               , "ProductionDate", "ProductionPlanDate", "Description", spjangcd
               , "GoodQty", "DefectQty"
               , "_created", "_creater_id"
            ) VALUES (
                 :workOrderNumber, 'suju', :sujuId
               , :materialId, :orderQty, 'wait'
               , now(), now(), :description, :spjangcd
               , 0, 0
               , now(), :userId
            )
            """, p);

		return workOrderNumber;
	}

	@Transactional
	public void cancelOrder(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		sqlRunner.execute("""
            DELETE FROM job_res
            WHERE "SourceTableName" = 'suju' AND "SourceDataPk" = :sujuId
            """, p);
	}

	private String generateWorkOrderNumber() {
		String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("prefix", "WO" + today + "%");

		Map<String, Object> row = sqlRunner.getRow("""
            SELECT COALESCE(MAX("WorkOrderNumber"), '') AS max_no
            FROM job_res
            WHERE "WorkOrderNumber" LIKE :prefix
            """, p);

		String maxNo = row == null ? "" : str(row.get("max_no"));
		int seq = 1;
		if (maxNo.length() >= 14) {
			try {
				seq = Integer.parseInt(maxNo.substring(10)) + 1;
			} catch (NumberFormatException ignore) { }
		}
		return String.format("WO%s%04d", today, seq);
	}

	// =================================================================
	// STD·구매품 품목 검색 (발주 연계용)
	//   제작품은 일회성이라 품목마스터에 넣지 않으므로 검색 대상이 아니다.
	// =================================================================
	/**
	 * 부품명 자동완성 (제작품용).
	 *
	 * 품목마스터가 아니라 <b>이미 입력된 부품 이름</b>에서 뽑는다.
	 * 제작품은 마스터에 등록하지 않으므로(4장) 마스터 검색이 걸릴 대상이 없고,
	 * 현장은 같은 이름을 프로젝트마다 반복 입력하므로 과거 입력이 곧 사전이 된다.
	 * 많이 쓴 이름일수록 위로.
	 */
	public List<Map<String, Object>> suggestPartName(String spjangcd, String keyword) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("keyword", "%" + (keyword == null ? "" : keyword.trim()) + "%");

		String sql = """
            SELECT b."PartName"  AS part_name
                 , b."Kind"      AS kind
                 , COUNT(*)      AS use_cnt
            FROM iljin_suju_bom b
            JOIN suju s ON s.id = b."Suju_id"
            WHERE s.spjangcd = :spjangcd
              AND b."Gubun" = '제작품'
              AND b."PartName" ILIKE :keyword
            GROUP BY b."PartName", b."Kind"
            ORDER BY COUNT(*) DESC, b."PartName"
            LIMIT 30
            """;

		return sqlRunner.getRows(sql, p);
	}

	public List<Map<String, Object>> searchMaterial(String spjangcd, String keyword) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("keyword", "%" + (keyword == null ? "" : keyword) + "%");

		String sql = """
            SELECT m.id
                 , m."Code"     AS code
                 , m."Name"     AS name
                 , m."Standard1" AS standard   -- material 은 Standard1/Standard2 로 나뉘어 있다
                 , u."Name"     AS unit_name
                 , g."Name"     AS group_name
            FROM material m
            LEFT JOIN unit u     ON u.id = m."Unit_id"
            LEFT JOIN mat_grp g  ON g.id = m."MaterialGroup_id"
            WHERE m.spjangcd = :spjangcd
              AND (m."Name" ILIKE :keyword OR m."Code" ILIKE :keyword)
            ORDER BY m."Name"
            LIMIT 50
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// [대시보드 연계] 유형별 필요량
	//   재고가 아니라 "필요량". 생산 실적의 누적 생산량과 나란히 놓고 본다.
	// =================================================================
	public List<Map<String, Object>> getKindSummary(String spjangcd, String projNo) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("projNo", projNo);

		String sql = """
            SELECT b."Kind"     AS kind
                 , SUM(b."Qty") AS need_qty
                 , COUNT(*)     AS part_cnt
            FROM iljin_suju_bom b
            JOIN suju s ON s.id = b."Suju_id"
            WHERE s.spjangcd = :spjangcd
              AND s.project_id = :projNo
              AND b."Gubun" = '제작품'
            GROUP BY b."Kind"
            ORDER BY SUM(b."Qty") DESC
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// [가공 키오스크 공급] 품목별 유형 소요량
	//
	//   키오스크에서 프로젝트+품목을 고르면 유형 타일을 그리고 "소요 N" 을 띄운다.
	//   지금 키오스크는 이 데이터를 JS 에 하드코딩(units[].need)하고 있는데,
	//   그 자리를 이 API 가 대체한다.
	//
	//   제작품만 대상. STD·구매품은 가공 대상이 아니라 발주 대상이다.
	// =================================================================
	public List<Map<String, Object>> getKindNeedByItem(Integer sujuId) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sujuId", sujuId);

		String sql = """
            SELECT b."Kind"     AS kind
                 , SUM(b."Qty") AS need_qty
                 , COUNT(*)     AS part_cnt
            FROM iljin_suju_bom b
            WHERE b."Suju_id" = :sujuId
              AND b."Gubun" = '제작품'
            GROUP BY b."Kind"
            ORDER BY SUM(b."Qty") DESC
            """;

		return sqlRunner.getRows(sql, params);
	}

	/**
	 * 프로젝트 전체의 유형별 소요량 (품목 미선택 시 키오스크가 쓰는 합계).
	 */
	public List<Map<String, Object>> getKindNeedByProject(String spjangcd, String projNo) {
		return getKindSummary(spjangcd, projNo);
	}

	// =================================================================
	// helper
	// =================================================================
	private String str(Object o) {
		return o == null ? "" : o.toString().trim();
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