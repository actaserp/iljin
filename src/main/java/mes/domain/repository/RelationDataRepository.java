package mes.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import mes.domain.entity.RelationData;

@Repository 
public interface RelationDataRepository extends JpaRepository<RelationData, Integer> {
	
	//List<RelationData> findByName (String name);
		
	RelationData getRelationDataById(Integer id);
	
	List<RelationData> findByDataPk1AndTableName1AndDataPk2AndTableName2(Integer pk1, String string, Integer pk2, String string2);

	//RelationData findByTableName1AndTableName2AndDataPk1(String string, String string2, Integer pid);

	List<RelationData> findByDataPk1AndTableName1AndTableName2(Integer id, String string, String string2);
	
	int deleteByDataPk1AndTableName1AndTableName2(Integer hp_id, String table1, String tabl2);

	// shipment(하위) 기준으로 연결데이터 삭제 : 출하지시 취소 시 rela_data 정리용
	int deleteByTableName2AndDataPk2In(String tableName2, List<Integer> dataPk2List);

	List<RelationData> findByDataPk1AndTableName1AndRelationNameAndTableName2(Integer bhId, String string,
			String string2, String string3);
}
