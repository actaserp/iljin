package mes.app.quality.Service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.RequiredArgsConstructor;
import mes.domain.entity.Tb_incoming_insp01;
import mes.domain.entity.Tb_incoming_insp01_dtl;
import mes.domain.repository.Tb_incoming_insp01Repository;
import mes.domain.repository.Tb_incoming_insp01_dtlRepository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IncomingInspectionService {

    private final Tb_incoming_insp01Repository headerRepository;
    private final Tb_incoming_insp01_dtlRepository detailRepository;

    @Autowired
    SqlRunner sqlRunner;

    /** 목록 조회 (헤더 기준 1행) */
    public List<Map<String, Object>> getList(String itemName, String spjangcd) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("item_name", itemName);
        params.addValue("spjangcd", spjangcd);

        StringBuilder sql = new StringBuilder("""
        SELECT a.id
             , a.item_code
             , a.item_name
             , COALESCE(a.supplier_name, a.supplier_code) AS supplier
             , a.drawing_no
             , a.rev_no
             , a.inspect_type
             , a.aql
             , a.use_yn
        FROM tb_incoming_insp01 a
        WHERE 1=1
        """);

        if (StringUtils.isNotEmpty(itemName)) {
            sql.append(" AND a.item_name ILIKE CONCAT('%', :item_name, '%') ");
        }
        if (StringUtils.isNotEmpty(spjangcd)) {
            sql.append(" AND a.spjangcd = :spjangcd ");
        }

        sql.append(" ORDER BY a.id DESC ");

        return sqlRunner.getRows(sql.toString(), params);
    }

    public Optional<Tb_incoming_insp01> findById(Integer id) {
        return headerRepository.findById(id);
    }

    /** 단건 상세 (헤더 + 검사항목 배열) */
    public Map<String, Object> getDetail(Integer id) {
        Optional<Tb_incoming_insp01> opt = headerRepository.findById(id);
        if (opt.isEmpty()) {
            return null;
        }
        Tb_incoming_insp01 h = opt.get();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", h.getId());
        result.put("item_code", h.getItemCode());
        result.put("item_name", h.getItemName());
        result.put("supplier", h.getSupplierName());
        result.put("supplier_code", h.getSupplierCode());
        result.put("drawing_no", h.getDrawingNo());
        result.put("rev_no", h.getRevNo());
        result.put("inspect_type", h.getInspectType());
        result.put("aql", h.getAql());
        result.put("issue_date", h.getIssueDate() != null ? h.getIssueDate().toString() : null);
        result.put("rev_date", h.getRevDate() != null ? h.getRevDate().toString() : null);
        result.put("remarks", h.getRemark());

        List<Tb_incoming_insp01_dtl> details = detailRepository.findByStdIdOrderBySeqAsc(id);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Tb_incoming_insp01_dtl d : details) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", d.getSeq());
            item.put("measure_name", d.getMeasureName());
            item.put("target_value", d.getTargetValue());
            item.put("usl", d.getUsl());
            item.put("lsl", d.getLsl());
            item.put("unit_name", d.getUnitName());
            item.put("sample_size", d.getSampleSize());
            item.put("inspect_method", d.getInspectMethod());
            item.put("judge_criteria", d.getJudgeCriteria());
            items.add(item);
        }
        result.put("items", items);

        return result;
    }

    /** 등록/수정 : 헤더 저장 후 검사항목 전량 교체 */
    @Transactional
    public Tb_incoming_insp01 save(Tb_incoming_insp01 header, List<Map<String, Object>> items) {

        Tb_incoming_insp01 saved = headerRepository.save(header);
        Integer stdId = saved.getId();

        // 기존 상세 전량 삭제 후 재삽입
        detailRepository.deleteByStdId(stdId);

        if (items != null) {
            int seq = 1;
            for (Map<String, Object> m : items) {
                Tb_incoming_insp01_dtl d = new Tb_incoming_insp01_dtl();
                d.setStdId(stdId);
                d.setSeq(seq++);
                d.setMeasureName(asString(m.get("measure_name")));
                d.setTargetValue(parseBigDecimal(m.get("target_value")));
                d.setUsl(parseBigDecimal(m.get("usl")));
                d.setLsl(parseBigDecimal(m.get("lsl")));
                d.setUnitName(asString(m.get("unit_name")));
                d.setSampleSize(parseInt(m.get("sample_size"), 1));
                d.setInspectMethod(asString(m.get("inspect_method")));
                d.setJudgeCriteria(asString(m.get("judge_criteria")));
                detailRepository.save(d);
            }
        }

        return saved;
    }

    @Transactional
    public void delete(Integer id) {
        // DB의 ON DELETE CASCADE로 상세도 함께 삭제됨. 안전하게 애플리케이션에서도 정리.
        detailRepository.deleteByStdId(id);
        headerRepository.deleteById(id);
    }

    // ---------- 파싱 유틸 ----------
    private String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private BigDecimal parseBigDecimal(Object o) {
        try {
            String s = asString(o);
            return s != null ? new BigDecimal(s) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(Object o, int defaultVal) {
        try {
            String s = asString(o);
            return s != null ? (int) Double.parseDouble(s) : defaultVal;
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
