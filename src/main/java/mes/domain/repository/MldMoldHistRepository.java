package mes.domain.repository;

import mes.domain.entity.MldMoldHist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MldMoldHistRepository extends JpaRepository<MldMoldHist, Integer> {

    List<MldMoldHist> findByMoldIdOrderByVersionDesc(Integer moldId);

    @Query(value = "SELECT COALESCE(MAX(\"Version\"), 0) FROM mld_mold_hist WHERE \"Mold_id\" = :moldId",
            nativeQuery = true)
    Integer findMaxVersion(@Param("moldId") Integer moldId);
}
