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
 * [저장 위치]  신규 테이블 없음. 표준 mat_produce 에 쌓는다.
 *
 * [조립은 한 단계]
 *   mat_produce."ProcessOrder" = 1, LastProcessYN = 'N'
 *   부품 → 유닛. 목표는 suju.unit_qty (유닛 총량)
 *
 *   예전에는 유닛 조립(1) / 공정 조립(2) 두 단계였는데 공정 조립을 폐기했다.
 *   현장이 "지그 1개 완료" 를 따로 세지 않았고,
 *   지그 대수를 담은 "Standard" 가 '1/1'(한 쌍) 같은 표기라 수량으로 쓸 수 없었다.
 *   <b>공정 완료 판정은 검사(ProcessOrder=3)가 가져간다</b> —
 *   검사 수량이 지그 대수(suju."SujuQty")에 도달하면 그 공정은 끝이다.
 *
 *   축이 둘로 줄었다.  유닛(조립) / 지그(검사).  환산이 없다.
 *
 * [설계 메모]
 *  - 진행중은 별도 테이블이 아니라 mat_produce."State" <> 'finished' 로 표현한다.
 *  - 부품을 소모하는 공정은 없다. mat_consu 를 만들지 않는다.
 *  - 재고를 만들지 않는다. mat_lot / mat_inout 생성 경로를 타지 않는다.
 *  - 검사는 유닛이 아니라 <b>공정(set) 단위</b>다.
 *    LastProcessYN='Y' 완료 건이 검사 대상이 된다.
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

	/** 조립 작업자 (재직자만. 조립 워크센터 인원이 위로) */
	@GetMapping("/worker_list")
	public AjaxResult workerList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodAssemblyService.getWorkerList(spjangcd);
		return result;
	}

	/**
	 * 조립 대상 품목 (지시된 것만).
	 *
	 * target_qty / done_qty / wip_qty 는 <b>유닛 축</b>이다 (suju.unit_qty 기준).
	 * set_target / insp_done / done_yn 은 검사 축이며 공정 완료 여부를 알려 준다.
	 */
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

	/**
	 * 조립 시작.
	 * 작업지시가 없는 품목은 실적을 붙일 job_res 가 없으므로 막는다.
	 */
	@PostMapping("/working_start")
	@Transactional
	public AjaxResult workingStart(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer sujuId = toInt(payload.get("sujuId"));
		if (sujuId == null) {
			result.success = false;
			result.message = "품목을 선택하세요.";
			return result;
		}
		if (toInt(payload.get("workerId")) == null) {
			result.success = false;
			result.message = "작업자를 선택하세요.";
			return result;
		}
		if (prodAssemblyService.getOrderInfo(sujuId) == null) {
			result.success = false;
			result.message = "작업 지시가 없는 품목입니다. 생산 지시 화면에서 먼저 지시하세요.";
			return result;
		}

		prodAssemblyService.startWorking(payload, user);

		result.success = true;
		result.message = "조립을 시작했습니다.";
		return result;
	}

	/**
	 * 조립 완료 — 수량을 입력받아 실적을 마감한다.
	 *
	 * 시작을 누르지 않고 바로 완료해도 동작한다 (현장이 시작을 자주 건너뛴다).
	 *
	 * 초과 완료는 <b>막지 않는다.</b> 목표보다 더 만드는 경우가 있고,
	 * 여기서 막으면 실적을 아예 입력하지 않게 되어 데이터가 더 나빠진다.
	 */
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
		if (toInt(payload.get("workerId")) == null) {
			result.success = false;
			result.message = "작업자를 선택하세요.";
			return result;
		}
		if (prodAssemblyService.getOrderInfo(sujuId) == null) {
			result.success = false;
			result.message = "작업 지시가 없는 품목입니다. 생산 지시 화면에서 먼저 지시하세요.";
			return result;
		}

		prodAssemblyService.complete(payload, user);

		result.success = true;
		result.message = "조립을 완료했습니다.";
		return result;
	}

	/** 작업 취소 (실적 없이 진행중만 해제) */
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
	public AjaxResult delete(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer id = toInt(payload.get("id"));
		if (id == null) {
			result.success = false;
			result.message = "취소할 실적이 지정되지 않았습니다.";
			return result;
		}

		prodAssemblyService.deleteResult(id, user);
		result.success = true;
		result.message = "취소되었습니다.";
		return result;
	}

	// =================================================================
	// helper
	// =================================================================

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