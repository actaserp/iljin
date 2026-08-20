package mes.app.pda.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class ProdPrepareApiService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 작업지시 목록 조회 (상태 = 'ordered' 인 것만)
     */
    public List<Map<String, Object>> jobOrderList(String dataDate, Integer workCenterPk, String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("dataDate", dataDate);
        param.addValue("workCenterPk", workCenterPk);
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select jr.id
                 , jr."WorkOrderNumber" as work_order_number
                 , jr."OrderQty" as order_qty
                 , jr."WorkIndex" as work_index
                 , to_char(jr."ProductionDate", 'yyyy-mm-dd') as production_date
                 , jr."State" as state
                 , case jr."State"
                     when 'ordered' then '지시'
                     when 'started' then '진행중'
                     when 'done'    then '완료'
                     else jr."State"
                   end as state_name
                 , m."Name" as mat_name
                 , m."Standard1" as standard
                 , u."Name" as unit_name
                 , wc."Name" as workcenter_name
                 , c."Name" as company_name
                 , jr."MaterialProcessInputRequest_id" as proc_input_req_id
            from job_res jr
            left join material m on m.id = jr."Material_id"
            left join unit u on u.id = m."Unit_id"
            left join work_center wc on wc.id = jr."WorkCenter_id"
            left join suju s on s.id = jr."SourceDataPk" and jr."SourceTableName" = 'suju'
            left join company c on c.id = s."Company_id"
            where jr."State" = 'ordered'
              and jr.spjangcd = :spjangcd
            """;

        if (StringUtils.hasText(dataDate))
            sql += " and to_char(jr.\"ProductionDate\", 'yyyy-mm-dd') = :dataDate ";
        if (workCenterPk != null)
            sql += " and jr.\"WorkCenter_id\" = :workCenterPk ";

        sql += " order by jr.\"WorkIndex\" asc nulls last, jr.id asc ";

        return sqlRunner.getRows(sql, param);
    }

    /**
     * 소요 자재 목록 조회 (BOM 기반)
     */
    public List<Map<String, Object>> bomDetailList(String jrPks, String dataDate) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("jrPks", jrPks);
        param.addValue("dataDate", dataDate);

        String sql = """
            select m.id as mat_pk
                 , m."Code" as mat_code
                 , m."Name" as mat_name
                 , mg."Name" as mat_group_name
                 , u."Name" as unit_name
                 , b."InputQty" as requ_qty
                 , coalesce(mih."CurrentStock", 0) as cur_stock
                 , coalesce(m."ProcessSafetyStock", 0) as proc_safety_stock
                 , 0 as input_req_qty
            from job_res jr
            inner join suju s on s.id = jr."SourceDataPk" and jr."SourceTableName" = 'suju'
            inner join bom b on b."Material_id" = s."Material_id"
                and (b."StartDate" is null or b."StartDate" <= current_date)
                and (b."EndDate"   is null or b."EndDate"   >= current_date)
            inner join material m on m.id = b."ChildMaterial_id"
            left join mat_grp mg on mg.id = m."MaterialGroup_id"
            left join unit u on u.id = m."Unit_id"
            left join mat_in_house mih on mih."Material_id" = m.id
                and mih."StoreHouse_id" = m."StoreHouse_id"
            where jr.id = any(string_to_array(:jrPks, ',')::int[])
            order by mg."Name", m."Name"
            """;

        return sqlRunner.getRows(sql, param);
    }

    /**
     * 워크센터 목록
     */
    public List<Map<String, Object>> getWorkcenterList(String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select id, "Name" as name
            from work_center
            where "Useyn" = '0'
            order by "Name"
            """;

        return sqlRunner.getRows(sql, param);
    }
}
