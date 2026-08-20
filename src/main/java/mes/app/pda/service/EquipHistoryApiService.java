package mes.app.pda.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class EquipHistoryApiService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 설비 목록
     */
    public List<Map<String, Object>> getEquipList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select e.id, e."Code" as code, e."Name" as name,
                   eg."Name" as group_name
            from equ e
            left join equ_grp eg on eg.id = e."EquipmentGroup_id"
            where e.spjangcd = :spjangcd
            order by eg."Name", e."Code"
            """;
        return sqlRunner.getRows(sql, param);
    }

    /**
     * 설비이력 목록
     */
    public List<Map<String, Object>> getList(String startDate, String endDate, Integer equId, String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("startDate", startDate);
        param.addValue("endDate", endDate);
        param.addValue("equId", equId);
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select eh.id
                 , bh.id as bh_id
                 , to_char(eh."DataDate", 'yyyy-mm-dd') as data_date
                 , e."Code" as equ_code
                 , e."Name" as equ_name
                 , eg."Name" as group_name
                 , eh."Content" as content
                 , eh."Description" as description
                 , eh."Cost" as cost
                 , eh."Char1" as manager
                 , to_char(eh._created, 'yyyy-mm-dd hh24:mi') as reg_date
            from equipment_history eh
            inner join equ e on e.id = eh."Equipment_id"
            left join equ_grp eg on eg.id = e."EquipmentGroup_id"
            left join bundle_head bh on bh.id = eh."ApprDataPk" and eh."ApprTableName" = 'bundle_head'
            where 1 = 1
              and eh._status = 'history'
            """;

        if (StringUtils.hasText(startDate)) sql += " and eh.\"DataDate\" >= cast(:startDate as date) ";
        if (StringUtils.hasText(endDate))   sql += " and eh.\"DataDate\" <= cast(:endDate as date) ";
        if (equId != null)                  sql += " and eh.\"Equipment_id\" = :equId ";

        sql += " order by eh.\"DataDate\" desc, eh.id desc ";

        return sqlRunner.getRows(sql, param);
    }
}
