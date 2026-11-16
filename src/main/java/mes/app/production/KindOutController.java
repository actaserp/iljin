package mes.app.production;

import lombok.RequiredArgsConstructor;
import mes.app.production.service.KindOutService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 유형별 외작 (생산 지시 → 프로젝트 전체 → 유형별 합계 표의 [외작]).
 * 발주 · 입고는 기존 외작 발주 / 외작 입고 화면이 그대로 받는다. 설명은 KindOutService.
 */
@RestController
@RequestMapping("/api/production/kind_out")
@RequiredArgsConstructor
public class KindOutController {

	private final KindOutService service;

	@GetMapping("/list")
	public AjaxResult list(@RequestParam(value = "spjangcd", required = false) String spjangcd,
						   @RequestParam("projNo") String projNo) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = service.list(spjangcd == null || spjangcd.isBlank() ? null : spjangcd, projNo);
		return r;
	}

	/** payload: {id?, projNo, kind, qty, companyId, companyName, dueDate?, remark?, spjangcd} */
	@PostMapping("/save")
	@Transactional
	public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {
		AjaxResult r = new AjaxResult();
		Object sp = payload.get("spjangcd");
		String err = service.save(payload, sp == null ? null : sp.toString(), (User) auth.getPrincipal());
		r.success = err == null;
		r.message = err;
		return r;
	}

	@PostMapping("/cancel")
	@Transactional
	public AjaxResult cancel(@RequestBody Map<String, Object> payload, Authentication auth) {
		AjaxResult r = new AjaxResult();
		Object id = payload.get("id");
		if (id == null) {
			r.success = false;
			r.message = "취소할 행이 없습니다.";
			return r;
		}
		String err = service.cancel((int) Double.parseDouble(id.toString()), (User) auth.getPrincipal());
		r.success = err == null;
		r.message = err;
		return r;
	}
}