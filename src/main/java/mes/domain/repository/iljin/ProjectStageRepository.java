package mes.domain.repository.iljin;

import mes.domain.entity.iljin.tb_da003_stage;
import mes.domain.entity.iljin.tb_da003_stage_id;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectStageRepository extends JpaRepository<tb_da003_stage, tb_da003_stage_id> {

}
