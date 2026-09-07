package mes.app.pda.controller;

import mes.app.inventory.service.MaterialMoveService;
import mes.app.pda.service.MaterialMoveApiService;
import mes.domain.entity.MaterialInout;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.MatInoutRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 작업지시서 공정투입 (PDA) — 자재 불출 = 창고이동
 *
 * [웹 원본] templates/inventory/mat_issue.html → /api/inventory/material_move
 *
 * 자재창고에 있는 자재를 공정창고 등 다른 창고로 옮긴다.
 * 이동 한 건은 <b>출고(move_out) + 입고(move_in) 두 행</b>으로 남는다 —
 * MaterialMoveController.saveMaterialMove 와 같은 규칙이다. 규칙이 갈리면
 * 웹에서 옮긴 것과 PDA 에서 옮긴 것의 수불 내역 모양이 달라진다.
 *
 * [이 컨트롤러가 따로 있는 이유]
 *   /api/** 는 CSRF 보호를 받는데 PDA 앱에는 토큰을 심을 곳이 없다.
 *   /pda/** 는 SecurityConfiguration 에서 이미 CSRF 예외라 여기로 감싼다.
 *   웹 화면의 CSRF 보호를 풀지 않으면서 앱을 붙이는 방법이다.
 */
@RestController
@RequestMapping("/pda/inventory/material_move")
public class MaterialMoveApiController {

    @Autowired
    MaterialMoveApiService materialMoveApiService;

    /** 자재 목록·재고 계산은 웹과 같은 서비스를 쓴다 (수치가 갈리면 안 된다) */
    @Autowired
    MaterialMoveService materialMoveService;

    @Autowired
    MatInoutRepository matInoutRepository;

    /** 창고 목록 (출발·도착 공용) */
    @GetMapping("/storehouse_list")
    public AjaxResult storeHouseList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = materialMoveApiService.getStoreHouseList(spjangcd);
        return result;
    }

    /**
     * 이동 가능한 자재 목록.
     * storehouse_id 를 넘기면 그 창고에 재고가 잡힌 자재만 나온다.
     */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "storehouse_id", required = false) Integer storehouseId,
            @RequestParam(value = "material_id", required = false) Integer materialId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {

        AjaxResult result = new AjaxResult();
        result.data = materialMoveService.getMaterialMoveList(storehouseId, materialId, keyword, spjangcd);
        return result;
    }

    /**
     * 창고이동 저장.
     *
     * 웹은 move_list 를 JSON <b>문자열</b>로 받지만, 앱에서는 그대로 배열로 보내는 게
     * 자연스러워 @RequestBody 로 받는다.
     *
     * payload = {
     *   to_storehouse_id : 도착 창고
     *   move_list        : [ { mat_id, storehouse_id, move_qty }, ... ]
     * }
     */
    @PostMapping("/move")
    @Transactional
    public AjaxResult move(@RequestBody Map<String, Object> payload, Authentication auth) {

        AjaxResult result = new AjaxResult();

        // /pda/** 는 permitAll 이라 세션이 끊긴 채로도 도달한다.
        // 그때 principal 은 문자열이라 캐스팅에서 터지므로 먼저 거른다.
        if (auth == null || !(auth.getPrincipal() instanceof User)) {
            result.success = false;
            result.message = "로그인이 필요합니다. 다시 로그인해 주세요.";
            return result;
        }
        User user = (User) auth.getPrincipal();

        Integer toStoreHouseId = toInt(payload.get("to_storehouse_id"));
        if (toStoreHouseId == null) {
            result.success = false;
            result.message = "도착 창고를 선택하세요.";
            return result;
        }

        Object rawList = payload.get("move_list");
        if (!(rawList instanceof List)) {
            result.success = false;
            result.message = "이동할 자재가 없습니다.";
            return result;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moveList = (List<Map<String, Object>>) rawList;
        if (moveList.isEmpty()) {
            result.success = false;
            result.message = "이동할 자재가 없습니다.";
            return result;
        }

        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        int moved = 0;

        try {
            for (Map<String, Object> row : moveList) {
                Integer matId = toInt(row.get("mat_id"));
                Integer fromHouseId = toInt(row.get("storehouse_id"));
                float moveQty = toFloat(row.get("move_qty"));

                if (matId == null || fromHouseId == null || moveQty <= 0) continue;

                // 같은 창고로 옮기면 수불만 두 줄 늘고 재고는 그대로다. 막는다.
                if (fromHouseId.equals(toStoreHouseId)) {
                    result.success = false;
                    result.message = "출발 창고와 도착 창고가 같습니다.";
                    return result;
                }

                // 출고
                MaterialInout mo = new MaterialInout();
                mo.setStoreHouseId(fromHouseId);
                mo.setMaterialId(matId);
                mo.setInOut("out");
                mo.setOutputType("move_out");
                mo.setOutputQty(moveQty);
                mo.setInoutDate(nowDate);
                mo.setInoutTime(nowTime);
                mo.setDescription("재고이동");
                mo.setState("confirmed");
                mo.set_status("a");
                mo.set_audit(user);
                matInoutRepository.save(mo);

                // 입고
                MaterialInout mi = new MaterialInout();
                mi.setStoreHouseId(toStoreHouseId);
                mi.setMaterialId(matId);
                mi.setInOut("in");
                mi.setInputType("move_in");
                mi.setInputQty(moveQty);
                mi.setInoutDate(nowDate);
                mi.setInoutTime(nowTime);
                mi.setDescription("재고이동");
                mi.setState("confirmed");
                mi.set_status("a");
                mi.set_audit(user);
                matInoutRepository.save(mi);

                moved++;
            }
        } catch (Exception e) {
            // @Transactional 이므로 전부 롤백된다
            result.success = false;
            result.message = "이동 처리 중 오류: " + e.getMessage();
            return result;
        }

        if (moved == 0) {
            result.success = false;
            result.message = "이동할 수량이 입력되지 않았습니다.";
            return result;
        }

        result.success = true;
        result.message = moved + "건 이동되었습니다.";
        result.data = moved;
        return result;
    }

    // ===== helper =====
    private Integer toInt(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private float toFloat(Object o) {
        if (o == null || o.toString().isBlank()) return 0f;
        try {
            return Float.parseFloat(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
