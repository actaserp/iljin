package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.ProdInspectService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 검사 공정
 *
 * [원칙]  ★ 내작·외작 구분 없이 <b>모든 공정(품목)이 검사 대상</b>이다.
 *   검사는 유닛이 아니라 공정 단위이므로 대상의 키는 suju.id 다.
 *
 * [검사 면제]
 *   외작 중 업체가 검사를 마치고 보낸 품목만 제외된다.
 *   그 목록이 mat_inout_inspect 이며, 이 서비스는 <b>읽기만</b> 한다.
 *   (입고에 종속된 구조라 내작을 표현할 수 없다. 외작 입고 시 채워진다)
 *
 * [검사 실적]  mat_produce ProcessOrder=3
 *     1 = 유닛 조립 / 2 = 공정 조립 / 3 = 검사
 *   신규 테이블 없이 조립과 같은 job_res 아래 순서대로 쌓인다.
 *
 * [상태]
 *   exempt 검사 면제 (업체 검사 완료분) / done 검사 완료 / wait 미검사
 *
 * [경로] /api/production/prod_inspect
 */
@Slf4j
@RestController
@RequestMapping("/api/production/prod_inspect")
@RequiredArgsConstructor
public class ProdInspectController {

	private final ProdInspectService prodInspectService;

	// =================================================================
	// 조회
	// =================================================================

	@GetMapping("/project_list")
	public AjaxResult projectList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodInspectService.getProjectList(spjangcd);
		return result;
	}

	@GetMapping("/worker_list")
	public AjaxResult workerList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodInspectService.getWorkerList(spjangcd);
		return result;
	}

	/** 검사 설비 (3차원 측정기 등). 없어도 검사 등록은 된다 */
	@GetMapping("/equipment_list")
	public AjaxResult equipmentList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodInspectService.getEquipmentList(spjangcd);
		return result;
	}

	/**
	 * 검사 대상 목록 — 작업 지시된 품목 전체.
	 * state: 'wait' / 'done' / 'exempt' / 생략 시 전체 (미검사가 위로)
	 */
	@GetMapping("/target_list")
	public AjaxResult targetList(@RequestParam("spjangcd") String spjangcd,
								 @RequestParam(value = "projNo", required = false) String projNo,
								 @RequestParam(value = "state", required = false) String state) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodInspectService.getTargetList(spjangcd, projNo, state);
		return result;
	}

	/** 상태별 건수 (탭 뱃지 / 대시보드용) */
	@GetMapping("/state_count")
	public AjaxResult stateCount(@RequestParam("spjangcd") String spjangcd,
								 @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodInspectService.getStateCount(spjangcd, projNo);
		return result;
	}

	@GetMapping("/log")
	public AjaxResult log(@RequestParam("spjangcd") String spjangcd,
						  @RequestParam(value = "projNo", required = false) String projNo,
						  @RequestParam(value = "sujuId", required = false) Integer sujuId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodInspectService.getLog(spjangcd, projNo, sujuId);
		return result;
	}

	// =================================================================
	// 검사 등록
	// =================================================================

	/**
	 * 검사 등록.
	 *
	 * 한 품목을 여러 번 검사할 수 있다 (나눠 입고되는 외작, 재검사).
	 * 중복 등록을 막지 않는다 — 이력이 쌓이는 구조다.
	 *
	 * 조립 실적이 없어도 막지 않는다. 외작은 조립 실적이 없고,
	 * 내작도 현장이 조립 입력을 건너뛰는 경우가 있다.
	 */
	@PostMapping("/save")
	@Transactional
	public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer sujuId = toInt(payload.get("sujuId"));
		if (sujuId == null) {
			result.success = false;
			result.message = "검사 대상을 선택하세요.";
			return result;
		}
		if (toInt(payload.get("workerId")) == null) {
			result.success = false;
			result.message = "검사자를 선택하세요.";
			return result;
		}

		double good = toDouble(payload.get("goodQty"));
		double defect = toDouble(payload.get("defectQty"));
		if (good <= 0 && defect <= 0) {
			result.success = false;
			result.message = "합격 또는 불합격 수량을 입력하세요.";
			return result;
		}

		/*
		 * ★ 작업지시 유무를 묻지 않는다.
		 *   외작은 우리가 지시할 대상이 아니라 지시가 없다.
		 *   예전에는 여기서 막는 바람에, 외작을 검사하려고 생산 지시 화면에서
		 *   억지로 지시를 넣는 일이 있었다.
		 *   실적을 매달 job_res 는 서비스가 필요할 때 만든다(ensureOrder).
		 *   수주에 없는 품목만 걸러 낸다.
		 */
		if (prodInspectService.getOrderInfo(sujuId) == null) {
			result.success = false;
			result.message = "수주에 없는 품목입니다.";
			return result;
		}

		Integer id = prodInspectService.save(payload, user);
		if (id != null) {
			// 검사가 공정 완료를 판정한다. 누적이 지그 대수에 닿으면 job_res 가 닫힌다.
			prodInspectService.syncJobResState(sujuId, user);
		}
		if (id == null) {
			result.success = false;
			result.message = "저장에 실패했습니다.";
			return result;
		}

		result.success = true;
		result.message = defect > 0
				? "검사 결과가 등록되었습니다. (불합격 " + trim(defect) + ")"
				: "검사 결과가 등록되었습니다.";
		result.data = id;
		return result;
	}

	/** 검사 취소 */
	/**
	 * 선택 일괄 검사.
	 *
	 * ★ 수량은 화면에서 받지 않고 각 품목의 <b>목표(지그 대수)</b>를 쓴다.
	 *   대수가 다른 품목이 섞였을 때 한 값을 밀어 넣으면 전부 틀린 값이 된다.
	 *   불합격이 있으면 그 건은 단건 등록으로 따로 받는다.
	 */
	@PostMapping("/bulk_save")
	@Transactional
	public AjaxResult bulkSave(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Object raw = payload.get("sujuIds");
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			result.success = false;
			result.message = "검사할 품목을 선택하세요.";
			return result;
		}
		if (toInt(payload.get("workerId")) == null) {
			result.success = false;
			result.message = "검사자를 선택하세요.";
			return result;
		}

		int ok = 0, skip = 0;
		for (Object o : list) {
			Integer sujuId = toInt(o);
			if (sujuId == null) continue;

			Map<String, Object> item = prodInspectService.getOrderInfo(sujuId);
			if (item == null) { skip++; continue; }

			double target = toDouble(item.get("jig_qty"));
			if (target <= 0) { skip++; continue; }   // 대수 미등록은 건너뛴다

			Map<String, Object> one = new HashMap<>(payload);
			one.put("sujuId", sujuId);
			one.put("goodQty", target);
			one.put("defectQty", 0);

			Integer id = prodInspectService.save(one, user);
			if (id == null) { skip++; continue; }

			prodInspectService.syncJobResState(sujuId, user);
			ok++;
		}

		result.success = ok > 0;
		result.message = ok + "건을 등록했습니다."
				+ (skip > 0 ? " (" + skip + "건은 대수가 없어 건너뜀)" : "");
		return result;
	}

	@PostMapping("/delete")
	@Transactional
	public AjaxResult delete(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer id = toInt(payload.get("id"));
		if (id == null) {
			result.success = false;
			result.message = "취소할 검사가 지정되지 않았습니다.";
			return result;
		}

		Integer sujuId = prodInspectService.sujuIdOf(id);
		prodInspectService.delete(id);
		// 취소로 누적이 내려가면 공정이 다시 열려야 한다
		if (sujuId != null) prodInspectService.syncJobResState(sujuId, user);
		result.success = true;
		result.message = "취소되었습니다.";
		return result;
	}

	// =================================================================
	// helper
	// =================================================================
	private String trim(double d) {
		return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
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