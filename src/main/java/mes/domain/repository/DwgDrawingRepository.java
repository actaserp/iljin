package mes.domain.repository;

import mes.domain.entity.DwgDrawing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DwgDrawingRepository extends JpaRepository<DwgDrawing, Integer> {

    /** 같은 대상(suju)의 기존 최신도면 해제 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE dwg_drawing
           SET "LatestYn" = 'N'
         WHERE "Suju_id" = :sujuId
           AND ( :id IS NULL OR id <> :id )
        """, nativeQuery = true)
    void clearLatest(@Param("sujuId") Integer sujuId,
                     @Param("id") Integer id);
    /** 최신도면 삭제 후, 남은 버전 중 가장 최근 것을 최신으로 승격 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE dwg_drawing
           SET "LatestYn" = 'Y'
         WHERE id = ( SELECT id FROM dwg_drawing
                       WHERE "Suju_id" = :sujuId
                       ORDER BY id DESC
                       LIMIT 1 )
        """, nativeQuery = true)
    void promoteLatest(@Param("sujuId") Integer sujuId);
}
