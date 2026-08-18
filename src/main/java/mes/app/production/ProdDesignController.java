package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.ProdDesignService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 생산 지시(설계)
 *
 * 프로젝트 → 품목(suju) → 부품(BOM) 등록 + 작업지시.
 *
 * [설계 메모]
 *  - JPA 엔티티/레포지토리 없음. 전부 SqlRunner(JDBC) 직접 쿼리.
 *  - 작업지시는 품목(suju 1행) 단위로 job_res 를 생성한다.
 *    SourceTableName='suju', SourceDataPk=suju.id 규약은 기존 대시보드가 이미 사용 중.
 *  - 2D 출도 여부는 이 화면에서 관리하지 않는다. (수주관리 dwg_drawing 이 단독 소유)
 *  - 재고/귀속/파기 없음. 이 화면은 "필요량"까지만 책임진다.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/prod_design")
@RequiredArgsConstructor
public class ProdDesignController {

	private final ProdDesignService prodDesignService;

	// =================================================================
	// 조회
	// =================================================================

	/** 프로젝트 콤보 */
	@GetMapping("/project_list")
	public AjaxResult projectList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getProjectList(spjangcd);
		return result;
	}

	/** 품목(suju) 목록 */
	@GetMapping("/item_list")
	public AjaxResult itemList(@RequestParam("spjangcd") String spjangcd,
							   @RequestParam("projNo") String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getItemList(spjangcd, projNo);
		return result;
	}

	/** 부품(BOM) 목록 */
	@GetMapping("/part_list")
	public AjaxResult partList(@RequestParam("sujuId") Integer sujuId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getPartList(sujuId);
		return result;
	}

	/**
	 * [가공 키오스크 공급] 품목별 유형 소요량.
	 * 키오스크가 유형 타일과 "소요 N" 을 그리는 데 쓴다.
	 */
	@GetMapping("/kind_need")
	public AjaxResult kindNeed(@RequestParam(value = "sujuId", required = false) Integer sujuId,
							   @RequestParam(value = "spjangcd", required = false) String spjangcd,
							   @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = (sujuId != null)
				? prodDesignService.getKindNeedByItem(sujuId)
				: prodDesignService.getKindNeedByProject(spjangcd, projNo);
		return result;
	}

	/** 유형(분류) 목록 */
	@GetMapping("/kind_list")
	public AjaxResult kindList(@RequestParam("spjangcd") String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getKindList(spjangcd);
		return result;
	}

	/** STD·구매품 품목 검색 (발주 연계) */
	@GetMapping("/material_search")
	public AjaxResult materialSearch(@RequestParam("spjangcd") String spjangcd,
									 @RequestParam(value = "keyword", required = false) String keyword) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.searchMaterial(spjangcd, keyword);
		return result;
	}

	/** 부품명 자동완성 (제작품 — 과거 입력된 부품명에서 추천) */
	@GetMapping("/part_name_suggest")
	public AjaxResult partNameSuggest(@RequestParam("spjangcd") String spjangcd,
									  @RequestParam(value = "keyword", required = false) String keyword) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.suggestPartName(spjangcd, keyword);
		return result;
	}

	/** 유형별 필요량 요약 (대시보드 연계용) */
	@GetMapping("/kind_summary")
	public AjaxResult kindSummary(@RequestParam("spjangcd") String spjangcd,
								  @RequestParam("projNo") String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getKindSummary(spjangcd, projNo);
		return result;
	}

	// =================================================================
	// 부품 저장 / 삭제
	// =================================================================

	/**
	 * 부품 일괄 저장.
	 * payload: { spjangcd, sujuId, items:[{id, partNo, partName, gubun, materialId,
	 *                                      kind, qty, state, remark}] }
	 */
	@PostMapping("/part_save")
	@Transactional
	public AjaxResult partSave(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String spjangcd = str(payload.get("spjangcd"));
		Integer sujuId = toInt(payload.get("sujuId"));

		if (sujuId == null) {
			result.success = false;
			result.message = "품목이 지정되지 않았습니다.";
			return result;
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items =
				(List<Map<String, Object>>) payload.getOrDefault("items", new ArrayList<>());

		int order = 0;
		for (Map<String, Object> it : items) {

			String partName = str(it.get("partName"));
			if (partName.isEmpty()) continue;   // 빈 행 무시

			String gubun = str(it.get("gubun"));
			if (gubun.isEmpty()) gubun = "제작품";
			it.put("gubun", gubun);

			if (str(it.get("state")).isEmpty()) it.put("state", "추정");

			String kind;
			Integer materialId;

			if ("제작품".equals(gubun)) {
				// 유형은 제작품만 의미 있음. 비어 있으면 부품명으로 자동 추정
				kind = str(it.get("kind"));
				if (kind.isEmpty() || "etc".equals(kind)) {
					kind = prodDesignService.guessKind(spjangcd, partName);
				}
				// 제작품은 프로젝트 1회성이라 품목마스터에 넣지 않는다
				materialId = null;
			} else {
				kind = "etc";
				materialId = toInt(it.get("materialId"));
			}


			prodDesignService.savePart(it, spjangcd, sujuId, order++, kind, materialId, user);
		}

		result.success = true;
		result.message = "저장되었습니다.";
		return result;
	}

	/** 부품 삭제 */
	@PostMapping("/part_delete")
	@Transactional
	public AjaxResult partDelete(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();

		Integer id = toInt(payload.get("id"));
		if (id == null) {
			result.success = false;
			result.message = "삭제할 부품이 지정되지 않았습니다.";
			return result;
		}

		prodDesignService.deletePart(id);
		result.success = true;
		return result;
	}

	// =================================================================
	// 유형(분류) 관리
	// =================================================================

	/**
	 * 유형 + 별칭 저장 (전체 교체)
	 * payload: { spjangcd, kinds:[{canon, aliases:"PLATE, BRACKET, 브라켓"}] }
	 */
	@PostMapping("/kind_save")
	@Transactional
	public AjaxResult kindSave(@RequestBody Map<String, Object> payload) {

		AjaxResult result = new AjaxResult();
		String spjangcd = str(payload.get("spjangcd"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> kinds =
				(List<Map<String, Object>>) payload.getOrDefault("kinds", new ArrayList<>());

		prodDesignService.saveKinds(spjangcd, kinds);

		result.success = true;
		return result;
	}

	// =================================================================
	// 작업지시
	// =================================================================

	/** 작업지시 생성 - 품목(suju) 단위로 job_res 1건 */
	@PostMapping("/order")
	@Transactional
	public AjaxResult order(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer sujuId = toInt(payload.get("sujuId"));
		if (sujuId == null) {
			result.success = false;
			result.message = "품목이 지정되지 않았습니다.";
			return result;
		}

		Map<String, Object> item = prodDesignService.getItem(sujuId);
		if (item == null) {
			result.success = false;
			result.message = "품목을 찾을 수 없습니다.";
			return result;
		}

		// 중복 지시 방지
		if (prodDesignService.countOrder(sujuId) > 0) {
			result.success = false;
			result.message = "이미 작업 지시된 품목입니다.";
			return result;
		}

		String workOrderNumber = prodDesignService.createOrder(item, user);

		result.success = true;
		result.message = "작업 지시가 생성되었습니다.";
		result.data = workOrderNumber;
		return result;
	}

	/** 작업지시 취소 - 실적이 이미 있으면 불가 (수량 꼬임 방지) */
	@PostMapping("/order_cancel")
	@Transactional
	public AjaxResult orderCancel(@RequestBody Map<String, Object> payload) {

		AjaxResult result = new AjaxResult();

		Integer sujuId = toInt(payload.get("sujuId"));
		if (sujuId == null) {
			result.success = false;
			result.message = "품목이 지정되지 않았습니다.";
			return result;
		}

		if (prodDesignService.countProduced(sujuId) > 0) {
			result.success = false;
			result.message = "이미 생산 실적이 있어 지시를 취소할 수 없습니다.";
			return result;
		}

		prodDesignService.cancelOrder(sujuId);

		result.success = true;
		result.message = "작업 지시가 취소되었습니다.";
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
}