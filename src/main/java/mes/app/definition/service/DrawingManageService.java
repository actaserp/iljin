package mes.app.definition.service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.RequiredArgsConstructor;
import mes.domain.entity.AttachFile;
import mes.domain.entity.DwgDrawing;
import mes.domain.repository.AttachFileRepository;
import mes.domain.repository.DwgDrawingRepository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DrawingManageService {

    private final DwgDrawingRepository drawingRepository;
    private final AttachFileRepository attachFileRepository;

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 유니트(공정) 목록 조회
     * 프로젝트 + 라인 선택 시 표시할 대상 목록 (suju 기준)
     * 각 행의 최신 도면 정보도 함께 반환
     */
    public List<Map<String, Object>> getUnitList(String spjangcd, String projectNo, String lineName) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("spjangcd", spjangcd);
        params.addValue("projectNo", projectNo);
        params.addValue("lineName", lineName);

        StringBuilder sql = new StringBuilder("""
        SELECT s.id            AS suju_id
             , s.project_id    AS project_no
             , s.line          AS line_name
             , m."Code"        AS proc_code
             , s."Material_Name" AS proc_name
             , s."Standard"    AS jig_set
             , s."SujuQty"     AS unit_qty
             , s.equip_type    AS equip_type
             , d.id            AS drawing_id
             , d."Version"     AS latest_version
             , d."FileName"    AS latest_file_name
             , d."FileDataPk"  AS latest_file_pk
             , TO_CHAR(d."RegDate", 'YYYY-MM-DD') AS latest_reg_date
        FROM suju s
        LEFT JOIN material m ON m.id = s."Material_id"
        LEFT JOIN dwg_drawing d
               ON d."Suju_id" = s.id
              AND d."LatestYn" = 'Y'
        WHERE 1=1
        """);

        if (StringUtils.isNotEmpty(spjangcd))  sql.append(" AND s.spjangcd = :spjangcd ");
        if (StringUtils.isNotEmpty(projectNo)) sql.append(" AND s.project_id = :projectNo ");
        if (StringUtils.isNotEmpty(lineName))  sql.append(" AND s.line = :lineName ");

        sql.append(" ORDER BY s.line, s.id ");

        return sqlRunner.getRows(sql.toString(), params);
    }

    /** 도면 목록 조회
     * latestOnly = 'Y' 이면 대상별 최신도면만 조회
     */
    public List<Map<String, Object>> getList(String spjangcd, String projectNo, String lineName,
                                             String unitCode, Integer sujuId, String latestOnly) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("spjangcd", spjangcd);
        params.addValue("projectNo", projectNo);
        params.addValue("lineName", lineName);
        params.addValue("unitCode", unitCode);
        params.addValue("sujuId", sujuId);

        StringBuilder sql = new StringBuilder("""
        SELECT a.id
             , a."Suju_id"     AS suju_id
             , a."Spjangcd"    AS spjangcd
             , a."ProjectNo"   AS project_no
             , a."ProjectName" AS project_name
             , a."LineName"    AS line_name
             , a."UnitCode"    AS unit_code
             , a."ProcessName" AS process_name
             , a."Equipment_id" AS equipment_id
             , e."Code"        AS equip_code
             , e."Name"        AS equip_name
             , a."DrawingNo"   AS drawing_no
             , a."Version"     AS version
             , a."LatestYn"    AS latest_yn
             , TO_CHAR(a."RegDate", 'YYYY-MM-DD') AS reg_date
             , a."FileName"    AS file_name
             , a."FilePath"    AS file_path
             , a."FileDataPk"  AS file_data_pk
             , a."Remark"      AS remark
        FROM dwg_drawing a
        LEFT JOIN equ e ON e.id = a."Equipment_id"
        WHERE 1=1
        """);

        if (sujuId != null)                    sql.append(" AND a.\"Suju_id\" = :sujuId ");
        if (StringUtils.isNotEmpty(spjangcd))  sql.append(" AND a.\"Spjangcd\" = :spjangcd ");
        if (StringUtils.isNotEmpty(projectNo)) sql.append(" AND a.\"ProjectNo\" = :projectNo ");
        if (StringUtils.isNotEmpty(lineName))  sql.append(" AND a.\"LineName\" = :lineName ");
        if (StringUtils.isNotEmpty(unitCode))  sql.append(" AND a.\"UnitCode\" = :unitCode ");
        if ("Y".equals(latestOnly))            sql.append(" AND a.\"LatestYn\" = 'Y' ");

        sql.append(" ORDER BY a.\"ProjectNo\", a.\"LineName\", a.\"UnitCode\", a.id DESC ");

        return sqlRunner.getRows(sql.toString(), params);
    }

    /** 특정 대상(suju)의 버전 이력 */
    public List<Map<String, Object>> getHistory(Integer sujuId) {
        return getList(null, null, null, null, sujuId, "N");
    }

    public Optional<DwgDrawing> findById(Integer id) {
        return drawingRepository.findById(id);
    }

    /**
     * 등록/수정
     * LatestYn = 'Y' 로 저장하면 같은 대상의 기존 도면은 자동으로 'N' 처리
     */
    @Transactional
    public DwgDrawing save(DwgDrawing drawing) {

        DwgDrawing saved = drawingRepository.save(drawing);

        if ("Y".equals(saved.getLatestYn()) && saved.getSujuId() != null) {
            drawingRepository.clearLatest(saved.getSujuId(), saved.getId());
        }

        return saved;
    }

    @Transactional
    public void delete(Integer id) {

        DwgDrawing drawing = drawingRepository.findById(id).orElse(null);
        if (drawing == null) return;

        Integer sujuId  = drawing.getSujuId();
        boolean wasLatest = "Y".equals(drawing.getLatestYn());

        // 1) 첨부파일 정리 (디스크 파일 + attach_file 레코드)
        deleteAttachFile(drawing.getFileDataPk());

        // 2) 도면 행 삭제
        drawingRepository.deleteById(id);

        // 3) 최신도면을 지웠다면 남은 버전 중 최근 것을 최신으로 승격
        if (wasLatest && sujuId != null) {
            drawingRepository.promoteLatest(sujuId);
        }
    }

    /** 첨부파일 삭제 : 실제 파일 삭제 후 attach_file 레코드 제거 */
    private void deleteAttachFile(Integer fileDataPk) {
        if (fileDataPk == null) return;

        try {
            AttachFile af = attachFileRepository.findById(fileDataPk).orElse(null);
            if (af == null) return;

            String filePath = af.getFilePath();
            String physicName = af.getPhysicFileName();

            if (filePath != null && physicName != null) {
                File f = new File(filePath + File.separator + physicName);
                if (f.exists()) {
                    f.delete();
                }
            }

            attachFileRepository.deleteById(fileDataPk);

        } catch (Exception e) {
            // 파일 정리 실패가 도면 삭제를 막지 않도록 무시
        }
    }
}
