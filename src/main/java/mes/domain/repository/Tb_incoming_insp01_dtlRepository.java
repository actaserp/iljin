package mes.domain.repository;

import mes.domain.entity.Tb_incoming_insp01_dtl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface Tb_incoming_insp01_dtlRepository extends JpaRepository<Tb_incoming_insp01_dtl, Integer> {

    List<Tb_incoming_insp01_dtl> findByStdIdOrderBySeqAsc(Integer stdId);

    void deleteByStdId(Integer stdId);
}
