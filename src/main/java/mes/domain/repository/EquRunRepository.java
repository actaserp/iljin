package mes.domain.repository;

import org.apache.ibatis.annotations.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import mes.domain.entity.EquRun;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;


@Repository
public interface EquRunRepository extends JpaRepository<EquRun, Integer>{

	EquRun getEquRunById(Integer id);

	@Query("SELECT e FROM EquRun e WHERE e.equipmentId = :equipmentId AND e.workOrderNumber = :orderNum AND e.runState = 'run' AND e.endDate IS NULL ORDER BY e.startDate DESC")
	Optional<EquRun> findLatestRunningByEquipmentAndOrder(@Param("equipmentId") Integer equipmentId, @Param("orderNum") String orderNum);

	@Query("SELECT e FROM EquRun e WHERE e.equipmentId = :equipmentId AND e.workOrderNumber = :orderNum AND e.runState = 'complete' ORDER BY e.endDate DESC")
	Optional<EquRun> findLatestCompleteByEquipmentAndOrder(@Param("equipmentId") Integer equipmentId, @Param("orderNum") String orderNum);

	long countByEquipmentIdAndRunState(Integer equipmentId, String runState);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	int deleteByWorkOrderNumberAndEquipmentId(String orderNum, Integer equipmentId);

	long countByJobResponseIdAndRunState(Integer jobResponseId, String runState);

	@Query("SELECT e FROM EquRun e WHERE e.jobResponseId = :jrPk AND e.runState = 'run' ORDER BY e.startDate DESC")
	Optional<EquRun> findLatestRunningByJobResponseId(@Param("jrPk") Integer jrPk);

	List<EquRun> findByEquipmentIdAndWorkOrderNumberAndRunState(Integer equipmentId, String workOrderNumber, String runState);
}
