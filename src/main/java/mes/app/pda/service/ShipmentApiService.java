package mes.app.pda.service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.app.shipment.service.ShipmentDoBService;
import mes.app.util.UtilClass;
import mes.domain.entity.*;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.services.DateUtil;
import mes.domain.services.SqlRunner;
import org.aspectj.weaver.loadtime.Aj;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ShipmentApiService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	MatLotRepository matLotRepository;

	@Autowired
	MatProcInputRepository matProcInputRepository;

	@Autowired
	MatProcInputReqRepository matProcInputReqRepository;
	@Autowired
	private ShipmentRepository shipmentRepository;
	@Autowired
	private MaterialRepository materialRepository;

	@Autowired
	TransactionTemplate transactionTemplate;
	@Autowired
	private ShipmentDoBService shipmentDoBService;
	@Autowired
	private MatLotConsRepository matLotConsRepository;
	@Autowired
	private ShipmentHeadRepository shipmentHeadRepository;


	public List<Map<String, Object>> ApigetShipmentOrderList(String date_from, String date_to, String state, Integer comp_pk, Integer mat_grp_pk, Integer mat_pk, String keyword) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("date_from", Date.valueOf(date_from));
		paramMap.addValue("date_to", Date.valueOf(date_to));
		paramMap.addValue("state", state);
		paramMap.addValue("comp_pk", comp_pk);
		paramMap.addValue("mat_grp_pk", mat_grp_pk);
		paramMap.addValue("mat_pk", mat_pk);
		paramMap.addValue("keyword", keyword);

		String sql = """
				with sg as (
				SELECT\s
				    s."ShipmentHead_id",
				    SUM(s."Qty") AS total_qty,
				    MIN(m."Name") ||\s
				      CASE\s
				        WHEN COUNT(DISTINCT m."Name") > 1\s
				        THEN ' 외 ' || (COUNT(DISTINCT m."Name") - 1) || '건'
				        ELSE ''
				      END AS item_names
				FROM shipment s
				JOIN material m ON m.id = s."Material_id"
				GROUP BY s."ShipmentHead_id"
				)
				    
				select sh.id
				, sh."Company_id" as company_id
				, c."Name" as company_name
				, sh."ShipDate" as ship_date
				, ROUND(sh."TotalQty"::numeric, 2) AS total_qty
				, sh."TotalPrice" as total_price
				, sh."TotalVat" as total_vat
				, sh."Description" as description
				, sh."State" as state
				, fn_code_name('shipment_state', sh."State") as state_name
				, to_char(coalesce(sh."OrderDate",sh."_created") ,'yyyy-mm-dd') as order_date
				, sh."StatementIssuedYN" as issue_yn
				, sh."StatementNumber" as stmt_number\s
				, sh."IssueDate" as issue_date
				, sh."DeliveryName" as delivery_name
				, sg."item_names" as material_name_summary
				from shipment_head sh\s
				join company c on c.id = sh."Company_id"
				join sg on sg."ShipmentHead_id" = sh.id
                where sh."ShipDate"  between :date_from and :date_to
				         """;
		if (comp_pk != null) {
			sql += " and sh.\"Company_id\" = :comp_pk ";
		}

		if (StringUtils.isEmpty(state) == false) {
			sql += "  and sh.\"State\" = :state ";
		}

		if (mat_pk != null || mat_grp_pk != null || StringUtils.isEmpty(keyword) == false) {
			sql += """
					and exists ( select 1
            		    from shipment s 
                        inner join material m on m.id = s."Material_id" 
                        left join mat_grp mg on mg.id = m."MaterialGroup_id"
                        where s."ShipmentHead_id" = sh.id 
					""";

			if (mat_pk != null) {
				sql += " and s.\"Material_id\" = :mat_pk ";
			}

			if (mat_grp_pk != null) {
				sql += " and mg.id = :mat_grp_pk ";
			}

			if (StringUtils.isEmpty(keyword) == false) {
				sql += """
						 and ( m."Name" ilike concat('%%', :keyword,'%%')
						       or m."Code" ilike concat('%%', :keyword,'%%'))
						""";
			}

			sql += """
					)
					       order by sh."ShipDate", c."Name", sh.id
					""";
		}

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}



	public List<Map<String, Object>> getShipmentList (Integer shipment_header_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("shipment_header_id", shipment_header_id);

		String sql = """
	       select
	        s."ShipmentHead_id" as sh_id
	        , s.id as shipment_id
	        , sh."State"
	        , s."Material_id"
	        , mg."Name" as mat_grp_name
	        , m."Name" as mat_name
	        , m."Code" as mat_code
	        , m."id" as item_code
	        , s."UnitPrice" as unit_price
	        , s."Price" as price
	        , s."Vat" as vat
	        , (s."Price" + s."Vat") as total_price
	        , m."VatExemptionYN" as vat_ex_yn
	        , u."Name" as unit_name 
	        , s."OrderQty"
	        , s."Qty"
	        , s."Description" as description
	        , m."Standard1" as stan_dard --규격
	        , (select coalesce(sum(mlc."OutputQty" ), 0) as lot_qty from mat_lot_cons mlc where mlc."SourceDataPk" = s.id and mlc."SourceTableName"='shipment') as lot_qty
	        from shipment s 
	            inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	            inner join material m on m.id = s."Material_id" 
	            left join mat_grp mg on mg.id = m."MaterialGroup_id" 
	            left join unit u on u.id = m."Unit_id" 
	        where sh.id = :shipment_header_id	
		        		 """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	public List<Map<String, Object>> getByLotNumber (String LotNum) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("lotNumber", LotNum);

		String sql = """
				SELECT ml.id,
				               ml.*,
				               m."Name" as material_name,
				               m.id as item_code,
				               m."Code" as material_code
				        FROM mat_lot ml
				        JOIN material m
				          ON ml."Material_id" = m.id
				        WHERE ml."LotNumber" = :lotNumber
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	public List<Map<String, Object>> getShipmentHeadList(String dateFrom, String dateTo,
														 //String compPk, String matGrpPk, String matPk,
														 String keyword, String state) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("dateFrom", dateFrom);
		paramMap.addValue("dateTo", dateTo);
//		paramMap.addValue("compPk", compPk);
//		paramMap.addValue("matGrpPk", matGrpPk);
//		paramMap.addValue("matPk", matPk);
		paramMap.addValue("keyword", keyword);
		//String state = "shipped";
		paramMap.addValue("state", state);

		String sql = """
				select sh.id
		        , sh."Company_id" as company_id
                , c."Name" as company_name
		        , sh."ShipDate" as ship_date
		        , sh."TotalQty" as total_qty
	            , sh."TotalPrice" as total_price
	            , sh."TotalVat" as total_vat
	            , sh."Description" as description
                , sh."State" as state
                , fn_code_name('shipment_state', sh."State") as state_name
                , to_char(coalesce(sh."OrderDate",sh."_created") ,'yyyy-mm-dd') as order_date
                , sh."StatementIssuedYN" as issue_yn
                , sh."StatementNumber" as stmt_number
                , sh."IssueDate" as issue_date
                from shipment_head sh
                join company c on c.id = sh."Company_id"
                where sh."ShipDate"  between cast(:dateFrom as date) and cast(:dateTo as date)
				""";

//		if (StringUtils.isEmpty(compPk)==false)  sql += " and sh.\"Company_id\" = cast(:compPk as Integer) ";
		if (StringUtils.isEmpty(state)==false)  sql += " and sh.\"State\" = :state ";
		if (//StringUtils.isEmpty(matPk)==false || StringUtils.isEmpty(matGrpPk)==false ||
				StringUtils.isEmpty(keyword)==false) {
			sql += """
					and exists ( select 1
        		    from shipment s
                    inner join material m on m.id = s."Material_id"
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    where s."ShipmentHead_id" = sh.id
					""";
//			if (StringUtils.isEmpty(matPk)==false)  sql += " and s.\"Material_id\"  = cast(:matPk as Integer) ";
//			if (StringUtils.isEmpty(matGrpPk)==false)  sql += " and mg.id  = cast(:matGrpPk as Integer) ";
			if (StringUtils.isEmpty(keyword)==false)  sql += " and ( m.\"Name\" ilike concat('%%',:keyword,'%%') or m.\"Code\" ilike concat('%%',:keyword,'%%')) )";


		}
		sql += """ 
		 		order by sh."ShipDate" desc
		 		""";
		List<Map<String,Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	public List<Map<String, Object>> getShipmentDetailItemList(String headId, Integer company_id){
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("headId", headId);
		paramMap.addValue("companyId", company_id);

		String sql = """
				select s.id as ship_pk
				, s."Material_id" as mat_pk
				, mg."Name" as mat_grp_name
				, m."Code" as mat_code
				, m."Name" as mat_name
	            , m."UnitPrice" as mat_unit_price
	            , coalesce((s."OrderQty" * m."UnitPrice"), 0) as order_mat_price
				, u."Name" as unit_name
				, s."OrderQty" as order_qty
				, s."Qty" as ship_qty
				, s."Description" as description
				, sh."Company_id" as company_id
				, m.id as material_id
				,case
					when s."SourceTableName" = 'product' then mcu."UnitPrice"
					else su."UnitPrice"
				end as unit_price
				
				,case
					when s."SourceTableName" = 'product' then 'N'
					else su."InVatYN"
				end as invatyn
			
				,TRUNC((
				                  CASE
				                    WHEN s."SourceTableName" = 'product' THEN mcu."UnitPrice" * s."Qty"
				                    WHEN su."InVatYN" = 'Y' THEN (su."UnitPrice" * (10.0 / 11)) * s."Qty"
				                    ELSE su."UnitPrice" * s."Qty"
				                  END
				                )::numeric, 2) AS price
				,case
					when s."SourceTableName" = 'product' then (mcu."UnitPrice" * s."Qty") * 0.1
					when su."InVatYN" = 'Y' then (su."UnitPrice" - (su."UnitPrice" * (10.0/11))) * s."Qty"
					else (su."UnitPrice" * s."Qty") * 0.1
				end as vat
	            , m."VatExemptionYN" as vat_exempt_yn
	            , s."SourceDataPk" as src_data_pk
	            , s."SourceTableName" as src_table_name
				from shipment  s
				inner join material m on m.id = s."Material_id" 
				inner join mat_grp mg on mg.id = m."MaterialGroup_id"
				left join unit u on u.id = m."Unit_id" 
	            inner join shipment_head sh on sh.id = s."ShipmentHead_id"  
	            left join (	
				             			select distinct on ("Material_id") "Material_id", "UnitPrice"
				             			from mat_comp_uprice
				         				WHERE "Type" = '02'
				             				AND "Company_id" = :companyId
				             				AND "ApplyEndDate" > CURRENT_DATE
				             			order by "Material_id", "ApplyStartDate" desc
				         				) mcu on mcu."Material_id" = s."Material_id" and s."SourceTableName" = 'product'
				left join suju su on su.id = s."SourceDataPk"	
				where s."ShipmentHead_id" = cast(:headId as Integer)
	            order by m."Code", m."Name"
				""";
		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	//출하동작 , 코드는 길지만 읽어보면 별거없음. 그냥 출하 + lot 소모 합쳐놓은거
	@Transactional
	public AjaxResult ShipmenSaveActionByLot(List<Shipment> shipmentList, Integer sh_id, List<Map<String, Object>>  BarcodeList, String sourceData, Authentication auth){

		/** init variables **/
		AjaxResult result = new AjaxResult();

		User user = (User)auth.getPrincipal();

		Timestamp now = DateUtil.getNowTimeStamp();

		//region 유효성 체크
		if (shipmentList != null){
			for (int i = 0; i < shipmentList.size(); i++) {
				Integer materialId = shipmentList.get(i).getMaterialId();
				Material material = materialRepository.getMaterialById(materialId);

				if (material != null) {
					Float currentStock = material.getCurrentStock() != null ? material.getCurrentStock() : 0;
					Double shipQty = shipmentList.get(i).getQty() != null ? shipmentList.get(i).getQty() : 0;

					Float parsedShipQty = shipQty.floatValue();

					if (Float.compare(currentStock, parsedShipQty) < 0) {
						result.success = false;
						result.message = "재고 수량이 부족합니다.";
						return result;
					}

				}
			}
		}else{
			result.success = false;
			result.message = "출하상세정보가 없습니다.";
			return result;
		}
		//endregion

		//region 일진 지그 LOT 검증 — 다른 품목·프로젝트의 지그가 실리는 것을 막는다
		/*
		 * ★ 지그 LOT 은 어느 수주 품목(suju) 것인지 알 수 있다.
		 *     내작: 검사 합격 때 생성 (SourceTableName='mat_produce')
		 *     외작: 수불전표등록 외작 입고 → 로트 입고등록 (mat_inout → balju."PlanDataPk")
		 *   그 LOT 은
		 *   어느 수주 품목(suju) 것인지 알 수 있다. 출하 행도 수주에서 만들었으면
		 *   SourceDataPk = suju.id 다. 둘이 다르면 출고를 막는다.
		 *
		 *   품목마스터는 품목명으로 get-or-create 되어 <b>프로젝트끼리 공유</b>된다
		 *   (A 프로젝트 S10 과 B 프로젝트 S10 이 같은 Material_id). 그래서 품목 일치만으로는
		 *   다른 프로젝트 지그를 걸러낼 수 없다 — 사업계획서의 '출하 스캔 검증'이 바로 이 경우다.
		 *
		 *   수주와 이어지지 않는 LOT(일반 구매품 등)이거나 출하 행에 수주 연결이 없으면 검사하지 않는다.
		 *   기존 다른 사업장의 출하 흐름을 바꾸지 않기 위해서다.
		 *   반드시 출하 처리(아래) 전에 검사한다 — 이 메서드는 실패를 예외 없이 반환하므로
		 *   먼저 저장해 버리면 롤백되지 않는다.
		 */
		for (Map<String, Object> bc : BarcodeList) {
			Object mlObj = bc.get("ml_id");
			Object smObj = bc.get("shipment_id");
			if (mlObj == null || smObj == null) continue;

			MapSqlParameterSource vp = new MapSqlParameterSource();
			vp.addValue("mlId", Integer.parseInt(mlObj.toString()));
			vp.addValue("smId", Integer.parseInt(smObj.toString()));

			Map<String, Object> chk = this.sqlRunner.getRow("""
					SELECT ml."LotNumber"         AS lot_number
					     , ml."SourceTableName"   AS lot_src
					     , ml."InputDateTime"     AS lot_at
					     , ls.id                  AS lot_suju_id
					     , ls.project_id          AS lot_proj
					     , ls."Material_Name"     AS lot_item
					     , sm."SourceDataPk"      AS ship_suju_id
					     , ss.project_id          AS ship_proj
					     , ss."Material_Name"     AS ship_item
					FROM mat_lot ml
					-- 내작: 검사 합격(또는 예전 공정 조립)으로 만든 LOT
					LEFT JOIN mat_produce mp ON ml."SourceTableName" = 'mat_produce' AND mp.id = ml."SourceDataPk"
					LEFT JOIN job_res j      ON j.id = mp."JobResponse_id" AND j."SourceTableName" = 'suju'
					-- 외작: 수불전표등록에서 외작 입고 → 로트 입고등록한 LOT
					LEFT JOIN mat_inout mi   ON ml."SourceTableName" = 'mat_inout' AND mi.id = ml."SourceDataPk"
					LEFT JOIN balju b        ON mi."SourceTableName" = 'balju' AND b.id = mi."SourceDataPk"
					                        AND b."PlanTableName" = 'suju'
					JOIN suju ls             ON ls.id = COALESCE(j."SourceDataPk", b."PlanDataPk")
					JOIN shipment sm         ON sm.id = :smId
					LEFT JOIN suju ss        ON ss.id = sm."SourceDataPk"
					WHERE ml.id = :mlId
					""", vp);

			if (chk == null || chk.get("ship_suju_id") == null) continue;

			if (!String.valueOf(chk.get("lot_suju_id")).equals(String.valueOf(chk.get("ship_suju_id")))) {
				result.success = false;
				result.message = "[" + chk.get("lot_number") + "] 출하 지시와 다른 지그입니다. "
						+ "라벨: " + chk.get("lot_proj") + " " + chk.get("lot_item")
						+ " / 출하 지시: " + chk.get("ship_proj") + " " + chk.get("ship_item");
				return result;
			}

			/*
			 * ★ 도면 Rev 검증 (사업계획서: 출하 스캔 시 '품목/도면 Rev 일치 여부', 불일치 시 출하 차단)
			 *
			 *   도면은 품목 단위로 올라온다 (생산 지시 화면, attach_file TableName='suju_drawing').
			 *   파일 이름에서 도면번호 · 리비전을 읽는다: FD10-01-03-REV11.pdf → FD10-01-03 / 11
			 *   (화면 drawParse · iljin_drawing.js 와 같은 규칙).
			 *
			 *   판정: 이 지그 LOT 이 생긴 뒤(= 검사 합격 뒤) <b>리비전이 오른 도면</b>이 있으면 막는다.
			 *     - LOT 이 생기기 전에 이미 있던 도면번호만 본다. 처음 올라온 도면(늦게 올린 것)은 변경이 아니다.
			 *     - 같은 리비전을 다시 올린 것도 변경이 아니다.
			 *   막히면 검사 공정에서 새 도면으로 재검사(검사 취소 → 다시 합격)하고 새 라벨을 붙인다.
			 *   새 LOT 은 새 도면 이후에 생기므로 통과한다.
			 *
			 *   내작 LOT 만 본다. 외작은 업체가 만든 지그라 입고 LOT 이 검사 시점이 아니다.
			 */
			if ("mat_produce".equals(String.valueOf(chk.get("lot_src"))) && chk.get("lot_at") != null) {
				MapSqlParameterSource rp = new MapSqlParameterSource();
				rp.addValue("sujuId", chk.get("lot_suju_id"));
				rp.addValue("lotAt", chk.get("lot_at"));
				List<Map<String, Object>> changed = this.sqlRunner.getRows("""
						WITH f AS (
						    SELECT a._created
						         , REGEXP_REPLACE(a."FileName", '\\.[^.]*$', '') AS b0
						    FROM attach_file a
						    WHERE a."TableName" = 'suju_drawing' AND a."DataPk" = CAST(:sujuId AS integer)
						      AND a."AttachName" = 'drawing'
						), p AS (
						    SELECT _created
						         , CASE WHEN b0 ~* '[\\s_.-]*(REV|R|V)[\\s_.-]*[0-9]+$'
						                THEN UPPER(REGEXP_REPLACE(b0, '[\\s_.-]*(REV|R|V)[\\s_.-]*[0-9]+$', '', 'i'))
						                WHEN b0 ~ '^.+[-_][0-9]{2}[-_][0-9]{2}[-_]00$'
						                THEN UPPER(REGEXP_REPLACE(b0, '[-_]00$', ''))
						                ELSE UPPER(b0) END AS no
						         , CASE WHEN b0 ~* '(REV|R|V)[\\s_.-]*[0-9]+$'
						                THEN CAST(SUBSTRING(b0 FROM '([0-9]+)$') AS integer)
						                WHEN b0 ~ '^.+[-_][0-9]{2}[-_][0-9]{2}[-_]00$' THEN 0
						                ELSE -1 END AS rev
						    FROM f
						)
						SELECT no
						     , MAX(rev) FILTER (WHERE _created <= :lotAt) AS rev_at_lot
						     , MAX(rev)                                   AS rev_now
						FROM p
						GROUP BY no
						HAVING COUNT(*) FILTER (WHERE _created <= :lotAt) > 0
						   AND MAX(rev) > MAX(rev) FILTER (WHERE _created <= :lotAt)
						ORDER BY no
						""", rp);
				if (changed != null && !changed.isEmpty()) {
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < changed.size() && i < 3; i++) {
						Map<String, Object> c = changed.get(i);
						if (sb.length() > 0) sb.append(", ");
						sb.append(c.get("no")).append(" REV").append(c.get("rev_at_lot"))
								.append("→REV").append(c.get("rev_now"));
					}
					if (changed.size() > 3) sb.append(" 외 ").append(changed.size() - 3).append("건");
					result.success = false;
					result.message = "[" + chk.get("lot_number") + "] 검사 후 도면이 바뀐 지그입니다 (" + sb + "). "
							+ "새 도면으로 재검사하고 라벨을 다시 붙인 뒤 출하하세요.";
					return result;
				}
			}
		}
		//endregion

		//region 같은 LOT 중복 스캔 — 합계로 현재고 확인 (출하 처리 전에 막는다)
		/*
		 * ★ 같은 LOT 이 여러 줄로 오면 <b>합계</b>로 현재고를 확인한다.
		 *   아래 LOT 소모 루프는 줄마다 따로 비교해서, 현재고 2 인 LOT 이
		 *   2 + 2 로 두 번 통과해 4 가 출고될 수 있다 (같은 LOT 라벨을 두 장 붙여 각각 찍은 경우).
		 *   출하 처리(저장)보다 먼저 검사해야 실패 시 아무것도 남지 않는다.
		 */
		java.util.Map<Integer, Float> sumByLot = new java.util.HashMap<>();
		for (Map<String, Object> bc : BarcodeList) {
			if (bc.get("ml_id") == null || bc.get("assignedQty") == null) continue;
			Integer lid = Integer.parseInt(bc.get("ml_id").toString());
			Float q = Float.valueOf(bc.get("assignedQty").toString());
			sumByLot.merge(lid, q, Float::sum);
		}
		for (java.util.Map.Entry<Integer, Float> e : sumByLot.entrySet()) {
			Optional<MaterialLot> l = matLotRepository.findById(e.getKey());
			if (l.isPresent() && l.get().getCurrentStock() != null
					&& e.getValue() > l.get().getCurrentStock()) {
				result.success = false;
				result.message = "[" + l.get().getLotNumber() + "] 같은 LOT 을 중복 스캔했습니다. "
						+ "현재고(" + l.get().getCurrentStock() + ")보다 많이 출고할 수 없습니다.";
				return result;
			}
		}
		//endregion

		List<Shipment> smList = shipmentRepository.findByShipmentHeadId(sh_id);
		ShipmentHead sh = shipmentHeadRepository.findById(sh_id).orElse(null);

		//region : 출하처리 로직
		if (smList != null) {

			for (int i = 0; i < smList.size(); i++) {
				Shipment sm = smList.get(i);

				if (sm != null) {
					Double orderQty = sm.getOrderQty();
					sm.set_status("a");
					sm.set_audit(user);
					sm.setQty(orderQty);

					this.shipmentRepository.save(sm);

				}
			}
		}

		sh.setState("shipped");
		shipmentHeadRepository.save(sh);
		//TODO: 확인필요
		//shipmentDoBService.updateShipmentStateComplete(sh_id, "");

		shipmentDoBService.updateSujuShipmentState(sh_id);
		//endregion


		//region: mat_lot_cons , lot 소모 처리 관련 로직
		for(int i=0; i < BarcodeList.size(); i++){
			Integer ml_id = (Integer) BarcodeList.get(i).get("ml_id");
			Float quantity = Float.valueOf( BarcodeList.get(i).get("assignedQty").toString());

			Optional<MaterialLot> mat_lot = matLotRepository.findById(ml_id);

			if(mat_lot.isPresent()){
				Float currentStock = mat_lot.get().getCurrentStock();
				if(quantity > currentStock){
					result.success = false;
					result.message = "[" + mat_lot.get().getLotNumber() + "] : " +"현재고 보다 많은 량을 출하 할 수 없습니다.";
					return  result;
				}

			}else{
				result.success = false;
				result.message = "해당 LOT에 대한정보가 없습니다.";
				return result;
			}

			Integer shipmentId = Integer.parseInt(BarcodeList.get(i).get("shipment_id").toString());
			MatLotCons matlotcons = new MatLotCons();
			matlotcons.setMaterialLotId(ml_id);
			matlotcons.setSourceDataPk(shipmentId);
			matlotcons.setSourceTableName("shipment");

			matlotcons.setOutputDateTime(now);
			matlotcons.setOutputQty(quantity);
			matlotcons.set_audit(user);

			matLotConsRepository.save(matlotcons);

			shipmentDoBService.updateShipmentQantityByLotConsume(sh_id, shipmentId, sourceData);
		}
		//endregion

		return result;
	}

}