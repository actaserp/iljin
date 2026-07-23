package mes.app.definition.service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.RequiredArgsConstructor;
import mes.domain.entity.AttachFile;
import mes.domain.entity.MldMold;
import mes.domain.repository.AttachFileRepository;
import mes.domain.repository.MldMoldRepository;
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
public class MoldManageService {

    private final MldMoldRepository moldRepository;
    private final AttachFileRepository attachFileRepository;

    @Autowired
    SqlRunner sqlRunner;

    /** 금형 목록 조회 (금형번호 / 금형명 검색) */
    public List<Map<String, Object>> getList(String spjangcd, String keyword, String status) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("spjangcd", spjangcd);
        params.addValue("keyword", keyword);
        params.addValue("status", status);

        StringBuilder sql = new StringBuilder("""
        SELECT a.id
             , a."MoldCode"    AS mold_code
             , a."MoldName"    AS mold_name
             , a."Standard"    AS standard
             , a."Material"    AS material
             , a."Company_id"  AS company_id
             , a."CompanyName" AS company_name
             , TO_CHAR(a."MakeDate", 'YYYY-MM-DD') AS make_date
             , a."Location"    AS location
             , a."Status"      AS status
             , CASE a."Status" WHEN 'USING'  THEN '사용중'
                               WHEN 'REPAIR' THEN '수리중'
                               WHEN 'SCRAP'  THEN '폐기'
                               ELSE a."Status" END AS status_name
             , a."FileDataPk"  AS file_data_pk
             , a."FileName"    AS file_name
             , a."Remark"      AS remark
        FROM mld_mold a
        WHERE 1=1
        """);

        if (StringUtils.isNotEmpty(spjangcd)) sql.append(" AND a.\"Spjangcd\" = :spjangcd ");
        if (StringUtils.isNotEmpty(status))   sql.append(" AND a.\"Status\" = :status ");
        if (StringUtils.isNotEmpty(keyword)) {
            sql.append(""" 
                AND ( a."MoldCode" ILIKE CONCAT('%', :keyword, '%')
                   OR a."MoldName" ILIKE CONCAT('%', :keyword, '%') )
                """);
        }

        sql.append(" ORDER BY a.\"MoldCode\" ");

        return sqlRunner.getRows(sql.toString(), params);
    }

    public Optional<MldMold> findById(Integer id) {
        return moldRepository.findById(id);
    }

    @Transactional
    public MldMold save(MldMold mold) {
        return moldRepository.save(mold);
    }

    /** 버전 이력 조회 : 별도 테이블 없이 attach_file 기준으로 구성 */
    public List<Map<String, Object>> getHistory(Integer moldId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("moldId", moldId);

        String sql = """
        SELECT a.id                                   AS file_data_pk
             , a."FileName"                           AS file_name
             , TO_CHAR(a._created, 'YYYY-MM-DD')      AS reg_date
             , ROW_NUMBER() OVER (ORDER BY a.id ASC)  AS version
        FROM attach_file a
        WHERE a."TableName" = 'mld_mold'
          AND a."DataPk"    = :moldId
        ORDER BY a.id DESC
        """;

        return sqlRunner.getRows(sql, params);
    }

    @Transactional
    public void delete(Integer id) {
        MldMold mold = moldRepository.findById(id).orElse(null);
        if (mold == null) return;

        // 이 금형에 달린 모든 첨부(전체 버전) 정리
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("moldId", id);
        List<Map<String, Object>> files = sqlRunner.getRows("""
            SELECT a.id FROM attach_file a
            WHERE a."TableName" = 'mld_mold' AND a."DataPk" = :moldId
            """, params);

        for (Map<String, Object> f : files) {
            deleteAttachFile((Integer) f.get("id"));
        }

        moldRepository.deleteById(id);
    }

    /** 첨부파일 삭제 : 실제 파일 + attach_file 레코드 */
    private void deleteAttachFile(Integer fileDataPk) {
        if (fileDataPk == null) return;
        try {
            AttachFile af = attachFileRepository.findById(fileDataPk).orElse(null);
            if (af == null) return;

            if (af.getFilePath() != null && af.getPhysicFileName() != null) {
                File f = new File(af.getFilePath() + File.separator + af.getPhysicFileName());
                if (f.exists()) f.delete();
            }
            attachFileRepository.deleteById(fileDataPk);
        } catch (Exception e) {
            // 파일 정리 실패가 삭제를 막지 않도록 무시
        }
    }
}
