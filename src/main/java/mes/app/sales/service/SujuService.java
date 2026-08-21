package mes.app.sales.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import mes.domain.entity.Suju;
import mes.domain.repository.SujuRepository;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;

@Slf4j
@Service
public class SujuService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	SujuRepository SujuRepository;


	// 수주 내역 조회
	public List<Map<String, Object>> getSujuList(Timestamp start, Timestamp end, String spjangcd, String company, String projno) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("start", start);
		dicParam.addValue("end", end);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("projno", projno);

		String sql = """
			WITH suju_state_summary AS (
			  SELECT
				sh.id AS suju_head_id,
				-- 상태 요약 계산
				CASE
				  WHEN COUNT(DISTINCT s."State") = 1 THEN MIN(s."State")
				  WHEN BOOL_AND(s."State" IN ('received', 'planned')) AND BOOL_OR(s."State" = 'planned') THEN 'part_planned'
				  WHEN BOOL_AND(s."State" IN ('received', 'ordered', 'planned')) AND BOOL_OR(s."State" = 'ordered') THEN 'part_ordered'
				  ELSE '기타'
				END AS summary_state
			   
			  FROM suju_head sh
			  JOIN suju s ON s."SujuHead_id" = sh.id
			   
			  GROUP BY sh.id
			),
			shipment_summary AS (
				SELECT
					s."SujuHead_id",
					SUM(s."SujuQty") AS total_qty,
					COALESCE(SUM(shp."shippedQty"), 0) AS total_shipped,
					CASE
						WHEN COUNT(shp."SourceDataPk") = 0 THEN ''                             -- 조인 안 됨
						WHEN COALESCE(SUM(shp."shippedQty"), 0) = 0 THEN 'ordered'             -- 조인 됐는데 출하량 0
						WHEN SUM(shp."shippedQty") >= SUM(s."SujuQty") THEN 'shipped'          -- 전량 출하
						WHEN SUM(shp."shippedQty") < SUM(s."SujuQty") THEN 'partial'           -- 일부 출하
						ELSE ''
				  	END AS shipment_state
				  FROM suju s
				  LEFT JOIN (
					SELECT "SourceDataPk", SUM("Qty") AS "shippedQty"
					FROM shipment
					GROUP BY "SourceDataPk"
				  ) shp ON shp."SourceDataPk" = s.id
				  GROUP BY s."SujuHead_id"
			)
			   
			SELECT
			  sh.id,
			  sh."JumunNumber",
			  to_char(sh."JumunDate", 'yyyy-mm-dd') AS "JumunDate",
			  to_char(sh."DeliveryDate", 'yyyy-mm-dd') AS "DueDate",
			  sh."Company_id",
			  c."BusinessNumber",
			  SUM(s."Price") AS "sujuPrice",
			  SUM(s."Vat") AS "sujuVat",
			  c."Name" AS "CompanyName",
			  sh."TotalPrice",
			  sh."Description",
			  sc_state."Value" AS "StateName",
			  sc_type."Value" AS "SujuTypeName",
			  sh.suju_name,
				s.project_id  as projno,
			  -- 대표 제품명 + 외 N개
			  CASE
				 WHEN COUNT(DISTINCT s."Material_id") = 1 THEN
				       (array_agg(COALESCE(m."Name", s."Material_Name") ORDER BY s.id))[1]  
				     ELSE
				       CONCAT(
				         (array_agg(COALESCE(m."Name", s."Material_Name") ORDER BY s.id))[1], 
				         ' 외 ',
				         COUNT(DISTINCT s."Material_id") - 1,
				         '개'
				       )
				   END AS product_name,
			  sss.summary_state AS "State",
			  sc_ship."Value" AS "ShipmentStateName",
			  -- WBS 확정 여부 : wbs_plan 에 이 수주로 복사된 행이 있으면 확정
			  --   suju_head_id IS NULL = 프로젝트 계획(가안), NOT NULL = 수주 확정분
			  CASE WHEN EXISTS (
			         SELECT 1 FROM wbs_plan wp WHERE wp.suju_head_id = sh.id
			       ) THEN '1' ELSE '0' END AS wbs_fixed
			   
			FROM suju_head sh
			JOIN suju s ON s."SujuHead_id" = sh.id
			LEFT JOIN material m ON m.id = s."Material_id"
			LEFT JOIN (
			  SELECT "SourceDataPk", SUM("Qty") AS "shippedQty"
			  FROM shipment
			  GROUP BY "SourceDataPk"
			) shp ON shp."SourceDataPk" = s.id
			LEFT JOIN company c ON c.id = sh."Company_id"
			LEFT JOIN shipment_summary ss ON ss."SujuHead_id" = sh.id
			LEFT JOIN suju_state_summary sss ON sss.suju_head_id = sh.id
			LEFT JOIN sys_code sc_state ON sc_state."Code" = sss.summary_state AND sc_state."CodeType" = 'suju_state'
			LEFT JOIN sys_code sc_type ON sc_type."Code" = sh."SujuType" AND sc_type."CodeType" = 'suju_type'
			LEFT JOIN sys_code sc_ship ON sc_ship."Code" = ss.shipment_state AND sc_ship."CodeType" = 'shipment_state'
            where 1 = 1
            and sh.spjangcd = :spjangcd
            and sh."JumunDate" between :start and :end
			""";

		if(projno != null && !projno.isEmpty()){
			sql += """
				and s.project_id = :projno
				""";
		}
		if (company != null && !company.isEmpty()) {
			dicParam.addValue("company", "%" + company + "%");   // ← 여기서 와일드카드 포함해서 덮어쓰기

			sql += """
          AND (
              c."Name" ILIKE :company
              OR CAST(c.id AS TEXT) ILIKE :company
          )
        """;
		}

		sql +="""
			group by
					 sh.id,
					 sh."JumunNumber",
					 sh."JumunDate",
					 sh."DeliveryDate",
					 sh."Company_id",
					 c."Name",
					 c."BusinessNumber",
					 sh."TotalPrice",
					 sh."Description",
					 sh."SujuType",
					 sh.suju_name,
					 sss.summary_state,
					 sc_state."Value",
					 sc_type."Value",
					 sc_ship."Value",
					 s.project_id
				order by sh."JumunDate" desc,  sh.id desc
			""";


		List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

		return itmes;
	}

	// 수주 상세정보 조회
	public Map<String, Object> getSujuDetail(int id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("id", id);

		String sql = """ 
			SELECT
				sh.id,
				sh."JumunNumber",
				to_char(sh."JumunDate", 'yyyy-mm-dd') AS "JumunDate",
				to_char(sh."DeliveryDate", 'yyyy-mm-dd') AS "DueDate",
				sh."Company_id",
				c."Name" AS "CompanyName",
				sh."TotalPrice",
				sh."Description",
				sh."SujuType",
				sh."SuJuOrderId" ,
				sh."SuJuOrderName"  ,
				sh."DeliveryName",
				sh."EstimateMemo" ,	
				sh.suju_name,
				sh.project_id AS "headProjno",
				fn_code_name('suju_type', sh."SujuType") AS "SujuTypeName",
				-- WBS 확정 여부 (목록과 동일 기준)
				CASE WHEN EXISTS (
				       SELECT 1 FROM wbs_plan wp WHERE wp.suju_head_id = sh.id
				     ) THEN '1' ELSE '0' END AS wbs_fixed
			FROM suju_head sh
			LEFT JOIN company c ON c.id = sh."Company_id"
			WHERE sh.id = :id
		""";

		String detailSql = """ 
			WITH shipment_status AS (
				   SELECT "SourceDataPk", SUM("Qty") AS shipped_qty
				   FROM shipment
				   WHERE "SourceTableName" = 'rela_data'
				   GROUP BY "SourceDataPk"
				 ),
				 suju_with_state AS (
				   SELECT
				     s.id AS suju_id,
				     s."SujuHead_id",
				     s."Material_id",
				     -- 코드/품명: material 없으면 공란 또는 suju.Material_Name 사용
				     m."Code" AS "product_code",
				     COALESCE(m."Name", s."Material_Name") AS "txtProductName",
				     mg."Name" AS "MaterialGroupName",
				     mg.id AS "MaterialGroup_id",
				     u."Name" AS "unit",
				     s."SujuQty" AS "quantity",
				     to_char(s."JumunDate", 'yyyy-mm-dd') AS "JumunDate",
				     to_char(s."DueDate", 'yyyy-mm-dd') AS "DueDate",
				     s."CompanyName",
				     s."Company_id",
				     s."SujuType",
				     s."UnitPrice" AS "unitPrice",
				     s."Vat"       AS "VatAmount",
				     s."Price"     AS "supplyAmount",
				     s."TotalAmount" AS "totalAmount",
				     to_char(s."_created", 'yyyy-mm-dd') AS "create_date",
				     s.project_id AS "projectHidden",
				     p.projnm     AS "project",
				     s."Description" AS "description",
				     s."InVatYN"  AS "invatyn",
				     s."SujuQty2",
				     s."AvailableStock",
				     s."ReservationStock",
				     s."State"    AS "original_state",
				     COALESCE(sh.shipped_qty, -1) AS "shipped_qty",
				     s."Standard" AS standard,
				     s."line"            AS "line",
			           s.equip_type        AS "equip_type",
			           s.pin_shift_unit    AS "pin_shift_unit",
			           s.leg_spec          AS "leg_spec",
			           s.leg_cnt           AS "leg_cnt",
			           s.make_type         AS "make_type",
			           s.design_comp_id    AS "design_comp_id",
			           s.design_comp_name  AS "design_comp_name",
			           to_char(s.draw_date, 'yyyy-mm-dd') AS "draw_date",
			           s.make_comp_id      AS "make_comp_id",
			           s.make_comp_name    AS "make_comp_name",
			           s.item_remark       AS "item_remark",
				
				     -- 마스터 규격 문자열(없으면 NULL)
				     NULLIF(btrim(COALESCE(m."Standard1",'') || ' ' || COALESCE(m."Standard2",'')),'') AS spec_master,
				
				     -- 마스터 규격이 있으면 잠금 Y, 없으면 N
				     CASE WHEN NULLIF(btrim(COALESCE(m."Standard1",'') || ' ' || COALESCE(m."Standard2",'')),'') IS NOT NULL
				          THEN 'Y' ELSE 'N' END AS standard_locked,
				
				     -- 최종 상태
				     CASE
				       WHEN sh.shipped_qty = -1 THEN s."State"
				       WHEN sh.shipped_qty = 0  THEN 'force_completion'
				       WHEN sh.shipped_qty >= s."SujuQty" THEN 'shipped'
				       WHEN sh.shipped_qty <  s."SujuQty" THEN 'partial'
				       ELSE s."State"
				     END AS final_state
				   FROM suju s
				   --여기부터 LEFT JOIN으로 변경
				   LEFT JOIN material  m  ON m.id = s."Material_id"
				   LEFT JOIN mat_grp   mg ON mg.id = m."MaterialGroup_id"
				   LEFT JOIN unit      u  ON u.id  = m."Unit_id"
				   LEFT JOIN TB_DA003  p  ON p."projno" = s.project_id
				   LEFT JOIN shipment_status sh ON sh."SourceDataPk" = s.id
				   WHERE s."SujuHead_id" = :id
				 )
				 SELECT
				   s."suju_id",
				   s."SujuHead_id",
				   s."Material_id",
				   s."product_code",
				   s."txtProductName",
				   s."MaterialGroupName",
				   s."MaterialGroup_id",
				   s."unit",
				   s."quantity",
				   s."JumunDate",
				   s."DueDate",
				   s."CompanyName",
				   s."Company_id",
				   s."SujuType",
				   s."unitPrice",
				   s."VatAmount",
				   s."supplyAmount",
				   s."totalAmount",
				   s.final_state AS "State",
				   COALESCE(sc_ship."Value", sc_suju."Value") AS "suju_StateName",
				   s."invatyn",
				   s."SujuQty2",
				   s."AvailableStock",
				   s."ReservationStock",
				   s."create_date",
				   s."projectHidden",
				   s."project",
				   s."description",
				   s.standard,
				   s.spec_master,
				   s.standard_locked,
				   s."line",
			       s."equip_type",
			       s."pin_shift_unit",
			       s."leg_spec",
			       s."leg_cnt",
			       s."make_type",
			       s."design_comp_id",
			       s."design_comp_name",
			       s."draw_date",
			       s."make_comp_id",
			       s."make_comp_name",
			       s."item_remark"
				 FROM suju_with_state s
				 LEFT JOIN sys_code sc_ship
				   ON sc_ship."Code" = s.final_state AND sc_ship."CodeType" = 'shipment_state'
				 LEFT JOIN sys_code sc_suju
				   ON sc_suju."Code" = s.final_state AND sc_suju."CodeType" = 'suju_state'
				 ORDER BY s."JumunDate", s.suju_id
		""";

		Map<String, Object> sujuHead = this.sqlRunner.getRow(sql, paramMap);
		List<Map<String, Object>> sujuList = this.sqlRunner.getRows(detailSql, paramMap);
//		log.info("수주상세조회 SQL: {}", detailSql);
//		log.info("SQL : {}", paramMap.getValues());
		sujuHead.put("sujuList", sujuList);

		return sujuHead;
	}

	// 제품 정보 조회
	public Map<String, Object> getSujuMatInfo(int product_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("product_id", product_id);

		String sql = """
			select m.id as mat_pk
			, m."AvailableStock" 
			, u."Name" as unit_name
			from material m 
			inner join unit u on u.id = m."Unit_id" 
			where m.id = :product_id
			""";

		Map<String, Object> item = this.sqlRunner.getRow(sql, paramMap);

		return item;
	}

	public String makeJumunNumber(Date dataDate) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("data_date", dataDate);

		String jumunNumber = "";

		String sql = """
		select "CurrVal" from seq_maker where "Code" = 'JumunNumber' and "BaseDate" = :data_date
		""";
		Map<String, Object> mapRow = this.sqlRunner.getRow(sql, paramMap);

		int currVal = 1;
		if (mapRow!=null && mapRow.containsKey("CurrVal")) {
			currVal =  (int)mapRow.get("CurrVal");
			sql = """
		    update seq_maker set "CurrVal" = "CurrVal" + 1, "_modified" = now()	where "Code" = 'JumunNumber' and "BaseDate" = :data_date
			""";
			this.sqlRunner.execute(sql, paramMap);
		}else {
			sql = """
			insert into seq_maker("Code", "BaseDate", "CurrVal", "_modified") values('JumunNumber', :data_date, 1, now());	
			""";
			this.sqlRunner.execute(sql, paramMap);
		}
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		jumunNumber = String.format("{0}-{1}", sdf.format(dataDate), currVal);
		return jumunNumber;
	}

	public String makeJumunNumberAndUpdateSuju(int suju_id, String dataDate) {

		Suju suju = this.SujuRepository.getSujuById(suju_id);
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("data_date", dataDate);

		String jumunNumber = suju.getJumunNumber();
		if(StringUtils.hasText(jumunNumber)==false) {
			Date jumun_date = CommonUtil.trySqlDate(dataDate);
			jumunNumber = this.makeJumunNumber(jumun_date);
			suju.setJumunNumber(jumunNumber);
			this.SujuRepository.save(suju);
		}
		return jumunNumber;
	}

	public List<Map<String, Object>> getPriceByMatAndComp(int matPk, int company_id, String ApplyStartDate){
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("mat_pk", matPk);
		dicParam.addValue("company_id", company_id);
		dicParam.addValue("ApplyStartDate", ApplyStartDate);

		String sql = """
			select mcu.id 
            , mcu."Company_id"
            , c."Name" as "CompanyName"
            , mcu."UnitPrice" 
            , mcu."FormerUnitPrice" 
            , mcu."ApplyStartDate"::date 
            , mcu."ApplyEndDate"::date 
            , mcu."ChangeDate"::date 
            , mcu."ChangerName" 
            from mat_comp_uprice mcu 
            inner join company c on c.id = mcu."Company_id"
            where 1=1
            and mcu."Material_id" = :mat_pk
            and mcu."Company_id" = :company_id
            and to_date(:ApplyStartDate, 'YYYY-MM-DD') between mcu."ApplyStartDate"::date and mcu."ApplyEndDate"::date
            and mcu."Type" = '02'
            order by c."Name", mcu."ApplyStartDate" desc
        """;


		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public String getNextCompCode() {
		MapSqlParameterSource params = new MapSqlParameterSource();
		// "Code" 가 varchar 라 MAX("Code") 는 사전순 최대값을 준다.
		// ('9' > '10' 이므로 9 를 넘기면 계속 10 이 나와 UNIQUE 위반)
		// 숫자 코드만 골라 bigint 로 비교해야 한다.
		String sql = """
       SELECT COALESCE(MAX(
                CASE WHEN btrim("Code") ~ '^[0-9]+$'
                     THEN btrim("Code")::bigint
                END
              ), 0) + 1 AS next_code
         FROM company
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, params);
		Object v = (row == null) ? null : row.get("next_code");
		long next = (v == null) ? 1L : Long.parseLong(v.toString());

		// 기존 코드가 '001' ~ '023' 3자리 0채움이라 형식을 맞춘다.
		// 999 를 넘으면 자릿수가 자연히 늘어난다.
		return String.format("%03d", next);
	}

	public String getNextMatCode() {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String sql = """
       SELECT COALESCE(MAX(
								CASE WHEN btrim("Code") ~ '^[0-9]+$'
										 THEN btrim("Code")::bigint
								END
							), 0) + 1 AS next_code
			 FROM material;
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, params);
		Object v = (row == null) ? null : row.get("next_code");
		return (v == null) ? "1" : v.toString();   // "1"부터 시작
	}


	public List<Map<String, Object>> getDetailList(Integer sujuId) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("suju_id", sujuId);

		String sql = """
        SELECT  
        "id",
				"suju_id",
				"Standard" AS standard,
				"Qty" AS qty, 
				"UnitName" ,
				"UnitPrice" as "sd_UnitPrice",
				"Price" as sd_price,
				"Vat" as sd_vat,
				"TotalAmount"
        FROM suju_detail
        WHERE "suju_id" = :suju_id
        ORDER BY "id" ASC;
    """;

		return sqlRunner.getRows(sql, params);
	}

	public Map<String, Object> getPrintList(int id) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);

		// 1) 헤더: spjangcd도 함께 조회
		String headSql = """
        SELECT
          sh.id,
          sh."JumunNumber",
          to_char(sh."JumunDate",   'yyyy-mm-dd') AS "JumunDate",
          to_char(sh."DeliveryDate",'yyyy-mm-dd') AS "DueDate",
          sh."Company_id",
          c."Name" AS "CompanyName",
          sh."TotalPrice",
          sh."Description",
          sh."SujuType",
          sh."SuJuOrderId",
          sh."SuJuOrderName",
          sh."DeliveryName",
          sh."spjangcd",  -- ★ 추가
          fn_code_name('suju_type', sh."SujuType") AS "SujuTypeName"
        FROM suju_head sh
        LEFT JOIN company c ON c.id = sh."Company_id"
        WHERE sh.id = :id
    """;
		Map<String, Object> head = sqlRunner.getRow(headSql, params);
		if (head == null) head = new HashMap<>();

		// 2-2) 아이템(여러 행) — 반드시 getRows 사용
		String itemsSql = """
      WITH head_suju AS (
				SELECT
					s.id              AS suju_id,
					s."SujuHead_id"   AS head_id,
					s."Material_id",
					COALESCE(m."Name", s."Material_Name") AS product_name,
					s."UnitPrice",
					s."Price"         AS suju_price,
					s."Vat"           AS suju_vat,
					s."TotalAmount"   AS suju_total,
					s."SujuQty",
					s."Standard"      AS suju_standard,
					s."Description",
					NULLIF(TRIM(u."Name" ), '') AS unit_name
				FROM suju s
				LEFT JOIN material m ON m.id = s."Material_id"
				LEFT JOIN unit u ON m."Unit_id" = u.id
				WHERE s."SujuHead_id" = :id
			),
			detail_raw AS (
				SELECT
					d.id,
					d.suju_id,
					CASE
						WHEN d."Standard" ~ '^[0-9]+(\\.[0-9]+)?$'
							THEN to_char(d."Standard"::numeric, 'FM9999990.000')
						ELSE NULLIF(TRIM(d."Standard"), '')
					END AS standard_text,
					CASE
						WHEN d."Standard" ~ '^[0-9]+(\\.[0-9]+)?$' THEN d."Standard"::numeric
						ELSE NULL
					END AS standard_num,
					d."Qty"::numeric AS qty,
					CASE
						WHEN d."Standard" ~ '^[0-9]+(\\.[0-9]+)?$'
							THEN (d."Qty"::numeric * d."Standard"::numeric)
						ELSE d."Qty"::numeric
					END AS effective_qty,
					/* ⬇ 디테일 단가/단위 */
					NULLIF(d."UnitPrice"::text, '')::numeric AS detail_unitprice,
					NULLIF(TRIM(d."UnitName"), '')           AS detail_unitname
				FROM suju_detail d
			),
			detail_sum AS (
				SELECT suju_id, SUM(effective_qty) AS sum_eff_qty
				FROM detail_raw
				GROUP BY suju_id
			),
			detail_alloc AS (
				SELECT
					r.suju_id,
					hs.head_id,
					hs.product_name,
				 r.standard_text::text AS "Standard",
					r.qty               AS "Qty",
					/* ⬇ 단가는 디테일>헤더 우선 */
					COALESCE(NULLIF(r.detail_unitprice, 0), hs."UnitPrice") AS "UnitPrice",
					/* ⬇ 단위는 디테일>헤더 우선 */
					COALESCE(r.detail_unitname, hs.unit_name)               AS "UnitName",
					CASE WHEN ds.sum_eff_qty > 0
							 THEN (r.effective_qty / ds.sum_eff_qty)
							 ELSE NULL
					END AS share,
					ROUND( (hs.suju_price::numeric) * (r.effective_qty / NULLIF(ds.sum_eff_qty, 0)), 0) AS "SupplyAmount",
					ROUND( (hs.suju_vat  ::numeric) * (r.effective_qty / NULLIF(ds.sum_eff_qty, 0)), 0) AS "VatAmount",
					ROUND( (hs.suju_total::numeric) * (r.effective_qty / NULLIF(ds.sum_eff_qty, 0)), 0) AS "TotalAmount",
					hs."Description"    AS "Description"
				FROM detail_raw r
				JOIN detail_sum ds ON ds.suju_id = r.suju_id
				JOIN head_suju hs  ON hs.suju_id = r.suju_id
			),
			fallback_rows AS (
				SELECT
					hs.suju_id,
					hs.head_id,
					hs.product_name,
							CASE
						WHEN hs.suju_standard ~ '^[0-9]+(\\.[0-9]+)?$'
							THEN to_char(hs.suju_standard::numeric, 'FM9999990.000')
						ELSE hs.suju_standard
					END::text           AS "Standard",
					hs."SujuQty"::numeric  AS "Qty",
					hs."UnitPrice"         AS "UnitPrice",
					/* ⬇ 디테일이 없을 때는 헤더 단위 사용 */
					hs.unit_name           AS "UnitName",
					NULL::numeric          AS share,
					COALESCE(hs.suju_price, 0)::numeric      AS "SupplyAmount",
					COALESCE(hs.suju_vat,   0)::numeric      AS "VatAmount",
					COALESCE(hs.suju_total, 0)::numeric      AS "TotalAmount",
					hs."Description"       AS "Description"
				FROM head_suju hs
				WHERE NOT EXISTS (SELECT 1 FROM suju_detail d WHERE d.suju_id = hs.suju_id)
			)
			SELECT
				x.product_name,
				x."Standard",
				x."Qty",
				s."SujuQty2",
				x."UnitPrice",
				x."UnitName",    
				x."SupplyAmount",
				x."VatAmount",
				x."TotalAmount",
				x."Description",
				sh."Description"      AS head_description
			FROM (
				SELECT suju_id, head_id, product_name, "Standard", "Qty", "UnitPrice", "UnitName", share,
							 "SupplyAmount", "VatAmount", "TotalAmount", "Description"
				FROM detail_alloc
				UNION ALL
				SELECT suju_id, head_id, product_name, "Standard", "Qty", "UnitPrice", "UnitName", share,
							 "SupplyAmount", "VatAmount", "TotalAmount", "Description"
				FROM fallback_rows
			) x
			JOIN suju s      ON s.id = x.suju_id
			JOIN suju_head sh ON sh.id = s."SujuHead_id"
			ORDER BY x.head_id, x.suju_id;
    """;

		List<Map<String, Object>> items = sqlRunner.getRows(itemsSql, params); // ★ 여러 행

		// 3) 공급자 정보: head에서 spjangcd 꺼내 바인딩
		String supplierSql = """
        SELECT
          saupnum  AS biz_no,
          spjangnm AS name,
          prenm  	 AS supplierceo,
          adresa   AS address,
          biztype  AS biz_type,
          item     AS biz_item,
          tel1     AS tel
        FROM tb_xa012
        WHERE spjangcd = :spjangcd
        LIMIT 1
    """;
		String site = (String) head.getOrDefault("spjangcd", "ZZ");
		Map<String, Object> supplier = sqlRunner.getRow(
			supplierSql, new MapSqlParameterSource().addValue("spjangcd", site)
		);
		if (supplier == null) supplier = Collections.emptyMap();

		// 2-3) 프런트가 기대하는 형태로 패키징
		Map<String, Object> out = new HashMap<>();
		out.put("head", head);     // 사용 안 하더라도 넣어두면 확장 편함
		out.put("items", items);   // adaptDetailResponse가 읽는 배열
		out.put("supplier", supplier);
		return out;
	}

	public List<Map<String, Object>> getWorkcenterList(int Factory_id) {
		MapSqlParameterSource params = new MapSqlParameterSource()
																		 .addValue("Factory_id", Factory_id);

		String sql = """
        select id as value, "Name" as text
        from work_center
        where "Factory_id" = cast(:Factory_id as int)
        order by "Name"
        """;

//		log.info("WorkCenterList SQL: {}", sql);
//		log.info("SQL Parameters: {}", params.getValues());

		return sqlRunner.getRows(sql, params); // ← getRow() 말고 getRows()
	}

}