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
     * 발주 입고 대상 목록의 공통 SELECT (where 1 = 1 까지).
     *
     * 기간 조회와 발주번호(QR) 조회가 같은 열을 내려줘야 앱의 ReceiveDto 가 양쪽에서 그대로 쓰인다.
     * 열을 고칠 일이 생기면 여기만 고친다 — 둘로 갈라두면 한쪽만 고쳐져 화면에 빈칸이 생긴다.
     */
    private static final String BALJU_SELECT = """
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
        """;


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

        String sql = BALJU_SELECT + """
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

    /**
     * 발주번호(발주서 QR)로 입고 대상 품목을 가져온다.
     *
     * e메일 발주서(balju_order.html)가 메일 보낼 때 발주번호를 QR 로 찍어 넣는다(iljin_label.js qrDataUrl).
     * PDA 가 그 QR 을 읽어 이 조회를 부르면 그 발주서의 미입고 품목만 내려간다.
     *
     * 화면의 조회기간과 발주/외작 구분은 보지 않는다 — QR 은 발주서 한 장을 가리키는 것이어서
     * 조회조건과 어긋나도 그 발주서대로 불러와야 한다(발주일이 조회기간 밖인 건이 실제로 있다).
     * 미입고 조건과 강제완료 제외는 기간 조회와 같게 유지한다.
     */
    public List<Map<String, Object>> getPdaBaljuByJumun(String jumunNumber, String spjangcd) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("jumunNumber", jumunNumber);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = BALJU_SELECT + """
          and b."JumunNumber" = :jumunNumber
          and b.spjangcd = :spjangcd
          AND COALESCE(mi."SujuQty2", 0) < b."SujuQty"
          and "State" != 'force_completion'
          order by m."Name"
        """;

        return this.sqlRunner.getRows(sql, dicParam);
    }

    /**
     * 발주번호 한 건의 머리 정보. 앱이 '없는 발주번호'와 '전량 입고된 발주번호'를 갈라 안내하는 데 쓴다.
     * 행이 없으면 그 발주번호가 아예 없는 것이고, remain_cnt 가 0 이면 전량 입고된 것이다.
     */
    public Map<String, Object> getBaljuJumunSummary(String jumunNumber, String spjangcd) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("jumunNumber", jumunNumber);
        dicParam.addValue("spjangcd", spjangcd);

        String sql = """
            select b."JumunNumber"
                 , max(b."CompanyName")                        as "CompanyName"
                 , to_char(max(b."JumunDate"), 'yyyy-mm-dd')    as "JumunDate"
                 , count(*)::integer                           as total_cnt
                 , sum(case when b."State" != 'force_completion'
                             and COALESCE(mi."SujuQty2", 0) < b."SujuQty"
                            then 1 else 0 end)::integer        as remain_cnt
              from balju b
              left join (
                   select "SourceDataPk", sum("InputQty") as "SujuQty2"
                     from mat_inout
                    where "SourceTableName" = 'balju'
                      and coalesce("_status", 'a') = 'a'
                      and "InOut" = 'in'
                    group by "SourceDataPk"
              ) mi on mi."SourceDataPk" = b.id
             where b."JumunNumber" = :jumunNumber
               and b.spjangcd = :spjangcd
             group by b."JumunNumber"
            """;

        return this.sqlRunner.getRow(sql, dicParam);
    }
}
