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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SpcStatisticsService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getMeasureCodes(String processCode) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("processCode", processCode);
		String sql = """
			SELECT DISTINCT
			    measure_code AS value,
			    measure_name AS text
			FROM tb_spc_std01
			WHERE process_code = :processCode
			  AND COALESCE(use_yn, 'Y') = 'Y'
			ORDER BY text;
			
			""";
		return sqlRunner.getRows(sql, dicParam);
	}

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
	// 2) spcList 메인 (Capability 스타일)
	// ---------------------------
	public Object getSpcListResult(
		String baseDir, String spjangcd,
		String dateFrom, String dateTo,
		String itemName, String processCode,
		String measureCode, String recipe
	) {

		// (A) 관리기준
		Map<String, Object> specRow = findSpec(processCode, measureCode, recipe, itemName);
		if (specRow == null || specRow.isEmpty()) {
			// spcList는 "없으면 빈 리턴" 정책이면 이렇게
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("message", "관리기준(스펙)이 등록되지 않았습니다.");
			empty.put("rows", List.of());
			empty.put("spec", Map.of());
			empty.put("kpi", emptyKpi());
			return empty;
		}

		Double target = toDouble(specRow.get("target_value"));
		Double usl = toDouble(specRow.get("usl"));
		Double lsl = toDouble(specRow.get("lsl"));
		Double ucl = toDouble(specRow.get("ucl"));
		Double lcl = toDouble(specRow.get("lcl"));

		Integer sampleSize = toInt(specRow.get("sample_size"), 1);
		Integer cycleValue = toInt(specRow.get("measure_cycle_value"), 1);
		String cycleUnit = toStr(specRow.get("measure_cycle_unit"));
		String cycleUnitName = toStr(specRow.get("measure_cycle_unit_name"));
		String unitName = toStr(specRow.get("unit_name"));
		String measureName = toStr(specRow.get("measure_name"));

		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("target_value", target);
		spec.put("usl", usl);
		spec.put("lsl", lsl);
		spec.put("ucl", ucl);
		spec.put("lcl", lcl);
		spec.put("sample_size", sampleSize);
		spec.put("measure_cycle_value", cycleValue);
		spec.put("measure_cycle_unit", cycleUnit);
		spec.put("measure_cycle_unit_name", cycleUnitName);
		spec.put("unit_name", unitName);
		spec.put("measure_name", measureName);
		spec.put("recipe", toStr(specRow.get("recipe")));
		spec.put("item_name", toStr(specRow.get("item_name")));

		// (B) 기간 파싱
		LocalDateTime from = parseDateTimeLocal(dateFrom);
		LocalDateTime to = parseDateTimeLocal(dateTo);
		if (from == null || to == null) throw new IllegalArgumentException("조회 일자 형식이 올바르지 않습니다.");
		if (to.isBefore(from)) throw new IllegalArgumentException("종료일이 시작일보다 빠릅니다.");

		// (C) 로그 스캔 -> time/value rows (spcList는 raw table용)
		LogScanTable scan = scanLogsForTable(baseDir, from, to, itemName, recipe, measureCode);

		// 값 없으면 빈
		if (scan.rows.isEmpty()) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("spec", spec);
			result.put("unit", unitName);
			result.put("rows", List.of());
			result.put("kpi", emptyKpi());
			result.put("recipe", recipe);
			result.put("item_name", itemName);
			result.put("process_code", processCode);
			result.put("measure_code", measureCode);
			result.put("measure_name", measureName);
			return result;
		}

		// (D) KPI 계산 (이미 table row에 value가 Double로 들어있게 만들자)
		List<Double> values = scan.rows.stream()
														.map(r -> (Double) r.get("value"))
														.filter(Objects::nonNull)
														.toList();

		Stats stats = calcStats(values);

		// ✅ 한계초과: spcList는 UCL/LCL 기준(너 기존 로직과 동일)
		int limitOver = countOutOfLimit(values, ucl, lcl);

		// ✅ CPK: spcList에서 CPK도 내려주고 싶으면 USL/LSL 기반으로 계산
		double cpk = calcCpk(usl, lsl, stats.mean, stats.sigma);

		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("mean", stats.mean);
		kpi.put("std", stats.sigma);
		kpi.put("min", stats.min);
		kpi.put("max", stats.max);
		kpi.put("n", stats.n);
		kpi.put("limitOverCount", limitOver);
		kpi.put("cpk", Double.isNaN(cpk) ? "" : cpk);

		// (E) judge 붙여서 rows 내려주기
		List<Map<String, Object>> tableRows = attachJudge(scan.rows, ucl, lcl);

		// (F) 최종
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("spec", spec);
		result.put("unit", unitName.isBlank() ? resolveUnit(measureCode) : unitName);
		result.put("rows", tableRows);
		result.put("kpi", kpi);
		result.put("recipe", recipe);
		result.put("item_name", itemName);
		result.put("measure_name", measureName);

		return result;
	}

	// =========================================================
// 로그 스캔(테이블용): time/value
// =========================================================
	private static class LogScanTable {
		List<Map<String, Object>> rows = new ArrayList<>();
	}

// ------------------------
// 매핑( enum/DTO 없이 )
// ------------------------

	// DIRECT 항목의 primary 후보
	private static final Map<String, List<String>> PRIMARY_COLS = Map.of(
		"O2_PPM", List.of("에어", "산소농도", "O2", "O2_PPM", "O2 PPM", "O2(ppm)", "O₂"),
		"CONV_SPEED", List.of("C/V Speed", "CV Speed", "C/V SPEED", "CV SPEED")
	);

	// DIRECT 항목의 fallback 후보(필요 시만)
	private static final Map<String, List<String>> FALLBACK_COLS = Map.of(
		"O2_PPM", List.of() // 현재 로그 포맷에선 primary에 "에어"가 있으니 보통 비움
	);

	// 계산형 항목 목록
	private static final Set<String> CALCULATED_CODES = Set.of(
		"PEAK_TEMP",
		"TAL_SEC"
	);

	// O2 유효성 판단에 필요한 컬럼 후보(로그 헤더 기준)
	private static final List<String> MODE_COLS = List.of("질소/에어", "모드", "N2/AIR", "N2/Air");
	private static final List<String> UNIT_COLS = List.of("단 위", "단위", "UNIT");
	private static final List<String> RUN_COLS  = List.of("가동 상태", "가동상태", "RUN");

	// ------------------------
// 엔트리
// ------------------------
	private LogScanTable scanLogsForTable(
		String baseDir, LocalDateTime from, LocalDateTime to,
		String itemName, String recipe,
		String measureCode
	) {
		Path root = Paths.get(baseDir);
		if (!Files.exists(root)) throw new IllegalArgumentException("로그 폴더가 존재하지 않습니다: " + baseDir);

		String code = normCode(measureCode);

		// DIRECT 항목만 컬럼 후보가 필요함
		List<String> primaryCandidates = isCalculated(code) ? List.of() : getPrimaryCandidates(code);
		List<String> fallbackCandidates = isCalculated(code) ? List.of() : getFallbackCandidates(code);

		LogScanTable out = new LogScanTable();

		try {
			Files.walk(root)
				.filter(Files::isRegularFile)
				.forEach(p -> {
					try {
						scanSingleFileForTable(p, from, to, itemName, recipe, code, primaryCandidates, fallbackCandidates, out);
					} catch (Exception ex) {
						log.warn("log scan failed: {} / {}", p, ex.getMessage());
					}
				});
		} catch (IOException e) {
			throw new RuntimeException("로그 폴더 탐색 실패: " + e.getMessage(), e);
		}

		// 시간순 정렬
		out.rows.sort(Comparator.comparing(r -> toLocalDateTimeSafe(r.get("time"))));
		return out;
	}

	private void scanSingleFileForTable(
		Path file, LocalDateTime from, LocalDateTime to,
		String itemName, String recipe,
		String measureCode,
		List<String> primaryCandidates,
		List<String> fallbackCandidates,
		LogScanTable out
	) throws IOException {

		Charset cs = Charset.forName("UTF-8");

		try (BufferedReader br = Files.newBufferedReader(file, cs)) {

			// ------------------------
			// 헤더 라인 찾기
			// ------------------------
			String headerLine = null;
			while (true) {
				String line = br.readLine();
				if (line == null) return;
				line = line.trim();
				if (line.isEmpty()) continue;
				headerLine = line;
				break;
			}

			String[] headers = splitColumns(headerLine);
			Map<String, Integer> idx = new HashMap<>();
			for (int i = 0; i < headers.length; i++) idx.put(normalize(headers[i]), i);

			Integer dtIdx = firstIndex(idx, List.of("날짜", "날 짜", "date", "datetime"));
			if (dtIdx == null) return;

			Integer recipeIdx = firstIndex(idx, List.of("파일이름", "파일 이름", "file name", "filename"));

			// O2 유효성 판단 인덱스(없어도 동작)
			Integer modeIdx = firstIndex(idx, MODE_COLS);
			Integer unitIdx = firstIndex(idx, UNIT_COLS);
			Integer runIdx  = firstIndex(idx, RUN_COLS);

			// ------------------------
			// DIRECT: valueIdx 찾기
			// CALCULATED: zone idx 모으기
			// ------------------------
			Integer valueIdx = null;
			List<Integer> zoneActualIdxs = List.of();

			if (!isCalculated(measureCode)) {
				valueIdx = findFirstIdx(idx, primaryCandidates);
				if (valueIdx == null) valueIdx = findFirstIdx(idx, fallbackCandidates);
				if (valueIdx == null) return;
			} else {
				// 계산형(PEAK_TEMP/TAL_SEC)에서 사용할 Zone 현재값 인덱스 모으기
				zoneActualIdxs = collectZoneActualIdxs(idx);
				if (zoneActualIdxs.isEmpty()) return;
			}

			// ------------------------
			// 데이터 라인 스캔
			// ------------------------
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;

				String[] cols = splitColumns(line);
				if (dtIdx >= cols.length) continue;

				LocalDateTime ts = parseKoreanLogDateTime(cols[dtIdx]);
				if (ts == null) continue;
				if (ts.isBefore(from) || ts.isAfter(to)) continue;

				// recipe filter
				if (recipe != null && !recipe.isBlank() && recipeIdx != null && recipeIdx < cols.length) {
					String r = cols[recipeIdx];
					if (r == null || !r.contains(recipe)) continue;
				}

				// itemName filter (현재 정책 유지)
				if (itemName != null && !itemName.isBlank() && recipeIdx != null && recipeIdx < cols.length) {
					String r = cols[recipeIdx];
					if (r == null || !r.contains(itemName)) continue;
				}

				Double v;

				if (!isCalculated(measureCode)) {
					// ------------------------
					// DIRECT: 컬럼 값 읽기
					// ------------------------
					if (valueIdx >= cols.length) continue;

					// O2_PPM이면 유효성 체크(N2/PPM/자동)
					if ("O2_PPM".equals(measureCode)) {
						if (!isValidO2Row(cols, modeIdx, unitIdx, runIdx)) continue;
					}

					v = toDouble(cols[valueIdx]);
					if (v == null) continue;

				} else {
					// ------------------------
					// CALCULATED
					// ------------------------
					if ("PEAK_TEMP".equals(measureCode)) {
						v = computePeakTemp(cols, zoneActualIdxs);
						if (v == null) continue;
					} else if ("TAL_SEC".equals(measureCode)) {
						// ⚠️ TAL은 누적 계산이 필요(행 단위 계산으로는 부정확)
						// TODO: 파일 단위로 time-series를 모아서 기준온도 이상 유지시간 누적 계산 구현 필요
						continue;
					} else {
						// 계산형인데 정의 안 된 경우
						continue;
					}
				}

				Map<String, Object> row = new LinkedHashMap<>();
				row.put("time", cols[dtIdx]);   // 원문 (예: 2026-01-26 오전 06:15:13)
				row.put("value", v);
				out.rows.add(row);
			}
		}
	}

	// ------------------------
// Judge 부착(기존 유지)
// ------------------------
	private List<Map<String, Object>> attachJudge(List<Map<String, Object>> rows, Double ucl, Double lcl) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Double v = (Double) r.get("value");
			String judge = "정상";
			if (v != null) {
				if (ucl != null && v > ucl) judge = "이상";
				else if (lcl != null && v < lcl) judge = "이상";
			}
			Map<String, Object> m = new LinkedHashMap<>(r);
			m.put("judge", judge);
			m.put("memo", "");
			out.add(m);
		}
		return out;
	}

	// =========================================================
// 유틸: enum/DTO 없이 매핑 조회
// =========================================================
	private static String normCode(String measureCode) {
		if (measureCode == null || measureCode.isBlank())
			throw new IllegalArgumentException("측정항목(measure_code)이 비었습니다.");
		return measureCode.trim().toUpperCase();
	}

	private boolean isCalculated(String measureCode) {
		return CALCULATED_CODES.contains(normCode(measureCode));
	}

	private List<String> getPrimaryCandidates(String measureCode) {
		String code = normCode(measureCode);
		List<String> cols = PRIMARY_COLS.get(code);
		if (cols == null)
			throw new IllegalArgumentException("PRIMARY 로그 컬럼 매핑이 정의되지 않았습니다: " + code);
		return cols;
	}

	private List<String> getFallbackCandidates(String measureCode) {
		String code = normCode(measureCode);
		return FALLBACK_COLS.getOrDefault(code, List.of());
	}

	private Integer findFirstIdx(Map<String, Integer> idx, List<String> candidates) {
		for (String c : candidates) {
			Integer ii = idx.get(normalize(c));
			if (ii != null) return ii;
		}
		return null;
	}

	// ZT 현재값N / ZB 현재값N 모으기 (로그에 따라 1~10 또는 1~12)
	private List<Integer> collectZoneActualIdxs(Map<String, Integer> idx) {
		List<Integer> list = new ArrayList<>();
		for (int n = 1; n <= 12; n++) {
			Integer zt = idx.get(normalize("ZT 현재값" + n));
			Integer zb = idx.get(normalize("ZB 현재값" + n));
			if (zt != null) list.add(zt);
			if (zb != null) list.add(zb);
		}
		return list;
	}

	private Double computePeakTemp(String[] cols, List<Integer> zoneActualIdxs) {
		Double max = null;
		for (Integer i : zoneActualIdxs) {
			if (i == null || i >= cols.length) continue;
			Double v = toDouble(cols[i]);
			if (v == null) continue;
			if (max == null || v > max) max = v;
		}
		return max;
	}

	// O2_PPM 유효성 판단: N2/PPM/자동
	private boolean isValidO2Row(String[] cols, Integer modeIdx, Integer unitIdx, Integer runIdx) {
		// 모드: N2일 때만 유효
		if (modeIdx != null && modeIdx < cols.length) {
			String mode = cols[modeIdx];
			if (mode != null && !mode.isBlank() && !"N2".equalsIgnoreCase(mode.trim())) return false;
		}
		// 단위: PPM일 때만 유효
		if (unitIdx != null && unitIdx < cols.length) {
			String unit = cols[unitIdx];
			if (unit != null && !unit.isBlank() && !"PPM".equalsIgnoreCase(unit.trim())) return false;
		}
		// 가동 상태: 자동일 때만 유효
		if (runIdx != null && runIdx < cols.length) {
			String run = cols[runIdx];
			if (run != null && !run.isBlank() && !run.contains("자동")) return false;
		}
		return true;
	}

	// =========================================================
	// KPI helpers
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

		if (s.n < 2) { s.sigma = 0.0; return s; }

		double ss = 0;
		for (double v : values) {
			double d = v - s.mean;
			ss += d * d;
		}
		s.sigma = Math.sqrt(ss / (s.n - 1)); // 표본 표준편차
		return s;
	}

	private int countOutOfLimit(List<Double> values, Double ucl, Double lcl) {
		if (ucl == null && lcl == null) return 0;
		int c = 0;
		for (double v : values) {
			if (ucl != null && v > ucl) c++;
			else if (lcl != null && v < lcl) c++;
		}
		return c;
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

	private Map<String, Object> emptyKpi() {
		Map<String, Object> kpi = new LinkedHashMap<>();
		kpi.put("mean", "");
		kpi.put("std", "");
		kpi.put("min", "");
		kpi.put("max", "");
		kpi.put("n", 0);
		kpi.put("limitOverCount", 0);
		kpi.put("cpk", "");
		return kpi;
	}

	// =========================================================
	// parsing/util (Capability에서 복붙)
	// =========================================================
	private LocalDateTime parseDateTimeLocal(String s) {
		if (s == null || s.isBlank()) return null;
		try { return LocalDateTime.parse(s); } catch (Exception ignore) {}
		try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); } catch (Exception ignore) {}
		return null;
	}

	private LocalDateTime parseKoreanLogDateTime(String s) {
		if (s == null) return null;
		s = s.trim();
		if (s.isEmpty()) return null;

		Pattern p = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(오전|오후)\\s+(\\d{1,2}:\\d{2}:\\d{2})$");
		Matcher m = p.matcher(s);
		if (!m.find()) return null;

		String date = m.group(1);
		String ampm = m.group(2);
		String time = m.group(3);

		try {
			LocalDate d = LocalDate.parse(date);
			LocalTime t = LocalTime.parse(padHour(time));
			int hour = t.getHour();
			if ("오후".equals(ampm) && hour < 12) hour += 12;
			if ("오전".equals(ampm) && hour == 12) hour = 0;
			return LocalDateTime.of(d, LocalTime.of(hour, t.getMinute(), t.getSecond()));
		} catch (Exception e) {
			return null;
		}
	}

	private String padHour(String hhmmss) {
		if (hhmmss == null) return "";
		String[] parts = hhmmss.split(":");
		if (parts.length != 3) return hhmmss;
		if (parts[0].length() == 1) parts[0] = "0" + parts[0];
		return parts[0] + ":" + parts[1] + ":" + parts[2];
	}

	private String[] splitColumns(String line) {
		if (line.contains("\t")) return line.split("\t", -1);
		return line.split("\\s{2,}", -1);
	}

	private String normalize(String s) {
		if (s == null) return "";
		return s.replace("\uFEFF", "")
						 .trim()
						 .replaceAll("\\s+", "")
						 .toLowerCase();
	}

	private Integer firstIndex(Map<String, Integer> idx, List<String> candidates) {
		for (String c : candidates) {
			Integer i = idx.get(normalize(c));
			if (i != null) return i;
		}
		return null;
	}

	private LocalDateTime toLocalDateTimeSafe(Object timeStr) {
		if (timeStr == null) return LocalDateTime.MIN;
		LocalDateTime t = parseKoreanLogDateTime(String.valueOf(timeStr));
		return (t == null) ? LocalDateTime.MIN : t;
	}

	private static String toStr(Object o) { return o == null ? "" : String.valueOf(o); }
	private static Integer toInt(Object o, int def) {
		if (o == null) return def;
		try {
			if (o instanceof Number) return ((Number) o).intValue();
			return Integer.parseInt(String.valueOf(o));
		} catch (Exception e) { return def; }
	}
	private static Double toDouble(Object o) {
		if (o == null) return null;
		try {
			if (o instanceof Number) return ((Number) o).doubleValue();
			String s = String.valueOf(o).trim();
			if (s.isEmpty()) return null;
			s = s.replaceAll(",", "");
			Matcher m = Pattern.compile("(-?\\d+(\\.\\d+)?)").matcher(s);
			if (m.find()) return Double.parseDouble(m.group(1));
			return null;
		} catch (Exception e) { return null; }
	}

	private String resolveUnit(String measureCode) {
		return switch (measureCode) {
			case "PEAK_TEMP" -> "℃";
			case "O2_PPM" -> "ppm";
			case "CONV_SPEED" -> "mm/min";
			default -> "";
		};
	}
}
