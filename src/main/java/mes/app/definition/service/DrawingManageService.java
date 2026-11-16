package mes.app.definition.service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.RequiredArgsConstructor;
import mes.app.production.service.PartDrawingService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 도면 관리 · 도면 이력 관리.
 *
 * <p>도면은 생산 지시 [도면 일괄] 과 같은 곳에 둔다 — 공통 첨부(attach_file)
 *   TableName='suju_drawing', DataPk=suju.id, AttachName='drawing'.
 *   그래서 어느 화면에서 올려도 생산 지시 · 키오스크 · 도면 관리 · 도면 이력 관리에 똑같이 나온다.
 *   업로드는 공통 업로더(/api/files/upload), 목록 · 지우기는 PartDrawingService 를 그대로 쓴다.
 *
 * <p>도면번호 · REV · 원본(DWG/DXF) 짝은 파일 이름에서 화면이 읽는다 (iljin_drawing.js).
 *   예전 방식(dwg_drawing 에 유니트당 버전을 손으로 입력)은 더 쓰지 않는다. 테이블은 남겨 둔다.
 */
@Service
@RequiredArgsConstructor
public class DrawingManageService {

    private final PartDrawingService partDrawingService;

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 유니트(공정) 목록 — 프로젝트(+라인) 의 수주 행.
     * 도면 = PDF·그림 파일 수, 원본 = DWG·DXF 파일 수 (리비전 · 형식이 다르면 따로 센다)
     */
    public List<Map<String, Object>> getUnitList(String spjangcd, String projectNo, String lineName) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("spjangcd", spjangcd);
        params.addValue("projectNo", projectNo);
        params.addValue("lineName", lineName);

        StringBuilder sql = new StringBuilder("""
        SELECT s.id              AS suju_id
             , s.project_id      AS project_no
             , s.line            AS line_name
             , m."Code"          AS proc_code
             , s."Material_Name" AS proc_name
             , s."Standard"      AS jig_set
             , s."SujuQty"       AS unit_qty
             , s.equip_type      AS equip_type
             , COALESCE(f.draw_cnt, 0) AS draw_cnt
             , COALESCE(f.orig_cnt, 0) AS orig_cnt
             , f.last_date
        FROM suju s
        LEFT JOIN material m ON m.id = s."Material_id"
        LEFT JOIN (
            SELECT a."DataPk" AS suju_id
                 , COUNT(*) FILTER (WHERE LOWER(COALESCE(a."ExtName", '')) NOT IN ('dwg', 'dxf')) AS draw_cnt
                 , COUNT(*) FILTER (WHERE LOWER(COALESCE(a."ExtName", '')) IN ('dwg', 'dxf'))     AS orig_cnt
                 , TO_CHAR(MAX(a._created), 'YYYY-MM-DD') AS last_date
            FROM attach_file a
            WHERE a."TableName" = 'suju_drawing' AND a."AttachName" = 'drawing'
            GROUP BY a."DataPk"
        ) f ON f.suju_id = s.id
        WHERE 1=1
        """);

        if (StringUtils.isNotEmpty(spjangcd))  sql.append(" AND s.spjangcd = :spjangcd ");
        if (StringUtils.isNotEmpty(projectNo)) sql.append(" AND s.project_id = :projectNo ");
        if (StringUtils.isNotEmpty(lineName))  sql.append(" AND s.line = :lineName ");

        sql.append(" ORDER BY s.line, s.id ");

        return sqlRunner.getRows(sql.toString(), params);
    }

    /** 유니트(수주)에 올린 도면 · 원본 파일 전부. 최신이 위 */
    public List<Map<String, Object>> getFiles(Integer sujuId) {
        return partDrawingService.list(sujuId);
    }

    /** 도면 · 원본 파일 하나 지우기. 이 화면들이 올린 도면(품목 도면)만 지운다 */
    public boolean delete(Integer id) {
        if (partDrawingService.get(id) == null) return false;
        partDrawingService.delete(id);
        return true;
    }
}