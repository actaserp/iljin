package mes.app.pda.controller;

import mes.app.quality.Service.QualityDefectSlipService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 불량실적 (PDA) — 불량전표
 *
 * [웹 원본] templates/quality/quality_defect_slip.html → /api/quality/defect_slip
 *
 * [이 컨트롤러가 따로 있는 이유]
 *   /api/** 는 CSRF 보호를 받는데 앱에는 토큰을 심을 곳이 없다.
 *   /pda/** 는 이미 CSRF 예외라 여기로 감싼다.
 *   저장·조회 로직은 웹과 같은 QualityDefectSlipService 를 그대로 쓴다.
 *
 * 불량유형 콤보는 sys_code 의 'bad_type' 을 쓴다. 목록 조회가 예전에는
 * fn_code_name('defect_type', ...) 으로 이름을 뽑아 늘 빈 값이었는데,
 * 두 코드그룹이 서로 겹치지 않아 생긴 문제라 'bad_type' 으로 맞췄다.
 */
@RestController
@RequestMapping("/pda/quality/defect_slip")
public class DefectSlipApiController {

    @Autowired
    QualityDefectSlipService qualityDefectSlipService;

    /** 목록 조회 */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "start", required = false) String start,
            @RequestParam(value = "end", required = false) String end,
            @RequestParam(value = "house_id", required = false) String houseId,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = qualityDefectSlipService.getList(start, end, houseId, state, keyword, spjangcd);
        return result;
    }

    /**
     * 저장 (신규/수정).
     * 웹은 form 파라미터로 받지만 앱에서는 JSON 이 자연스러워 @RequestBody 로 받는다.
     */
    @PostMapping("/save")
    @Transactional
    public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {
        AjaxResult result = new AjaxResult();

        // /pda/** 는 permitAll 이라 세션이 끊긴 채로도 도달한다
        if (auth == null || !(auth.getPrincipal() instanceof User)) {
            result.success = false;
            result.message = "로그인이 필요합니다. 다시 로그인해 주세요.";
            return result;
        }
        User user = (User) auth.getPrincipal();

        try {
            Map<String, Object> row = qualityDefectSlipService.save(
                    strOrNull(payload.get("id")),
                    str(payload.get("defect_date")),
                    str(payload.get("house_id")),
                    str(payload.get("process")),
                    str(payload.get("material_id")),
                    str(payload.get("bad_qty")),
                    str(payload.get("bad_type")),
                    str(payload.get("bad_reason")),
                    str(payload.get("disposal")),
                    str(payload.get("manager")),
                    str(payload.get("description")),
                    str(payload.get("spjangcd")),
                    user.getId()
            );
            result.success = true;
            result.data = row;
            result.message = strOrNull(payload.get("id")) == null ? "등록되었습니다." : "수정되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }
        return result;
    }

    /** 삭제 */
    @PostMapping("/delete")
    @Transactional
    public AjaxResult delete(@RequestBody Map<String, Object> payload) {
        AjaxResult result = new AjaxResult();
        try {
            String id = strOrNull(payload.get("id"));
            if (id == null) {
                result.success = false;
                result.message = "삭제할 불량전표가 지정되지 않았습니다.";
                return result;
            }
            qualityDefectSlipService.delete(id);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }

    // ===== helper =====

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** 신규/수정을 가르는 값이라 빈 문자열은 null 로 눕힌다 */
    private String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
