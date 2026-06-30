package mes.app.spc.Service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SpcCapabilityService {

	@Autowired
	SqlRunner sqlRunner;
	// ---------------------------
	// 1) 관리기준 조회 (수정본)
	// ---------------------------
	public Map<String, Object> findSpec(String processCd, String metricCd, String recipe, String itemName) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("process_cd", processCd);
		param.addValue("metric_cd", metricCd);
		param.addValue("recipe", recipe);
		param.addValue("item_name", itemName);

		String sql = """
        select spc.*,
               s."Value" as measure_cycle_unit_name
        from tb_spc_std01 spc
        left join sys_code s
               on s."Code" = spc.measure_cycle_unit
              and s."CodeType" = 'measure_cycle_unit'
        where spc.process_code = :process_cd
          and spc.measure_code = :metric_cd
          and coalesce(spc.use_yn, 'Y') = 'Y'
          -- ✅ recipe/item 조건은 있으면 적용, 없으면 무시 (fallback 가능)
          and (coalesce(:recipe,'') = '' or spc.recipe = :recipe)
          and (coalesce(:item_name,'') = '' or spc.item_name = :item_name)
        order by
          -- ✅ recipe가 들어왔으면 recipe 매칭이 먼저
          case when coalesce(:recipe,'') <> '' and spc.recipe = :recipe then 0 else 1 end,
          -- ✅ item이 들어왔으면 item 매칭이 먼저
          case when coalesce(:item_name,'') <> '' and spc.item_name = :item_name then 0 else 1 end,
          spc.updated_at desc,
          spc.id desc
        limit 1
        """;


		return sqlRunner.getRow(sql, param);
	}
	// ---------------------------
	// 2) 메인 서비스
	// ---------------------------
	public Object getCapabilityResult(String baseDir, String spjangcd,
																		String dateFrom, String dateTo,
																		String itemName, String processCode,
																		String measureCode, String recipe) {

		// (A) 관리기준
		Map<String, Object> specRow = findSpec(processCode, measureCode, recipe, itemName);
		if (specRow == null || specRow.isEmpty()) {
			throw new IllegalArgumentException("관리기준이 없습니다. (공정/측정항목 기준)");
		}

		Double target = toDouble(specRow.get("target_value"));
		Double usl = toDouble(specRow.get("usl"));
		Double lsl = toDouble(specRow.get("lsl"));
		Double ucl = toDouble(specRow.get("ucl"));
		Double lcl = toDouble(specRow.get("lcl"));
		Integer sampleSize = toInt(specRow.get("sample_size"), 1);
		Integer cycleValue = toInt(specRow.get("measure_cycle_value"), 1);
		String cycleUnitName = toStr(specRow.get("measure_cycle_unit_name"));
		String cycleUnit = toStr(specRow.get("measure_cycle_unit"));
		String unitName = toStr(specRow.get("unit_name"));
		String measureName = toStr(specRow.get("measure_name"));

		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("target", target);
		spec.put("usl", usl);
		spec.put("lsl", lsl);
		spec.put("ucl", ucl);
		spec.put("lcl", lcl);
		spec.put("sampleSize", sampleSize);
		spec.put("measureCycleValue", cycleValue);
		spec.put("measureCycleUnit", cycleUnit);
		spec.put("measure_cycle_unit_name", cycleUnitName);
		spec.put("unitName", unitName);
		spec.put("measureName", measureName);

		// (B) 기간 파싱
		LocalDateTime from = parseDateTimeLocal(dateFrom);
		LocalDateTime to = parseDateTimeLocal(dateTo);
		if (from == null || to == null) {
			throw new IllegalArgumentException("조회 일자 형식이 올바르지 않습니다.");
		}
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("종료일이 시작일보다 빠릅니다.");
		}

		// (C) 로그 읽고 values 추출
		List<Double> values = new ArrayList<>();
		Map<String, List<Double>> valuesByEq = new LinkedHashMap<>();

		LogScanResult scan = scanLogs(baseDir, from, to, itemName, recipe, measureCode);
		values.addAll(scan.values);
		valuesByEq.putAll(scan.byEquipment);

		if (values.isEmpty()) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("spec", spec);
			result.put("kpi", emptyKpi());
			result.put("histogram", emptyHistogram(target, usl, lsl));
			result.put("capability", emptyCapability());
			result.put("box", Collections.emptyList());
			result.put("periodText", formatPeriod(from, to));
			result.put("normality", Map.of("resultText", "-"));
			return result;
		}

		// (D) KPI 계산
		Stats stats = calcStats(values);
		int n = stats.n;
		double mean = stats.mean;
		double sigmaOverall = stats.sigma;     // 현재 overall=within 동일 처리
		double sigmaWithin = stats.sigma;

		double cp = calcCp(usl, lsl, sigmaWithin);
		double cpk = calcCpk(usl, lsl, mean, sigmaWithin);
		double pp = calcCp(usl, lsl, sigmaOverall);
		double ppk = calcCpk(usl, lsl, mean, sigmaOverall);

		int oos = countOutOfSpec(values, usl, lsl);

		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("n", n);
		kpi.put("mean", mean);
		kpi.put("sigmaWithin", sigmaWithin);
		kpi.put("sigmaOverall", sigmaOverall);
		kpi.put("min", stats.min);
		kpi.put("max", stats.max);
		kpi.put("oosCount", oos);
		kpi.put("cp", cp);
		kpi.put("cpk", cpk);
		kpi.put("pp", pp);
		kpi.put("ppk", ppk);
		kpi.put("judge", judgeByCpk(cpk));

		// (E) 히스토그램 bins
		Map<String, Object> histogram = buildHistogram(values, target, usl, lsl, mean, unitName);

		// (F) capability 차트용
		Map<String, Object> capability = new LinkedHashMap<>();
		capability.put("labels", List.of("Cp", "Cpk"));
		capability.put("values", List.of(cp, cpk));
		capability.put("guides", List.of(1.00, 1.33, 1.67));

		// (G) 설비별 box plot
		List<Map<String, Object>> box = new ArrayList<>();
		for (Map.Entry<String, List<Double>> e : valuesByEq.entrySet()) {
			String eq = e.getKey();
			List<Double> vs = e.getValue();
			if (vs == null || vs.size() < 2) continue;

			Box bx = calcBox(vs);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("label", eq);
			row.put("min", bx.min);
			row.put("q1", bx.q1);
			row.put("median", bx.median);
			row.put("q3", bx.q3);
			row.put("max", bx.max);
			box.add(row);
		}

		// (H) 최종 조립
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("spec", spec);
		result.put("kpi", kpi);
		result.put("histogram", histogram);
		result.put("capability", capability);
		result.put("box", box);
		result.put("periodText", formatPeriod(from, to));
		result.put("normality", Map.of("resultText", "-")); // 정규성 검정은 추후

		return result;
	}

	// =========================================================
	// 로그 스캔/파싱
	// =========================================================

	private static class LogScanResult {
		List<Double> values = new ArrayList<>();
		Map<String, List<Double>> byEquipment = new LinkedHashMap<>();
	}

	private LogScanResult scanLogs(String baseDir,
																 LocalDateTime from, LocalDateTime to,
																 String itemName, String recipe,
																 String measureCode) {

		Path root = Paths.get(baseDir);
		if (!Files.exists(root)) {
			throw new IllegalArgumentException("로그 폴더가 존재하지 않습니다: " + baseDir);
		}

		// ✅ measureCode -> 로그 헤더 후보명들 (확정된 것만)
		List<String> colCandidates = resolveLogColumns(measureCode);

		LogScanResult out = new LogScanResult();

		// 파일 확장자는 환경마다 다르니 전부 열되, 너무 크면 조건으로 걸러도 됨
		try {
			Files.walk(root)
				.filter(Files::isRegularFile)
				.forEach(p -> {
					try {
						scanSingleFile(p, from, to, itemName, recipe, colCandidates, out);
					} catch (Exception ex) {
						// 파일 하나 실패가 전체를 깨지 않게
						log.warn("log scan failed: {} / {}", p, ex.getMessage());
					}
				});
		} catch (IOException e) {
			throw new RuntimeException("로그 폴더 탐색 실패: " + e.getMessage(), e);
		}

		return out;
	}

	private void scanSingleFile(Path file,
															LocalDateTime from, LocalDateTime to,
															String itemName, String recipe,
															List<String> colCandidates,
															LogScanResult out) throws IOException {

		// ✅ 설비명: 경로에서 REFLOW-xx 추출 (없으면 상위폴더명)
		String equipment = extractEquipmentName(file);

		// ✅ 인코딩: 로그/엑셀 저장형태에 따라 다를 수 있음(필요 시 CP949로 변경)
		Charset cs = Charset.forName("UTF-8");

		try (BufferedReader br = Files.newBufferedReader(file, cs)) {
			String headerLine = null;

			// header 찾기 (첫 유효 라인)
			while (true) {
				String line = br.readLine();
				if (line == null) return;
				line = line.trim();
				if (line.isEmpty()) continue;
				headerLine = line;
				break;
			}

			// 탭/다중 공백을 고려: 보통 탭 구분
			String[] headers = splitColumns(headerLine);

			// column index 맵
			Map<String, Integer> idx = new HashMap<>();
			for (int i = 0; i < headers.length; i++) {
				idx.put(normalize(headers[i]), i);
			}

			// 날짜 컬럼 후보: "날 짜" 또는 "날짜"
			Integer dtIdx = firstIndex(idx, List.of("날짜", "날 짜", "date", "datetime"));
			if (dtIdx == null) {
				// 파일마다 형식이 다르면 스킵
				return;
			}

			// 레시피 컬럼 후보: "파일 이름"
			Integer recipeIdx = firstIndex(idx, List.of("파일이름", "파일 이름", "file name", "filename"));

			// 측정 컬럼 idx 찾기
			Integer valueIdx = null;
			for (String c : colCandidates) {
				Integer ii = idx.get(normalize(c));
				if (ii != null) {
					valueIdx = ii;
					break;
				}
			}
			if (valueIdx == null) {
				// 이 파일엔 해당 컬럼이 없을 수 있음
				return;
			}

			// 데이터 라인들
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;

				String[] cols = splitColumns(line);
				if (dtIdx >= cols.length) continue;

				LocalDateTime ts = parseKoreanLogDateTime(cols[dtIdx]);
				if (ts == null) continue;

				if (ts.isBefore(from) || ts.isAfter(to)) continue;

				// recipe 필터 (optional)
				if (recipe != null && !recipe.isBlank() && recipeIdx != null && recipeIdx < cols.length) {
					String r = cols[recipeIdx];
					if (r == null || !r.contains(recipe)) continue;
				}

				// itemName 필터는 로그에 품목명이 없을 가능성이 커서,
				// 지금은 "파일이름에 포함" 정도로만 (확실치 않으면 적용 X)
				if (itemName != null && !itemName.isBlank() && recipeIdx != null && recipeIdx < cols.length) {
					String r = cols[recipeIdx];
					if (r == null || !r.contains(itemName)) {
						// 품목명이 파일명에 없으면 제외 (정책이 다르면 여기 조정)
						continue;
					}
				}

				if (valueIdx >= cols.length) continue;
				Double v = toDouble(cols[valueIdx]);
				if (v == null) continue;

				out.values.add(v);
				out.byEquipment.computeIfAbsent(equipment, k -> new ArrayList<>()).add(v);
			}
		}
	}

	// measureCode -> 로그 컬럼 후보명 (확정된 것만)
	private List<String> resolveLogColumns(String measureCode) {
		if (measureCode == null || measureCode.isBlank()) {
			throw new IllegalArgumentException("측정항목(measure_code)이 비었습니다.");
		}

		// ✅ 아래는 네가 확정한 것만 매핑 추가하면 됨
		// 로그 헤더와 정확히 맞춰야 함(띄어쓰기/대소문자/특수문자 주의)
		switch (measureCode.trim().toUpperCase()) {
			case "CONV_SPEED":
				return List.of("C/V Speed", "C/V SPEED", "CV SPEED");
			case "O2_PPM":
				// 너가 올린 설명 기준: "에어"가 산소농도 값(예: 10500)
				return List.of("에어", "산소농도", "O2", "O2_PPM");
			case "TAL_SEC":
				return List.of("TAL", "TAL_SEC");
			default:
				// ⚠️ 여기서 임의 추측하지 않고 에러로 알려줌
				throw new IllegalArgumentException("measure_code 로그 컬럼 매핑이 정의되지 않았습니다: " + measureCode);
		}
	}

	// =========================================================
	// 통계/차트 유틸
	// =========================================================

	private static class Stats {
		int n;
		double mean;
		double sigma;
		double min;
		double max;
	}

	private Stats calcStats(List<Double> values) {
		Stats s = new Stats();
		s.n = values.size();

		double sum = 0;
		s.min = Double.POSITIVE_INFINITY;
		s.max = Double.NEGATIVE_INFINITY;

		for (double v : values) {
			sum += v;
			if (v < s.min) s.min = v;
			if (v > s.max) s.max = v;
		}
		s.mean = sum / s.n;

		if (s.n < 2) {
			s.sigma = 0.0;
			return s;
		}

		double ss = 0;
		for (double v : values) {
			double d = v - s.mean;
			ss += d * d;
		}
		// 표본 표준편차
		s.sigma = Math.sqrt(ss / (s.n - 1));
		return s;
	}

	private int countOutOfSpec(List<Double> values, Double usl, Double lsl) {
		if (usl == null && lsl == null) return 0;
		int c = 0;
		for (double v : values) {
			if (usl != null && v > usl) c++;
			else if (lsl != null && v < lsl) c++;
		}
		return c;
	}

	private double calcCp(Double usl, Double lsl, double sigma) {
		if (usl == null || lsl == null) return Double.NaN;
		if (sigma <= 0) return Double.NaN;
		return (usl - lsl) / (6.0 * sigma);
	}

	private double calcCpk(Double usl, Double lsl, double mean, double sigma) {
		if (sigma <= 0) return Double.NaN;
		Double cpu = (usl == null) ? null : (usl - mean) / (3.0 * sigma);
		Double cpl = (lsl == null) ? null : (mean - lsl) / (3.0 * sigma);

		if (cpu == null && cpl == null) return Double.NaN;
		if (cpu == null) return cpl;
		if (cpl == null) return cpu;
		return Math.min(cpu, cpl);
	}

	private String judgeByCpk(double cpk) {
		if (Double.isNaN(cpk)) return "-";
		if (cpk >= 1.67) return "우수";
		if (cpk >= 1.33) return "양호";
		if (cpk >= 1.00) return "보통";
		return "미흡";
	}

	private Map<String, Object> buildHistogram(List<Double> values,
																						 Double target, Double usl, Double lsl,
																						 double mean, String unitName) {
		int n = values.size();
		double min = values.stream().mapToDouble(d -> d).min().orElse(0);
		double max = values.stream().mapToDouble(d -> d).max().orElse(0);

		int k = Math.max(5, (int) Math.ceil(Math.log(n) / Math.log(2) + 1)); // Sturges
		double binW = (max - min) / k;
		if (binW <= 0) binW = 1;

		int[] counts = new int[k];
		for (double v : values) {
			int idx = (int) Math.floor((v - min) / binW);
			if (idx < 0) idx = 0;
			if (idx >= k) idx = k - 1;
			counts[idx]++;
		}

		List<String> labels = new ArrayList<>();
		List<Integer> data = new ArrayList<>();
		List<Map<String, Object>> bins = new ArrayList<>();

		for (int i = 0; i < k; i++) {
			double a = min + i * binW;
			double b = a + binW;

			labels.add(fmt(a) + "~" + fmt(b));
			data.add(counts[i]);

			Map<String, Object> bin = new LinkedHashMap<>();
			bin.put("from", a);
			bin.put("to", b);
			bin.put("count", counts[i]);
			bins.add(bin);
		}

		Map<String, Object> hist = new LinkedHashMap<>();
		hist.put("labels", labels);
		hist.put("counts", data);
		hist.put("bins", bins);

		hist.put("min", min);
		hist.put("max", max);
		hist.put("mean", mean);
		hist.put("target", target);
		hist.put("usl", usl);
		hist.put("lsl", lsl);
		hist.put("unitName", unitName);

		return hist;
	}

	private static class Box {
		double min, q1, median, q3, max;
	}

	private Box calcBox(List<Double> values) {
		List<Double> v = new ArrayList<>(values);
		Collections.sort(v);

		Box b = new Box();
		b.min = v.get(0);
		b.max = v.get(v.size() - 1);
		b.q1 = percentile(v, 25);
		b.median = percentile(v, 50);
		b.q3 = percentile(v, 75);
		return b;
	}

	private double percentile(List<Double> sorted, int p) {
		if (sorted == null || sorted.isEmpty()) return Double.NaN;
		int n = sorted.size();
		if (n == 1) return sorted.get(0);

		// Linear interpolation (Excel-ish)
		double rank = (p / 100.0) * (n - 1);
		int lo = (int) Math.floor(rank);
		int hi = (int) Math.ceil(rank);
		if (lo == hi) return sorted.get(lo);
		double w = rank - lo;
		return sorted.get(lo) * (1 - w) + sorted.get(hi) * w;
	}

	// =========================================================
	// Empty structures
	// =========================================================
	private Map<String, Object> emptyKpi() {
		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("n", 0);
		kpi.put("mean", "");
		kpi.put("sigmaWithin", "");
		kpi.put("sigmaOverall", "");
		kpi.put("min", "");
		kpi.put("max", "");
		kpi.put("oosCount", 0);
		kpi.put("cp", "");
		kpi.put("cpk", "");
		kpi.put("pp", "");
		kpi.put("ppk", "");
		kpi.put("judge", "-");
		return kpi;
	}

	private Map<String, Object> emptyHistogram(Double target, Double usl, Double lsl) {
		Map<String, Object> h = new LinkedHashMap<>();
		h.put("labels", Collections.emptyList());
		h.put("counts", Collections.emptyList());
		h.put("bins", Collections.emptyList());
		h.put("mean", "");
		h.put("target", target);
		h.put("usl", usl);
		h.put("lsl", lsl);
		return h;
	}

	private Map<String, Object> emptyCapability() {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("labels", List.of("Cp", "Cpk"));
		c.put("values", List.of(0, 0));
		c.put("guides", List.of(1.00, 1.33, 1.67));
		return c;
	}

	// =========================================================
	// Parsing helpers
	// =========================================================

	private LocalDateTime parseDateTimeLocal(String s) {
		if (s == null || s.isBlank()) return null;
		// input type=datetime-local => "2026-01-26T06:15"
		try {
			return LocalDateTime.parse(s);
		} catch (Exception ignore) {
		}
		// 혹시 공백형이면 추가
		try {
			return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		} catch (Exception ignore) {
		}
		return null;
	}

	// 예: "2026-01-26 오전 06:15:13" , "2016-09-27 오전 8:33:48"
	private LocalDateTime parseKoreanLogDateTime(String s) {
		if (s == null) return null;
		s = s.trim();
		if (s.isEmpty()) return null;

		// 정규식: yyyy-MM-dd (오전|오후) H:mm:ss
		Pattern p = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(오전|오후)\\s+(\\d{1,2}:\\d{2}:\\d{2})$");
		Matcher m = p.matcher(s);
		if (!m.find()) return null;

		String date = m.group(1);
		String ampm = m.group(2);
		String time = m.group(3);

		try {
			LocalDate d = LocalDate.parse(date);
			LocalTime t = LocalTime.parse(padHour(time)); // 8:33:48 -> 08:33:48
			int hour = t.getHour();

			if ("오후".equals(ampm) && hour < 12) hour += 12;
			if ("오전".equals(ampm) && hour == 12) hour = 0;

			return LocalDateTime.of(d, LocalTime.of(hour, t.getMinute(), t.getSecond()));
		} catch (Exception e) {
			return null;
		}
	}

	private String padHour(String hhmmss) {
		// "8:33:48" -> "08:33:48"
		if (hhmmss == null) return "";
		String[] parts = hhmmss.split(":");
		if (parts.length != 3) return hhmmss;
		if (parts[0].length() == 1) parts[0] = "0" + parts[0];
		return parts[0] + ":" + parts[1] + ":" + parts[2];
	}

	private String formatPeriod(LocalDateTime from, LocalDateTime to) {
		Duration d = Duration.between(from, to);
		long minutes = d.toMinutes();
		if (minutes < 60) return minutes + "분";
		long hours = minutes / 60;
		if (hours < 24) return hours + "시간";
		long days = hours / 24;
		return days + "일";
	}

	private String extractEquipmentName(Path file) {
		String p = file.toString().replace("\\", "/");
		Pattern r = Pattern.compile("(REFLOW-\\d+)", Pattern.CASE_INSENSITIVE);
		Matcher m = r.matcher(p);
		if (m.find()) return m.group(1).toUpperCase();

		// 없으면 상위 폴더명
		Path parent = file.getParent();
		if (parent != null) return parent.getFileName().toString();
		return "UNKNOWN";
	}

	private String[] splitColumns(String line) {
		// 탭 우선, 탭이 없으면 다중 공백
		if (line.contains("\t")) {
			return line.split("\t", -1);
		}
		return line.split("\\s{2,}", -1);
	}

	private String normalize(String s) {
		if (s == null) return "";
		return s.replace("\uFEFF", "") // BOM 제거
						 .trim()
						 .replaceAll("\\s+", "")  // 공백 제거(날 짜 vs 날짜 등)
						 .toLowerCase();
	}

	private Integer firstIndex(Map<String, Integer> idx, List<String> candidates) {
		for (String c : candidates) {
			Integer i = idx.get(normalize(c));
			if (i != null) return i;
		}
		return null;
	}

	private String fmt(double v) {
		return String.format(Locale.US, "%.2f", v);
	}

	// =========================================================
	// basic util
	// =========================================================
	private static String toStr(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static Integer toInt(Object o, int def) {
		if (o == null) return def;
		try {
			if (o instanceof Number) return ((Number) o).intValue();
			return Integer.parseInt(String.valueOf(o));
		} catch (Exception e) {
			return def;
		}
	}

	private static Double toDouble(Object o) {
		if (o == null) return null;
		try {
			if (o instanceof Number) return ((Number) o).doubleValue();
			String s = String.valueOf(o).trim();
			if (s.isEmpty()) return null;
			// "1500 RPM" 같은 문자열이면 숫자만 추출 시도
			s = s.replaceAll(",", "");
			Matcher m = Pattern.compile("(-?\\d+(\\.\\d+)?)").matcher(s);
			if (m.find()) return Double.parseDouble(m.group(1));
			return null;
		} catch (Exception e) {
			return null;
		}
	}

}
