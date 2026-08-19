package mes.app.quality.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class QualityDefectSlipService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 불량전표 목록 조회
     */
    public List<Map<String, Object>> getList(
            String start, String end, String houseId,
            String state, String keyword, String spjangcd) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("start", start);
        param.addValue("end", end);
        param.addValue("houseId", houseId);
        param.addValue("state", state);
        param.addValue("keyword", keyword);
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select qd.id
                 , qd."DefectNo" as defect_no
                 , to_char(qd."DefectDate", 'yyyy-mm-dd') as defect_date
                 , sh."Name" as house_name
                 , qd."Process" as process
                 , m."Code" as material_code
                 , m."Name" as material_name
                 , fn_code_name('mat_type', mg."MaterialType") as material_type
                 , u."Name" as unit_name
                 , qd."BadQty" as bad_qty
                 , fn_code_name('defect_type', qd."BadType") as bad_type_name
                 , qd."BadType" as bad_type
                 , qd."BadReason" as bad_reason
                 , fn_code_name('defect_disposal', qd."Disposal") as disposal_name
                 , qd."Disposal" as disposal
                 , qd."Manager" as manager
                 , qd."Description" as description
                 , qd."State" as state
                 , case when qd."State" = 'confirmed' then '확정' else '작성중' end as state_name
                 , to_char(qd."_created", 'yyyy-mm-dd hh24:mi') as reg_date
            from quality_defect qd
            inner join material m on m.id = qd."Material_id"
            left join mat_grp mg on mg.id = m."MaterialGroup_id"
            left join unit u on u.id = m."Unit_id"
            left join store_house sh on sh.id = qd."StoreHouse_id"
            where 1 = 1
              and qd.spjangcd = :spjangcd
              and qd."DefectDate" between cast(:start as date) and cast(:end as date)
            """;

        if (StringUtils.hasText(houseId)) sql += " and qd.\"StoreHouse_id\" = cast(:houseId as Integer) ";
        if (StringUtils.hasText(state))   sql += " and qd.\"State\" = :state ";
        if (StringUtils.hasText(keyword)) sql += " and (upper(m.\"Name\") like concat('%%',upper(:keyword),'%%') or upper(qd.\"DefectNo\") like concat('%%',upper(:keyword),'%%')) ";

        sql += " order by qd.\"DefectDate\" desc, qd.id desc ";

        return this.sqlRunner.getRows(sql, param);
    }

    /**
     * 불량전표 저장 (신규/수정) - 전표번호 자동채번
     */
    public Map<String, Object> save(
            String id, String defectDate, String houseId, String process,
            String materialId, String badQty, String badType, String badReason,
            String disposal, String manager, String description,
            String spjangcd, int createrId) {

        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("defectDate", defectDate);
        param.addValue("houseId", StringUtils.hasText(houseId) ? Integer.parseInt(houseId) : null);
        param.addValue("process", process);
        param.addValue("materialId", StringUtils.hasText(materialId) ? Integer.parseInt(materialId) : null);
        param.addValue("badQty", StringUtils.hasText(badQty) ? Double.parseDouble(badQty.replace(",","")) : 0);
        param.addValue("badType", badType);
        param.addValue("badReason", badReason);
        param.addValue("disposal", disposal);
        param.addValue("manager", manager);
        param.addValue("description", description);
        param.addValue("spjangcd", spjangcd);
        param.addValue("createrId", createrId);

        String sql;
        if (StringUtils.hasText(id)) {
            // 수정
            param.addValue("id", Integer.parseInt(id));
            sql = """
                update quality_defect set
                    "DefectDate"    = cast(:defectDate as date),
                    "StoreHouse_id" = :houseId,
                    "Process"       = :process,
                    "Material_id"   = :materialId,
                    "BadQty"        = :badQty,
                    "BadType"       = :badType,
                    "BadReason"     = :badReason,
                    "Disposal"      = :disposal,
                    "Manager"       = :manager,
                    "Description"   = :description,
                    "_modified"     = now(),
                    "_modifier_id"  = :createrId
                where id = :id
                """;
            this.sqlRunner.execute(sql, param);
            return Map.of("id", Integer.parseInt(id));
        } else {
            // 신규 insert 후 id 반환
            sql = """
                insert into quality_defect (
                    "DefectDate", "StoreHouse_id", "Process",
                    "Material_id", "BadQty", "BadType", "BadReason",
                    "Disposal", "Manager", "Description",
                    "State", spjangcd, "_created", "_creater_id"
                ) values (
                    cast(:defectDate as date), :houseId, :process,
                    :materialId, :badQty, :badType, :badReason,
                    :disposal, :manager, :description,
                    'draft', :spjangcd, now(), :createrId
                )
                returning id
                """;
            Map<String, Object> row = this.sqlRunner.getRow(sql, param);

            // DefectNo 채번 후 업데이트
            if (row != null) {
                int newId = (int) row.get("id");
                String defectNo = "QD-" + defectDate.replace("-", "").substring(0, 8)
                        + "-" + String.format("%04d", newId);
                MapSqlParameterSource updateParam = new MapSqlParameterSource();
                updateParam.addValue("id", newId);
                updateParam.addValue("defectNo", defectNo);
                this.sqlRunner.execute(
                    "update quality_defect set \"DefectNo\" = :defectNo where id = :id",
                    updateParam);
                row.put("DefectNo", defectNo);
            }
            return row;
        }
    }

    /**
     * 불량전표 삭제 (작성중만)
     */
    public boolean delete(String id) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("id", Integer.parseInt(id));
        String sql = """
            delete from quality_defect
            where id = :id and "State" = 'draft'
            """;
        this.sqlRunner.getRow(sql, param);
        return true;
    }
}
