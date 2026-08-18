package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.ProdAssemblyService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 조립 공정 (PC 화면 + 키오스크 공용 API)
 *
 *  동선: 작업자 → 프로젝트 → 품목 → 시작 / 완료(수량)
 *
 * [설계 메모]
 *  - 단위는 유닛(품목)이다. 가공 키오스크의 가공품 개수와 다른 축이다.
 *  - 조립 완료 누적이 곧 완제품 재고. 재고가 존재하는 유일한 지점이다.
 *  - 조립에는 설비가 없다. 작업중 상태의 키는 품목(suju).
 */
@Slf4j
@RestController
@RequestMapping("/api/production/prod_assembly")
@RequiredArgsConstructor
public class ProdAssemblyController {

	private final ProdAssemblyService prodAssemblyService;

	// =================================================================
	// 조회
	// =================================================================

	@GetMapping("/project_list")
	public AjaxResult projectList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodAssemblyService.getProjectList(spjangcd);
		return result;
	}

	@GetMapping("/worker_list")
	public AjaxResult workerList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodAssemblyService.getWorkerList(spjangcd);
		return result;
	}

	/** 조립 대상 품목 (지시된 것만. 유니트수 / 완료 / 재공 / 진행중) */
	@GetMapping("/item_list")
	public AjaxResult itemList(@RequestParam("spjangcd") String spjangcd,
							   @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodAssemblyService.getItemList(spjangcd, projNo);
		return result;
	}

	@GetMapping("/working_list")
	public AjaxResult workingList(@RequestParam("spjangcd") String spjangcd,
								  @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodAssemblyService.getWorkingList(spjangcd, projNo);
		return result;
	}

	@GetMapping("/log")
	public AjaxResult log(@RequestParam("spjangcd") String spjangcd,
						  @RequestParam(value = "prodDate", required = false) String prodDate,
						  @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodAssemblyService.getResultLog(spjangcd, prodDate, projNo);
		return result;
	}

	// =================================================================
	// 작업 시작 / 완료
	// =================================================================

	@PostMapping("/working_start")
	@Transactional
	public AjaxResult workingStart(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		if (toInt(payload.get("sujuId")) == null) {
			result.success = false;
			result.message = "품목을 선택하세요.";
			return result;
		}
		if (str(payload.get("worker")).isEmpty()) {
			result.success = false;
			result.message = "작업자를 선택하세요.";
			return result;
		}

		prodAssemblyService.startWorking(payload, user);

		result.success = true;
		result.message = "조립을 시작했습니다.";
		return result;
	}

	/** 조립 완료 — 수량을 입력받아 실적을 남기고 작업중에서 내린다 */
	@PostMapping("/complete")
	@Transactional
	public AjaxResult complete(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer sujuId = toInt(payload.get("sujuId"));
		double qty = toDouble(payload.get("qty"));

		if (sujuId == null) {
			result.success = false;
			result.message = "품목을 선택하세요.";
			return result;
		}
		if (qty <= 0) {
			result.success = false;
			result.message = "완료 수량을 입력하세요.";
			return result;
		}
		if (str(payload.get("worker")).isEmpty()) {
			result.success = false;
			result.message = "작업자를 선택하세요.";
			return result;
		}

		prodAssemblyService.complete(payload, user);

		result.success = true;
		result.message = "조립을 완료했습니다.";
		return result;
	}

	/** 작업 취소 (실적 없이 작업중만 해제) */
	@PostMapping("/working_cancel")
	@Transactional
	public AjaxResult workingCancel(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();

		Integer sujuId = toInt(payload.get("sujuId"));
		if (sujuId == null) {
			result.success = false;
			result.message = "품목이 지정되지 않았습니다.";
			return result;
		}

		prodAssemblyService.endWorking(sujuId);
		result.success = true;
		result.message = "작업을 취소했습니다.";
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

		prodAssemblyService.deleteResult(id);
		result.success = true;
		result.message = "취소되었습니다.";
		return result;
	}

	// =================================================================
	// helper
	// =================================================================
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