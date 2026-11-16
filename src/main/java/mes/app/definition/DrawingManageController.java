package mes.app.definition;

import lombok.RequiredArgsConstructor;
import mes.app.definition.service.DrawingManageService;
import mes.domain.model.AjaxResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 도면 관리 · 도면 이력 관리.
 * 파일은 생산 지시 [도면 일괄] 과 같은 곳(attach_file 'suju_drawing')에 둔다 — DrawingManageService 참조.
 * 올리기는 공통 업로더(/api/files/upload), 보기 · 원본 열기는 /api/production/part_drawing (iljin_drawing.js).
 */
@RestController
@RequestMapping("/api/drawing-manage")
@RequiredArgsConstructor
public class DrawingManageController {

    private final DrawingManageService service;

    /** 유니트(공정) 목록 + 도면 · 원본 개수 - 프로젝트/라인 선택 시 */
    @GetMapping("/units")
    public AjaxResult units(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "project_no", required = false) String projectNo,
            @RequestParam(value = "line_name", required = false) String lineName
    ) {
        AjaxResult result = new AjaxResult();
        try {
            result.success = true;
            result.data = service.getUnitList(spjangcd, projectNo, lineName);
        } catch (Exception e) {
            result.success = false;
            result.message = "유니트 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** 유니트(suju)에 올린 도면 · 원본 파일 */
    @GetMapping("/files")
    public AjaxResult files(@RequestParam("suju_id") Integer sujuId) {
        AjaxResult result = new AjaxResult();
        try {
            result.success = true;
            result.data = service.getFiles(sujuId);
        } catch (Exception e) {
            result.success = false;
            result.message = "도면 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** 도면 · 원본 파일 삭제 */
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
            result.success = service.delete(Integer.valueOf(String.valueOf(id)));
            if (!result.success) result.message = "삭제할 도면을 찾을 수 없습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }
}