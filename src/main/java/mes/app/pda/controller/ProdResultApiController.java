package mes.app.pda.controller;

import lombok.RequiredArgsConstructor;
import mes.app.production.service.ProdResultService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 생산실적입력 (PDA) — 가공 키오스크
 *
 * [웹 원본] templates/production/prod_result_input_kiosk.html
 *          → /api/production/prod_result_kiosk
 *
 * 키오스크의 ctx_bar 모드 중 <b>가공(machining)</b> 만 다룬다.
 * 유닛·공정 조립은 prod_assembly 쪽이라 앱에서는 쓰지 않는다.
 *
 * [이 컨트롤러가 따로 있는 이유]
 *   /api/** 는 CSRF 보호를 받는데 앱에는 토큰을 심을 곳이 없다.
 *   /pda/** 는 이미 CSRF 예외라 여기로 감싼다.
 *   조회·저장 로직은 웹과 같은 ProdResultService 를 그대로 쓴다 —
 *   필요량·생산량 계산이 갈리면 웹과 앱이 다른 수치를 보여준다.
 */
@RestController
@RequestMapping("/pda/production/prod_result")
@RequiredArgsConstructor
public class ProdResultApiController {

    private final ProdResultService prodResultService;

    // ===== 마스터 =====

    /**
     * 가공공정 목록 (= 워크센터).
     *
     * 웹 키오스크의 opSel 과 같은 목록이다. 앱에서도 "이 PDA 는 지금 어느 공정에서
     * 쓰고 있는가" 를 골라 설비·작업자 목록을 그 공정으로 좁힌다.
     * workcenter_id 는 작업자 목록에서 담당 인원을 위로 올리는 데 쓴다.
     */
    @GetMapping("/operation_list")
    public AjaxResult operationList(@RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getOperationList(spjangcd);
        return result;
    }

    @GetMapping("/equipment_list")
    public AjaxResult equipmentList(@RequestParam("spjangcd") String spjangcd,
                                    @RequestParam(value = "operation", required = false) String operation) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getEquipmentList(spjangcd, operation);
        return result;
    }

    @GetMapping("/worker_list")
    public AjaxResult workerList(@RequestParam("spjangcd") String spjangcd,
                                 @RequestParam(value = "workCenterId", required = false) Integer workCenterId) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getWorkerList(spjangcd, workCenterId);
        return result;
    }

    @GetMapping("/project_list")
    public AjaxResult projectList(@RequestParam("spjangcd") String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getProjectList(spjangcd);
        return result;
    }

    /** 수주 목록 — 프로젝트당 여러 건이라 이 단계가 없으면 같은 품목명을 구분할 수 없다 */
    @GetMapping("/suju_head_list")
    public AjaxResult sujuHeadList(@RequestParam("spjangcd") String spjangcd,
                                   @RequestParam(value = "projNo", required = false) String projNo) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getSujuHeadList(spjangcd, projNo);
        return result;
    }

    @GetMapping("/item_list")
    public AjaxResult itemList(@RequestParam("spjangcd") String spjangcd,
                               @RequestParam(value = "projNo", required = false) String projNo,
                               @RequestParam(value = "sujuHeadId", required = false) Integer sujuHeadId) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getItemList(spjangcd, projNo, sujuHeadId);
        return result;
    }

    @GetMapping("/kind_tiles")
    public AjaxResult kindTiles(@RequestParam("spjangcd") String spjangcd,
                                @RequestParam(value = "projNo", required = false) String projNo,
                                @RequestParam(value = "sujuId", required = false) Integer sujuId,
                                @RequestParam(value = "operation", required = false) String operation,
                                @RequestParam(value = "sujuHeadId", required = false) Integer sujuHeadId) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getKindTiles(spjangcd, projNo, sujuId, operation, sujuHeadId);
        return result;
    }

    // ===== 실적 =====

    /**
     * 실적 등록.
     * 공정은 화면 값을 믿지 않고 설비 코드로 서버가 결정한다 — 웹과 같은 규칙이다.
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

        String spjangcd = str(payload.get("spjangcd"));
        String equipment = str(payload.get("equipment"));
        double qty = toDouble(payload.get("goodQty"));

        if (equipment.isEmpty()) {
            result.success = false;
            result.message = "설비를 선택하세요.";
            return result;
        }
        if (qty <= 0) {
            result.success = false;
            result.message = "수량을 입력하세요.";
            return result;
        }

        String operation = prodResultService.operationOf(spjangcd, equipment);
        if (operation.isEmpty()) {
            result.success = false;
            result.message = "설비에 연결된 가공공정이 없습니다: " + equipment;
            return result;
        }

        prodResultService.saveResult(payload, operation, user);

        result.success = true;
        result.message = "등록되었습니다.";
        return result;
    }

    /** 실적 취소 */
    @PostMapping("/delete")
    @Transactional
    public AjaxResult delete(@RequestBody Map<String, Object> payload) {
        AjaxResult result = new AjaxResult();

        Integer id = toInt(payload.get("id"));
        if (id == null) {
            result.success = false;
            result.message = "취소할 실적이 지정되지 않았습니다.";
            return result;
        }

        prodResultService.deleteResult(id);
        result.success = true;
        result.message = "취소되었습니다.";
        return result;
    }

    /** 등록 내역 */
    @GetMapping("/log")
    public AjaxResult log(@RequestParam("spjangcd") String spjangcd,
                          @RequestParam("prodDate") String prodDate,
                          @RequestParam(value = "equipment", required = false) String equipment,
                          @RequestParam(value = "operation", required = false) String operation) {
        AjaxResult result = new AjaxResult();
        result.data = prodResultService.getResultLog(spjangcd, prodDate, equipment, operation);
        return result;
    }

    // ===== helper =====

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private Integer toInt(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double toDouble(Object o) {
        if (o == null || o.toString().isBlank()) return 0d;
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
