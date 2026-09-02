package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 생산 지시(설계)
 *
 * [BOM 2단계]  수주 &gt; 공정(품목) &gt; 유닛 &gt; 자재유형(부품)
 *
 *  - 품목(item)  : suju 1행. S10, RS01 등. 작업지시 단위. 현장 용어로는 "공정"
 *  - 유닛(unit)  : 그 품목을 이루는 구성품. <b>suju.unit_qty</b> (유닛 총량)
 *  - 지그 대수    : suju."SujuQty". 검사 목표. 유닛 축과 <b>다른 축</b>이다
 *                  예) SujuQty 2 · unit_qty 20 → 지그 2대, 유닛 20개 (1대당 10개)
 *  - 부품(part)  : iljin_suju_bom 1행
 *  - 유형(kind)  : plate / block / shim / etc. 가공 실적의 집계 단위.
 *                  다리발은 가공 대상이 아니므로 유형에 두지 않는다 (구분으로 갈린다)
 *
 * ★ 부품 "Qty" 는 <b>공정(품목) 전체 총량</b>이다. 유닛 수를 곱하지 않는다.
 *   현장은 "이 지그에 브라켓 300개" 로 지시하지 "유닛 1개당 2개" 로 지시하지 않는다.
 *   필요량 = SUM("Qty") 이고, ProdResult / DashProject 도 같은 식을 쓴다.
 *   한쪽만 고치면 생산지시 화면과 키오스크가 서로 다른 필요량을 보여준다.
 *
 *   "QtyType" 은 다리발 시딩 행을 구분하는 표식으로만 남아 있고
 *   <b>필요량 계산에 관여하지 않는다.</b>
 *
 * [설계 메모]
 *  - <b>부품을 소모하는 공정은 없다.</b> 가공 실적은 유형 단위로 통으로만 쌓이고
 *    이 BOM 과 차감 관계가 없다. 여기는 "필요량"까지만 책임진다.
 *  - 가공공정(레이저절단/선반밀링/와이어커팅) 라우팅 없음. 부품에 공정을 지정하지 않는다.
 *  - JPA 엔티티/레포지토리 사용 안 함. 전부 SqlRunner(JDBC) 직접 쿼리.
 *    dirty checking 이 없으므로 INSERT/UPDATE 시 컬럼을 전부 나열한다.
 *  - 2D 출도 여부는 수주관리(dwg_drawing)가 단독 소유. 여기서 관리하지 않는다.
 *  - 수주는 수정 대상이 아니다. suju 는 <b>읽기만</b> 한다.
 */
@Service
@RequiredArgsConstructor
public class ProdDesignService {

	private final SqlRunner sqlRunner;

	/** 수량 축 — 유닛 1개당 (기본) */
	public static final String QTY_TYPE_UNIT = "unit";
	/** 수량 축 — 공정(품목)당 총량. 다리발 등 유닛에 종속되지 않는 부품 */
	public static final String QTY_TYPE_TOTAL = "total";

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
	//   부품수 / 필요량 / 다리발 / 작업지시 상태를 함께 내려준다.
	// =================================================================
	public List<Map<String, Object>> getItemList(String spjangcd, String projNo) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("projNo", projNo);

		/*
		 * ★ 외작(make_type='outsource')은 목록에서 뺀다.
		 *   업체가 만들어 오는 지그다. 우리가 부품을 등록하거나 가공을 지시할 대상이 아닌데
		 *   목록에 섞여 있으니 실수로 지시가 나갔다.
		 *
		 *   단 <b>이미 지시된 외작은 남긴다.</b> 숨겨 버리면 잘못 누른 지시를
		 *   취소할 방법이 사라진다. 화면에서 지우고 나면 그때부터 안 보인다.
		 *
		 *   검사는 이 목록과 무관하다. 검사 화면이 suju 를 직접 보므로
		 *   외작을 여기서 빼도 검사 대상에서 빠지지 않는다.
		 *   (외작의 검사 면제 여부는 발주 입고에서 등록된다)
		 */
		String sql = """
            SELECT s.id                       AS suju_id
                 , s.project_id               AS proj_no
                 , s.line                     AS line_name
                 , s."Material_Name"          AS item_name      -- 품목명 (S10 등)
                 , s."Material_id"            AS material_id
                 , m."Code"                   AS item_code
                 , s.equip_type               AS equip_type
                 -- ★ 유닛 수량은 suju.unit_qty 다. "SujuQty" 는 지그 대수라 축이 다르다.
                 , COALESCE(s.unit_qty, 0)    AS unit_qty      -- 유닛 총량
                 , COALESCE(s."SujuQty", 0)   AS jig_qty       -- 지그 대수
                 , s."Standard"               AS set_cnt
                 , s.make_type                AS make_type      -- 내작/외작
                 , s.make_comp_name           AS make_comp
                 , s.leg_spec                 AS leg_spec       -- 다리발 사양
                 , s.leg_cnt                  AS leg_cnt        -- 다리발 개수
                 , s.pin_shift_unit           AS pin_shift_unit
                 , s.item_remark              AS item_remark
                 , s.spjangcd                 AS spjangcd
                 , TO_CHAR(s.draw_date, 'YYYY-MM-DD') AS draw_date
                 , COALESCE(b.part_cnt, 0)    AS part_cnt       -- 등록된 부품 수
                 -- ★ 부품 수량은 <b>공정(품목) 전체 총량</b>이다. 유니트수를 곱하지 않는다.
                 --   현장이 부품표에 적는 것은 "이 지그에 브라켓 300개" 이지
                 --   "유닛 1개당 브라켓 2개" 가 아니다.
                 --   qty_per_unit / qty_total 은 화면 호환을 위해 남겨 두지만
                 --   need_qty 계산에는 더 이상 관여하지 않는다.
                 , COALESCE(b.qty_per_unit, 0) AS qty_per_unit
                 , COALESCE(b.qty_total, 0)    AS qty_total
                 , COALESCE(b.qty_sum, 0)      AS need_qty       -- 전체 필요량
                 , CASE WHEN j.cnt > 0 THEN 'Y' ELSE 'N' END AS ordered_yn
                 , j.work_order_number        AS work_order_number
                 -- 수주가 여럿이면 같은 품목명이 여러 번 나온다. 수주일·업체로 구분한다
                 , s."SujuHead_id"             AS suju_head_id
                 , COALESCE(sh.suju_name, '')  AS suju_name
                 , TO_CHAR(sh."JumunDate", 'MM-DD') AS jumun_date
                 , COALESCE(c."Name", '')      AS company
                 , COALESCE(sh."SujuType", '') AS suju_type
            FROM suju s
            LEFT JOIN suju_head sh ON sh.id = s."SujuHead_id"
            LEFT JOIN company c    ON c.id = sh."Company_id"
            LEFT JOIN material m ON m.id = s."Material_id"
            LEFT JOIN (
                SELECT "Suju_id"
                     , COUNT(*) AS part_cnt
                     , SUM("Qty") AS qty_sum
                     , SUM("Qty") FILTER (WHERE "QtyType" <> 'total') AS qty_per_unit
                     , SUM("Qty") FILTER (WHERE "QtyType" =  'total') AS qty_total
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
              AND (COALESCE(s.make_type, '') <> 'outsource'   -- 내작 · 미지정만
                   OR COALESCE(j.cnt, 0) > 0)                 -- 지시된 외작은 취소용으로 남김
            ORDER BY s.line, s.id
            """;

		return sqlRunner.getRows(sql, params);
	}

	/** 품목 단건 (작업지시 생성 / 다리발 보강 시 필요) */
	public Map<String, Object> getItem(Integer sujuId) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sujuId", sujuId);

		String sql = """
            SELECT s.id
                 , s."Material_id"   AS material_id
                 , s."Material_Name" AS item_name
                 , COALESCE(s.unit_qty, 0)  AS unit_qty   -- 구성 유닛 수 (참고)
                 , COALESCE(s."SujuQty", 0) AS jig_qty    -- 지그 대수. 작업지시 수량
                 , s.project_id      AS proj_no
                 , s.spjangcd        AS spjangcd
                 , s.leg_spec        AS leg_spec
                 , s.leg_cnt         AS leg_cnt
            FROM suju s
            WHERE s.id = :sujuId
            """;

		return sqlRunner.getRow(sql, params);
	}

	// =================================================================
	// 부품(BOM) 목록 - 화면 오른쪽 표
	//   qty        = 공정(품목) 전체 총량 (사용자가 입력하는 값)
	//   need_qty   = qty 그대로. 유닛수를 곱하지 않는다
	// =================================================================
	/**
	 * 부품 목록.
	 *
	 * ★ sujuId 가 없으면 <b>프로젝트 공통</b> 부품이다.
	 *   2D 도면이 나오기 전에는 "plate 400개 만들어 놔" 처럼
	 *   어느 품목 것인지 모르는 상태로 지시가 나간다 (SPEC 3-1).
	 *   그걸 아무 품목에나 붙이면 "A12 에 plate 400개" 라는 거짓이 남고,
	 *   나중에 2D 가 나와도 갈라낼 방법이 없다.
	 *
	 *   실적(iljin_prod_result)은 이미 "Suju_id" 를 비울 수 있는데
	 *   필요량만 못 비우고 있었다. 짝을 맞춘 것이다.
	 */
	public List<Map<String, Object>> getPartList(Integer sujuId, String spjangcd, String projNo) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sujuId", sujuId);
		params.addValue("spjangcd", spjangcd);
		params.addValue("projNo", nullIfEmpty(projNo));

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
                 , b."Qty"           AS qty            -- 공정(품목) 전체 총량
                 , COALESCE(s.unit_qty, 0)             AS unit_qty
                 , b."QtyType"       AS qty_type      -- unit / total
                 , b."Qty" AS need_qty
                 , b."State"         AS state
                 , b."AttachFile_id" AS attach_file_id
                 , b."Remark"        AS remark
                 , b."_order"        AS sort_order
            FROM iljin_suju_bom b
            LEFT JOIN suju s     ON s.id = b."Suju_id"
            LEFT JOIN material m ON m.id = b."Material_id"
            WHERE (CAST(:sujuId AS integer) IS NOT NULL
                   AND b."Suju_id" = CAST(:sujuId AS integer))
               OR (CAST(:sujuId AS integer) IS NULL
                   AND b."Suju_id" IS NULL
                   AND b.spjangcd = :spjangcd
                   AND b."Project_id" = CAST(:projNo AS varchar))
            ORDER BY b."_order", b.id
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// 부품 저장 (INSERT / UPDATE)
	//   qty 의 의미는 qtyType 이 정한다.
	//     'unit'  : 유닛 1개당 개수. 전체 필요량은 조회 시 유니트수를 곱한다
	//     'total' : 공정당 총량. 곱하지 않는다 (다리발 등)
	//   전체 필요량을 저장하지 않는 이유: 수주에서 유니트수가 바뀌면
	//   저장된 총량은 즉시 거짓이 된다.
	// =================================================================
	@Transactional
	public void savePart(Map<String, Object> item, String spjangcd, Integer sujuId,
						 String projNo, Integer order, String kind, Integer materialId, User user) {

		// 수량 축. 화면이 안 보내거나 이상한 값이면 기본값 'unit'
		String qtyType = QTY_TYPE_TOTAL.equals(str(item.get("qtyType")))
				? QTY_TYPE_TOTAL : QTY_TYPE_UNIT;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("sujuId", sujuId);
		p.addValue("partNo", str(item.get("partNo")));
		p.addValue("partName", str(item.get("partName")));
		p.addValue("gubun", str(item.get("gubun")));
		p.addValue("materialId", materialId);
		p.addValue("kind", kind);
		p.addValue("qty", toDouble(item.get("qty")));
		p.addValue("qtyType", qtyType);
		p.addValue("state", str(item.get("state")));
		p.addValue("remark", str(item.get("remark")));
		p.addValue("order", order);
		p.addValue("userId", user.getId());
		// sujuId 가 null 이면 프로젝트 공통 부품이다. 그때만 Project_id 가 쓰인다
		p.addValue("projNo", nullIfEmpty(projNo));

		Integer id = toInt(item.get("id"));

		if (id == null) {
			sqlRunner.execute("""
                INSERT INTO iljin_suju_bom (
                     spjangcd, "Suju_id", "Project_id", "PartNo", "PartName", "Gubun"
                   , "Material_id", "Kind", "Qty", "QtyType", "State"
                   , "Remark", "_order", "_created", "_creater_id"
                ) VALUES (
                     :spjangcd, :sujuId, :projNo, :partNo, :partName, :gubun
                   , :materialId, :kind, :qty, :qtyType, :state
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
                  , "QtyType"      = :qtyType
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
	// 다리발 시딩
	// =================================================================

	/**
	 * 다리발 부품 시딩(seed).
	 *
	 * 수주(공정별 수량집계 엑셀)에 다리발 사양/개수가 들어 있으면
	 * 부품표에 1행을 깔아 준다. 수주는 수정 대상이 아니므로
	 * <b>여기서는 suju 를 읽기만</b> 한다.
	 *
	 * ★ 이것은 <b>동기화가 아니라 1회 시딩</b>이다.
	 *   수주는 "다리발이 몇 개 붙는다"까지만 알고,
	 *   <b>사 올지 만들지는 모른다.</b> 그 결정은 생산지시에서 한다.
	 *   따라서 시딩 이후로는 부품표가 원본이며, 수주값이 바뀌어도 따라가지 않는다.
	 *   불일치는 숨기지 않고 품목 목록에 수주 원본(leg_spec/leg_cnt)을
	 *   나란히 표시해 눈에 보이게 한다.
	 *
	 * ★ 구분(Gubun)을 비워 둔다. 사용자가 제작품/구매품을 골라야 한다.
	 *   사 오는 다리발은 구매품이라 품목마스터 연결이 필요하고(발주 대상),
	 *   만드는 다리발은 제작품이라 연결하지 않는다.
	 *   유형(Kind)도 비운다 — 만드는 경우 실제 형상대로 분류되어야 하며,
	 *   'leg' 같은 별도 유형을 만들면 가공 유형 집계가 오염된다.
	 *
	 * ★ QtyType='total'. leg_cnt 는 <b>공정(품목)당 총량</b>이라
	 *   필요량 계산에서 유니트수를 곱하지 않는다.
	 *   엑셀 값을 그대로 저장한다 — 유닛당으로 나누면 분수가 생기고 원본을 잃는다.
	 *
	 * 호출 지점은 <b>작업지시 생성 직전</b>이다.
	 * 부품 목록 조회 시점에 넣으면 사용자가 지운 행이 새로고침마다 되살아난다.
	 * 이미 total 축 부품이 있으면 아무것도 하지 않는다 (사용자 수정본 보호).
	 *
	 * @return 생성했으면 true
	 */
	@Transactional
	public boolean seedLegPart(Integer sujuId, User user) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);

		Map<String, Object> s = sqlRunner.getRow("""
            SELECT s.spjangcd, s.leg_spec, s.leg_cnt
            FROM suju s WHERE s.id = :sujuId
            """, p);
		if (s == null) return false;

		String legSpec = str(s.get("leg_spec"));
		double legCnt = parseLegCnt(s.get("leg_cnt"));
		if (legSpec.isEmpty() && legCnt <= 0) return false;   // 다리발 없는 공정

		int exist = sqlRunner.queryForCount("""
            SELECT COUNT(*) FROM iljin_suju_bom
            WHERE "Suju_id" = :sujuId AND "QtyType" = 'total'
            """, p);
		if (exist > 0) return false;

		MapSqlParameterSource ip = new MapSqlParameterSource();
		ip.addValue("spjangcd", str(s.get("spjangcd")));
		ip.addValue("sujuId", sujuId);
		ip.addValue("partName", legSpec.isEmpty() ? "다리발" : legSpec);
		ip.addValue("qtyType", QTY_TYPE_TOTAL);
		ip.addValue("qty", legCnt <= 0 ? 1d : legCnt);
		ip.addValue("userId", user.getId());

		// Gubun / Kind 는 비운다. 사용자가 제작·구매를 정해야 저장이 완결된다.
		sqlRunner.execute("""
            INSERT INTO iljin_suju_bom (
                 spjangcd, "Suju_id", "PartNo", "PartName", "Gubun"
               , "Material_id", "Kind", "Qty", "QtyType", "State"
               , "Remark", "_order", "_created", "_creater_id"
            ) VALUES (
                 :spjangcd, :sujuId, '', :partName, ''
               , NULL, '', :qty, :qtyType, '추정'
               , '수주 다리발사양에서 생성 — 제작/구매 구분을 지정하세요'
               , 999, now(), :userId
            )
            """, ip);
		return true;
	}

	/**
	 * 다리발 개수 파싱.
	 * leg_cnt 는 varchar 라 "4", "4개", "4EA" 같은 값이 섞여 들어온다.
	 * 숫자만 뽑는다.
	 */
	private double parseLegCnt(Object o) {
		String v = str(o).replaceAll("[^0-9.]", "");
		if (v.isEmpty()) return 0d;
		try {
			return Double.parseDouble(v);
		} catch (NumberFormatException e) {
			return 0d;
		}
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

	/**
	 * 실적이 이미 발생했는지 (지시 취소 가능 여부 판정).
	 *
	 * job_res."GoodQty" 가 아니라 <b>mat_produce 행의 존재</b>로 판정한다.
	 * 조립 실적은 mat_produce 에 쌓이므로 job_res 만 보면
	 * 실적이 있는데도 지시가 취소되어 실적이 고아가 된다.
	 * 진행중(State='working') 행도 실적으로 친다 — 작업자가 붙어 있는데
	 * 지시가 사라지면 키오스크가 그 작업을 잃는다.
	 *
	 * 가공 실적은 품목이 선택 항목이라 품목 없이 프로젝트에만 붙을 수 있으므로
	 * 취소 판정에 쓰지 않는다 (품목에 귀속된 것만 판정 대상).
	 */
	public int countProduced(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);
		return sqlRunner.queryForCount("""
            SELECT COUNT(*)
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            WHERE j."SourceDataPk" = :sujuId
            """, p);
	}

	@Transactional
	public String createOrder(Map<String, Object> item, User user) {

		String workOrderNumber = generateWorkOrderNumber();

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("workOrderNumber", workOrderNumber);
		p.addValue("sujuId", toInt(item.get("id")));
		p.addValue("materialId", toInt(item.get("material_id")));
		// ★ 작업지시 수량은 <b>지그 대수</b>다.
		//   조립·검사가 그 축으로 세므로 job_res 도 같은 축이어야 진척률이 맞는다.
		//   유닛수(unit_qty)는 그 지그가 몇 유닛으로 이루어지는지일 뿐 목표가 아니다.
		p.addValue("orderQty", toDouble(item.get("jig_qty")));
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
	 * 제작품은 마스터에 등록하지 않으므로 마스터 검색이 걸릴 대상이 없고,
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

	/**
	 * 부품명 목록 → 품목마스터 일괄 매칭.
	 *
	 * ★ 현장 작업자는 품목코드를 모른다. 엑셀에는 부품명만 들어온다.
	 *   그래서 일단 전부 밀어넣고, <b>붙지 않은 것만</b> 골라 화면에서 고르게 한다.
	 *
	 * 매칭 순서 — 위에서 맞으면 아래는 보지 않는다.
	 *   1) <b>과거 이력</b>. 같은 부품명으로 이미 연결한 적이 있으면 그 품목.
	 *      한 번 골라 두면 다음 프로젝트부터는 손이 안 간다.
	 *   2) 품목코드 완전일치 (엑셀에 코드를 넣은 경우)
	 *   3) 품목명 완전일치가 <b>정확히 1건</b>
	 *   4) 그 외 → 미매칭. 후보를 함께 돌려주므로 화면이 바로 고를 수 있다.
	 *
	 * ★ 여러 건일 때 임의로 첫 건을 잡지 않는다.
	 *   STD 와 구매품에 같은 이름이 흔한데, 엉뚱한 품목이 그대로 발주로
	 *   넘어가는 것이 미연결로 남는 것보다 나쁘다.
	 *
	 * 대소문자·앞뒤 공백은 무시한다. 엑셀에서 흔히 섞여 들어온다.
	 */
	public List<Map<String, Object>> matchMaterials(String spjangcd, List<String> names) {

		List<Map<String, Object>> out = new ArrayList<>();
		if (names == null || names.isEmpty()) return out;

		// 정규화 키 → 원본 이름 (응답에는 화면이 보낸 원본을 그대로 돌려준다)
		LinkedHashMap<String, String> keys = new LinkedHashMap<>();
		for (String n : names) {
			String k = norm(n);
			if (!k.isEmpty()) keys.putIfAbsent(k, n);
		}
		if (keys.isEmpty()) return out;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("names", new ArrayList<>(keys.keySet()));

		// 후보: 이름 또는 코드가 일치하는 품목 전부
		List<Map<String, Object>> cand = sqlRunner.getRows("""
            SELECT m.id
                 , m."Code"      AS code
                 , m."Name"      AS name
                 , m."Standard1" AS standard
                 , g."Name"      AS group_name
                 , UPPER(BTRIM(m."Name")) AS key_name
                 , UPPER(BTRIM(m."Code")) AS key_code
            FROM material m
            LEFT JOIN mat_grp g ON g.id = m."MaterialGroup_id"
            WHERE m.spjangcd = :spjangcd
              AND (UPPER(BTRIM(m."Name")) IN (:names)
                   OR UPPER(BTRIM(m."Code")) IN (:names))
            ORDER BY m."Name", m."Code"
            """, p);
		if (cand == null) cand = new ArrayList<>();

		// 과거 이력: 같은 부품명으로 마지막에 연결한 품목
		List<Map<String, Object>> hist = sqlRunner.getRows("""
            SELECT DISTINCT ON (UPPER(BTRIM(b."PartName")))
                   UPPER(BTRIM(b."PartName")) AS key_name
                 , m.id
                 , m."Code"      AS code
                 , m."Name"      AS name
                 , m."Standard1" AS standard
                 , g."Name"      AS group_name
            FROM iljin_suju_bom b
            JOIN material m     ON m.id = b."Material_id"
            LEFT JOIN mat_grp g ON g.id = m."MaterialGroup_id"
            WHERE b.spjangcd = :spjangcd
              AND b."Material_id" IS NOT NULL
              AND UPPER(BTRIM(b."PartName")) IN (:names)
            ORDER BY UPPER(BTRIM(b."PartName")), b.id DESC
            """, p);
		if (hist == null) hist = new ArrayList<>();

		Map<String, Map<String, Object>> histByKey = new HashMap<>();
		for (Map<String, Object> h : hist) histByKey.put(str(h.get("key_name")), h);

		for (Map.Entry<String, String> e : keys.entrySet()) {
			String key = e.getKey();

			List<Map<String, Object>> byName = new ArrayList<>();
			List<Map<String, Object>> byCode = new ArrayList<>();
			for (Map<String, Object> c : cand) {
				if (key.equals(str(c.get("key_name")))) byName.add(c);
				else if (key.equals(str(c.get("key_code")))) byCode.add(c);
			}

			Map<String, Object> row = new LinkedHashMap<>();
			row.put("partName", e.getValue());

			Map<String, Object> hit = null;
			String source = null;

			if (histByKey.containsKey(key)) {
				hit = histByKey.get(key);
				source = "history";
			} else if (byCode.size() == 1) {
				hit = byCode.get(0);
				source = "code";
			} else if (byName.size() == 1) {
				hit = byName.get(0);
				source = "name";
			}

			if (hit != null) {
				row.put("matched", true);
				row.put("source", source);
				row.put("materialId", hit.get("id"));
				row.put("code", hit.get("code"));
				row.put("name", hit.get("name"));
			} else {
				row.put("matched", false);
				List<Map<String, Object>> cs = byName.isEmpty() ? byCode : byName;
				List<Map<String, Object>> slim = new ArrayList<>();
				for (Map<String, Object> c : cs) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", c.get("id"));
					m.put("code", c.get("code"));
					m.put("name", c.get("name"));
					m.put("standard", c.get("standard"));
					m.put("group_name", c.get("group_name"));
					slim.add(m);
				}
				row.put("candidates", slim);
			}
			out.add(row);
		}
		return out;
	}

	/** 매칭 키. 대소문자·앞뒤 공백 무시 */
	private String norm(Object o) {
		return str(o).toUpperCase();
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
	//   ★ 유닛 1개당 수량 × 유니트수 (2단계 BOM)
	// =================================================================
	public List<Map<String, Object>> getKindSummary(String spjangcd, String projNo) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("projNo", projNo);

		String sql = """
            SELECT b."Kind"     AS kind
                 , SUM(b."Qty") AS need_qty
                 , SUM(b."Qty") AS qty_per_unit
                 , COUNT(*)     AS part_cnt
            FROM iljin_suju_bom b
            -- ★ 프로젝트 공통 부품(Suju_id IS NULL)도 함께 센다.
            --   2D 전 추정 물량이라 품목에 안 붙지만 필요량에는 들어가야 한다.
            LEFT JOIN suju s ON s.id = b."Suju_id"
            WHERE b.spjangcd = :spjangcd
              AND COALESCE(s.project_id, b."Project_id") = :projNo
              AND b."Gubun" = '제작품'
            GROUP BY b."Kind"
            ORDER BY 2 DESC
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// [가공 키오스크 공급] 유형별 소요량 + 생산 누적
	//
	//   키오스크는 공정(품목)과 유형이 <b>둘 다 선택 항목</b>이다.
	//   품목을 안 고르면 실적이 프로젝트에만 귀속되므로,
	//   타일의 "생산 M" 도 같은 기준으로 집계해야 숫자가 맞는다.
	//     품목 선택  → 그 품목에 귀속된 실적만
	//     품목 미선택 → 프로젝트 전체 실적 (품목 없는 것 포함)
	//
	//   제작품만 대상. STD·구매품은 가공 대상이 아니라 발주 대상이다.
	//   leg(다리발)는 공정당 총량이므로 유니트수를 곱하지 않는다.
	//
	//   ★ operation 을 넘기면 <b>그 공정의 실적만</b> 센다.
	//     한 부품은 절단 → 가공 → 와이어커팅을 거치며 공정마다 다시 세어지므로,
	//     공정을 합치면 실물보다 큰 수가 나온다
	//     (plate 를 340개 자르고 120개를 가공했는데 460 으로 표시되는 식).
	//     비우면 전 공정 합계가 나오니, 공정별 화면은 반드시 넘길 것.
	//
	//   ※ 키오스크 본체는 ProdResultService.getKindTiles() 를 쓴다.
	//     여기 두 메서드는 생산 지시 화면과 대시보드가 쓰는 것으로,
	//     같은 계산식을 공유한다. 계산식을 고칠 때 양쪽을 같이 고칠 것.
	// =================================================================

	/** 품목 선택 시 — 그 품목의 유형별 소요/생산 */
	public List<Map<String, Object>> getKindNeedByItem(Integer sujuId, String operation) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("sujuId", sujuId);
		params.addValue("operation", nullIfEmpty(operation));

		String sql = """
            SELECT COALESCE(k.kind, r.kind)   AS kind
                 , COALESCE(k.need_qty, 0)    AS need_qty
                 , COALESCE(k.qty_per_unit, 0) AS qty_per_unit
                 , COALESCE(k.part_cnt, 0)    AS part_cnt
                 , COALESCE(r.done_qty, 0)    AS done_qty
            FROM (
                SELECT b."Kind" AS kind
                     , SUM(b."Qty") AS need_qty
                     , SUM(b."Qty") AS qty_per_unit
                     , COUNT(*)     AS part_cnt
                FROM iljin_suju_bom b
                JOIN suju s ON s.id = b."Suju_id"
                WHERE b."Suju_id" = :sujuId
                  AND b."Gubun" = '제작품'
                GROUP BY b."Kind"
            ) k
            FULL OUTER JOIN (
                SELECT COALESCE("Kind", 'etc') AS kind
                     , SUM(COALESCE("GoodQty", 0)) AS done_qty
                FROM iljin_prod_result
                WHERE "Suju_id" = :sujuId
                  AND (CAST(:operation AS varchar) IS NULL
                       OR "Operation" = CAST(:operation AS varchar))
                GROUP BY COALESCE("Kind", 'etc')
            ) r ON r.kind = k.kind
            ORDER BY COALESCE(k.need_qty, 0) DESC
            """;

		return sqlRunner.getRows(sql, params);
	}

	/**
	 * 프로젝트 전체의 유형별 소요/생산 (품목 미선택 시 키오스크가 쓰는 합계).
	 *
	 * 생산 누적에는 <b>품목이 지정되지 않은 실적도 포함</b>된다.
	 * 가공 실적은 프로젝트에만 귀속될 수 있기 때문이다.
	 */
	public List<Map<String, Object>> getKindNeedByProject(String spjangcd, String projNo,
														  String operation) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("projNo", projNo);
		params.addValue("operation", nullIfEmpty(operation));

		String sql = """
            SELECT COALESCE(k.kind, r.kind)    AS kind
                 , COALESCE(k.need_qty, 0)     AS need_qty
                 , COALESCE(k.qty_per_unit, 0) AS qty_per_unit
                 , COALESCE(k.part_cnt, 0)     AS part_cnt
                 , COALESCE(r.done_qty, 0)     AS done_qty
            FROM (
                SELECT b."Kind" AS kind
                     , SUM(b."Qty") AS need_qty
                     , SUM(b."Qty") AS qty_per_unit
                     , COUNT(*)     AS part_cnt
                FROM iljin_suju_bom b
                -- 프로젝트 공통 부품(Suju_id IS NULL)도 필요량에 포함한다
                LEFT JOIN suju s ON s.id = b."Suju_id"
                WHERE b.spjangcd = :spjangcd
                  AND COALESCE(s.project_id, b."Project_id") = :projNo
                  AND b."Gubun" = '제작품'
                GROUP BY b."Kind"
            ) k
            FULL OUTER JOIN (
                SELECT COALESCE("Kind", 'etc') AS kind
                     , SUM(COALESCE("GoodQty", 0)) AS done_qty
                FROM iljin_prod_result
                WHERE spjangcd = :spjangcd
                  AND "Project_id" = :projNo
                  AND (CAST(:operation AS varchar) IS NULL
                       OR "Operation" = CAST(:operation AS varchar))
                GROUP BY COALESCE("Kind", 'etc')
            ) r ON r.kind = k.kind
            ORDER BY COALESCE(k.need_qty, 0) DESC
            """;

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// helper
	// =================================================================
	private String str(Object o) {
		return o == null ? "" : o.toString().trim();
	}

	/**
	 * 화면에서 빈 문자열이 올 수 있다. SQL 의 :param IS NULL 분기가 동작하도록 NULL 로 정규화한다.
	 * (빈 문자열을 그대로 넘기면 "Operation" = '' 이 되어 결과가 0 건이 된다)
	 */
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