package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.ProdDesignService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 생산 지시 — 부품표 엑셀 양식 내려받기 / 올리기.
 *
 * <p>[설계]  <b>저장은 하지 않는다.</b>
 *   올린 파일은 서버가 행으로 읽어 돌려주기만 하고, 화면이 그 행을 기존
 *   "일괄 입력" 미리보기에 그대로 넣는다. 그래서 유형 자동분류 · 구분 보정 ·
 *   STD/구매품 품목 매칭 · 저장(part_save) 은 붙여넣기와 <b>완전히 같은 경로</b>를 탄다.
 *   저장 규칙을 두 군데 두지 않기 위해서다.
 *
 * <p>[열 순서]  부품명 · 수량 · 구분 · 유형 · 비고
 *   일괄 입력(parseBulk)의 열 순서와 같다. 비고는 화면이 읽지 않는다(작성자 메모용).
 *   수량은 <b>품목 전체 총량</b>이다 (ProdDesignService 부품 목록 주석 참고).
 *
 * <p>[경로]  ProdDesignController 와 같은 base 경로를 쓰되 하위 경로가 겹치지 않는다.
 *   기존 컨트롤러를 건드리지 않으려고 파일을 나눴다.
 *
 * <p>양식은 코드로 만든다. 서버 폴더에 파일을 두는 방식(SujuTemplateController)과 달리
 *   유형 목록이 마스터에서 오므로, 유형을 추가하면 양식의 드롭다운에도 바로 반영된다.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/prod_design")
@RequiredArgsConstructor
public class ProdDesignExcelController {

	private final ProdDesignService prodDesignService;
	private final SqlRunner sqlRunner;

	private static final String SHEET_DATA = "부품표";
	private static final String SHEET_GUIDE = "작성방법";
	private static final String[] HEADERS = {"부품명", "수량", "구분", "유형", "비고"};
	private static final String[] GUBUN = {"제작품", "STD", "구매품"};

	/** 드롭다운을 걸어 둘 행 수. 이보다 많이 쓰면 드롭다운만 없고 읽기는 된다 */
	private static final int VALIDATION_ROWS = 500;
	/** 한 번에 읽을 최대 행 수. 잘못된 파일로 화면이 멈추지 않게 */
	private static final int MAX_ROWS = 2000;
	private static final long MAX_BYTES = 5L * 1024 * 1024;

	// =================================================================
	// 양식 내려받기
	// =================================================================

	/**
	 * 부품표 양식(.xlsx).
	 *
	 * @param sujuId 선택한 품목. "프로젝트 전체" 행은 suju_id 가 없으므로 비어서 온다.
	 */
	@GetMapping("/excel_template")
	public ResponseEntity<byte[]> excelTemplate(
			@RequestParam("spjangcd") String spjangcd,
			@RequestParam(value = "sujuId", required = false) Integer sujuId) {

		String itemName = "프로젝트전체";
		String projNo = "";
		if (sujuId != null) {
			Map<String, Object> item = prodDesignService.getItem(sujuId);
			if (item != null) {
				itemName = str(item.get("item_name"));
				projNo = str(item.get("proj_no"));
			}
		}

		List<String> kinds = new ArrayList<>();
		for (Map<String, Object> k : prodDesignService.getKindList(spjangcd)) {
			String c = str(k.get("canon"));
			if (!c.isEmpty()) kinds.add(c);
		}
		if (!kinds.contains("etc")) kinds.add("etc");

		byte[] body;
		try (XSSFWorkbook wb = new XSSFWorkbook();
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			buildDataSheet(wb, kinds);
			buildGuideSheet(wb, projNo, itemName, kinds);
			wb.write(out);
			body = out.toByteArray();
		} catch (Exception e) {
			log.error("[부품표 양식] 생성 실패 sujuId={}", sujuId, e);
			return ResponseEntity.status(500).build();
		}

		String fileName = "부품표_" + safeFileName(itemName) + ".xlsx";
		String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
		String disposition = "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition)
				.contentType(MediaType.parseMediaType(
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.contentLength(body.length)
				.body(body);
	}

	private void buildDataSheet(Workbook wb, List<String> kinds) {
		Sheet sh = wb.createSheet(SHEET_DATA);

		CellStyle head = wb.createCellStyle();
		Font bold = wb.createFont();
		bold.setBold(true);
		head.setFont(bold);
		head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		head.setAlignment(HorizontalAlignment.CENTER);
		head.setBorderBottom(BorderStyle.THIN);

		Row r = sh.createRow(0);
		for (int i = 0; i < HEADERS.length; i++) {
			Cell c = r.createCell(i);
			c.setCellValue(HEADERS[i]);
			c.setCellStyle(head);
		}
		sh.setColumnWidth(0, 40 * 256);
		sh.setColumnWidth(1, 10 * 256);
		sh.setColumnWidth(2, 12 * 256);
		sh.setColumnWidth(3, 14 * 256);
		sh.setColumnWidth(4, 30 * 256);
		sh.createFreezePane(0, 1);

		// 수량 칸은 숫자 서식. 텍스트로 들어가 "4 " 처럼 공백이 붙는 걸 줄인다
		CellStyle num = wb.createCellStyle();
		num.setDataFormat(wb.createDataFormat().getFormat("0"));
		sh.setDefaultColumnStyle(1, num);

		DataValidationHelper dv = sh.getDataValidationHelper();
		addList(sh, dv, 2, GUBUN);
		// 유형 목록이 길면(255자 초과) 드롭다운을 걸 수 없다. 그때는 작성방법 시트 목록을 보고 쓴다
		String joined = String.join(",", kinds);
		if (joined.length() <= 250) addList(sh, dv, 3, kinds.toArray(new String[0]));
	}

	private void addList(Sheet sh, DataValidationHelper dv, int col, String[] values) {
		addList(sh, dv, col, values, VALIDATION_ROWS);
	}

	private void addList(Sheet sh, DataValidationHelper dv, int col, String[] values, int lastRow) {
		DataValidationConstraint c = dv.createExplicitListConstraint(values);
		CellRangeAddressList range = new CellRangeAddressList(1, lastRow, col, col);
		DataValidation v = dv.createValidation(c, range);
		// 목록에 없는 값도 허용한다(경고만). 구분은 화면이 제작품으로, 유형은 자동분류로 보정한다
		v.setErrorStyle(DataValidation.ErrorStyle.WARNING);
		v.setShowErrorBox(true);
		sh.addValidationData(v);
	}

	private void buildGuideSheet(Workbook wb, String projNo, String itemName, List<String> kinds) {
		Sheet sh = wb.createSheet(SHEET_GUIDE);
		sh.setColumnWidth(0, 16 * 256);
		sh.setColumnWidth(1, 80 * 256);

		String[][] lines = {
				{"프로젝트", projNo},
				{"품목", itemName},
				{"", ""},
				{"부품명", "필수. 비어 있는 행은 건너뜁니다."},
				{"수량", "품목 전체에 필요한 총량. 비우면 1."},
				{"구분", "제작품 / STD / 구매품. 비우면 제작품."},
				{"유형", "비우면 부품명으로 자동 분류합니다 (예: PIN BRKT → plate). 제작품만 해당."},
				{"비고", "메모용. 시스템에 저장되지 않습니다."},
				{"", ""},
				{"올리기", "생산 지시 > 품목 선택 > 일괄 입력 > [엑셀 올리기]. 미리보기를 확인한 뒤 [추가] → [저장]."},
				{"주의", "STD · 구매품은 품목 지정이 필요합니다. 이름이 맞으면 자동으로 붙고, 안 붙은 것은 표에서 지정하세요."},
				{"", ""},
				{"유형 목록", String.join(", ", kinds)},
		};
		for (int i = 0; i < lines.length; i++) {
			Row r = sh.createRow(i);
			r.createCell(0).setCellValue(lines[i][0]);
			r.createCell(1).setCellValue(lines[i][1]);
		}
	}

	// =================================================================
	// 엑셀 올리기 — 읽기만 한다
	// =================================================================

	/**
	 * 올린 엑셀을 행으로 돌려준다. 저장하지 않는다.
	 *
	 *  data.rows = [[부품명, 수량, 구분, 유형], ...]  (문자열 그대로)
	 *  보정·분류는 화면의 parseBulk 가 한다 — 붙여넣기와 같은 규칙을 쓰기 위해.
	 *
	 *  시트는 "부품표" 를 우선 찾고, 없으면 첫 시트를 읽는다(사용자가 이름을 바꿨거나
	 *  자기 엑셀을 그대로 올린 경우). 첫 행이 머리글이면 건너뛴다.
	 */
	@PostMapping("/excel_parse")
	public AjaxResult excelParse(@RequestParam("upload_file") MultipartFile file) {
		AjaxResult result = new AjaxResult();

		if (file == null || file.isEmpty()) {
			result.success = false;
			result.message = "파일이 비어 있습니다.";
			return result;
		}
		if (file.getSize() > MAX_BYTES) {
			result.success = false;
			result.message = "파일이 너무 큽니다 (5MB 이하).";
			return result;
		}
		String name = str(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
		if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
			result.success = false;
			result.message = "엑셀 파일(.xlsx, .xls)만 올릴 수 있습니다.";
			return result;
		}

		List<List<String>> rows = new ArrayList<>();
		int skipped = 0;
		try (InputStream in = file.getInputStream();
			 Workbook wb = WorkbookFactory.create(in)) {

			Sheet sh = wb.getSheet(SHEET_DATA);
			if (sh == null) sh = wb.getSheetAt(0);

			DataFormatter fmt = new DataFormatter();
			FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();

			for (int i = sh.getFirstRowNum(); i <= sh.getLastRowNum(); i++) {
				if (rows.size() >= MAX_ROWS) break;
				Row r = sh.getRow(i);
				if (r == null) continue;

				List<String> cols = new ArrayList<>(4);
				for (int c = 0; c < 4; c++) {
					cols.add(cellText(r.getCell(c), fmt, ev));
				}
				String partName = cols.get(0);
				if (partName.isEmpty()) {
					// 부품명 없이 다른 칸만 채워진 행은 알려준다(조용히 버리면 빠진 줄 모른다)
					if (!cols.get(1).isEmpty() || !cols.get(2).isEmpty() || !cols.get(3).isEmpty()) skipped++;
					continue;
				}
				if (rows.isEmpty() && isHeader(partName)) continue;
				rows.add(cols);
			}
		} catch (Exception e) {
			log.warn("[부품표 업로드] 읽기 실패: {}", file.getOriginalFilename(), e);
			result.success = false;
			result.message = "엑셀을 읽지 못했습니다. 양식을 내려받아 다시 작성해 주세요.";
			return result;
		}

		Map<String, Object> data = new HashMap<>();
		data.put("rows", rows);
		data.put("skipped", skipped);
		data.put("truncated", rows.size() >= MAX_ROWS);
		result.success = true;
		result.data = data;
		return result;
	}

	// =================================================================
	// 프로젝트 전체 부품표 — 품목마다 따로 올리지 않고 한 파일로
	//
	//   열: 라인 · 품목 · 부품명 · 수량 · 구분 · 유형 · 비고
	//   품목 단위 양식에 [라인 · 품목] 두 칸이 앞에 붙은 것뿐이다.
	//
	//   [저장 규칙]  품목 단위 저장(part_save)과 같은 판정을 쓰되, 여러 품목을
	//   한 트랜잭션으로 넣는다. 다른 점은 두 가지:
	//     1) 지시가 나간 품목도 반영한다. 들어가는 부품은 '확정' (지시 = 품목 부품 확정 규칙과 같다)
	//     2) STD·구매품 중 품목(자재)이 안 붙은 행은 저장하지 않고 돌려준다.
	//        part_save 는 한 건만 안 붙어도 전체를 거부하는데, 프로젝트 전체를
	//        그렇게 막으면 수십 개 품목이 구매품 하나 때문에 다 막힌다.
	//        돌려받은 행은 화면이 해당 품목을 열 때 미저장 행으로 다시 깔아 준다.
	// =================================================================

	private static final String SHEET_ITEMS = "품목목록";
	private static final String[] HEADERS_PROJECT = {"라인", "품목", "부품명", "수량", "구분", "유형", "비고"};
	static final String COMMON_ITEM = "프로젝트 전체";   // 선행 가공 (2D 전, 품목 미지정)

	/** 프로젝트 전체 부품표 양식. 품목 칸은 그 프로젝트 품목 드롭다운 */
	@GetMapping("/excel_template_project")
	public ResponseEntity<byte[]> excelTemplateProject(
			@RequestParam("spjangcd") String spjangcd,
			@RequestParam("projNo") String projNo) {

		List<Map<String, Object>> items = prodDesignService.getItemList(spjangcd, projNo);

		List<String> kinds = new ArrayList<>();
		for (Map<String, Object> k : prodDesignService.getKindList(spjangcd)) {
			String c = str(k.get("canon"));
			if (!c.isEmpty()) kinds.add(c);
		}
		if (!kinds.contains("etc")) kinds.add("etc");

		byte[] body;
		try (XSSFWorkbook wb = new XSSFWorkbook();
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			CellStyle head = headStyle(wb);

			// --- 부품표 ---
			Sheet sh = wb.createSheet(SHEET_DATA);
			Row hr = sh.createRow(0);
			for (int i = 0; i < HEADERS_PROJECT.length; i++) {
				Cell c = hr.createCell(i);
				c.setCellValue(HEADERS_PROJECT[i]);
				c.setCellStyle(head);
			}
			int[] widths = {10, 14, 36, 8, 10, 12, 24};
			for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i] * 256);
			sh.createFreezePane(0, 1);
			CellStyle num = wb.createCellStyle();
			num.setDataFormat(wb.createDataFormat().getFormat("0"));
			sh.setDefaultColumnStyle(3, num);

			// --- 품목목록 (드롭다운 원본 + 참고) ---
			Sheet is = wb.createSheet(SHEET_ITEMS);
			String[] ih = {"라인", "품목", "설비타입", "유니트", "등록된 부품", "작업지시"};
			Row ir = is.createRow(0);
			for (int i = 0; i < ih.length; i++) {
				Cell c = ir.createCell(i);
				c.setCellValue(ih[i]);
				c.setCellStyle(head);
			}
			int n = 1;
			Row cr = is.createRow(n++);
			cr.createCell(0).setCellValue("");
			cr.createCell(1).setCellValue(COMMON_ITEM);
			cr.createCell(2).setCellValue("선행 가공 (2D 전)");
			for (Map<String, Object> it : items) {
				Row r = is.createRow(n++);
				r.createCell(0).setCellValue(str(it.get("line_name")));
				r.createCell(1).setCellValue(str(it.get("item_name")));
				r.createCell(2).setCellValue(str(it.get("equip_type")));
				r.createCell(3).setCellValue(str(it.get("unit_qty")));
				r.createCell(4).setCellValue(str(it.get("part_cnt")));
				r.createCell(5).setCellValue("Y".equals(str(it.get("ordered_yn"))) ? "지시완료(올려도 반영 안 됨)" : "");
			}
			int[] iw = {10, 16, 14, 8, 10, 24};
			for (int i = 0; i < iw.length; i++) is.setColumnWidth(i, iw[i] * 256);
			is.createFreezePane(0, 1);

			// 드롭다운: 품목은 시트 참조(255자 제한 없음), 구분·유형은 목록
			DataValidationHelper dv = sh.getDataValidationHelper();
			DataValidationConstraint itemC = dv.createFormulaListConstraint(
					"'" + SHEET_ITEMS + "'!$B$2:$B$" + (n));
			DataValidation itemV = dv.createValidation(itemC, new CellRangeAddressList(1, VALIDATION_ROWS * 4, 1, 1));
			itemV.setErrorStyle(DataValidation.ErrorStyle.WARNING);
			itemV.setShowErrorBox(true);
			sh.addValidationData(itemV);
			addList(sh, dv, 4, GUBUN, VALIDATION_ROWS * 4);
			String joined = String.join(",", kinds);
			if (joined.length() <= 250) addList(sh, dv, 5, kinds.toArray(new String[0]), VALIDATION_ROWS * 4);

			// --- 작성방법 ---
			Sheet gs = wb.createSheet(SHEET_GUIDE);
			gs.setColumnWidth(0, 16 * 256);
			gs.setColumnWidth(1, 90 * 256);
			String[][] lines = {
					{"프로젝트", projNo},
					{"", ""},
					{"라인 · 품목", "품목목록 시트에 있는 이름 그대로. 같은 품목이 이어지면 첫 행만 써도 됩니다 (빈 칸은 위 행과 같음)."},
					{"", "품목 이름이 두 라인에 다 있으면 라인을 꼭 쓰세요. 품목에 '" + COMMON_ITEM + "'을 쓰면 선행 가공(2D 전, 품목 미지정)으로 들어갑니다."},
					{"부품명", "필수. 비어 있는 행은 건너뜁니다."},
					{"수량", "품목 전체에 필요한 총량. 비우면 1."},
					{"구분", "제작품 / STD / 구매품. 비우면 제작품."},
					{"유형", "비우면 부품명으로 자동 분류합니다. 제작품만 해당."},
					{"비고", "메모용. 저장되지 않습니다."},
					{"", ""},
					{"올리기", "생산 지시 > 프로젝트 선택 > [엑셀 일괄] > [엑셀 올리기]. 품목별 미리보기를 확인하고 [저장]."},
					{"이미 부품이 있는 품목", "기본은 건너뜁니다. '기존 부품 교체'를 켜면 지우고 새로 넣습니다."},
					{"작업지시 나간 품목", "반영합니다. 들어가는 부품은 '확정' 상태가 됩니다."},
					{"STD · 구매품", "자재 이름이 맞으면 자동으로 붙어 저장됩니다. 안 붙은 것은 저장되지 않고, 해당 품목을 열면 노란 칸에 다시 나옵니다."},
					{"", ""},
					{"유형 목록", String.join(", ", kinds)},
			};
			for (int i = 0; i < lines.length; i++) {
				Row r = gs.createRow(i);
				r.createCell(0).setCellValue(lines[i][0]);
				r.createCell(1).setCellValue(lines[i][1]);
			}

			wb.write(out);
			body = out.toByteArray();
		} catch (Exception e) {
			log.error("[부품표 양식-프로젝트] 생성 실패 projNo={}", projNo, e);
			return ResponseEntity.status(500).build();
		}

		String fileName = "부품표_" + safeFileName(projNo) + "_전체.xlsx";
		String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
				.contentType(MediaType.parseMediaType(
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.contentLength(body.length)
				.body(body);
	}

	/**
	 * 프로젝트 전체 부품표 읽기. 저장하지 않는다.
	 *  data.rows = [[라인, 품목, 부품명, 수량, 구분, 유형], ...]
	 *  라인·품목이 비어 있으면 위 행 값을 이어 쓴다 (셀 병합·첫 행만 쓰기 대응 — 병합 셀은 POI 에서 빈 칸으로 읽힌다).
	 */
	@PostMapping("/excel_parse_project")
	public AjaxResult excelParseProject(@RequestParam("upload_file") MultipartFile file) {
		AjaxResult result = new AjaxResult();
		String err = checkFile(file);
		if (err != null) {
			result.success = false;
			result.message = err;
			return result;
		}

		List<List<String>> rows = new ArrayList<>();
		int skipped = 0;
		try (InputStream in = file.getInputStream();
			 Workbook wb = WorkbookFactory.create(in)) {

			Sheet sh = wb.getSheet(SHEET_DATA);
			if (sh == null) sh = wb.getSheetAt(0);
			DataFormatter fmt = new DataFormatter();
			FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();

			String lastLine = "", lastItem = "";
			boolean first = true;
			for (int i = sh.getFirstRowNum(); i <= sh.getLastRowNum(); i++) {
				if (rows.size() >= MAX_ROWS) break;
				Row r = sh.getRow(i);
				if (r == null) continue;

				List<String> cols = new ArrayList<>(6);
				for (int c = 0; c < 6; c++) cols.add(cellText(r.getCell(c), fmt, ev));

				if (first) {
					first = false;
					String a = cols.get(0).replace(" ", ""), b = cols.get(1).replace(" ", "");
					if (a.equals("라인") || b.equals("품목") || isHeader(cols.get(2))) continue;
				}

				// 품목이 새로 적히면 라인도 그 행 값으로 바꾼다 (빈 라인을 위 품목의 라인으로 잇지 않는다)
				if (!cols.get(1).isEmpty()) {
					lastItem = cols.get(1);
					lastLine = cols.get(0);
				} else if (!cols.get(0).isEmpty()) {
					lastLine = cols.get(0);
				}

				if (cols.get(2).isEmpty()) {
					if (!cols.get(3).isEmpty() || !cols.get(4).isEmpty() || !cols.get(5).isEmpty()) skipped++;
					continue;
				}
				cols.set(0, lastLine);
				cols.set(1, lastItem);
				rows.add(cols);
			}
		} catch (Exception e) {
			log.warn("[부품표 업로드-프로젝트] 읽기 실패: {}", file.getOriginalFilename(), e);
			result.success = false;
			result.message = "엑셀을 읽지 못했습니다. 양식을 내려받아 다시 작성해 주세요.";
			return result;
		}

		Map<String, Object> data = new HashMap<>();
		data.put("rows", rows);
		data.put("skipped", skipped);
		data.put("truncated", rows.size() >= MAX_ROWS);
		result.success = true;
		result.data = data;
		return result;
	}

	/**
	 * 프로젝트 전체 부품 저장 (한 트랜잭션).
	 * payload: { spjangcd, projNo, replace,
	 *            groups:[{sujuId(null=공통), items:[{partName, qty, gubun, kind, materialId}]}] }
	 * 응답 data: [{sujuId, status: saved|skip_existing|invalid, ordered, saved, deleted, unmatched:[...]}]
	 */
	@PostMapping("/part_save_project")
	@Transactional
	public AjaxResult partSaveProject(@RequestBody Map<String, Object> payload, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		String spjangcd = str(payload.get("spjangcd"));
		String projNo = str(payload.get("projNo"));
		boolean replace = Boolean.parseBoolean(str(payload.get("replace")));
		if (projNo.isEmpty()) {
			result.success = false;
			result.message = "프로젝트가 지정되지 않았습니다.";
			return result;
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> groups =
				(List<Map<String, Object>>) payload.getOrDefault("groups", new ArrayList<>());

		List<Map<String, Object>> report = new ArrayList<>();
		for (Map<String, Object> g : groups) {
			Integer sujuId = toInt(g.get("sujuId"));
			Map<String, Object> rep = new HashMap<>();
			rep.put("sujuId", sujuId);
			report.add(rep);

			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("sujuId", sujuId);
			p.addValue("projNo", projNo);
			p.addValue("spjangcd", spjangcd);

			// 화면이 보낸 sujuId 가 정말 이 프로젝트 품목인지 서버에서 다시 본다
			if (sujuId != null) {
				int ok = sqlRunner.queryForCount("""
                    SELECT COUNT(*) FROM suju
                    WHERE id = :sujuId AND project_id = :projNo AND spjangcd = :spjangcd
                    """, p);
				if (ok == 0) {
					rep.put("status", "invalid");
					continue;
				}
			}
			/*
			 * 지시가 나간 품목도 반영한다 (도면이 늦게 나오거나 바뀌면 지시 후에 부품표가 채워진다).
			 * 지시된 품목의 부품은 '확정' 이다 — 지시할 때 품목 부품을 통째로 확정하는 규칙과 맞춘다.
			 * 가공 실적은 부품 행이 아니라 유형별 합계로 잡히므로 교체해도 실적이 끊기지 않는다.
			 */
			boolean ordered = sujuId != null && prodDesignService.countOrder(sujuId) > 0;
			rep.put("ordered", ordered);

			String where = sujuId != null
					? "\"Suju_id\" = :sujuId"
					: "\"Suju_id\" IS NULL AND spjangcd = :spjangcd AND \"Project_id\" = :projNo";
			int existing = sqlRunner.queryForCount("SELECT COUNT(*) FROM iljin_suju_bom WHERE " + where, p);
			if (existing > 0 && !replace) {
				rep.put("status", "skip_existing");
				rep.put("existing", existing);
				continue;
			}
			if (existing > 0) {
				sqlRunner.execute("DELETE FROM iljin_suju_bom WHERE " + where, p);
			}
			rep.put("deleted", existing);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> items =
					(List<Map<String, Object>>) g.getOrDefault("items", new ArrayList<>());
			List<Map<String, Object>> unmatched = new ArrayList<>();
			int order = 0;
			for (Map<String, Object> it : items) {
				String partName = str(it.get("partName"));
				if (partName.isEmpty()) continue;

				String gubun = str(it.get("gubun"));
				if (!Arrays.asList(GUBUN).contains(gubun)) gubun = "제작품";
				it.put("gubun", gubun);
				it.put("state", ordered ? "확정" : "계획");
				it.put("qtyType", "unit");
				it.put("id", null);
				it.put("partNo", String.valueOf(order + 1));

				String kind;
				Integer materialId = null;
				if ("제작품".equals(gubun)) {
					kind = str(it.get("kind"));
					if (kind.isEmpty() || "etc".equals(kind)) kind = prodDesignService.guessKind(spjangcd, partName);
				} else {
					materialId = toInt(it.get("materialId"));
					if (materialId == null) {   // part_save 와 같은 규칙 — 자재 없이 발주 대상을 저장하지 않는다
						unmatched.add(it);
						continue;
					}
					kind = "etc";
				}
				prodDesignService.savePart(it, spjangcd, sujuId, projNo, order++, kind, materialId, user);
			}
			rep.put("status", "saved");
			rep.put("saved", order);
			rep.put("unmatched", unmatched);
		}

		result.success = true;
		result.data = report;
		return result;
	}

	private static CellStyle headStyle(Workbook wb) {
		CellStyle head = wb.createCellStyle();
		Font bold = wb.createFont();
		bold.setBold(true);
		head.setFont(bold);
		head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		head.setAlignment(HorizontalAlignment.CENTER);
		head.setBorderBottom(BorderStyle.THIN);
		return head;
	}

	private static String checkFile(MultipartFile file) {
		if (file == null || file.isEmpty()) return "파일이 비어 있습니다.";
		if (file.getSize() > MAX_BYTES) return "파일이 너무 큽니다 (5MB 이하).";
		String name = str(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
		if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) return "엑셀 파일(.xlsx, .xls)만 올릴 수 있습니다.";
		return null;
	}

	private static Integer toInt(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		try {
			return Integer.parseInt(o.toString().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String cellText(Cell cell, DataFormatter fmt, FormulaEvaluator ev) {
		if (cell == null) return "";
		try {
			// 수식 칸은 계산된 값으로, 숫자는 화면에 보이는 그대로(4.0 → 4)
			return fmt.formatCellValue(cell, ev).replace(' ', ' ').trim();
		} catch (Exception e) {
			return "";
		}
	}

	private static boolean isHeader(String first) {
		String s = first.replace(" ", "").toLowerCase(Locale.ROOT);
		return s.equals("부품명") || s.equals("품명") || s.equals("partname") || s.equals("no") || s.equals("순번");
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}

	private static String safeFileName(String s) {
		String t = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
		return t.isEmpty() ? "품목" : t;
	}
}