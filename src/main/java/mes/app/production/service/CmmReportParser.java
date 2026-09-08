package mes.app.production.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3차원 측정 성적서(CF180 양식) 파서.
 *
 * [양식 구조]  실제 파일을 뜯어 확인한 좌표다.
 *
 *   1~19행   도면 이미지 + 헤더(MODEL / MCS NO / DRAW No. / DRAW NAME / DATE)
 *   20~22행  표 머리   AXIS POSITION | T | L | H
 *                      각 축마다 DATUM / MAKER(LH,RH) / KIA(LH,RH)
 *   23~46행  측정점    C열 = 측정점 번호
 *   47~48행  경도 SPEC / LOC 경도 / PIN 경도 / CLAMP GAP
 *
 * [★ 시트를 성적서 구분자로 쓰지 말 것]
 *   CF180 파일은 시트가 19개지만 성적서는 <b>1부</b>다.
 *   측정점이 1~64 로 시트를 가로질러 연속되고, 시트마다 4점씩 끊긴 이유는
 *   페이지마다 해당 4점의 부분 확대도를 넣기 위해서다(시트당 이미지 1장).
 *   그래서 <b>전 시트를 이어 붙여</b> 한 성적서로 읽는다.
 *
 * [헤더는 비어 있는 게 정상]
 *   확인한 19개 시트 전부 MODEL·MCS NO·DATE 가 미기입이었다.
 *   즉 파일만 보고 어느 품목인지 자동으로 알아낼 수 없다.
 *   품목은 반드시 화면에서 사람이 고르고, 여기서 읽은 헤더는 참고값일 뿐이다.
 *   <b>파일명이나 헤더로 품목을 매칭하지 말 것.</b>
 *
 * [구형 .xls]
 *   2009년에 만들어진 BIFF8 파일이다. WorkbookFactory 를 쓰면
 *   .xls(HSSF) 와 .xlsx(XSSF) 를 모두 받는다. XSSF 만으로는 열리지 않는다.
 */
@Slf4j
@Component
public class CmmReportParser {

	// --- 측정 그리드 (0-based) ---------------------------------------
	private static final int ROW_POINT_FROM = 22;   // 23행
	private static final int ROW_POINT_TO   = 45;   // 46행
	private static final int COL_POINT_NO   = 2;    // C

	/** 축별 컬럼: {datum, maker LH, maker RH, kia LH, kia RH} */
	private static final Map<String, int[]> AXIS_COLS = new LinkedHashMap<>() {{
		put("T", new int[]{ 3,  6,  8, 10, 12});   // D / G,I / K,M
		put("L", new int[]{14, 17, 19, 21, 23});   // O / R,T / V,X
		put("H", new int[]{25, 28, 30, 32, 34});   // Z / AC,AE / AG,AI
	}};

	// --- 헤더 --------------------------------------------------------
	private static final int ROW_HEAD_LABEL = 0;    // 1행: MODEL / MCS NO / ...
	private static final int ROW_HEAD_VALUE = 2;    // 3행: 값

	// --- 경도 / GAP (0-based) ----------------------------------------
	//   G47 'LH' 는 라벨이고 실제 값은 I47:N47 병합칸에 '40~45' 같은 문자열로 들어간다.
	//   숫자가 아니라서 자동판정 대상이 아니다.
	private static final int ROW_HRC_LH = 46;       // 47행
	private static final int ROW_HRC_RH = 47;       // 48행
	private static final int COL_HRC_SPEC  = 0;     // A47:D48  'HRC38이상'
	private static final int COL_LOC_VAL   = 8;     // I
	private static final int COL_PIN_VAL   = 18;    // S
	private static final int COL_GAP_VAL   = 30;    // AE

	// =================================================================

	public static class Point {
		public int seq;
		public String pointNo;
		public String axis;
		public Double datum;
		/** 제작사(우리) 실측 */
		public Double mfrLh, mfrRh;
		/** 발주처 실측 */
		public Double custLh, custRh;

		public boolean hasMfr()  { return mfrLh  != null || mfrRh  != null; }
		public boolean hasCust() { return custLh != null || custRh != null; }
	}

	public static class Result {
		/**
		 * 양식에 인쇄된 발주처 라벨. 이 샘플은 'KIA' 지만 물량에 따라 달라진다.
		 * 표시용 값일 뿐이고 판정이나 컬럼 선택에 쓰지 않는다.
		 */
		public String customerName;
		public String model, mcsNo, drawNo, drawName, measureDate;
		public String hardnessSpec;
		public String locHrcLh, locHrcRh, pinHrcLh, pinHrcRh, clampGapLh, clampGapRh;
		public final List<Point> points = new ArrayList<>();
		/** 파싱 중 건너뛴 것들. 화면에 그대로 보여 준다 */
		public final List<String> warnings = new ArrayList<>();
	}

	/**
	 * 첨부로 이미 저장된 성적서 파일을 읽는다.
	 *
	 * ★ 파일을 새로 받지 않고 <b>기존 첨부를 읽기만</b> 한다.
	 *   업로드는 공통 업로더(ax5 · /api/files)가 그대로 담당하고,
	 *   여기서 다시 저장하면 같은 파일이 두 곳에 생긴다.
	 *
	 * [★ 두 벌을 <b>둘 다</b> 읽는다]
	 *   양식에는 제작사(MAKER)와 발주처(라벨은 물량마다 다름) 두 벌의 실측 열이 있다.
	 *   한쪽만 골라 읽으면
	 *     - 재측정 때 앞 값이 사라지고,
	 *     - 현장이 어느 칸을 쓰는지 확인되지 않은 상태에서 한쪽을 버리게 된다.
	 *   샘플 한 부만 보고 "발주처 칸만 쓴다" 고 단정할 근거가 없으므로 둘 다 담는다.
	 *   비어 있는 쪽은 그냥 null 로 남는다 — 판정에서 알아서 빠진다.
	 */
	public Result parse(File file) throws Exception {

		Result r = new Result();

		try (InputStream is = new FileInputStream(file);
			 Workbook wb = WorkbookFactory.create(is)) {

			int seq = 0;
			boolean headRead = false;

			for (int si = 0; si < wb.getNumberOfSheets(); si++) {
				Sheet sh = wb.getSheetAt(si);
				if (sh == null) continue;

				// 헤더·경도는 어느 시트에나 같은 값이 있다. 처음 채워진 시트 것을 쓴다
				if (!headRead && readHeader(sh, r)) headRead = true;
				readHardness(sh, r);
				if (r.customerName == null) r.customerName = readCustomerName(sh);

				for (int ri = ROW_POINT_FROM; ri <= ROW_POINT_TO; ri++) {
					Row row = sh.getRow(ri);
					if (row == null) continue;

					String pointNo = text(sh, row, COL_POINT_NO);
					if (pointNo.isEmpty()) continue;   // 빈 행 — 양식이 24행까지 그려져 있다

					for (Map.Entry<String, int[]> e : AXIS_COLS.entrySet()) {
						int[] c = e.getValue();
						Double datum  = number(sh, row, c[0]);
						Double mfrLh  = number(sh, row, c[1]);
						Double mfrRh  = number(sh, row, c[2]);
						Double custLh = number(sh, row, c[3]);
						Double custRh = number(sh, row, c[4]);

						// 전부 비면 그 축은 측정하지 않은 것. 행을 만들지 않는다
						if (datum == null && mfrLh == null && mfrRh == null
								&& custLh == null && custRh == null) continue;

						Point p = new Point();
						p.seq = ++seq;
						p.pointNo = pointNo;
						p.axis = e.getKey();
						p.datum = datum;
						p.mfrLh = mfrLh;
						p.mfrRh = mfrRh;
						p.custLh = custLh;
						p.custRh = custRh;
						r.points.add(p);
					}
				}
			}
		}

		if (r.points.isEmpty()) {
			r.warnings.add("측정값을 찾지 못했습니다. CF180 성적서 양식이 맞는지 확인하세요.");
		}
		// 측정점은 있는데 DATUM 이 통째로 빈 경우 — 판정이 불가능하므로 미리 알린다
		long noDatum = r.points.stream().filter(p -> p.datum == null).count();
		if (noDatum > 0) {
			r.warnings.add("설계값(DATUM)이 없는 항목 " + noDatum + "건은 판정에서 제외됩니다.");
		}
		// 어느 칸이 비어 있는지 알려 준다. 양식을 잘못 골랐는지 바로 보이게
		boolean anyMfr  = r.points.stream().anyMatch(Point::hasMfr);
		boolean anyCust = r.points.stream().anyMatch(Point::hasCust);
		if (!r.points.isEmpty() && !anyMfr && !anyCust) {
			r.warnings.add("설계값만 있고 실측값이 없습니다. 성적서가 작성 전인지 확인하세요.");
		} else if (!anyMfr) {
			r.warnings.add("제작사(MAKER) 칸이 비어 있습니다. "
					+ (r.customerName == null ? "발주처" : r.customerName) + " 칸만 판정합니다.");
		} else if (!anyCust) {
			r.warnings.add((r.customerName == null ? "발주처" : r.customerName)
					+ " 칸이 비어 있습니다. 제작사(MAKER) 칸만 판정합니다.");
		}
		return r;
	}

	/**
	 * 발주처 라벨을 읽는다. 21행 세 번째 덩어리(H축 기준 AG열)에 인쇄돼 있다.
	 * 이 샘플은 'KIA' 지만 물량마다 다르므로 <b>값으로</b> 다룬다.
	 */
	private String readCustomerName(Sheet sh) {
		Row row = sh.getRow(20);          // 21행
		if (row == null) return null;
		for (int col : new int[]{10, 21, 32}) {   // K / V / AG — 각 축의 발주처 칸
			String v = text(sh, row, col);
			if (!v.isEmpty() && !"DATUM".equalsIgnoreCase(v) && !"MAKER".equalsIgnoreCase(v)) {
				return v;
			}
		}
		return null;
	}

	// =================================================================
	// 헤더
	// =================================================================

	/**
	 * 라벨(1행)을 찾아 그 아래(3행) 값을 읽는다.
	 * 좌표를 박지 않는 이유는 양식마다 라벨 위치가 조금씩 다르기 때문이다.
	 *
	 * @return 하나라도 값을 읽었으면 true
	 */
	private boolean readHeader(Sheet sh, Result r) {
		Row label = sh.getRow(ROW_HEAD_LABEL);
		Row value = sh.getRow(ROW_HEAD_VALUE);
		if (label == null || value == null) return false;

		boolean any = false;
		for (int ci = 0; ci <= 35; ci++) {
			String lab = text(sh, label, ci).toUpperCase().replaceAll("[\\s\\n.]", "");
			if (lab.isEmpty()) continue;
			String val = text(sh, value, ci);
			if (val.isEmpty()) continue;

			switch (lab) {
				case "MODEL"    -> { r.model    = val; any = true; }
				case "MCSNO"    -> { r.mcsNo    = val; any = true; }
				case "DRAWNO"   -> { r.drawNo   = val; any = true; }
				case "DRAWNAME" -> { r.drawName = val; any = true; }
				case "DATE"     -> { r.measureDate = val; any = true; }
				default -> { }
			}
		}
		// 'CTR FLR-' 처럼 라벨 자체가 값을 품은 칸이 있다. 도면명이 비면 그쪽을 쓴다
		if (r.drawName == null) {
			String t = text(sh, value, 19);   // T열
			if (!t.isEmpty()) { r.drawName = t; any = true; }
		}
		return any;
	}

	private void readHardness(Sheet sh, Result r) {
		Row r47 = sh.getRow(ROW_HRC_LH);
		Row r48 = sh.getRow(ROW_HRC_RH);
		if (r47 == null || r48 == null) return;

		if (r.hardnessSpec == null) r.hardnessSpec = blankToNull(text(sh, r48, COL_HRC_SPEC));
		if (r.locHrcLh   == null) r.locHrcLh   = blankToNull(text(sh, r47, COL_LOC_VAL));
		if (r.locHrcRh   == null) r.locHrcRh   = blankToNull(text(sh, r48, COL_LOC_VAL));
		if (r.pinHrcLh   == null) r.pinHrcLh   = blankToNull(text(sh, r47, COL_PIN_VAL));
		if (r.pinHrcRh   == null) r.pinHrcRh   = blankToNull(text(sh, r48, COL_PIN_VAL));
		if (r.clampGapLh == null) r.clampGapLh = blankToNull(text(sh, r47, COL_GAP_VAL));
		if (r.clampGapRh == null) r.clampGapRh = blankToNull(text(sh, r48, COL_GAP_VAL));
	}

	// =================================================================
	// 셀 읽기
	//   ★ 양식이 병합 범벅이다. 값은 좌상단 앵커에만 있고 나머지 칸은 비어 있으므로
	//     좌표를 그대로 읽으면 자주 null 이 나온다. 병합을 되짚어 앵커를 찾는다.
	// =================================================================

	private Cell resolve(Sheet sh, Row row, int col) {
		Cell c = row.getCell(col);
		if (c != null && c.getCellType() != CellType.BLANK) return c;

		for (CellRangeAddress m : sh.getMergedRegions()) {
			if (m.isInRange(row.getRowNum(), col)) {
				Row ar = sh.getRow(m.getFirstRow());
				return ar == null ? null : ar.getCell(m.getFirstColumn());
			}
		}
		return null;
	}

	private String text(Sheet sh, Row row, int col) {
		Cell c = resolve(sh, row, col);
		if (c == null) return "";
		return switch (c.getCellType()) {
			case STRING  -> c.getStringCellValue().trim();
			case NUMERIC -> DateUtil.isCellDateFormatted(c)
					? String.valueOf(c.getDateCellValue())
					: trimNum(c.getNumericCellValue());
			case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
			case FORMULA -> formulaText(c);
			default      -> "";
		};
	}

	/** 숫자만. 문자가 섞이면(예: '40~45') null 을 돌려 판정에서 빠지게 한다 */
	private Double number(Sheet sh, Row row, int col) {
		Cell c = resolve(sh, row, col);
		if (c == null) return null;

		if (c.getCellType() == CellType.NUMERIC) return c.getNumericCellValue();
		if (c.getCellType() == CellType.FORMULA) {
			try { return c.getNumericCellValue(); } catch (Exception ignore) { return null; }
		}
		if (c.getCellType() == CellType.STRING) {
			// 손으로 친 값에 공백·단위가 붙는 경우가 있다
			String s = c.getStringCellValue().trim().replace(",", "").replace("mm", "");
			if (s.isEmpty()) return null;
			try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
		}
		return null;
	}

	private String formulaText(Cell c) {
		try {
			return switch (c.getCachedFormulaResultType()) {
				case STRING  -> c.getStringCellValue().trim();
				case NUMERIC -> trimNum(c.getNumericCellValue());
				default      -> "";
			};
		} catch (Exception e) {
			return "";
		}
	}

	private String trimNum(double d) {
		return (d == Math.floor(d) && !Double.isInfinite(d))
				? String.valueOf((long) d) : String.valueOf(d);
	}

	private String blankToNull(String s) {
		if (s == null) return null;
		String t = s.trim();
		// '∼' 만 있는 칸은 미기입이다 (양식이 범위 기호를 미리 찍어 둔다)
		if (t.isEmpty() || "∼".equals(t) || "~".equals(t)) return null;
		return t;
	}
}