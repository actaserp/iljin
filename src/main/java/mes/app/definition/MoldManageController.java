package mes.app.definition;

import lombok.RequiredArgsConstructor;
import mes.app.definition.service.MoldManageService;
import mes.domain.entity.MldMold;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 금형 관리
 */
@RestController
@RequestMapping("/api/mold-manage")
@RequiredArgsConstructor
public class MoldManageController {

    private final MoldManageService service;

    /** 목록 조회 */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status
    ) {
        AjaxResult result = new AjaxResult();
        try {
            List<Map<String, Object>> items = service.getList(spjangcd, keyword, status);
            result.success = true;
            result.data = items;
        } catch (Exception e) {
            result.success = false;
            result.message = "목록 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** 버전 이력 조회 */
    @GetMapping("/history")
    public AjaxResult history(@RequestParam("mold_id") Integer moldId) {
        AjaxResult result = new AjaxResult();
        try {
            result.success = true;
            result.data = service.getHistory(moldId);
        } catch (Exception e) {
            result.success = false;
            result.message = "이력 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** 단건 상세 */
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("id") Integer id) {
        AjaxResult result = new AjaxResult();
        return service.findById(id)
                .map(m -> {
                    result.success = true;
                    result.data = m;
                    return result;
                })
                .orElseGet(() -> {
                    result.success = false;
                    result.message = "해당 금형이 존재하지 않습니다.";
                    return result;
                });
    }

    /** 등록 / 수정 */
    @PostMapping("/save")
    public AjaxResult save(@RequestParam Map<String, String> params, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        try {
            MldMold m;
            String id = params.get("id");
            if (id != null && !id.isEmpty()) {
                m = service.findById(Integer.valueOf(id)).orElse(new MldMold());
            } else {
                m = new MldMold();
            }

            m.setMoldCode(params.get("mold_code"));
            m.setMoldName(emptyToNull(params.get("mold_name")));
            m.setStandard(emptyToNull(params.get("standard")));
            m.setMaterial(emptyToNull(params.get("material")));
            m.setCompanyName(emptyToNull(params.get("company_name")));
            m.setLocation(emptyToNull(params.get("location")));
            m.setStatus(params.getOrDefault("status", "USING"));
            m.setRemark(emptyToNull(params.get("remark")));
            m.setSpjangcd(emptyToNull(params.get("spjangcd")));

            String companyId = params.get("company_id");
            m.setCompanyId((companyId != null && !companyId.trim().isEmpty())
                    ? Integer.valueOf(companyId.trim()) : null);

            String makeDate = params.get("make_date");
            m.setMakeDate((makeDate != null && !makeDate.isEmpty())
                    ? LocalDate.parse(makeDate.substring(0, 10)) : null);

            String fileDataPk = params.get("file_data_pk");
            if (fileDataPk != null && !fileDataPk.trim().isEmpty()) {
                m.setFileDataPk(Integer.valueOf(fileDataPk.trim()));
                m.setFileName(emptyToNull(params.get("file_name")));
            }

            m.set_audit(user);

            result.success = true;
            result.data = service.save(m);

        } catch (Exception e) {
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }
        return result;
    }

    /** 삭제 */
    @PostMapping("/delete")
    public AjaxResult delete(@RequestBody Map<String, Object> body) {
        AjaxResult result = new AjaxResult();
        try {
            Object id = body.get("id");
            if (id == null) {
                result.success = false;
                result.message = "삭제할 ID가 없습니다.";
                return result;
            }
            service.delete(Integer.valueOf(String.valueOf(id)));
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }

    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
