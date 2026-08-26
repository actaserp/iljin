package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ProjectRegistrationServicr {
  @Autowired
  SqlRunner sqlRunner;


  public List<Map<String, Object>> getProjectList(String srchStartDt, String srchEndDt, String spjangcd,
                                                  String cboCompany, String txtDescription) {
    MapSqlParameterSource dicParam = new MapSqlParameterSource();

    dicParam.addValue("srchStartDt", srchStartDt);
    dicParam.addValue("srchEndDt", srchEndDt);
    dicParam.addValue("spjangcd", spjangcd);
    dicParam.addValue("cboCompany", cboCompany);
    dicParam.addValue("txtDescription", txtDescription);

    String sql = """
        SELECT * 
        FROM tb_da003 td 
        WHERE td.spjangcd = :spjangcd
          AND td.contdate BETWEEN :srchStartDt AND :srchEndDt
    """;
    if (cboCompany != null && !cboCompany.isEmpty()) {
      sql += "  AND td.balcltcd = :cboCompany ";
      dicParam.addValue("cboCompany", Integer.parseInt(cboCompany));
    }
    if (txtDescription != null && !txtDescription.isEmpty()) {
      sql += " AND td.projnm LIKE :txtDescription ";
      dicParam.addValue("txtDescription", "%" + txtDescription + "%");
    }

//    log.info("프로젝트 관리 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
    List<Map<String, Object>> itmes = this.sqlRunner.getRows(sql, dicParam);

    return itmes;
  }

  // 진행단계 조회 (프로젝트 단위, 순번 오름차순)
  public List<Map<String, Object>> getStageList(String spjangcd, String projno) {
    MapSqlParameterSource dicParam = new MapSqlParameterSource();
    dicParam.addValue("spjangcd", spjangcd);
    dicParam.addValue("projno", projno);

    // wbs_plan_id 로 WBS 세부단계와 연결된다. 이름으로 맞추지 않는다 —
    //  `설계` vs `3D 설계`, `1차 검도` vs `1차검도` 처럼 표기가 갈리면 조용히 어긋난다.
    String sql = """
        SELECT s.seq, s.stagenm, s.pldate, s.cpdate, s.endflag, s.remark,
               s.wbs_plan_id, s.charge_id, ps."Name" AS charge_name,
               w.step_name || ' > ' || w.task_name AS wbs_label,
               COALESCE(w.auto_source, '')          AS wbs_auto_source,
               w.workcenter_id
        FROM tb_da003_stage s
        LEFT JOIN wbs_plan w  ON w.id = s.wbs_plan_id
        LEFT JOIN person   ps ON ps.id = s.charge_id
        WHERE s.spjangcd = :spjangcd
          AND s.projno = :projno
        ORDER BY s.seq ASC
    """;

    return this.sqlRunner.getRows(sql, dicParam);
  }

  // 진행단계 전체 삭제 (프로젝트 단위)
  public void deleteByProject(String spjangcd, String projno) {
    MapSqlParameterSource dicParam = new MapSqlParameterSource();
    dicParam.addValue("spjangcd", spjangcd);
    dicParam.addValue("projno", projno);

    String sql = """
        DELETE FROM tb_da003_stage
        WHERE spjangcd = :spjangcd
          AND projno = :projno
    """;

    this.sqlRunner.execute(sql, dicParam);
  }
}