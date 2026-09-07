package mes.app.pda.controller;

import mes.app.maintenance.service.EquipmentMaintService;
import mes.app.pda.service.EquipMaintApiService;
import mes.domain.entity.EquipmentMaint;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.EquipmentMaintRepository;
import mes.domain.services.CommonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.Map;

/**
 * 설비이력 (PDA) — 설비 보전(정비) 이력
 *
 * [웹 원본] templates/maintenance/equip_maint_a.html → /api/maintenance/equipment_maint
 * [테이블]  equip_maint
 *
 * 정비 구분은 예방정비(prevention) / 고장정비(failure) 둘이고,
 * 고장정비일 때만 고장 발생/복구 시각과 고장 내용을 함께 남긴다.
 *
 * [이 컨트롤러가 따로 있는 이유]
 *   /api/** 는 CSRF 보호를 받는데 앱에는 토큰을 심을 곳이 없다.
 *   /pda/** 는 이미 CSRF 예외라 여기로 감싼다.
 *   조회·저장 로직은 웹과 같은 EquipmentMaintService / Repository 를 그대로 쓴다.
 */
@RestController
@RequestMapping("/pda/maintenance/equip_maint")
public class EquipMaintApiController {

    @Autowired
    EquipMaintApiService equipMaintApiService;

    /** 목록 조회는 웹과 같은 서비스를 쓴다 (조건이 갈리면 안 된다) */
    @Autowired
    EquipmentMaintService equipmentMaintService;

    @Autowired
    EquipmentMaintRepository equipmentMaintRepository;

    /** 설비 목록 (폐기 설비 제외) */
    @GetMapping("/equip_list")
    public AjaxResult equipList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {
        AjaxResult result = new AjaxResult();
        result.data = equipMaintApiService.getEquipList(spjangcd);
        return result;
    }

    /**
     * 정비 이력 목록.
     * 웹은 srchStartDt / srchEndDt / cboEquipment 라는 화면 컨트롤 이름을 그대로 쓰지만,
     * 앱에서는 뜻이 드러나는 이름으로 받고 여기서 넘겨준다.
     */
    @GetMapping("/read")
    public AjaxResult read(
            @RequestParam(value = "start_date", required = false) String startDate,
            @RequestParam(value = "end_date", required = false) String endDate,
            @RequestParam(value = "equ_id", required = false) String equId) {

        // 원 쿼리는 cast("DataDate" as text) between :start and :end 로 비교한다.
        // 종료일을 'yyyy-MM-dd' 로 그대로 넘기면 그날 등록분('...-31 09:00:00.1')이
        // 문자열 비교에서 뒤로 밀려 빠진다. 하루 끝까지 채워서 넘긴다.
        String end = endDate == null ? "" : endDate.trim();
        if (end.length() == 10) end = end + " 23:59:59";

        AjaxResult result = new AjaxResult();
        result.data = equipmentMaintService.getEquipmentMaintList(
                startDate == null ? "" : startDate,
                end,
                equId == null ? "" : equId);
        return result;
    }

    /** 상세 (수정 화면을 열 때 쓴다 — 목록에 없는 시각·정비업체 항목이 여기 있다) */
    @GetMapping("/detail")
    public AjaxResult detail(@RequestParam("id") int id) {
        AjaxResult result = new AjaxResult();
        result.data = equipmentMaintService.getEquipmentMaintDetail(id);
        return result;
    }

    /**
     * 저장 (신규/수정).
     *
     * id 가 오면 수정이다. 웹 컨트롤러와 같은 규칙을 그대로 따른다.
     * 시각은 비워 보낼 수 있게 하고 그때 00:00 으로 채운다 —
     * 현장에서 분 단위까지 적지 않는 경우가 많은데,
     * LocalTime.parse 는 빈 문자열에서 예외를 던진다.
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
            Integer id = toInt(payload.get("id"));
            Integer equipmentId = toInt(payload.get("equipment_id"));
            String maintType = str(payload.get("maint_type"));

            if (equipmentId == null) {
                result.success = false;
                result.message = "설비를 선택하세요.";
                return result;
            }
            if (!"prevention".equals(maintType) && !"failure".equals(maintType)) {
                result.success = false;
                result.message = "정비구분을 선택하세요.";
                return result;
            }

            String maintStartDate = str(payload.get("maint_start_date"));
            String maintEndDate = str(payload.get("maint_end_date"));
            if (maintStartDate.isEmpty() || maintEndDate.isEmpty()) {
                result.success = false;
                result.message = "정비 시작일과 종료일을 입력하세요.";
                return result;
            }

            EquipmentMaint em = (id == null)
                    ? new EquipmentMaint()
                    : equipmentMaintRepository.getEquipmentMaintById(id);
            if (em == null) {
                result.success = false;
                result.message = "수정할 정비 이력을 찾을 수 없습니다.";
                return result;
            }

            em.setDataDate(new Timestamp(System.currentTimeMillis()));
            em.setEquipment_id(equipmentId);
            em.setMaintType(maintType);
            em.setMaintStartDate(CommonUtil.tryTimestamp(maintStartDate));
            em.setMaintEndDate(CommonUtil.tryTimestamp(maintEndDate));
            em.setMaintStartTime(time(payload.get("maint_start_time")));
            em.setMaintEndTime(time(payload.get("maint_end_time")));
            em.setServicerName(str(payload.get("service_name")));
            em.setMaintCost(CommonUtil.tryFloatNull(payload.get("maint_cost")));
            em.setDescription(str(payload.get("description")));

            // 고장 관련 항목은 고장정비일 때만 채운다.
            // 예방정비로 바꿔 저장하면 이전 고장 값이 남지 않도록 비운다.
            if ("failure".equals(maintType)) {
                String failStart = str(payload.get("fail_start_date"));
                String failEnd = str(payload.get("fail_end_date"));
                em.setFailStartDate(failStart.isEmpty() ? null : CommonUtil.tryTimestamp(failStart));
                em.setFailEndDate(failEnd.isEmpty() ? null : CommonUtil.tryTimestamp(failEnd));
                em.setFailStartTime(timeOrNull(payload.get("fail_start_time")));
                em.setFailEndTime(timeOrNull(payload.get("fail_end_time")));
                em.setFailHr(CommonUtil.tryFloatNull(payload.get("fail_hr")));
                em.setFailDescription(CommonUtil.tryString(str(payload.get("fail_description"))));
            } else {
                em.setFailStartDate(null);
                em.setFailEndDate(null);
                em.setFailStartTime(null);
                em.setFailEndTime(null);
                em.setFailHr(null);
                em.setFailDescription(null);
            }

            em.set_audit(user);
            equipmentMaintRepository.save(em);

            result.success = true;
            result.message = (id == null) ? "저장되었습니다." : "수정되었습니다.";
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
            Integer id = toInt(payload.get("id"));
            if (id == null) {
                result.success = false;
                result.message = "삭제할 정비 이력이 지정되지 않았습니다.";
                return result;
            }
            equipmentMaintRepository.deleteById(id);
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

    private Integer toInt(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 비어 있으면 00:00 (정비 시각은 not null 로 다뤄진다) */
    private LocalTime time(Object o) {
        LocalTime t = timeOrNull(o);
        return t == null ? LocalTime.MIDNIGHT : t;
    }

    /** "HH:mm" / "HH:mm:ss" 를 받는다. 형식이 어긋나면 null */
    private LocalTime timeOrNull(Object o) {
        String s = str(o);
        if (s.isEmpty()) return null;
        try {
            return LocalTime.parse(s.length() == 5 ? s : s.substring(0, 8));
        } catch (Exception e) {
            return null;
        }
    }
}
