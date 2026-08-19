package mes.app.quality;

import mes.app.quality.service.QualityDefectSlipService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quality/defect_slip")
public class QualityDefectSlipController {

    @Autowired
    QualityDefectSlipService qualityDefectSlipService;

    // 목록 조회
    @GetMapping("/read")
    public AjaxResult getList(
        @RequestParam(value = "start",    required = false) String start,
        @RequestParam(value = "end",      required = false) String end,
        @RequestParam(value = "house_id", required = false) String houseId,
        @RequestParam(value = "state",    required = false) String state,
        @RequestParam(value = "keyword",  required = false) String keyword,
        @RequestParam(value = "spjangcd", required = false) String spjangcd) {

        List<Map<String, Object>> items = this.qualityDefectSlipService.getList(
            start, end, houseId, state, keyword, spjangcd);

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    // 저장 (신규/수정)
    @PostMapping("/save")
    public AjaxResult save(
        @RequestParam Map<String, String> params,
        Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        try {
            Map<String, Object> row = this.qualityDefectSlipService.save(
                params.get("id"),
                params.get("defect_date"),
                params.get("house_id"),
                params.get("process"),
                params.get("material_id"),
                params.get("bad_qty"),
                params.get("bad_type"),
                params.get("bad_reason"),
                params.get("disposal"),
                params.get("manager"),
                params.get("description"),
                params.get("spjangcd"),
                user.getId()
            );
            result.success = true;
            result.data = row;
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }
        return result;
    }

    // 삭제
    @PostMapping("/delete")
    public AjaxResult delete(
        @RequestParam(value = "id") String id) {

        AjaxResult result = new AjaxResult();
        try {
            this.qualityDefectSlipService.delete(id);
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }
}
