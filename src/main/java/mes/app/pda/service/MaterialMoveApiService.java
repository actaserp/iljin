package mes.app.pda.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PDA 자재 불출(창고이동) 보조 조회.
 *
 * 자재 목록 자체는 웹과 같은 {@link mes.app.inventory.service.MaterialMoveService}
 * 를 그대로 쓴다 — 재고 계산이 갈리면 웹과 PDA 가 다른 수치를 보여주기 때문이다.
 * 여기에는 PDA 화면에만 필요한 창고 목록만 둔다.
 */
@Service
public class MaterialMoveApiService {

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 창고 목록.
     *
     * 출발/도착 창고를 모두 이 목록에서 고른다.
     * HouseType 을 같이 주어 화면에서 공정창고(process)를 구분해 보여줄 수 있게 한다.
     */
    public List<Map<String, Object>> getStoreHouseList(String spjangcd) {
        MapSqlParameterSource param = new MapSqlParameterSource();
        param.addValue("spjangcd", spjangcd);

        String sql = """
            select sh.id
                 , sh."Name"      as name
                 , sh."HouseType" as house_type
                 -- 코드그룹명은 'storehouse_type' 이다 (StoreHouseService 와 동일하게 맞춘다)
                 , fn_code_name('storehouse_type', sh."HouseType") as house_type_name
            from store_house sh
            order by sh."Name"
            """;

        return sqlRunner.getRows(sql, param);
    }
}
