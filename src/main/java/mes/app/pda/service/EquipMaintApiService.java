package mes.app.pda.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PDA 설비이력(정비) 보조 조회.
 *
 * 정비 이력 목록·상세는 웹과 같은 EquipmentMaintService 를 그대로 쓴다.
 * 여기에는 PDA 화면의 설비 선택에 필요한 목록만 둔다.
 */
@Service
public class EquipMaintApiService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 설비 목록.
     * 폐기된 설비는 뺀다 — 정비 이력을 새로 남길 대상이 아니다.
     */
    public List<Map<String, Object>> getEquipList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select e.id
                 , e."Code"  as code
                 , e."Name"  as name
                 , eg."Name" as group_name
            from equ e
            left join equ_grp eg on eg.id = e."EquipmentGroup_id"
            where e.spjangcd = :spjangcd
              and e."DisposalDate" is null
            order by eg."Name", e."Code"
            """;

        return sqlRunner.getRows(sql, param);
    }
}
