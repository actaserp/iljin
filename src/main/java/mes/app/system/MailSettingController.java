package mes.app.system;

import mes.app.MailService;
import mes.app.balju.service.BaljuMailCcService;
import mes.app.system.service.MailSettingService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 시스템관리 > 메일 설정 (2026-11-03)
 *   GET  /api/system/mail_setting/read    회사 공용 SMTP 설정 (비밀번호 제외) + 메일 서비스 기본값 목록
 *   POST /api/system/mail_setting/save    저장 (password 가 비면 기존 비밀번호 유지)
 *   POST /api/system/mail_setting/test    저장 전 값으로 시험 발송 (to 생략 시 로그인 사용자 이메일)
 *   GET  /api/system/mail_setting/status  발송 가능 여부 (발주서 발송 팝업 「보내는 계정」 표시용)
 *   GET  /api/system/mail_setting/cc_list 발주메일 고정 참조 (사용자 + 체크 여부)
 *   POST /api/system/mail_setting/cc_save 고정 참조 저장 {ids}
 *
 *  화면(메뉴)은 관리자만 열리게 메뉴 권한으로 막는다. 서버에서도 save/test 는 관리자만 허용한다.
 */
@RestController
@RequestMapping("/api/system/mail_setting")
public class MailSettingController {

	@Autowired
	MailSettingService mailSettingService;

	@Autowired
	MailService mailService;

	@Autowired
	BaljuMailCcService baljuMailCcService;

	@GetMapping("/read")
	public AjaxResult read(Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isAdmin(auth)) { r.success = false; r.message = "관리자만 볼 수 있습니다."; return r; }
		r.data = mailSettingService.getCompanyForView();
		return r;
	}

	@GetMapping("/status")
	public AjaxResult status() {
		AjaxResult r = new AjaxResult();
		r.data = mailSettingService.companyStatus();
		return r;
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isAdmin(auth)) { r.success = false; r.message = "관리자만 저장할 수 있습니다."; return r; }
		try {
			mailSettingService.saveCompany(body, ((User) auth.getPrincipal()).getId());
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@PostMapping("/test")
	public AjaxResult test(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isAdmin(auth)) { r.success = false; r.message = "관리자만 시험 발송할 수 있습니다."; return r; }
		User user = (User) auth.getPrincipal();
		String to = body.get("to") == null ? null : body.get("to").toString().trim();
		if (!StringUtils.hasText(to)) to = user.getEmail();
		if (!StringUtils.hasText(to) || !to.contains("@")) {
			r.success = false;
			r.message = "시험 메일을 받을 주소를 입력하세요. (사용자 관리에 이메일이 없습니다)";
			return r;
		}
		try {
			mailService.sendTest(body, to);
			r.message = to + " 로 시험 메일을 보냈습니다. 메일함(스팸함 포함)을 확인하세요.";
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	/** 발주메일 고정 참조 목록 (사용자 화면에서 옮김) */
	@GetMapping("/cc_list")
	public AjaxResult ccList(Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isAdmin(auth)) { r.success = false; r.message = "관리자만 볼 수 있습니다."; return r; }
		r.data = baljuMailCcService.listForSetting();
		return r;
	}

	/** body = {ids:[user_id...]} — 체크된 사람만 참조로 남긴다 */
	@PostMapping("/cc_save")
	public AjaxResult ccSave(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isAdmin(auth)) { r.success = false; r.message = "관리자만 저장할 수 있습니다."; return r; }
		java.util.List<Integer> ids = new java.util.ArrayList<>();
		if (body.get("ids") instanceof java.util.List<?> l)
			for (Object o : l) {
				try { ids.add(Integer.valueOf(String.valueOf(o))); } catch (NumberFormatException ignore) { }
			}
		baljuMailCcService.saveAll(ids);
		return r;
	}

	/** superuser 또는 사용자그룹 코드 admin/dev */
	private boolean isAdmin(Authentication auth) {
		if (auth == null || !(auth.getPrincipal() instanceof User u)) return false;
		if (Boolean.TRUE.equals(u.getSuperUser())) return true;
		try {
			String code = u.getUserProfile().getUserGroup().getCode();
			return "admin".equalsIgnoreCase(code) || "dev".equalsIgnoreCase(code);
		} catch (Exception e) {
			return false;
		}
	}
}