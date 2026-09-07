package mes.app.pda.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
public class ReceiveApiService {


    @Autowired
    SqlRunner sqlRunner;


    /**
     * PDA 발주 입고 대상 목록.
     *
     * @param baljuType 'outsource' = 외작만 / 'balju' = 외작 제외 / null = 전체.
     *                  웹 수불전표등록(rp_input.html)이 발주입고와 외작입고를 나누는 기준과 같다
     *                  (MaterialInoutService.getBaljuList 참조). 외작만 검사여부를 함께 남긴다.
     */
    public List<Map<String, Object>> getPdaBaljuList(Timestamp start, Timestamp end, String spjangcd,
                                                     String baljuType) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("start", start);
        dicParam.addValue("end", end);
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("baljuType",
                (baljuType == null || baljuType.isBlank()) ? null : baljuType.trim());

        String sql = """
        select b.id
          , b."JumunNumber"
          , b."Material_id" as "Material_id"
          , mg."Name" as "MaterialGroupName"
          , mg.id as "MaterialGroup_id"
          , fn_code_name('mat_type', mg."MaterialType") as "MaterialTypeName"
          , m.id as "Material_id_mat"
          , m."Code" as product_code
          , m."Name" as product_name
          , u."Name" as unit
          , b."SujuQty"::integer as "SujuQty"
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
          , COALESCE(mi."SujuQty2", 0)::integer AS "SujuQty2"
          , fn_code_name('balju_state', b."State") as "StateName"
          , fn_code_name('shipment_state', b."ShipmentState") as "ShipmentStateName"
          , b."State"
          , to_char(b."_created", 'yyyy-mm-dd') as create_date
          , case b."PlanTableName" when 'prod_week_term' then '주간계획' when 'bundle_head' then '임의계획' else b."PlanTableName" end as plan_state
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
          where 1 = 1
          and b."JumunDate" between :start and :end 
          AND COALESCE(mi."SujuQty2", 0) < b."SujuQty"
          and b.spjangcd = :spjangcd
          and "State" != 'force_completion'
          -- 외작/발주 구분. SujuType 이 비어 있는 건도 있어 'balju' 쪽에 포함시킨다
          -- (IS DISTINCT FROM 은 NULL 도 '외작이 아님'으로 친다)
          and (CAST(:baljuType AS varchar) IS NULL
               OR (CAST(:baljuType AS varchar) = 'outsource' AND b."SujuType" = 'outsource')
               OR (CAST(:baljuType AS varchar) = 'balju' AND b."SujuType" IS DISTINCT FROM 'outsource'))
			order by b."JumunDate" desc,  m."Name"
			""";

//    log.info("발주 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
        List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

        return itmes;
    }
}
