package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.WbsFormService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * WBS 표준 템플릿 관리.
 *
 *  화면: project_registration.html 의 [WBS 표준 템플릿 관리] 모달
 *        (ProjectRegistrationPage.wfUrl() = '/api/transaction/wbsForm')
 *
 *  ※ 경로가 화면의 wfUrl() 과 정확히 같아야 한다. 다르면 404 가 나는데
 *    AjaxUtil 의 실패 콜백을 안 넘기고 있어 목록이 조용히 빈 채로 남는다.
 *    ("등록된 템플릿이 없습니다" 만 뜨고 에러가 안 보인다)
 */
@Slf4j
@RestController
@RequestMapping("/api/transaction/wbsForm")
public class WbsFormController {

	@Autowired
	WbsFormService wbsFormService;

	/** 템플릿 목록 (사용 건수 포함). 상태 무관 — 모달 왼쪽 목록용 */
	@GetMapping("/form_list")
	public AjaxResult getFormList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = this.wbsFormService.getFormList(spjangcd);
		result.success = true;
		return result;
	}

	/**
	 * 적용중(active) 템플릿만. 프로젝트 등록 폼의 콤보용.
	 *  개정으로 이전 리비전이 expired 되면 여기서 빠진다 —
	 *  화면이 (지난 버전) 항목을 따로 붙여 처리한다.
	 */
	@GetMapping("/active_list")
	public AjaxResult getActiveFormList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.data = this.wbsFormService.getActiveFormList(spjangcd);
		result.success = true;
		return result;
	}

	/** 템플릿 상세 = 마스터 + 대단계 + 세부단계 (중첩) */
	@GetMapping("/form_detail")
	public AjaxResult getFormDetail(@RequestParam("id") Integer id) {
		AjaxResult result = new AjaxResult();
		Map<String, Object> detail = this.wbsFormService.getFormDetail(id);

		if (detail == null) {
			result.success = false;
			result.message = "템플릿을 찾을 수 없습니다.";
			return result;
		}
		result.data = detail;
		result.success = true;
		return result;
	}

	/**
	 * 초안 저장. 신규면 Rev.1 draft 를 만든다.
	 *
	 *  ★ 화면이 응답의 data 를 새 목록의 선택 id 로 쓴다 (wfLoadList(result.data)).
	 *    서비스가 돌려주는 map 의 "id" 를 data 에 넣어야 저장 직후 그 템플릿이 다시 열린다.
	 *
	 *  ★ spjangcd 는 payload 에 실려 온다 (wfSave 가 붙여 보낸다).
	 *    URL 파라미터로 받으면 안 된다.
	 */
	@PostMapping("/form_save")
	@Transactional
	public AjaxResult saveForm(@RequestBody Map<String, Object> payload,
														 Authentication auth) {
		User user = (auth == null) ? null : (User) auth.getPrincipal();
		String spjangcd = asStr(payload.get("spjangcd"));

		Map<String, Object> r = this.wbsFormService.saveForm(payload, spjangcd, user);

		AjaxResult result = new AjaxResult();
		result.success = Boolean.TRUE.equals(r.get("success"));
		result.message = asStr(r.get("message"));
		result.data = r.get("id");
		return result;
	}

	/** 개정 = active 를 복사해 revision+1 draft 생성 */
	@PostMapping("/form_revise")
	@Transactional
	public AjaxResult reviseForm(@RequestBody Map<String, Object> params,
															 Authentication auth) {
		User user = (auth == null) ? null : (User) auth.getPrincipal();

		Map<String, Object> r = this.wbsFormService.reviseForm(
			asInt(params.get("base_id")), asStr(params.get("spjangcd")), user);

		AjaxResult result = new AjaxResult();
		result.success = Boolean.TRUE.equals(r.get("success"));
		result.message = asStr(r.get("message"));
		result.data = r.get("id");
		return result;
	}

	/**
	 * 적용 = draft → active. 같은 form_code 의 기존 active 는 expired 로 내린다.
	 *
	 *  화면은 [적용] 을 누르면 form_save 를 먼저 부르고 이어서 여기를 부른다.
	 *  이미 만들어진 프로젝트 일정은 바뀌지 않는다 (재전개해야 반영된다).
	 */
	@PostMapping("/form_apply")
	@Transactional
	public AjaxResult applyForm(@RequestBody Map<String, Object> params,
															Authentication auth) {
		User user = (auth == null) ? null : (User) auth.getPrincipal();

		Map<String, Object> r = this.wbsFormService.applyForm(
			asInt(params.get("id")), asStr(params.get("spjangcd")), user);

		AjaxResult result = new AjaxResult();
		result.success = Boolean.TRUE.equals(r.get("success"));
		result.message = asStr(r.get("message"));
		return result;
	}

	/** 초안 삭제. 프로젝트에서 사용중이면 서비스가 막는다 */
	@PostMapping("/form_delete")
	@Transactional
	public AjaxResult deleteForm(@RequestBody Map<String, Object> params) {
		Map<String, Object> r = this.wbsFormService.deleteForm(asInt(params.get("id")));

		AjaxResult result = new AjaxResult();
		result.success = Boolean.TRUE.equals(r.get("success"));
		result.message = asStr(r.get("message"));
		return result;
	}

	/* ================= 헬퍼 ================= */

	private String asStr(Object o) {
		if (o == null) return null;
		String s = String.valueOf(o).trim();
		return s.isEmpty() ? null : s;
	}

	private Integer asInt(Object o) {
		if (o == null) return null;
		String s = String.valueOf(o).trim();
		if (s.isEmpty()) return null;
		try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
	}
}