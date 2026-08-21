package mes.app.inventory.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class MaterialInoutService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getMaterialInout(String srchStartDt, String srchEndDt, String housePk,
																										String matType, String matGrpPk, String keyword, String spjangcd) {
		return getMaterialInout(srchStartDt, srchEndDt, housePk, matType, matGrpPk, keyword, spjangcd, null);
	}

	public List<Map<String, Object>> getMaterialInout(String srchStartDt, String srchEndDt, String housePk,
																										String matType, String matGrpPk, String keyword, String spjangcd, String inTestYn) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    -- 외작 입고 검사여부 (mat_inout_inspect). 입고 건 1:1 이라 스칼라 서브쿼리로 붙인다.
                    --  select distinct 를 쓰는 쿼리라 조인으로 붙이면 행이 늘 수 있다.
                    , (select case mii."InspectYN" when 'Y' then '검사'
                                                   when 'N' then '미검사' end
                         from mat_inout_inspect mii
                        where mii."MatInout_id" = mi.id) as inspect_yn
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    --left join mat_order mo on mi."MaterialOrder_id" = mo.id 
                    --and m.id = mo."Material_id" 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    --and sh."HouseType" = 'material'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if ("Y".equals(inTestYn)) sql +=" and m.\"InTestYN\" = 'Y' ";

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> getMaterialInoutReceipt(String srchStartDt, String srchEndDt, String housePk,
																													 String matType, String matGrpPk, String keyword, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    -- 외작 입고 검사여부 (mat_inout_inspect). 입고 건 1:1 이라 스칼라 서브쿼리로 붙인다.
                    --  select distinct 를 쓰는 쿼리라 조인으로 붙이면 행이 늘 수 있다.
                    , (select case mii."InspectYN" when 'Y' then '검사'
                                                   when 'N' then '미검사' end
                         from mat_inout_inspect mii
                        where mii."MatInout_id" = mi.id) as inspect_yn
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    --left join mat_order mo on mi."MaterialOrder_id" = mo.id 
                    --and m.id = mo."Material_id" 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    AND mi."InOut" IN ('in', 'return')
                    --and sh."HouseType" = 'material'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> getMaterialInoutIssue(String srchStartDt, String srchEndDt, String housePk,
																												 String matType, String matGrpPk, String keyword, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    AND mi."InOut" IN ('out', 'recall')
                    and mi."OutputType" != 'disposal_out'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> getMaterialInoutDisposal(String srchStartDt, String srchEndDt, String housePk,
																														String matType, String matGrpPk, String keyword, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "material_code"
                    , m."Name" as "material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."LotSize"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as material_type
                    --, coalesce(lot_cnt.lot_count,0) as lot_count
                    , (select count(ml."LotNumber") as lot_count 
                        from mat_lot ml 
                        where ml."SourceTableName" ='mat_inout' 
                        and ml."SourceDataPk" = mi.id
                        )  as lot_count 
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    where 1 = 1
                    and m."Useyn" = '0'
                    and mi."OutputType" = 'disposal_out'
                    and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
                    and mi.spjangcd = :spjangcd
				""";

		if (StringUtils.isEmpty(housePk)==false) sql +=" and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false) sql +=" and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql +=" and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by \"InoutDate\" desc, \"InoutTime\" desc, mi.id desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	/**
	 * 제품 반입전표 - 수동 등록 반품만 조회 (InOut='return', SourceTableName IS NULL)
	 */
	public List<Map<String, Object>> getProductReceiptSlipList(
		String srchStartDt, String srchEndDt, String housePk,
		String matType, String matGrpPk, String keyword, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
			select mi.id as mio_pk
			     , to_char(mi."InoutDate", 'yyyy-mm-dd') as "InoutDate"
			     , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
			     , fn_code_name('return_type', mi."InputType") as inout_type
			     , sh."Name" as store_house_name
			     , fn_code_name('mat_type', mg."MaterialType") as material_type
			     , m."Code" as material_code
			     , m."Name" as material_name
			     , u."Name" as unit_name
			     , coalesce(mi."InputQty", 0) as "InputQty"
			     , mih."CurrentStock" as "HouseStock"
			     , mi."Description"
			from mat_inout mi
			inner join material m on m.id = mi."Material_id"
			left join mat_grp mg on mg.id = m."MaterialGroup_id"
			left join store_house sh on sh.id = mi."StoreHouse_id"
			left join unit u on u.id = m."Unit_id"
			left join mat_in_house mih on mih."Material_id" = m.id and mih."StoreHouse_id" = mi."StoreHouse_id"
			where 1 = 1
			  and m."Useyn" = '0'
			  and mi."InOut" = 'return'
			  and mi."SourceTableName" is null
			  and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
			  and mi.spjangcd = :spjangcd
			""";

		if (StringUtils.isEmpty(housePk)==false)  sql += " and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false)   sql += " and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false)  sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false)   sql += " and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by mi.\"InoutDate\" desc, mi.\"InoutTime\" desc, mi.id desc ";

		return this.sqlRunner.getRows(sql, param);
	}

	/**
	 * 반출전표 - 수동 등록 반출만 조회 (SourceTableName is null)
	 * 출하/생산 자동 차감분 제외
	 */
	public List<Map<String, Object>> getOutboundSlipList(
		String srchStartDt, String srchEndDt, String housePk,
		String matType, String matGrpPk, String keyword, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
			select mi.id as mio_pk
			     , to_char(mi."InoutDate", 'yyyy-mm-dd') as "InoutDate"
			     , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
			     , fn_code_name('output_type', mi."OutputType") as inout_type
			     , sh."Name" as store_house_name
			     , fn_code_name('mat_type', mg."MaterialType") as material_type
			     , m."Code" as material_code
			     , m."Name" as material_name
			     , u."Name" as unit_name
			     , coalesce(mi."OutputQty", 0) as "OutputQty"
			     , mih."CurrentStock" as "HouseStock"
			     , mi."Description"
			from mat_inout mi
			inner join material m on m.id = mi."Material_id"
			left join mat_grp mg on mg.id = m."MaterialGroup_id"
			left join store_house sh on sh.id = mi."StoreHouse_id"
			left join unit u on u.id = m."Unit_id"
			left join mat_in_house mih on mih."Material_id" = m.id and mih."StoreHouse_id" = mi."StoreHouse_id"
			where 1 = 1
			  and m."Useyn" = '0'
			  and mi."InOut" = 'out'
			  and mi."SourceTableName" is null
			  and mi."InoutDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
			  and mi.spjangcd = :spjangcd
			""";

		if (StringUtils.isEmpty(housePk)==false)  sql += " and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matType)==false)   sql += " and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false)  sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false)   sql += " and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";

		sql += " order by mi.\"InoutDate\" desc, mi.\"InoutTime\" desc, mi.id desc ";

		return this.sqlRunner.getRows(sql, param);
	}

	/**
	 * 자재LOT현황 - material+mat_in_house 기준
	 * lot_only='Y': mat_lot에 등록된 품목만
	 * remain_only='Y': 현재고 > 0 인 것만
	 */
	public List<Map<String, Object>> getLotStatus(
		String matType, String matGrpPk, String housePk,
		String keyword, String remainOnly, String lotOnly, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("matType", matType);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("housePk", housePk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);

		String sql = """
			select m.id as material_id
			     , m."Code" as material_code
			     , m."Name" as material_name
			     , fn_code_name('mat_type', mg."MaterialType") as material_type
			     , mg."Name" as mat_grp_name
			     , u."Name" as unit_name
			     , sh."Name" as store_house_name
			     , ml."LotNumber" as lot_number
			     , coalesce(ml."InputQty", 0) as input_qty
			     , coalesce(ml."CurrentStock", 0) as lot_stock
			     , coalesce(mh."CurrentStock", 0) as current_stock
			     , to_char(ml."InputDateTime", 'yyyy-mm-dd') as input_date
			     , to_char(ml."EffectiveDate", 'yyyy-mm-dd') as effective_date
			     , ml."Description" as description
			     , m."LotUseYN" as lot_use_yn
			from material m
			inner join mat_grp mg on mg.id = m."MaterialGroup_id"
			left join unit u on u.id = m."Unit_id"
			left join mat_in_house mh on mh."Material_id" = m.id
			left join store_house sh on sh.id = mh."StoreHouse_id"
			left join mat_lot ml on ml."Material_id" = m.id
			    and ml."StoreHouse_id" = mh."StoreHouse_id"
			where 1 = 1
			  and m."Useyn" = '0'
			  and m."spjangcd" = :spjangcd
			""";

		if (StringUtils.isEmpty(matType)==false)  sql += " and mg.\"MaterialType\" = :matType ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(housePk)==false)  sql += " and mh.\"StoreHouse_id\" = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false)  sql += " and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if ("Y".equals(lotOnly))                  sql += " and exists (select 1 from mat_lot ml2 where ml2.\"Material_id\" = m.id) ";
		if ("Y".equals(remainOnly))               sql += " and coalesce(mh.\"CurrentStock\", 0) > 0 ";

		sql += " order by mg.\"MaterialType\", mg.\"Name\", m.\"Code\", ml.\"InputDateTime\" desc nulls last ";

		return this.sqlRunner.getRows(sql, param);
	}

	/**
	 * 수입검사현황 - test_result 기준 검사 이력 조회
	 * 검사일 기준, 판정/품목그룹/품명 필터
	 */
	public List<Map<String, Object>> getInspectionHistory(
		String srchStartDt, String srchEndDt, String matGrpPk,
		String keyword, String judge, String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("judge", judge);
		param.addValue("spjangcd", spjangcd);

		String sql = """
			select tr.id as test_result_id
			     , to_char(tr."TestDateTime", 'yyyy-mm-dd') as test_date
			     , up."Name" as checker_name
			     , tir."JudgeCode" as judge_code
			     , tir."CharResult" as test_remark
			     , m.id as "Material_id"
			     , m."Code" as material_code
			     , m."Name" as material_name
			     , fn_code_name('mat_type', mg."MaterialType") as material_type
			     , u."Name" as unit_name
			     , coalesce(mi."InputQty", 0) as "InputQty"
			     , to_char(mi."InoutDate", 'yyyy-mm-dd') as "InoutDate"
			     , sh."Name" as store_house_name
			     , b."CompanyName" as company_name
			     , to_char(b."JumunDate", 'yyyy-mm-dd') as balju_date
			from test_result tr
			inner join mat_inout mi on tr."SourceDataPk" = mi.id and tr."SourceTableName" = 'mat_inout'
			inner join material m on m.id = mi."Material_id"
			left join mat_grp mg on mg.id = m."MaterialGroup_id"
			left join unit u on u.id = m."Unit_id"
			left join store_house sh on sh.id = mi."StoreHouse_id"
			left join balju b on b.id = mi."SourceDataPk" and mi."SourceTableName" = 'balju'
			left join user_profile up on up."User_id" = tr."_creater_id"
			left join test_item_result tir on tir."TestResult_id" = tr.id
			where 1 = 1
			  and m."Useyn" = '0'
			  and tr.spjangcd = :spjangcd
			  and tr."TestDateTime" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
			""";

		if (StringUtils.isEmpty(matGrpPk)==false) sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false)  sql += " and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if (StringUtils.isEmpty(judge)==false)    sql += " and tir.\"JudgeCode\" = :judge ";

		sql += " order by tr.\"TestDateTime\" desc, tr.id desc ";

		return this.sqlRunner.getRows(sql, param);
	}

	/**
	 * 수입검사 대상 목록 (발주 기준 - 미입고 품목도 표시)
	 * - 발주(balju) 드라이빙, mat_inout LEFT JOIN → 입고 전이어도 표시
	 * - inTestYn='Y': InTestYN='Y' 품목만, 그 외: 전체 발주
	 * - status: ''=전체, 'waiting'=검사대기(미입고 포함), 'done'=검사완료
	 */
	public List<Map<String, Object>> getReceivingInspectionList(
		String srchStartDt, String srchEndDt, String housePk,
		String matGrpPk, String keyword, String status, String inTestYn, String spjangcd) {
		return getReceivingInspectionList(srchStartDt, srchEndDt, housePk, matGrpPk, keyword, status, inTestYn, spjangcd, null);
	}

	public List<Map<String, Object>> getReceivingInspectionList(
		String srchStartDt, String srchEndDt, String housePk,
		String matGrpPk, String keyword, String status, String inTestYn, String spjangcd, String judge) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("srchStartDt", srchStartDt);
		param.addValue("srchEndDt", srchEndDt);
		param.addValue("housePk", housePk);
		param.addValue("matGrpPk", matGrpPk);
		param.addValue("keyword", keyword);
		param.addValue("spjangcd", spjangcd);
		param.addValue("judge", judge);

		String sql = """
			select b.id as balju_id
			     , mi.id as mio_pk
			     , b."Material_id"
			     , m."Code" as material_code
			     , m."Name" as material_name
			     , u2."Name" as unit_name
			     , fn_code_name('mat_type', mg."MaterialType") as material_type
			     , mg."Name" as mat_grp_name
			     , b."CompanyName" as company_name
			     , to_char(b."JumunDate", 'yyyy-mm-dd') as balju_date
			     , to_char(b."DueDate", 'yyyy-mm-dd') as due_date
			     , b."SujuQty" as balju_qty
			     , coalesce(mi."PotentialInputQty", 0) as "potentialInputQty"
			     , coalesce(mi."InputQty", 0) as "InputQty"
			     , (b."SujuQty" - coalesce(mi_sum."total_input", 0)) as remain_qty
			     , to_char(mi."InoutDate", 'yyyy-mm-dd') as "InoutDate"
			     , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
			     , sh."Name" as store_house_name
			     , coalesce(fn_code_name('inout_state', mi."State"), '미입고') as inout_state
			     , mi."State" as state_code
			     , m."ValidDays"
			     , m."InTestYN" as in_test_yn
			     , tir."JudgeCode" as judge_code
			     , case when mi.id is null then '미입고'
			            when tr.id is not null then '검사완료'
			            else '검사대기' end as test_state
			     , to_char(tr."TestDateTime", 'yyyy-mm-dd') as test_date
			from balju b
			inner join material m on m.id = b."Material_id"
			left join mat_grp mg on mg.id = m."MaterialGroup_id"
			left join unit u2 on m."Unit_id" = u2.id
			left join mat_inout mi on mi."SourceDataPk" = b.id
			    and mi."SourceTableName" = 'balju'
			    and mi."InOut" = 'in'
			    and coalesce(mi."_status", 'a') = 'a'
			left join store_house sh on sh.id = mi."StoreHouse_id"
			left join test_result tr on tr."SourceDataPk" = mi.id and tr."SourceTableName" = 'mat_inout'
			left join test_item_result tir on tr.id = tir."TestResult_id"
			left join (
			    select "SourceDataPk", sum("InputQty") as total_input
			    from mat_inout
			    where "SourceTableName" = 'balju' and "InOut" = 'in' and coalesce("_status",'a') = 'a'
			    group by "SourceDataPk"
			) mi_sum on mi_sum."SourceDataPk" = b.id
			where 1 = 1
			  and m."Useyn" = '0'
			  and b."JumunDate" between cast(:srchStartDt as date) and cast(:srchEndDt as date)
			  and b.spjangcd = :spjangcd
			""";

		if ("Y".equals(inTestYn)) sql += " and m.\"InTestYN\" = 'Y' ";
		if (StringUtils.isEmpty(housePk)==false)  sql += " and sh.id = cast(:housePk as Integer) ";
		if (StringUtils.isEmpty(matGrpPk)==false) sql += " and m.\"MaterialGroup_id\" = cast(:matGrpPk as Integer) ";
		if (StringUtils.isEmpty(keyword)==false)  sql += " and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if (StringUtils.isEmpty(judge)==false)    sql += " and tir.\"JudgeCode\" = :judge ";

		if ("waiting".equals(status)) {
			// 검사대기: 입고됐으나 검사결과 없음
			sql += " and mi.id is not null and tr.id is null ";
		} else if ("done".equals(status)) {
			sql += " and tr.id is not null ";
		} else if ("not_received".equals(status)) {
			// 미입고: 아직 입고 자체가 없는 발주
			sql += " and mi.id is null ";
		}

		sql += " order by b.\"JumunDate\" desc, mi.\"InoutDate\" desc nulls last, b.id desc ";

		return this.sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getMaterialInoutDetail(Integer mio_pk) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mio_pk", mio_pk);

		String sql = """
					select distinct mi.id as mio_pk
                    , fn_code_name('inout_type', mi."InOut") as inout
                    , mi."InOut" as "inoutSelect"
					, mg."Name" as "cboMaterialGroupName"
					, mg."id" as "cboMaterialGroup"
					, COALESCE(NULLIF(mi."InputType", ''), NULLIF(mi."OutputType", '')) AS "InoutType"
					, to_char(mi."InoutDate", 'yyyy-mm-dd') || 'T' || to_char(mi."InoutTime", 'hh24:mi') as "inoutDate"
					,COALESCE(
						   NULLIF(mi."InputQty", 0),
						   NULLIF(mi."OutputQty", 0),
						   NULLIF(mi."PotentialInputQty", 0),
						   0
						 ) AS "InoutQty"
					, mg."MaterialType" as "cboMaterialType"
                    , mi."Material_id"
                    , mi."InputType" 
                    , mi."OutputType" 
                    , case when mi."InOut" = 'in' then fn_code_name('input_type', mi."InputType") 
	                    when mi."InOut" = 'out' then fn_code_name('output_type', mi."OutputType") 
	                    when mi."InOut" = 'recall' then fn_code_name('recall_type', mi."OutputType")
	                    when mi."InOut" = 'return' then fn_code_name('return_type', mi."InputType")
	                    end as inout_type
                    , to_char(mi."InoutDate",'yyyy-mm-dd ') as "InoutDate"
                    , to_char(mi."InoutTime", 'hh24:mi') as "InoutTime"
                    , sh."Name" as "store_house_name"
                    , m."Code" as "Material_code"
                    , m."Name" as "Material_name"
                    , m."CurrentStock" 
                    , m."ValidDays"
                    , m."PackingUnitQty"
                    , mi."StoreHouse_id"
                    , mih2."CurrentStock" as "HouseStock"
                    , m."SafetyStock" 
                    , coalesce(mi."InputQty", 0) as "InputQty"
                    , coalesce(mi."OutputQty", 0) as "OutputQty"
                    , u2."Name" as "unit_name"
                    , mi."Description" 
                    , fn_code_name('mat_type', mg."MaterialType") as "cboMaterialTypeName"
                    , coalesce(mi."PotentialInputQty",0) as "potentialInputQty"
                    , fn_code_name('inout_state', mi."State" ) as "inout_state"
                    , var."StateName" as "state_name"
                    , tir."JudgeCode" as judge_code
                    , m."LotUseYN" as lot_use
                    , mi."Company_id" as "cboCompany"
                    , c."Name" as "CompanyName"
                    from mat_inout mi 
                    inner join material m on mi."Material_id" = m.id
                    left join mat_grp mg on mg.id = m."MaterialGroup_id"
                    inner join store_house sh on mi."StoreHouse_id" = sh.id
                    left join unit u2 on m."Unit_id" = u2.id 
                    left join mat_in_house mih2 on mih2."Material_id"  = m.id
                    and mih2."StoreHouse_id" = mi."StoreHouse_id"
                    left join rela_data rd on mi.id = rd."DataPk2" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName2"  = 'mat_inout'
                    left join bundle_head bh on bh.id = rd."DataPk1" and rd."RelationName" = 'mat_inout_test_result' and rd."TableName1"  = 'bundle_head'
                    left join v_appr_result var on var."SourceDataPk" = bh.id and var."SourceTableName" ='bundle_head'
                    left join test_result tr on tr."SourceDataPk"  = mi.id and tr."SourceTableName" = 'mat_inout'
                    left join test_item_result tir on tr.id = tir."TestResult_id"
                    left join company c on c.id= mi."Company_id"
                    where 1 = 1
                    and m."Useyn" = '0'
					and mi.id = :mio_pk
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> mioLotList(String mioId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
            select 
            mi.id as mio_id
            , ml.id as ml_id
            , ml."LotNumber" 
            , m."Name" as "MaterialName"
            , m."Code" as "MaterialCode" 
            , mg."Name" as "MaterialGroupName" 
            , m."MaterialGroup_id" 
            , m."Unit_id" 
            , m."ValidDays" 
            , u."Name" as "UnitName"
            , ml."InputQty"
            , m."Thickness"
            , m."Width"
            , m."Length"
            , to_char(ml."InputDateTime",'yyyy-MM-dd hh24:mi:ss') as "InputDateTime"
            , to_char(ml."EffectiveDate",'yyyy-MM-dd') as "EffectiveDate"
            , ml."Description"
            , ml."StoreHouse_id" as store_house_id
            from mat_lot ml  
                left join material m on m.id = ml."Material_id"
                left join mat_grp mg on mg.id = m."MaterialGroup_id" 
                left join unit u on u.id = m."Unit_id" 
                left join mat_inout mi on ml."SourceDataPk" = mi.id and ml."SourceTableName" ='mat_inout'
            where mi.id = cast(:mioId as Integer) 
			""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);
		return items;
	}

	public List<Map<String, Object>> mioTestList(Integer mioId, Integer testResultId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);
		param.addValue("testResultId", testResultId);

		String sql = """
				select ti.id, up."Name" as "CheckName", ti."ResultType" as "resultType", to_char(tir."TestDateTime", 'YYYY-MM-DD') as "testDate"
				, tir."JudgeCode", tir."CharResult" , ti."Name" as name ,tir."Char1" as result1
				, tr.id as "testResultId", tr."TestMaster_id" as "testMasterId"
				from test_item_result tir
				inner join test_result tr on tr.id = tir."TestResult_id"
				inner join test_item ti on tir."TestItem_id"  = ti.id 
				inner join user_profile up on tir."_creater_id"  = up."User_id" 
				where tr."SourceTableName" = 'mat_inout' and tr."SourceDataPk" = :mioId
				and tr.id= :testResultId
				order by ti.id
				""";



		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public Integer getTestMasterByItem(Integer mioId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
                    SELECT tmm."TestMaster_id" AS testMasterId
                            FROM mat_inout mi
                            INNER JOIN test_mast_mat tmm ON mi."Material_id" = tmm."Material_id"
                            WHERE mi.id = :mioId
                            LIMIT 1
                """;

		List<Map<String, Object>> result = this.sqlRunner.getRows(sql, param);
		return result.isEmpty() ? null : (Integer) result.get(0).get("testMasterId");
	}

	public List<Map<String, Object>> prodTestListByTestMaster(Integer testMasterId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("testMasterId", testMasterId);

		String sql = """
                    SELECT tm.id AS testMasterId, ti.id, ti."Name" AS name, ti."ResultType" AS "resultType",
                           tim."SpecText" AS "specText", '' AS result1
                    FROM test_item_mast tim
                    INNER JOIN test_mast tm ON tim."TestMaster_id" = tm.id
                    INNER JOIN test_item ti ON tim."TestItem_id" = ti.id
                    WHERE tm.id = :testMasterId
                """;

		return this.sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> mioTestDefaultList() {

		String sql = """
				select ti.id,ti."Name" as name, ti."ResultType" as "resultType", '' as result1
				from test_item ti
				inner join test_method tm on ti."TestMethod_id"  = tm.id 
				where tm."Code"  = 'inout_test'
				order by ti.id
			    """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, null);

		return items;
	}

	public Map<String, Object> getEffectDate(Integer mioId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
				select (case when mi."EffectiveDate" = null then null else to_char(mi."EffectiveDate", 'YYYY-MM-DD') end)  as "EffectiveDate"
				from mat_inout mi 
				inner join material m on m.id = mi."Material_id"
				where mi.id = :mioId
				""";

		Map<String,Object> items = this.sqlRunner.getRow(sql, param);

		return items;
	}

	/**
	 * 발주 입고 대상 목록.
	 * @param baljuType 'outsource' = 외작만 / 'balju' = 외작 제외(NULL 포함) / null = 전체
	 */
	public List<Map<String, Object>> getBaljuList(Timestamp start, Timestamp end, String spjangcd, String baljuType) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("start", start);
		dicParam.addValue("end", end);
		dicParam.addValue("spjangcd", spjangcd);

		String sql = """
        select b.id
          , b."JumunNumber"
          , b."Material_id" as "Material_id"
          , mg."Name" as "MaterialGroupName"
          , mg.id as "MaterialGroup_id"
          , fn_code_name('mat_type', mg."MaterialType") as "MaterialTypeName"
          , m.id as "Material_id"
          , m."Code" as product_code
          , m."Name" as product_name
          , u."Name" as unit
          , b."Standard" as standard
          , b."SujuQty" as "SujuQty"
          , to_char(b."JumunDate", 'yyyy-mm-dd') as "JumunDate"
          , to_char(b."DueDate", 'yyyy-mm-dd') as "DueDate"
          , b."CompanyName"
          , b."Company_id"
          , b."SujuType"
          , fn_code_name('Balju_type', b."SujuType") as "BaljuTypeName"
          , to_char(b."ProductionPlanDate", 'yyyy-mm-dd') as production_plan_date
          , to_char(b."ShipmentPlanDate", 'yyyy-mm-dd') as shiment_plan_date
          , b."Description"
          , b."AvailableStock" as "AvailableStock"
          , b."ReservationStock" as "ReservationStock"
          , COALESCE(mi."SujuQty2", 0) AS "SujuQty2"
          , COALESCE(mi."PreInputQty", 0) AS "PreInputQty"
          , fn_code_name('balju_state', b."State") as "StateName"
          , fn_code_name('shipment_state', b."ShipmentState") as "ShipmentStateName"
          , b."State"
          , to_char(b."_created", 'yyyy-mm-dd') as create_date
          , b.spjangcd
          , case b."PlanTableName" when 'prod_week_term' then '주간계획' when 'bundle_head' then '임의계획' when 'suju' then '수주' else b."PlanTableName" end as plan_state
          from balju b
          inner join material m on m.id = b."Material_id"
          inner join mat_grp mg on mg.id = m."MaterialGroup_id"
          left join unit u on m."Unit_id" = u.id
          left join company c on c.id= b."Company_id"
          LEFT JOIN (
			   SELECT
				   "SourceDataPk",
				   -- 확정 입고 수량 (_status = 'a')
				   SUM(CASE WHEN COALESCE("_status", 'a') = 'a'
							THEN COALESCE("InputQty", 0) ELSE 0 END) AS "SujuQty2",
				   -- 가입고 수량 (입고검사 대기, _status = 't')
				   SUM(CASE WHEN COALESCE("_status", 'a') = 't'
							THEN COALESCE("PotentialInputQty", 0) ELSE 0 END) AS "PreInputQty"
			   FROM mat_inout
			   WHERE "SourceTableName" = 'balju'
				 AND COALESCE("_status", 'a') IN ('a', 't')
				 AND "InOut" = 'in'
			   GROUP BY "SourceDataPk"
		   ) mi ON mi."SourceDataPk" = b.id
          where 1 = 1
          and b."JumunDate" between :start and :end 
          -- 확정 입고 + 가입고 합계가 발주수량 이상이면 목록에서 제외
          AND COALESCE(mi."SujuQty2", 0) + COALESCE(mi."PreInputQty", 0) < b."SujuQty"
          and b.spjangcd = :spjangcd
          and "State" != 'force_completion'
          -- 외작 입고 / 발주 입고 분리. SujuType 이 NULL 인 건은 발주 입고 쪽에 둔다
          AND (
                CAST(:baljuType AS varchar) IS NULL
             OR (CAST(:baljuType AS varchar) = 'outsource' AND b."SujuType" = 'outsource')
             OR (CAST(:baljuType AS varchar) = 'balju'     AND COALESCE(b."SujuType", '') <> 'outsource')
          )
			order by b."JumunDate" desc,  m."Name"
			""";

		dicParam.addValue("baljuType", baljuType);

//    log.info("발주 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
		List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

		return itmes;
	}

	public List<Map<String, Object>> getBaljuInList(Timestamp start, Timestamp end, String spjangcd, Integer choComp, String keyword) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("start", start);
		dicParam.addValue("end", end);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("choComp", choComp);
		dicParam.addValue("keyword", keyword);

		String sql = """
        select b.id
          , b."JumunNumber"
          , b."Material_id" as "Material_id"
          , mg."Name" as "MaterialGroupName"
          , mg.id as "MaterialGroup_id"
          , fn_code_name('mat_type', mg."MaterialType") as "MaterialTypeName"
          , m.id as "Material_id"
          , m."Code" as product_code
          , m."Name" as product_name
          , u."Name" as unit
          , b."SujuQty" as "SujuQty"
          , to_char(b."JumunDate", 'yyyy-mm-dd') as "JumunDate"
          , to_char(b."DueDate", 'yyyy-mm-dd') as "DueDate"
          , b."CompanyName"
          , b."Company_id"
          , b."SujuType"
          , fn_code_name('Balju_type', b."SujuType") as "BaljuTypeName"
          , to_char(b."ProductionPlanDate", 'yyyy-mm-dd') as production_plan_date
          , to_char(b."ShipmentPlanDate", 'yyyy-mm-dd') as shiment_plan_date
          , b."Description"
          , b."AvailableStock" as "AvailableStock"
          , b."ReservationStock" as "ReservationStock"
          , COALESCE(mi."SujuQty2", 0) AS "SujuQty2"
          , COALESCE(mi_return."ReturnQty", 0) AS "ReturnQty"
          , fn_code_name('balju_state', b."State") as "StateName"
          , fn_code_name('shipment_state', b."ShipmentState") as "ShipmentStateName"
          , b."State"
          , to_char(b."_created", 'yyyy-mm-dd') as create_date
          , case b."PlanTableName" when 'prod_week_term' then '주간계획' when 'bundle_head' then '임의계획' when 'suju' then '수주' else b."PlanTableName" end as plan_state
          from balju b
          inner join material m on m.id = b."Material_id"
          inner join mat_grp mg on mg.id = m."MaterialGroup_id"
          left join unit u on m."Unit_id" = u.id
          left join company c on c.id= b."Company_id"
          LEFT JOIN (
			   SELECT
				   "SourceDataPk",
				   SUM("InputQty") AS "SujuQty2"
			   FROM mat_inout
			   WHERE "SourceTableName" = 'balju'
				 AND COALESCE("_status", 'a') = 'a'
				 AND "InOut" = 'in'
			   GROUP BY "SourceDataPk"
		   ) mi ON mi."SourceDataPk" = b.id
		  LEFT JOIN (
			 SELECT
				 "SourceDataPk",
				 SUM("InputQty") AS "ReturnQty"
			 FROM mat_inout
			 WHERE "SourceTableName" = 'balju'
			   AND COALESCE("_status", 'a') = 'a'
			   AND "InOut" = 'return'
			 GROUP BY "SourceDataPk"
		 ) mi_return ON mi_return."SourceDataPk" = b.id
          where 1 = 1
          and b."JumunDate" between :start and :end 
          AND COALESCE(mi."SujuQty2", 0) > 0
          and b.spjangcd = :spjangcd
         """;

		if (StringUtils.isEmpty(keyword)==false) sql +=" and upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') ";
		if(choComp != null) {
			sql += """ 
					and b."Company_id" = :choComp
					""";
		}

		sql += " order by b.\"JumunDate\" desc,  m.\"Name\" ";

//    log.info("발주 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
		List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

		return itmes;
	}

}