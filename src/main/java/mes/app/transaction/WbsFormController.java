package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.WbsFormService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * WBS 표준 템플릿 관리.
 *
 *  draft --[/form_apply]--> active --[/form_revise]--> 새 draft
 *   · active 는 읽기전용
 *   · 사용중인 템플릿은 삭제 불가
 */
@Slf4j
@RestController
@RequestMapping("/api/transaction/wbsForm")
public class WbsFormController {

	@Autowired
	WbsFormService wbsFormService;

	/** 템플릿 목록 */
	@GetMapping("/form_list")
	public AjaxResult formList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.wbsFormService.getFormList(spjangcd);
		return r;
	}

	/** 적용중 템플릿만 — 프로젝트 등록 콤보용 */
	@GetMapping("/active_list")
	public AjaxResult activeList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.data = this.wbsFormService.getActiveFormList(spjangcd);
		return r;
	}

	/** 템플릿 상세 (대단계 + 세부단계 중첩) */
	@GetMapping("/form_detail")
	public AjaxResult formDetail(@RequestParam("id") Integer id) {
		AjaxResult r = new AjaxResult();
		Map<String, Object> data = this.wbsFormService.getFormDetail(id);
		if (data == null) {
			r.success = false;
			r.message = "템플릿을 찾을 수 없습니다.";
			return r;
		}
		r.data = data;
		return r;
	}

	/** 초안 저장 (신규 등록 포함) */
	@PostMapping("/form_save")
	@Transactional
	public AjaxResult formSave(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String spjangcd = str(payload.get("spjangcd"));

		Map<String, Object> res = this.wbsFormService.saveForm(payload, spjangcd, user);
		return toAjax(res);
	}

	/** 개정 — active 복사해서 revision+1 초안 생성 */
	@PostMapping("/form_revise")
	@Transactional
	public AjaxResult formRevise(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		Integer baseId = toInt(payload.get("base_id"));
		String spjangcd = str(payload.get("spjangcd"));

		if (baseId == null) {
			AjaxResult r = new AjaxResult();
			r.success = false;
			r.message = "개정할 템플릿이 지정되지 않았습니다.";
			return r;
		}
		return toAjax(this.wbsFormService.reviseForm(baseId, spjangcd, user));
	}

	/** 적용 — draft → active */
	@PostMapping("/form_apply")
	@Transactional
	public AjaxResult formApply(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		Integer id = toInt(payload.get("id"));
		String spjangcd = str(payload.get("spjangcd"));

		if (id == null) {
			AjaxResult r = new AjaxResult();
			r.success = false;
			r.message = "적용할 템플릿이 지정되지 않았습니다.";
			return r;
		}
		return toAjax(this.wbsFormService.applyForm(id, spjangcd, user));
	}

	/** 초안 삭제 */
	@PostMapping("/form_delete")
	@Transactional
	public AjaxResult formDelete(@RequestBody Map<String, Object> payload) {
		Integer id = toInt(payload.get("id"));
		if (id == null) {
			AjaxResult r = new AjaxResult();
			r.success = false;
			r.message = "삭제할 템플릿이 지정되지 않았습니다.";
			return r;
		}
		return toAjax(this.wbsFormService.deleteForm(id));
	}

	/* ---------- 헬퍼 ---------- */

	private AjaxResult toAjax(Map<String, Object> res) {
		AjaxResult r = new AjaxResult();
		r.success = Boolean.TRUE.equals(res.get("success"));
		r.message = (String) res.get("message");
		r.data = res.get("id");
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