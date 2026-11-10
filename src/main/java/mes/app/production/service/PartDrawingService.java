package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 품목 도면 (생산 지시 화면).
 *
 * <p>[저장 위치]  기존 첨부(attach_file)를 그대로 쓴다. 새 테이블 없음.
 *   TableName='suju_drawing', DataPk=suju.id, AttachName='drawing'.
 *   업로드는 공통 업로더(/api/files/upload)가 한다. 여기서는 목록 · 보기 · 지우기만.
 *
 * <p>[부품과 짝짓지 않는다]  설계 업체마다 같은 부품을 plate / 버킷 등으로 달리 불러
 *   부품표는 유형 단위로만 관리한다. 도면은 품목 아래 목록으로 둔다.
 *   도면번호 · 리비전은 파일 이름에서 화면이 읽는다 (FD10-01-03-REV11.pdf).
 *
 * <p>[부품 도면 행]  TableName='iljin_suju_bom' 은 시험 중 잠깐 쓰던 방식이다.
 *   남아 있는 행이 목록에서 사라지지 않게 조회에만 포함한다.
 *
 * <p>[도면 관리 화면(dwg_drawing)]  건드리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PartDrawingService {

  public static final String T_PART = "iljin_suju_bom";
  public static final String T_ITEM = "suju_drawing";
  public static final String ATTACH_NAME = "drawing";

  private final SqlRunner sqlRunner;

  /** 품목(suju)의 도면 전부 — 부품 도면 + 품목 도면. 최신이 위 */
  public List<Map<String, Object>> list(Integer sujuId) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("sujuId", sujuId);
    return sqlRunner.getRows("""
            SELECT a.id
                 , a."TableName"                AS table_name
                 , a."DataPk"                   AS data_pk
                 , CASE WHEN a."TableName" = 'iljin_suju_bom' THEN a."DataPk" END AS part_id
                 , b."PartName"                 AS part_name
                 , a."FileName"                 AS file_name
                 , LOWER(COALESCE(a."ExtName", '')) AS ext
                 , a."FileSize"                 AS file_size
                 , TO_CHAR(a._created, 'YYYY-MM-DD HH24:MI') AS created
            FROM attach_file a
            LEFT JOIN iljin_suju_bom b ON a."TableName" = 'iljin_suju_bom' AND b.id = a."DataPk"
            WHERE a."AttachName" = 'drawing'
              AND (   (a."TableName" = 'iljin_suju_bom' AND b."Suju_id" = :sujuId)
                   OR (a."TableName" = 'suju_drawing'   AND a."DataPk"   = :sujuId))
            ORDER BY a.id DESC
            """, p);
  }

  /** 화면이 보낸 첨부 id 가 정말 부품/품목 도면인지 */
  public Map<String, Object> get(Integer id) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("id", id);
    List<Map<String, Object>> rows = sqlRunner.getRows("""
            SELECT a.*, CASE WHEN a."TableName" = 'iljin_suju_bom' THEN b."Suju_id" ELSE a."DataPk" END AS suju_id
            FROM attach_file a
            LEFT JOIN iljin_suju_bom b ON a."TableName" = 'iljin_suju_bom' AND b.id = a."DataPk"
            WHERE a.id = :id
              AND a."AttachName" = 'drawing'
              AND a."TableName" IN ('iljin_suju_bom', 'suju_drawing')
            """, p);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 첨부 행만 지운다. 디스크 파일은 남긴다 (잘못 지웠을 때 되살릴 여지) */
  public void delete(Integer id) {
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("id", id);
    sqlRunner.execute("""
            DELETE FROM attach_file
            WHERE id = :id AND "AttachName" = 'drawing' AND "TableName" IN ('iljin_suju_bom', 'suju_drawing')
            """, p);
  }
}