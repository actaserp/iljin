package mes.app.pda.controller;

import mes.app.pda.service.EquipHistoryApiService;
import mes.domain.entity.BundleHead;
import mes.domain.entity.EquipmentHistory;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.BundleHeadRepository;
import mes.domain.repository.EquipmentHistoryRepository;
import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pda/equip/history")
public class EquipHistoryApiController {

    @Autowired
    EquipHistoryApiService equipHistoryApiService;

    @Autowired
    EquipmentHistoryRepository equipmentHistoryRepository;

    @Autowired
    BundleHeadRepository bundleHeadRepository;

    /**
     * 설비 목록 조회
     */
    @GetMapping("/equip_list")
    public AjaxResult getEquipList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = equipHistoryApiService.getEquipList(spjangcd);
        return result;
    }

    /**
     * 설비이력 목록 조회
     */
    @GetMapping("/read")
    public AjaxResult getList(
            @RequestParam(value = "start_date", required = false) String startDate,
            @RequestParam(value = "end_date", required = false) String endDate,
            @RequestParam(value = "equ_id", required = false) Integer equId,
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = equipHistoryApiService.getList(startDate, endDate, equId, spjangcd);
        return result;
    }

    /**
     * 설비이력 저장
     */
    @PostMapping("/save")
    public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {
        AjaxResult result = new AjaxResult();

        // /pda/** 는 permitAll 이라 세션이 끊긴 상태로도 여기 도달한다.
        // 그때 principal 은 문자열 "anonymousUser" 라 캐스팅에서 터지므로 먼저 거른다.
        if (auth == null || !(auth.getPrincipal() instanceof User)) {
            result.success = false;
            result.message = "로그인이 필요합니다. 다시 로그인해 주세요.";
            return result;
        }
        User user = (User) auth.getPrincipal();

        try {
            Integer equId = Integer.parseInt(payload.get("equ_id").toString());
            String dataDate = payload.get("data_date").toString();
            String content = payload.getOrDefault("content", "").toString();
            String description = payload.getOrDefault("description", "").toString();
            String manager = payload.getOrDefault("manager", "").toString();
            Object costObj = payload.get("cost");
            Integer cost = costObj != null && !costObj.toString().isEmpty()
                    ? Integer.parseInt(costObj.toString()) : null;

            // eh_id 가 오면 수정이다.
            // 예전에는 이 분기가 없어 수정을 눌러도 새 행이 하나 더 생겼다
            // (웹 EquipHistoryCardController.save 에는 있던 분기가 이식에서 빠졌다).
            Object ehIdObj = payload.get("eh_id");
            if (ehIdObj != null && !ehIdObj.toString().isEmpty()) {
                Integer ehId = Integer.parseInt(ehIdObj.toString());
                EquipmentHistory eh = equipmentHistoryRepository.getEquipmentHistoryById(ehId);
                if (eh == null) {
                    result.success = false;
                    result.message = "수정할 설비이력을 찾을 수 없습니다.";
                    return result;
                }

                eh.setEquipmentId(equId);
                eh.setDataDate(Date.valueOf(dataDate));
                eh.setContent(content);
                eh.setDescription(description);
                eh.setCost(cost);
                eh.setChar1(manager);
                eh.set_audit(user);
                equipmentHistoryRepository.save(eh);

                // 연결된 BundleHead 도 같이 맞춰 준다 (목록의 bh_id 가 여기서 나온다)
                if (eh.getApprDataPk() != null) {
                    BundleHead bh = bundleHeadRepository.getBundleHeadById(eh.getApprDataPk());
                    if (bh != null) {
                        bh.setChar1(content);
                        bh.setDate1(CommonUtil.tryTimestamp(dataDate));
                        bh.set_audit(user);
                        bundleHeadRepository.save(bh);
                    }
                }

                result.success = true;
                result.message = "수정되었습니다.";
                return result;
            }

            // BundleHead 생성
            BundleHead bh = new BundleHead();
            bh.setTableName("equip_history");
            bh.setChar1(content);
            bh.setDate1(CommonUtil.tryTimestamp(dataDate));
            bh.set_audit(user);
            bh = bundleHeadRepository.save(bh);

            // EquipmentHistory 생성
            EquipmentHistory eh = new EquipmentHistory();
            eh.setEquipmentId(equId);
            eh.setDataDate(Date.valueOf(dataDate));
            eh.setContent(content);
            eh.setDescription(description);
            eh.setCost(cost);
            eh.setChar1(manager);
            eh.setApprDataPk(bh.getId());
            eh.setApprTableName("bundle_head");
            eh.set_status("history");
            eh.set_audit(user);
            equipmentHistoryRepository.save(eh);

            result.success = true;
            result.message = "저장되었습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "저장 실패: " + e.getMessage();
        }
        return result;
    }

    /**
     * 설비이력 삭제
     */
    @PostMapping("/delete")
    public AjaxResult delete(@RequestBody Map<String, Object> payload) {
        AjaxResult result = new AjaxResult();
        try {
            Integer bhId = Integer.parseInt(payload.get("bh_id").toString());
            equipmentHistoryRepository.deleteByApprDataPk(bhId);
            bundleHeadRepository.deleteById(bhId);
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제 실패: " + e.getMessage();
        }
        return result;
    }
}
