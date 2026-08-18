package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.ProdResultService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 생산 실적 입력 (가공 키오스크)
 *
 * 작업자 → 설비(공정 자동) → 프로젝트 → 품목 → 유형 → 수량
 *
 * [설계 메모]
 *  - 기록 단위는 가공품(부품) 개수. 유닛 개수가 아니다.
 *  - 가공공정은 화면이 보내는 값을 믿지 않고 설비 코드로 서버에서 결정한다.
 *  - 유형 미선택은 'etc' 로 정규화 (회의록: 구분 안 되면 전부 기타).
 *  - 재고 차감 없음. 누적 생산량만 쌓는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/prod_result_kiosk")
@RequiredArgsConstructor
public class ProdResultController {

	private final ProdResultService prodResultService;

	// =================================================================
	// 마스터
	// =================================================================

	/** 가공공정 목록 (키오스크 선택용) */
	@GetMapping("/operation_list")
	public AjaxResult operationList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getOperationList(spjangcd);
		return result;
	}

	/** 설비 목록 (공정 지정 시 해당 공정만) */
	@GetMapping("/equipment_list")
	public AjaxResult equipmentList(@RequestParam("spjangcd") String spjangcd,
									@RequestParam(value = "operation", required = false) String operation) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getEquipmentList(spjangcd, operation);
		return result;
	}

	/** 작업자 목록 (person 테이블. 담당 워크센터 우선) */
	@GetMapping("/worker_list")
	public AjaxResult workerList(@RequestParam("spjangcd") String spjangcd,
								 @RequestParam(value = "workCenterId", required = false) Integer workCenterId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getWorkerList(spjangcd, workCenterId);
		return result;
	}

	/** 진행중 프로젝트 */
	@GetMapping("/project_list")
	public AjaxResult projectList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getProjectList(spjangcd);
		return result;
	}

	/** 품목 목록 (작업 지시된 것만) */
	@GetMapping("/item_list")
	public AjaxResult itemList(@RequestParam("spjangcd") String spjangcd,
							   @RequestParam("projNo") String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getItemList(spjangcd, projNo);
		return result;
	}

	/**
	 * 유형 타일 - 필요량(BOM) vs 누적 생산량.
	 * 생산지시 화면에서 등록한 부품이 여기 타일로 나타난다.
	 */
	@GetMapping("/kind_tiles")
	public AjaxResult kindTiles(@RequestParam("spjangcd") String spjangcd,
								@RequestParam(value = "projNo", required = false) String projNo,
								@RequestParam(value = "sujuId", required = false) Integer sujuId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getKindTiles(spjangcd, projNo, sujuId);
		return result;
	}

	// =================================================================
	// 실적
	// =================================================================

	/** 실적 등록 */
	@PostMapping("/save")
	@Transactional
	public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

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

		// 공정은 화면 값을 믿지 않고 설비로 결정한다
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
						  @RequestParam(value = "equipment", required = false) String equipment) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getResultLog(spjangcd, prodDate, equipment);
		return result;
	}

	// =================================================================
	// 작업중
	// =================================================================

	@GetMapping("/working_list")
	public AjaxResult workingList(@RequestParam("spjangcd") String spjangcd,
								  @RequestParam(value = "equipment", required = false) String equipment) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodResultService.getWorkingList(spjangcd, equipment);
		return result;
	}

	@PostMapping("/working_start")
	@Transactional
	public AjaxResult workingStart(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String spjangcd = str(payload.get("spjangcd"));
		String equipment = str(payload.get("equipment"));

		if (equipment.isEmpty()) {
			result.success = false;
			result.message = "설비를 선택하세요.";
			return result;
		}

		String operation = prodResultService.operationOf(spjangcd, equipment);
		if (operation.isEmpty()) {
			result.success = false;
			result.message = "설비에 연결된 가공공정이 없습니다: " + equipment;
			return result;
		}

		prodResultService.startWorking(payload, operation, user);

		result.success = true;
		result.message = "작업을 시작했습니다.";
		return result;
	}

	@PostMapping("/working_end")
	@Transactional
	public AjaxResult workingEnd(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();

		Integer id = toInt(payload.get("id"));
		if (id == null) {
			result.success = false;
			result.message = "종료할 작업이 지정되지 않았습니다.";
			return result;
		}

		prodResultService.endWorking(id);
		result.success = true;
		result.message = "작업을 종료했습니다.";
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