package mes.app.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import mes.app.quality.Service.IncomingInspectionService;
import mes.domain.entity.Tb_incoming_insp01;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 수입검사기준서
 */
@RestController
@RequestMapping("/api/incoming-inspection")
@RequiredArgsConstructor
public class IncomingInspectionController {

    private final IncomingInspectionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 목록 조회 */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "item_name", required = false) String itemName,
            @RequestParam(value = "spjangcd", required = false) String spjangcd
    ) {
        AjaxResult result = new AjaxResult();
        try {
            List<Map<String, Object>> items = service.getList(itemName, spjangcd);
            result.success = true;
            result.data = items;
        } catch (Exception e) {
            result.success = false;
            result.message = "목록 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** 단건 상세 (헤더 + 검사항목) */
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("id") Integer id) {
        AjaxResult result = new AjaxResult();
        Map<String, Object> data = service.getDetail(id);
        if (data != null) {
            result.success = true;
            result.data = data;
        } else {
            result.success = false;
            result.message = "해당 데이터가 존재하지 않습니다.";
        }
        return result;
    }

    /** 등록 / 수정 */
    @PostMapping("/save")
    public AjaxResult save(@RequestParam Map<String, String> params,
                           Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        OffsetDateTime now = OffsetDateTime.now();

        try {
            Tb_incoming_insp01 header;

            if (params.get("id") != null && !params.get("id").isEmpty()) {
                header = service.findById(Integer.valueOf(params.get("id")))
                        .orElse(new Tb_incoming_insp01());
            } else {
                header = new Tb_incoming_insp01();
            }

            // 헤더 매핑
            header.setItemCode(params.get("item_code"));
            header.setItemName(params.get("item_name"));
            header.setSupplierName(emptyToNull(params.get("supplier")));
            header.setSupplierCode(emptyToNull(params.get("supplier_code")));
            header.setDrawingNo(emptyToNull(params.get("drawing_no")));
            header.setRevNo(emptyToNull(params.get("rev_no")));
            header.setInspectType(params.getOrDefault("inspect_type", "SAMPLING"));
            header.setAql(emptyToNull(params.get("aql")));
            header.setIssueDate(parseDate(params.get("issue_date")));
            header.setRevDate(parseDate(params.get("rev_date")));
            header.setUseYn(params.getOrDefault("use_yn", "Y"));
            header.setRemark(params.get("remarks"));
            header.setSpjangcd(params.get("spjangcd"));

            if (header.getId() == null) {
                header.setCreatedAt(now);
                header.setCreatedBy(user.getUsername());
            }
            header.setUpdatedAt(now);
            header.setUpdatedBy(user.getUsername());

            // 검사항목 배열 파싱
            List<Map<String, Object>> items = null;
            String itemsJson = params.get("items");
            if (itemsJson != null && !itemsJson.isEmpty()) {
                items = objectMapper.readValue(itemsJson, new TypeReference<List<Map<String, Object>>>() {});
            }

            Tb_incoming_insp01 saved = service.save(header, items);

            result.success = true;
            result.data = saved;

        } catch (Exception e) {
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }

        return result;
    }

    /** 삭제 */
    @PostMapping("/delete")
    public AjaxResult delete(@RequestBody Tb_incoming_insp01 req) {
        AjaxResult result = new AjaxResult();
        try {
            if (req.getId() == null) {
                result.success = false;
                result.message = "삭제할 ID가 없습니다.";
                return result;
            }
            service.delete(req.getId());
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }

    // ---------- 유틸 ----------
    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private LocalDate parseDate(String s) {
        try {
            return (s != null && !s.isEmpty()) ? LocalDate.parse(s.substring(0, 10)) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
