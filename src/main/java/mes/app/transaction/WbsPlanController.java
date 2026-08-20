package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.WbsPlanService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WBS 계획 / 수주 확정.
 *
 *   /plan_list   프로젝트 계획 조회 (가안)
 *   /plan_make   템플릿 → 계획 생성            (프로젝트 등록 화면)
 *   /fix_list    수주 확정 WBS 조회
 *   /fix_make    계획 → 수주 확정              (수주 관리 화면, 'WBS 확정' 버튼)
 *   /rows_save   담당자 / 진척률 / 실적일 저장
 */
@Slf4j
@RestController
@RequestMapping("/api/transaction/wbsPlan")
public class WbsPlanController {

	@Autowired
	WbsPlanService wbsPlanService;

	@GetMapping("/plan_list")
	public AjaxResult planList(@RequestParam("spjangcd") String spjangcd,
														 @RequestParam("projno") String projno) {
		AjaxResult r = new AjaxResult();
		r.data = this.wbsPlanService.getPlanList(spjangcd, projno);
		return r;
	}

	@GetMapping("/fix_list")
	public AjaxResult fixList(@RequestParam("suju_head_id") Integer sujuHeadId) {
		AjaxResult r = new AjaxResult();
		r.data = this.wbsPlanService.getFixList(sujuHeadId);
		return r;
	}

	/** 템플릿을 프로젝트에 전개 */
	@PostMapping("/plan_make")
	@Transactional
	public AjaxResult planMake(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String spjangcd = str(payload.get("spjangcd"));
		String projno   = str(payload.get("projno"));
		Integer formId  = toInt(payload.get("wbs_form_id"));

		if (projno == null || projno.isEmpty()) return fail("프로젝트가 지정되지 않았습니다.");
		if (formId == null) return fail("WBS 템플릿을 선택하세요.");

		return toAjax(this.wbsPlanService.generatePlan(spjangcd, projno, formId, user));
	}

	/** 수주 확정 */
	@PostMapping("/fix_make")
	@Transactional
	public AjaxResult fixMake(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		Integer sujuHeadId = toInt(payload.get("suju_head_id"));
		if (sujuHeadId == null) return fail("수주가 지정되지 않았습니다.");

		return toAjax(this.wbsPlanService.confirmForSuju(sujuHeadId, user));
	}

	/** 담당자 / 진척률 / 실적일 저장 */
	@PostMapping("/rows_save")
	@Transactional
	@SuppressWarnings("unchecked")
	public AjaxResult rowsSave(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		Object rowsObj = payload.get("rows");
		List<Map<String, Object>> rows =
			(rowsObj instanceof List) ? (List<Map<String, Object>>) rowsObj : new ArrayList<>();

		return toAjax(this.wbsPlanService.saveRows(rows, user));
	}

	/* ---------- 헬퍼 ---------- */

	private AjaxResult fail(String msg) {
		AjaxResult r = new AjaxResult();
		r.success = false;
		r.message = msg;
		return r;
	}

	private AjaxResult toAjax(Map<String, Object> res) {
		AjaxResult r = new AjaxResult();
		r.success = Boolean.TRUE.equals(res.get("success"));
		r.message = (String) res.get("message");
		r.data = res.get("count");
		return r;
	}

	private static String str(Object o) {
		return (o == null) ? null : String.valueOf(o).trim();
	}

	private static Integer toInt(Object o) {
		if (o == null) return null;
		String s = String.valueOf(o).trim();
		if (s.isEmpty()) return null;
		try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
	}
}