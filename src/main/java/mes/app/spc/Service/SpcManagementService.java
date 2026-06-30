package mes.app.spc.Service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.RequiredArgsConstructor;
import mes.domain.entity.Tb_spc_std01;
import mes.domain.repository.Tb_spc_std01Repository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SpcManagementService {

    private final Tb_spc_std01Repository repository;

    @Autowired
    SqlRunner sqlRunner;

    //목록 조회
    public List<Map<String, Object>> getList(String srchMat, String srchRecipe) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("srchMat", srchMat);
        params.addValue("srchRecipe", srchRecipe);

        StringBuilder sql = new StringBuilder("""
        SELECT a.*
        , b."Value" AS measure_cycle_unit_name
        FROM tb_spc_std01 a
        LEFT JOIN sys_code b
           ON a.measure_cycle_unit = b."Code"
          AND b."CodeType" = 'measure_cycle_unit'
        WHERE 1=1
        """);

        if (StringUtils.isNotEmpty(srchMat)) {
            sql.append(" AND item_name ILIKE CONCAT('%', :srchMat, '%') ");
        }

        if (StringUtils.isNotEmpty(srchRecipe)) {
            sql.append(" AND recipe ILIKE CONCAT('%', :srchRecipe, '%') ");
        }

        sql.append(" ORDER BY id DESC ");

        return sqlRunner.getRows(sql.toString(), params);
    }


    public Optional<Tb_spc_std01> findById(Integer id) {
        return repository.findById(id);
    }

    public Tb_spc_std01 save(Tb_spc_std01 entity) {
        return repository.save(entity);
    }

    public void delete(Integer id) {
        repository.deleteById(id);
    }
}
