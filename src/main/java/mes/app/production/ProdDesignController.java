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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 생산 지시(설계)
 *
 * 프로젝트 → 품목(suju) → 부품(BOM) 등록 + 작업지시.
 *
 * [BOM 2단계]  수주 &gt; 공정(품목) &gt; 유닛 &gt; 자재유형(부품)
 *   부품의 "Qty" 는 기본이 <b>유닛 1개당</b> 개수이고,
 *   "QtyType"='total' 인 행만 <b>공정당 총량</b>이다 (다리발 등).
 *   전체 필요량은 조회 시 서버가 계산해 내려준다.
 *
 * [설계 메모]
 *  - JPA 엔티티/레포지토리 없음. 전부 SqlRunner(JDBC) 직접 쿼리.
 *  - 작업지시는 품목(suju 1행) 단위로 job_res 를 생성한다.
 *    SourceTableName='suju', SourceDataPk=suju.id 규약은 기존 대시보드가 이미 사용 중.
 *  - <b>수주는 이 화면의 수정 대상이 아니다.</b> suju 는 읽기만 한다.
 *    다리발도 suju 를 고치지 않고 부품표에 1행을 보강하는 방식으로 반영한다.
 *  - 2D 출도 여부는 이 화면에서 관리하지 않는다. (수주관리 dwg_drawing 이 단독 소유)
 *  - 부품을 소모하는 공정은 없다. 이 화면은 "필요량"까지만 책임진다.
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

	/** 품목(suju) 목록 — 다리발 / 부품수 / 필요량 / 지시상태 포함 */
	@GetMapping("/item_list")
	public AjaxResult itemList(@RequestParam("spjangcd") String spjangcd,
							   @RequestParam("projNo") String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getItemList(spjangcd, projNo);
		return result;
	}

	/**
	 * 부품(BOM) 목록.
	 *
	 * sujuId 를 주면 그 품목의 부품, 안 주면 <b>프로젝트 공통</b> 부품이다.
	 * 2D 도면 전에는 "plate 400개 만들어 놔" 처럼 어느 품목 것인지 모르는
	 * 상태로 지시가 나가므로, 그런 물량이 들어갈 자리가 따로 필요하다.
	 */
	@GetMapping("/part_list")
	public AjaxResult partList(@RequestParam(value = "sujuId", required = false) Integer sujuId,
							   @RequestParam(value = "spjangcd", required = false) String spjangcd,
							   @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = prodDesignService.getPartList(sujuId, spjangcd, projNo);
		return result;
	}

	/**
	 * [가공 키오스크 공급] 유형별 소요량 + 생산 누적.
	 *
	 * 키오스크는 품목·유형이 모두 선택 항목이므로 두 갈래로 집계한다.
	 *   sujuId 있음 → 그 품목 기준
	 *   sujuId 없음 → 프로젝트 전체 (품목 미지정 실적 포함)
	 */
	@GetMapping("/kind_need")
	public AjaxResult kindNeed(@RequestParam(value = "sujuId", required = false) Integer sujuId,
							   @RequestParam(value = "spjangcd", required = false) String spjangcd,
							   @RequestParam(value = "projNo", required = false) String projNo,
							   @RequestParam(value = "operation", required = false) String operation) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		// operation 을 비우면 전 공정 합계다. 한 부품이 공정마다 다시 세어지므로
		// 실물보다 큰 수가 나온다 — 공정별 화면은 반드시 넘길 것.
		result.data = (sujuId != null)
				? prodDesignService.getKindNeedByItem(sujuId, operation)
				: prodDesignService.getKindNeedByProject(spjangcd, projNo, operation);
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

	/**
	 * 부품명 목록 → 품목 일괄 매칭 (STD·구매품).
	 *
	 * payload: { spjangcd, names: ["CLAMP CYL(SMC)", "AIR CYL", ...] }
	 *
	 * 작업자가 품목코드를 알 리 없으므로 엑셀에는 부품명만 들어온다.
	 * 여기서 붙일 수 있는 것만 붙이고, 나머지는 후보와 함께 돌려준다.
	 * 화면은 미매칭 건만 모아 보여주고 사용자가 고르게 한다.
	 *
	 * 조회 전용이라 GET 이 맞지만, 이름이 수십 건이라 URL 길이를 넘긴다.
	 */
	@PostMapping("/material_match")
	public AjaxResult materialMatch(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();

		@SuppressWarnings("unchecked")
		List<String> names = (List<String>) payload.getOrDefault("names", new ArrayList<String>());

		result.success = true;
		result.data = prodDesignService.matchMaterials(str(payload.get("spjangcd")), names);
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
	 *                                      kind, qty, qtyType, state, remark}] }
	 *
	 * qty 의 의미는 qtyType 이 정한다.
	 *   'unit'(기본) : 유닛 1개당 개수. 필요량 = qty × 유니트수
	 *   'total'      : 공정당 총량.     필요량 = qty  (다리발 등)
	 * 전체 필요량을 저장하지 않는 이유: 수주에서 유니트수가 바뀌면
	 * 저장된 총량이 즉시 거짓이 되기 때문이다.
	 */
	@PostMapping("/part_save")
	@Transactional
	public AjaxResult partSave(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String spjangcd = str(payload.get("spjangcd"));
		Integer sujuId = toInt(payload.get("sujuId"));
		String projNo = str(payload.get("projNo"));

		// sujuId 가 없으면 프로젝트 공통 부품이다. 그때는 projNo 가 있어야 한다.
		if (sujuId == null && projNo.isEmpty()) {
			result.success = false;
			result.message = "품목 또는 프로젝트가 지정되지 않았습니다.";
			return result;
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items =
				(List<Map<String, Object>>) payload.getOrDefault("items", new ArrayList<>());

		/*
		 * ★ 검증을 저장보다 <b>먼저 전부</b> 끝낸다.
		 *
		 *   이전에는 루프 안에서 검증하고 실패 시 return 했는데,
		 *   @Transactional 은 <b>예외</b>에만 롤백한다. 정상 return 은 커밋된다.
		 *   그래서 앞쪽 행은 INSERT 된 채 화면에는 실패 메시지가 떴고,
		 *   화면은 loadParts 를 다시 부르지 않아 그 행들의 id 가 null 로 남았다.
		 *   사용자가 고쳐서 다시 저장하면 같은 행이 또 INSERT 된다.
		 *   실제로 '가공품 3000' 이 3건, 'AIR CYL 10' 이 2건 쌓였다.
		 *
		 *   한 건이라도 문제가 있으면 아무것도 저장하지 않는다.
		 */
		List<Map<String, Object>> targets = new ArrayList<>();
		List<String> unmatched = new ArrayList<>();

		for (Map<String, Object> it : items) {

			String partName = str(it.get("partName"));
			if (partName.isEmpty()) continue;   // 빈 행 무시

			String gubun = str(it.get("gubun"));
			if (gubun.isEmpty()) gubun = "제작품";
			it.put("gubun", gubun);

			if (str(it.get("state")).isEmpty()) it.put("state", "추정");

			if ("제작품".equals(gubun)) {
				// 유형은 제작품만 의미 있음. 비어 있으면 부품명으로 자동 추정.
				String kind = str(it.get("kind"));
				if (kind.isEmpty() || "etc".equals(kind)) {
					kind = prodDesignService.guessKind(spjangcd, partName);
				}
				it.put("_kind", kind);
				// 제작품은 프로젝트 1회성이라 품목마스터에 넣지 않는다
				it.put("_materialId", null);
			} else {
				// STD·구매품은 발주 대상이라 품목 지정을 강제한다
				Integer materialId = toInt(it.get("materialId"));
				if (materialId == null) {
					unmatched.add(partName);
				}
				it.put("_kind", "etc");
				it.put("_materialId", materialId);
			}
			targets.add(it);
		}

		if (!unmatched.isEmpty()) {
			result.success = false;
			result.message = String.join(", ", unmatched)
					+ " — 품목을 지정해야 합니다. (발주 연계) 저장하지 않았습니다.";
			result.data = unmatched;
			return result;
		}

		int order = 0;
		for (Map<String, Object> it : targets) {
			prodDesignService.savePart(it, spjangcd, sujuId, projNo, order++,
					str(it.get("_kind")), toInt(it.get("_materialId")), user);
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

	/**
	 * 작업지시 생성 - 품목(suju) 단위로 job_res 1건.
	 *
	 * 지시 직전에 수주의 다리발 정보를 부품표에 1행 시딩한다.
	 * 조회 시점이 아니라 여기서 하는 이유: 조회에 INSERT 를 넣으면
	 * 사용자가 지운 행이 새로고침마다 되살아난다.
	 */
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

		// 다리발이 있는 공정이면 부품표에 1행 시딩 (구분은 사용자가 지정)
		boolean legAdded = prodDesignService.seedLegPart(sujuId, user);

		String workOrderNumber = prodDesignService.createOrder(item, user);

		Map<String, Object> data = new HashMap<>();
		data.put("workOrderNumber", workOrderNumber);
		data.put("legAdded", legAdded);

		result.success = true;
		result.message = legAdded
				? "작업 지시가 생성되었습니다. 다리발 1건이 부품표에 추가되었으니 "
				+ "제작/구매 구분을 지정하세요."
				: "작업 지시가 생성되었습니다.";
		result.data = data;
		return result;
	}

	/**
	 * 작업지시 취소.
	 * mat_produce 에 조립 실적(진행중 포함)이 하나라도 있으면 불가하다.
	 * job_res."GoodQty" 만 보면 진행중인 작업을 놓쳐 실적이 고아가 된다.
	 */
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