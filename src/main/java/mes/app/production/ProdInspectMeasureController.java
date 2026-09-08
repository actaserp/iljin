package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.common.service.FileService;
import mes.app.production.service.AttachFileResolver;
import mes.app.production.service.CmmReportParser;
import mes.app.production.service.ProdInspectMeasureService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3차원 측정 성적서 — 파싱 · 자동 합불 판정 · 측정값 조회 · 공차 설정.
 *
 * [경로]  /api/production/prod_inspect_measure
 *   ★ 기존 /api/production/prod_inspect (ProdInspectController) 와 분리했다.
 *     같은 경로에 얹으면 Ambiguous mapping 으로 기동이 막힌다.
 *
 * [★ 파일을 받지 않는다 — 이미 올라온 첨부를 읽는다]
 *   원본 성적서는 기존 첨부 업로더(ax5 · attach_file · AttachName='inspect')가
 *   그대로 저장한다. 이 컨트롤러는 그 파일을 <b>읽기만</b> 한다.
 *
 *   이렇게 한 이유:
 *     - 공통 파일 저장 로직(FileService · /api/files)을 건드리지 않는다.
 *       FileService 에는 애초에 저장 메서드가 없다 — 조회·갱신·삭제뿐이다.
 *     - 같은 파일을 두 번 올리지 않아도 된다.
 *     - 파싱이 실패해도 원본은 첨부에 남는다.
 *     - 나중에 공차가 바뀌면 원본을 다시 읽어 재판정할 수 있다.
 *
 *   그래서 멀티파트가 없고 통신은 전부 AjaxUtil 로 나간다(CSRF 자동 처리).
 *
 * [흐름]
 *   1) 검사 등록 (기존 /prod_inspect/save) → mat_produce 1건
 *   2) 첨부 관리에서 성적서 xls 업로드 (기존 업로더 그대로)
 *   3) /attach_list  그 검사 건에 올라온 엑셀 목록
 *   4) /parse        선택한 첨부를 읽어 파싱 + 자동판정 <b>미리보기</b> (저장 안 함)
 *   5) /save         확인 후 적재
 *   6) /override     검사자가 판정을 뒤집으면 사유와 함께 남긴다
 *
 *   4단계를 둔 이유는 잘못된 파일·잘못된 공차로 판정이 들어가는 것을 막기 위해서다.
 *   현장이 양식을 조금 고쳐 쓰는 일이 흔한데, 그때 조용히 빈 값이 적재되면
 *   "전부 합격" 으로 보인다.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/prod_inspect_measure")
@RequiredArgsConstructor
public class ProdInspectMeasureController {

	private final ProdInspectMeasureService measureService;
	private final CmmReportParser parser;
	private final AttachFileResolver attachResolver;
	private final FileService fileService;   // 읽기만 한다

	/** 검사 첨부의 AttachName / TableName. 기존 화면(prod_inspection.html)이 쓰는 값 */
	private static final String ATTACH_NAME = "inspect";
	private static final String ATTACH_TABLE = "mat_produce";

	// =================================================================
	// 첨부 목록
	// =================================================================

	/**
	 * 검사 1건에 올라온 첨부 중 <b>엑셀만</b> 추린다.
	 * 도면 사진·pdf 도 같은 AttachName 으로 붙으므로 여기서 걸러야
	 * 현장이 이미지 파일을 골라 파싱 실패를 보는 일이 없다.
	 */
	@GetMapping("/attach_list")
	public AjaxResult attachList(@RequestParam("matProduceId") Integer matProduceId) {
		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> all =
				fileService.getAttachFile(ATTACH_TABLE, matProduceId, ATTACH_NAME);

		List<Map<String, Object>> excels = new ArrayList<>();
		for (Map<String, Object> a : all) {
			String name = str(a.get("FileName"));
			String ext = str(a.get("ExtName")).toLowerCase().replace(".", "");
			if (ext.isEmpty() && name.contains(".")) {
				ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
			}
			if (!"xls".equals(ext) && !"xlsx".equals(ext)) continue;

			Map<String, Object> m = new HashMap<>();
			m.put("attach_id", a.get("id"));
			m.put("file_name", name);
			m.put("ext", ext);
			m.put("file_size", a.get("fileSize"));
			excels.add(m);
		}

		result.success = true;
		result.data = excels;
		return result;
	}

	// =================================================================
	// 파싱 (미리보기)
	// =================================================================

	/**
	 * 성적서 파싱 + 자동판정 미리보기. <b>저장하지 않는다.</b>
	 *
	 * ★ 읽을 열(MAKER/KIA)은 파서가 값이 든 쪽을 자동으로 고른다.
	 *   현장이 자사 측정도 제출용이라 KIA 칸에 적기 때문에,
	 *   사람이 고르게 하면 개념과 칸 이름이 어긋나 빈 값만 저장된다.
	 *
	 * @param attachId 읽을 첨부(attach_file.id)
	 */
	@GetMapping("/parse")
	public AjaxResult parse(@RequestParam("matProduceId") Integer matProduceId,
							@RequestParam("attachId") Integer attachId) {

		AjaxResult result = new AjaxResult();

		Map<String, Object> ctx = measureService.getContext(matProduceId);
		if (ctx == null) {
			result.success = false;
			result.message = "검사 건을 찾을 수 없습니다.";
			return result;
		}

		File f = locate(attachId, matProduceId);
		if (f == null) {
			result.success = false;
			result.message = "첨부 파일을 열지 못했습니다. 파일이 삭제되었거나 저장 경로가 다릅니다.";
			return result;
		}

		Map<String, Object> att = fileService.getAttachFileDetail(attachId);

		try {
			CmmReportParser.Result parsed = parser.parse(f);

			Map<String, double[]> tol = measureService.getToleranceMap(
					str(ctx.get("spjangcd")), toInt(ctx.get("suju_id")));

			Map<String, Object> judged = measureService.judge(parsed, tol);

			judged.put("mat_produce_id", matProduceId);
			judged.put("attach_id", attachId);
			judged.put("customer_name", parsed.customerName);
			judged.put("file_name", att == null ? f.getName() : str(att.get("FileName")));
			judged.put("item_name", ctx.get("item_name"));
			judged.put("jig_qty", ctx.get("jig_qty"));
			judged.put("model", parsed.model);
			judged.put("mcs_no", parsed.mcsNo);
			judged.put("draw_no", parsed.drawNo);
			judged.put("draw_name", parsed.drawName);
			judged.put("hardness_spec", parsed.hardnessSpec);
			judged.put("loc_hrc_lh", parsed.locHrcLh);
			judged.put("loc_hrc_rh", parsed.locHrcRh);
			judged.put("pin_hrc_lh", parsed.pinHrcLh);
			judged.put("pin_hrc_rh", parsed.pinHrcRh);
			judged.put("clamp_gap_lh", parsed.clampGapLh);
			judged.put("clamp_gap_rh", parsed.clampGapRh);

			result.success = true;
			result.data = judged;
			return result;

		} catch (Exception e) {
			log.warn("[prod_inspect_measure] 성적서 파싱 실패 attachId={}", attachId, e);
			result.success = false;
			// 원인을 감추면 현장이 무엇을 고쳐야 하는지 모른다
			result.message = "성적서를 읽지 못했습니다. CF180 측정 양식(xls/xlsx)이 맞는지 확인하세요."
					+ (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
			return result;
		}
	}

	// =================================================================
	// 적재
	// =================================================================

	/**
	 * 파싱 결과 적재.
	 *
	 * ★ 화면이 보낸 판정을 믿지 않는다. 첨부를 다시 읽어 파싱하고 공차도 서버에서 다시 찾는다.
	 *   미리보기와 저장 사이에 공차가 바뀌었을 수 있고, 무엇보다 판정값을
	 *   클라이언트가 만들어 보낼 수 있으면 품질 이력이 근거를 잃는다.
	 */
	@PostMapping("/save")
	@Transactional
	public AjaxResult save(@RequestBody Map<String, Object> payload, Authentication auth) {

		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer matProduceId = toInt(payload.get("matProduceId"));
		Integer attachId = toInt(payload.get("attachId"));

		if (matProduceId == null || attachId == null) {
			result.success = false;
			result.message = "성적서를 선택하세요.";
			return result;
		}

		Map<String, Object> ctx = measureService.getContext(matProduceId);
		if (ctx == null) {
			result.success = false;
			result.message = "검사 건을 찾을 수 없습니다.";
			return result;
		}

		File f = locate(attachId, matProduceId);
		if (f == null) {
			result.success = false;
			result.message = "첨부 파일을 열지 못했습니다.";
			return result;
		}

		String spjangcd = str(ctx.get("spjangcd"));
		Integer sujuId = toInt(ctx.get("suju_id"));
		Map<String, Object> att = fileService.getAttachFileDetail(attachId);
		String fileName = att == null ? f.getName() : str(att.get("FileName"));

		try {
			CmmReportParser.Result parsed = parser.parse(f);
			if (parsed.points.isEmpty()) {
				result.success = false;
				result.message = "측정값을 찾지 못해 저장하지 않았습니다.";
				return result;
			}

			Map<String, double[]> tol = measureService.getToleranceMap(spjangcd, sujuId);
			Map<String, Object> judged = measureService.judge(parsed, tol);

			Integer reportId = measureService.saveReport(
					matProduceId, sujuId, spjangcd,
					attachId, fileName, parsed, judged, user);

			if (reportId == null) {
				result.success = false;
				result.message = "저장에 실패했습니다.";
				return result;
			}

			String auto = (String) judged.get("auto_result");
			measureService.applyAutoResult(matProduceId, auto, user);

			int ng = judged.get("ng_cnt") == null ? 0 : toInt(judged.get("ng_cnt"));
			int pointCnt = judged.get("point_cnt") == null ? 0 : toInt(judged.get("point_cnt"));

			Map<String, Object> data = new HashMap<>();
			data.put("report_id", reportId);
			data.put("auto_result", auto);
			data.put("ng_cnt", ng);
			data.put("point_cnt", pointCnt);
			data.put("warnings", judged.get("warnings"));

			result.success = true;
			result.data = data;
			int mfrNg  = judged.get("mfr_ng_cnt")  == null ? 0 : toInt(judged.get("mfr_ng_cnt"));
			int custNg = judged.get("cust_ng_cnt") == null ? 0 : toInt(judged.get("cust_ng_cnt"));
			int noTol  = judged.get("no_tolerance_cnt") == null ? 0 : toInt(judged.get("no_tolerance_cnt"));
			String cust = str(judged.get("customer_name"));
			if (cust.isEmpty()) cust = "발주처";

			result.message = auto == null
					? "측정값 " + pointCnt + "건을 저장했습니다. ("
					+ (noTol > 0 ? "공차 기준이 없어" : "실측값이 없어") + " 판정하지 않았습니다)"
					: "fail".equals(auto)
					? "측정값 " + pointCnt + "건 저장 · 이탈 " + ng + "건"
					+ " (제작사 " + mfrNg + " / " + cust + " " + custNg + ") — 불합격"
					: "측정값 " + pointCnt + "건 저장 · 전 항목 합격";
			return result;

		} catch (Exception e) {
			log.warn("[prod_inspect_measure] 성적서 저장 실패 mp={} attach={}", matProduceId, attachId, e);
			result.success = false;
			result.message = "성적서를 저장하지 못했습니다."
					+ (e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
			return result;
		}
	}

	/** 검사자가 자동판정을 뒤집는다 (반자동) */
	@PostMapping("/override")
	@Transactional
	public AjaxResult override(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer reportId = toInt(payload.get("reportId"));
		String finalResult = str(payload.get("finalResult"));
		String reason = str(payload.get("reason"));

		if (reportId == null || finalResult.isEmpty()) {
			result.success = false;
			result.message = "판정을 선택하세요.";
			return result;
		}
		// 자동판정을 뒤집는 일이라 사유가 없으면 나중에 아무도 근거를 모른다
		if (reason.isEmpty()) {
			result.success = false;
			result.message = "자동판정과 다르게 확정하려면 사유를 입력하세요.";
			return result;
		}

		measureService.overrideResult(reportId, finalResult, reason, user);
		result.success = true;
		result.message = "판정을 확정했습니다.";
		return result;
	}

	// =================================================================
	// 조회
	// =================================================================

	@GetMapping("/report")
	public AjaxResult report(@RequestParam("matProduceId") Integer matProduceId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = measureService.getReport(matProduceId);
		return result;
	}

	@GetMapping("/measure_list")
	public AjaxResult measureList(@RequestParam("reportId") Integer reportId,
								  @RequestParam(value = "ngOnly", defaultValue = "false") boolean ngOnly) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = measureService.getMeasures(reportId, ngOnly);
		return result;
	}

	@GetMapping("/report_list")
	public AjaxResult reportList(@RequestParam("sujuId") Integer sujuId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = measureService.getReportsBySuju(sujuId);
		return result;
	}

	// =================================================================
	// 공차 설정
	// =================================================================

	@GetMapping("/tolerance_list")
	public AjaxResult toleranceList(@RequestParam("spjangcd") String spjangcd,
									@RequestParam(value = "sujuId", required = false) Integer sujuId) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = measureService.getToleranceList(spjangcd, sujuId);
		return result;
	}

	@PostMapping("/tolerance_save")
	@Transactional
	public AjaxResult toleranceSave(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		double lo = toDouble(payload.get("tolLower"));
		double hi = toDouble(payload.get("tolUpper"));
		if (lo > hi) {
			result.success = false;
			result.message = "하한이 상한보다 큽니다.";
			return result;
		}

		measureService.saveTolerance(payload, user);
		result.success = true;
		result.message = "공차를 저장했습니다. 이후 등록되는 성적서부터 적용됩니다.";
		return result;
	}

	@PostMapping("/tolerance_delete")
	@Transactional
	public AjaxResult toleranceDelete(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Integer id = toInt(payload.get("id"));
		if (id == null) {
			result.success = false;
			result.message = "삭제할 공차가 지정되지 않았습니다.";
			return result;
		}
		measureService.deleteTolerance(id, user);
		result.success = true;
		result.message = "삭제되었습니다.";
		return result;
	}

	// =================================================================
	// helper
	// =================================================================

	/**
	 * 첨부 id → 디스크 파일.
	 *
	 * ★ 그 첨부가 <b>정말 이 검사 건의 것인지</b> 확인한다.
	 *   attachId 를 화면에서 받으므로, 확인하지 않으면 남의 검사 건 파일을
	 *   id 만 바꿔 읽을 수 있다.
	 */
	private File locate(Integer attachId, Integer matProduceId) {
		if (attachId == null) return null;

		Map<String, Object> att = fileService.getAttachFileDetail(attachId);
		if (att == null) return null;

		if (!ATTACH_TABLE.equals(str(att.get("TableName")))
				|| !ATTACH_NAME.equals(str(att.get("AttachName")))
				|| !String.valueOf(matProduceId).equals(str(att.get("DataPk")))) {
			log.warn("[prod_inspect_measure] 이 검사 건의 첨부가 아니다 attachId={} mp={}",
					attachId, matProduceId);
			return null;
		}
		return attachResolver.resolve(att);
	}

	private String str(Object o) { return o == null ? "" : o.toString().trim(); }

	private Integer toInt(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		try { return Integer.parseInt(o.toString().trim()); }
		catch (NumberFormatException e) { return null; }
	}

	private double toDouble(Object o) {
		if (o == null || o.toString().isBlank()) return 0d;
		try { return Double.parseDouble(o.toString().trim()); }
		catch (NumberFormatException e) { return 0d; }
	}
}