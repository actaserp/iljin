package mes.app.definition;

import lombok.RequiredArgsConstructor;
import mes.app.definition.service.DrawingManageService;
import mes.domain.entity.DwgDrawing;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 도면 관리
 */
@RestController
@RequestMapping("/api/drawing-manage")
@RequiredArgsConstructor
public class DrawingManageController {

    private final DrawingManageService service;

    /** 유니트(공정) 목록 - 프로젝트/라인 선택 시 */
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

    /** 목록 조회 */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "spjangcd", required = false) String spjangcd,
            @RequestParam(value = "project_no", required = false) String projectNo,
            @RequestParam(value = "line_name", required = false) String lineName,
            @RequestParam(value = "unit_code", required = false) String unitCode,
            @RequestParam(value = "suju_id", required = false) Integer sujuId,
            @RequestParam(value = "latest_only", required = false) String latestOnly
    ) {
        AjaxResult result = new AjaxResult();
        try {
            List<Map<String, Object>> items =
                    service.getList(spjangcd, projectNo, lineName, unitCode, sujuId, latestOnly);
            result.success = true;
            result.data = items;
        } catch (Exception e) {
            result.success = false;
            result.message = "목록 조회 실패: " + e.getMessage();
        }
        return result;
    }

    /** 특정 대상(suju)의 버전 이력 */
    @GetMapping("/history")
    public AjaxResult history(@RequestParam("suju_id") Integer sujuId) {
        AjaxResult result = new AjaxResult();
        try {
            result.success = true;
            result.data = service.getHistory(sujuId);
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
                .map(d -> {
                    result.success = true;
                    result.data = d;
                    return result;
                })
                .orElseGet(() -> {
                    result.success = false;
                    result.message = "해당 도면이 존재하지 않습니다.";
                    return result;
                });
    }

    /** 등록 / 수정 */
    @PostMapping("/save")
    public AjaxResult save(@RequestParam Map<String, String> params, Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        try {
            DwgDrawing d;
            String id = params.get("id");

            if (id != null && !id.isEmpty()) {
                d = service.findById(Integer.valueOf(id)).orElse(new DwgDrawing());
            } else {
                d = new DwgDrawing();
            }

            d.setSpjangcd(emptyToNull(params.get("spjangcd")));
            d.setProjectNo(params.get("project_no"));

            String sujuId = params.get("suju_id");
            if (sujuId != null && !sujuId.trim().isEmpty()) {
                d.setSujuId(Integer.valueOf(sujuId.trim()));
            }
            d.setProjectName(emptyToNull(params.get("project_name")));
            d.setLineName(emptyToNull(params.get("line_name")));
            d.setUnitCode(params.get("unit_code"));
            d.setProcessName(emptyToNull(params.get("process_name")));
            d.setDrawingNo(emptyToNull(params.get("drawing_no")));
            d.setVersion(params.get("version"));
            d.setLatestYn(params.getOrDefault("latest_yn", "Y"));
            d.setFileName(emptyToNull(params.get("file_name")));
            d.setFilePath(emptyToNull(params.get("file_path")));
            d.setRemark(emptyToNull(params.get("remark")));

            String fileDataPk = params.get("file_data_pk");
            if (fileDataPk != null && !fileDataPk.isEmpty()) {
                d.setFileDataPk(Integer.valueOf(fileDataPk));
            }

            String equipmentId = params.get("equipment_id");
            if (equipmentId != null && !equipmentId.trim().isEmpty()) {
                d.setEquipmentId(Integer.valueOf(equipmentId.trim()));
            } else {
                d.setEquipmentId(null);
            }

            String regDate = params.get("reg_date");
            if (regDate != null && !regDate.isEmpty()) {
                d.setRegDate(Timestamp.valueOf(regDate.substring(0, 10) + " 00:00:00"));
            } else if (d.getRegDate() == null) {
                d.setRegDate(new Timestamp(System.currentTimeMillis()));
            }

            d.set_audit(user);

            result.success = true;
            result.data = service.save(d);

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
