package mes.app.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3차원 측정 성적서 — 측정값 적재 · 공차 · 자동 합불 판정.
 *
 * [기존 구조와의 관계]
 *   검사 실적은 그대로 mat_produce(ProcessOrder=3) 가 갖는다.
 *   이 서비스는 그 <b>1건에 딸린 측정 상세</b>만 다룬다.
 *   ProdInspectService 를 건드리지 않으므로 성적서를 안 쓰는 검사(간이 등록,
 *   bulk_save)는 지금까지와 똑같이 동작한다.
 *
 * [★ 성적서 1부 = 검사 1건]
 *   "2대" 는 좌우 한 쌍(LH/RH)을 뜻하고, 그 좌우는 성적서 안에 컬럼으로 들어 있다.
 *   즉 1부가 쌍을 다 담으므로 호기를 따로 적어 나눌 일이 없다.
 *   같은 형상을 여러 대 만드는 경우가 확인되면 그때 insp_report.unit_no 를 되살린다
 *   (컬럼은 nullable 로 남겨 뒀다).
 *
 * [판정]
 *   dev = 실측 - DATUM,  tol_lower <= dev <= tol_upper 이면 합격.
 *   한 점이라도 fail 이면 성적서 전체가 fail 이다.
 *
 *   ★ 자동판정은 <b>기본값일 뿐</b>이고 검사자가 뒤집을 수 있다(final_result).
 *     사업계획서의 '반자동' 이 이 뜻이다. 실물을 보고 판단할 여지를 남긴다.
 *     자동판정과 최종판정이 어긋나는 건이 쌓이면 그게 공차를 다시 볼 신호다.
 *
 *   경도·CLAMP GAP 은 판정하지 않는다. 양식이 '40~45' 같은 범위 문자열이라
 *   숫자 비교가 성립하지 않는다. 값만 보여주고 사람이 본다.
 *
 * [경로] ProdInspectMeasureController — /api/production/prod_inspect_measure
 *   기존 ProdInspectController(/api/production/prod_inspect)와 겹치지 않게 분리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProdInspectMeasureService {

	private final SqlRunner sqlRunner;

	// =================================================================
	// 공차
	// =================================================================

	/**
	 * 축별 공차를 뽑는다.  좁은 범위가 이긴다: 품목+축 > 품목 > 전역+축 > 전역
	 * (SPEC 5-1 의 유형 별칭 우선순위와 같은 방식)
	 *
	 * 못 찾으면 그 축은 판정하지 않는다(judge = NULL). 임의 기본값을 코드에 박지 않는다 —
	 * 기준이 없는데 합격을 찍으면 그 순간부터 판정을 믿을 수 없게 된다.
	 */
	public Map<String, double[]> getToleranceMap(String spjangcd, Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("sujuId", sujuId);

		List<Map<String, Object>> rows = sqlRunner.getRows("""
            SELECT axis, tol_lower, tol_upper
                 , CASE WHEN scope = 'SUJU' AND axis IS NOT NULL THEN 0
                        WHEN scope = 'SUJU'                      THEN 1
                        WHEN axis IS NOT NULL                    THEN 2
                        ELSE 3 END AS pri
            FROM insp_tolerance
            WHERE spjangcd = :spjangcd
              AND COALESCE(_status, 'a') = 'a'
              AND (scope = 'GLOBAL'
                   OR (scope = 'SUJU' AND "Suju_id" = CAST(:sujuId AS integer)))
            ORDER BY pri DESC
            """, p);

		// pri 내림차순으로 넣으므로 뒤에 오는(더 좁은) 규칙이 덮어쓴다
		Map<String, double[]> map = new HashMap<>();
		for (Map<String, Object> r : rows) {
			double lo = toDouble(r.get("tol_lower"));
			double hi = toDouble(r.get("tol_upper"));
			String axis = str(r.get("axis"));
			if (axis.isEmpty()) {
				for (String a : new String[]{"T", "L", "H"}) map.put(a, new double[]{lo, hi});
			} else {
				map.put(axis, new double[]{lo, hi});
			}
		}
		return map;
	}

	public List<Map<String, Object>> getToleranceList(String spjangcd, Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("sujuId", sujuId);

		return sqlRunner.getRows("""
            SELECT t.id, t.scope, t."Suju_id" AS suju_id, t.axis
                 , t.tol_lower, t.tol_upper, t.remark
                 , s."Material_Name" AS item_name
            FROM insp_tolerance t
            LEFT JOIN suju s ON s.id = t."Suju_id"
            WHERE t.spjangcd = :spjangcd
              AND COALESCE(t._status, 'a') = 'a'
              AND (CAST(:sujuId AS integer) IS NULL
                   OR t.scope = 'GLOBAL'
                   OR t."Suju_id" = CAST(:sujuId AS integer))
            ORDER BY CASE WHEN t.scope = 'SUJU' THEN 0 ELSE 1 END, t.axis NULLS FIRST
            """, p);
	}

	@Transactional
	public void saveTolerance(Map<String, Object> payload, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", toInt(payload.get("id")));
		p.addValue("spjangcd", str(payload.get("spjangcd")));
		p.addValue("scope", str(payload.get("scope")).isEmpty() ? "GLOBAL" : str(payload.get("scope")));
		p.addValue("sujuId", toInt(payload.get("sujuId")));
		p.addValue("axis", nullIfEmpty(payload.get("axis")));
		p.addValue("lower", toDouble(payload.get("tolLower")));
		p.addValue("upper", toDouble(payload.get("tolUpper")));
		p.addValue("remark", nullIfEmpty(payload.get("remark")));
		p.addValue("userId", user.getId());

		if (toInt(payload.get("id")) == null) {
			sqlRunner.execute("""
                INSERT INTO insp_tolerance (
                     spjangcd, scope, "Suju_id", axis, tol_lower, tol_upper, remark
                   , _status, _created, _creater_id
                ) VALUES (
                     :spjangcd, :scope, :sujuId, :axis, :lower, :upper, :remark
                   , 'a', now(), :userId
                )
                """, p);
		} else {
			sqlRunner.execute("""
                UPDATE insp_tolerance
                   SET axis = :axis, tol_lower = :lower, tol_upper = :upper
                     , remark = :remark, _modified = now(), _modifier_id = :userId
                 WHERE id = :id
                """, p);
		}
	}

	@Transactional
	public void deleteTolerance(Integer id, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", id);
		p.addValue("userId", user.getId());
		// 지우지 않고 내린다. 지난 판정이 어느 기준을 썼는지는 insp_measure 에 남아 있다
		sqlRunner.execute("""
            UPDATE insp_tolerance
               SET _status = 'd', _modified = now(), _modifier_id = :userId
             WHERE id = :id
            """, p);
	}

	// =================================================================
	// 판정
	// =================================================================

	/**
	 * 파싱 결과에 공차를 적용해 판정을 매긴다. 저장하지 않는다 —
	 * 화면 미리보기와 실제 저장이 같은 함수를 쓰게 하려는 것이다.
	 * 두 곳에 따로 두면 "미리보기는 합격인데 저장하니 불합격" 이 난다.
	 */
	public Map<String, Object> judge(CmmReportParser.Result parsed, Map<String, double[]> tol) {

		List<Map<String, Object>> rows = new ArrayList<>();
		int mfrNg = 0, custNg = 0, mfrJudged = 0, custJudged = 0;
		int noTol = 0;

		/*
		 * ★ 발주처 칸이 절대 좌표인지 편차인지 판별한다.
		 *
		 *   샘플 성적서를 보면 발주처 칸 값이 0.03 / -0.01 / 0.05 처럼 0 근처다.
		 *   설계값이 -33.41 인 자리에 0.04 가 들어 있으니 이건 좌표가 아니라
		 *   <b>이미 계산된 편차</b>다. 이걸 좌표로 보고 한 번 더 빼면
		 *   편차가 33.45 로 나와 전 항목이 이탈로 찍힌다 (실제로 그랬다).
		 *
		 *   다만 샘플 한 부로 단정할 수 없어 값의 크기로 판별하고 결과를 기록해 둔다.
		 *   현장에 "발주처 칸에 실측을 적나 편차를 적나" 가 확인되면
		 *   이 판별을 지우고 한쪽으로 고정하면 된다.
		 */
		boolean custIsDev = looksLikeDeviation(parsed);

		for (CmmReportParser.Point pt : parsed.points) {
			double[] t = tol.get(pt.axis);

			Map<String, Object> m = new HashMap<>();
			m.put("seq", pt.seq);
			m.put("point_no", pt.pointNo);
			m.put("axis", pt.axis);
			m.put("datum", pt.datum);
			m.put("tol_lower", t == null ? null : t[0]);
			m.put("tol_upper", t == null ? null : t[1]);

			// 제작사
			Double mDevLh = dev(pt.mfrLh, pt.datum), mDevRh = dev(pt.mfrRh, pt.datum);
			String mJLh = verdict(mDevLh, t), mJRh = verdict(mDevRh, t);
			m.put("mfr_lh", pt.mfrLh);   m.put("mfr_rh", pt.mfrRh);
			m.put("mfr_dev_lh", mDevLh); m.put("mfr_dev_rh", mDevRh);
			m.put("mfr_judge_lh", mJLh); m.put("mfr_judge_rh", mJRh);

			// 발주처. 편차가 적혀 있으면 그 값이 곧 편차다 — 다시 빼지 않는다
			Double cDevLh = custIsDev ? pt.custLh : dev(pt.custLh, pt.datum);
			Double cDevRh = custIsDev ? pt.custRh : dev(pt.custRh, pt.datum);
			String cJLh = verdict(cDevLh, t), cJRh = verdict(cDevRh, t);
			// 편차만 적힌 경우 실측 좌표는 존재하지 않으므로 비워 둔다.
			// 편차를 실측 칸에 넣으면 화면에서 좌표로 오독된다
			m.put("cust_lh", custIsDev ? null : pt.custLh);
			m.put("cust_rh", custIsDev ? null : pt.custRh);
			m.put("cust_dev_lh", cDevLh);  m.put("cust_dev_rh", cDevRh);
			m.put("cust_judge_lh", cJLh);  m.put("cust_judge_rh", cJRh);

			if (mJLh != null) mfrJudged++;
			if (mJRh != null) mfrJudged++;
			if (cJLh != null) custJudged++;
			if (cJRh != null) custJudged++;
			if ("fail".equals(mJLh)) mfrNg++;
			if ("fail".equals(mJRh)) mfrNg++;
			if ("fail".equals(cJLh)) custNg++;
			if ("fail".equals(cJRh)) custNg++;

			if (t == null && (pt.hasMfr() || pt.hasCust())) noTol++;

			rows.add(m);
		}

		int judged = mfrJudged + custJudged;
		int ng = mfrNg + custNg;

		Map<String, Object> out = new HashMap<>();
		out.put("rows", rows);
		out.put("point_cnt", parsed.points.size());
		out.put("judged_cnt", judged);
		out.put("mfr_judged_cnt", mfrJudged);
		out.put("cust_judged_cnt", custJudged);
		out.put("mfr_ng_cnt", mfrNg);
		out.put("cust_ng_cnt", custNg);
		out.put("ng_cnt", ng);
		out.put("customer_name", parsed.customerName);
		out.put("cust_value_mode", custIsDev ? "DEV" : "ABS");
		out.put("no_tolerance_cnt", noTol);

		/*
		 * 전체 판정.
		 *
		 * ★ 어느 쪽을 기준으로 삼을지는 <b>아직 정해지지 않았다.</b>
		 *   샘플 한 부만으로 "발주처 칸만 쓴다" 고 단정할 수 없어서,
		 *   지금은 <b>값이 있는 쪽을 모두 본다</b> — 한쪽만 차 있으면 그쪽,
		 *   둘 다 차 있으면 둘 다 합격이어야 합격이다.
		 *
		 *   나중에 "발주처만으로 판정한다" 같은 규칙이 정해지면 이 한 곳만 고치면 된다.
		 *   측정값은 양쪽 다 저장돼 있으므로 과거 데이터도 다시 판정할 수 있다.
		 *
		 *   판정한 항목이 하나도 없으면 pass 를 찍지 않는다.
		 *   공차가 없거나 실측이 비었을 때 "전부 합격" 으로 보이는 게 가장 위험하다.
		 */
		out.put("auto_result", judged == 0 ? null : (ng > 0 ? "fail" : "pass"));

		if (custIsDev && custJudged > 0) {
			parsed.warnings.add((parsed.customerName == null ? "발주처" : parsed.customerName)
					+ " 칸의 값이 설계값 대비 <b>편차</b>로 보여 그대로 편차로 씁니다. "
					+ "실측 좌표를 적는 양식이면 알려 주세요.");
		}
		if (judged == 0 && !parsed.points.isEmpty() && noTol > 0) {
			parsed.warnings.add("공차 기준이 없어 자동판정을 하지 못했습니다. 공차 설정을 확인하세요.");
		}
		out.put("warnings", parsed.warnings);
		return out;
	}

	/**
	 * 발주처 칸이 편차인지 본다.
	 *
	 * 설계값과 나란히 놓았을 때 값이 한 자릿수 이상 작으면 좌표가 아니라 편차다.
	 * (설계값 -33.41 자리에 0.04 가 들어 있는 식)
	 * 설계값이 0 근처인 항목은 판단 근거가 못 되므로 세지 않는다.
	 */
	private boolean looksLikeDeviation(CmmReportParser.Result parsed) {
		int small = 0, total = 0;
		for (CmmReportParser.Point p : parsed.points) {
			if (p.datum == null || Math.abs(p.datum) < 1) continue;
			for (Double v : new Double[]{p.custLh, p.custRh}) {
				if (v == null) continue;
				total++;
				if (Math.abs(v) < Math.abs(p.datum) / 10) small++;
			}
		}
		// 근거가 너무 적으면 손대지 않는다. 기본은 좌표로 본다
		return total >= 5 && small > total * 0.8;
	}

	private Double dev(Double val, Double datum) {
		if (val == null || datum == null) return null;
		return round4(val - datum);
	}

	private String verdict(Double dev, double[] tol) {
		if (dev == null || tol == null) return null;   // 미측정 또는 기준 없음 → 판정 안 함
		return (dev >= tol[0] && dev <= tol[1]) ? "pass" : "fail";
	}

	private Double round4(double d) {
		return Math.round(d * 10000d) / 10000d;
	}

	// =================================================================
	// 적재
	// =================================================================

	/**
	 * 성적서를 검사 1건(mat_produce)에 붙인다.
	 *
	 * 재파싱은 <b>덮어쓰기</b>다. 같은 검사 건에 성적서를 다시 올리면
	 * 이전 측정값을 지우고 새로 넣는다. 잘못 올린 파일이 반쪽 남는 것보다 낫다.
	 * (재측정은 성적서 교체가 아니라 <b>검사 건을 새로 등록</b>하는 것이다.
	 *  그래야 이력이 남고 부적합 조치 흐름과 이어진다)
	 *
	 * @return insp_report.id
	 */
	@Transactional
	public Integer saveReport(Integer matProduceId, Integer sujuId, String spjangcd,
							  Integer attachId, String fileName,
							  CmmReportParser.Result parsed, Map<String, Object> judged,
							  User user) {

		// 덮어쓰기. insp_measure 는 ON DELETE CASCADE 로 따라 지워진다
		MapSqlParameterSource d = new MapSqlParameterSource();
		d.addValue("mpId", matProduceId);
		sqlRunner.execute("DELETE FROM insp_report WHERE \"MatProduce_id\" = :mpId", d);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("mpId", matProduceId);
		p.addValue("sujuId", sujuId);
		p.addValue("customerName", parsed.customerName);
		p.addValue("custValueMode", judged.get("cust_value_mode"));
		p.addValue("model", parsed.model);
		p.addValue("mcsNo", parsed.mcsNo);
		p.addValue("drawNo", parsed.drawNo);
		p.addValue("drawName", parsed.drawName);
		p.addValue("hardnessSpec", parsed.hardnessSpec);
		p.addValue("locLh", parsed.locHrcLh);
		p.addValue("locRh", parsed.locHrcRh);
		p.addValue("pinLh", parsed.pinHrcLh);
		p.addValue("pinRh", parsed.pinHrcRh);
		p.addValue("gapLh", parsed.clampGapLh);
		p.addValue("gapRh", parsed.clampGapRh);
		p.addValue("attachId", attachId);
		p.addValue("fileName", fileName);
		p.addValue("pointCnt", judged.get("point_cnt"));
		p.addValue("ngCnt", judged.get("ng_cnt"));
		p.addValue("mfrNgCnt", judged.get("mfr_ng_cnt"));
		p.addValue("custNgCnt", judged.get("cust_ng_cnt"));
		p.addValue("autoResult", judged.get("auto_result"));
		p.addValue("userId", user.getId());

		Integer reportId = sqlRunner.queryForObject("""
            INSERT INTO insp_report (
                 spjangcd, "MatProduce_id", "Suju_id", customer_name, cust_value_mode
               , model, mcs_no, draw_no, draw_name
               , hardness_spec, loc_hrc_lh, loc_hrc_rh, pin_hrc_lh, pin_hrc_rh
               , clamp_gap_lh, clamp_gap_rh
               , "AttachFile_id", file_name, point_cnt
               , mfr_ng_cnt, cust_ng_cnt, ng_cnt, auto_result
               , _status, _created, _creater_id
            ) VALUES (
                 :spjangcd, :mpId, :sujuId, :customerName, :custValueMode
               , :model, :mcsNo, :drawNo, :drawName
               , :hardnessSpec, :locLh, :locRh, :pinLh, :pinRh
               , :gapLh, :gapRh
               , :attachId, :fileName, :pointCnt
               , :mfrNgCnt, :custNgCnt, :ngCnt, :autoResult
               , 'a', now(), :userId
            )
            RETURNING id
            """, p, (rs, rowNum) -> rs.getInt("id"));

		if (reportId == null) return null;

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) judged.get("rows");
		for (Map<String, Object> m : rows) {
			MapSqlParameterSource q = new MapSqlParameterSource();
			q.addValue("reportId", reportId);
			for (String k : new String[]{
					"seq", "point_no", "axis", "datum",
					"mfr_lh", "mfr_rh", "mfr_dev_lh", "mfr_dev_rh", "mfr_judge_lh", "mfr_judge_rh",
					"cust_lh", "cust_rh", "cust_dev_lh", "cust_dev_rh", "cust_judge_lh", "cust_judge_rh",
					"tol_lower", "tol_upper"}) {
				q.addValue(k, m.get(k));
			}

			sqlRunner.execute("""
                INSERT INTO insp_measure (
                     "Report_id", seq, point_no, axis, datum
                   , mfr_lh, mfr_rh, mfr_dev_lh, mfr_dev_rh, mfr_judge_lh, mfr_judge_rh
                   , cust_lh, cust_rh, cust_dev_lh, cust_dev_rh, cust_judge_lh, cust_judge_rh
                   , tol_lower, tol_upper
                ) VALUES (
                     :reportId, :seq, :point_no, :axis, :datum
                   , :mfr_lh, :mfr_rh, :mfr_dev_lh, :mfr_dev_rh, :mfr_judge_lh, :mfr_judge_rh
                   , :cust_lh, :cust_rh, :cust_dev_lh, :cust_dev_rh, :cust_judge_lh, :cust_judge_rh
                   , :tol_lower, :tol_upper
                )
                """, q);
		}
		return reportId;
	}

	/**
	 * 자동판정을 검사 실적의 판정으로 반영한다.
	 *
	 * ★ 검사자가 이미 손으로 판정을 정한 뒤에 성적서를 올린 경우가 있으므로
	 *   mat_produce."InspectResult" 가 비어 있을 때만 채운다.
	 *   사람이 정한 값을 시스템이 덮지 않는다.
	 */
	@Transactional
	public void applyAutoResult(Integer matProduceId, String autoResult, User user) {
		if (autoResult == null) return;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mpId", matProduceId);
		p.addValue("result", autoResult);
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            UPDATE mat_produce
               SET "InspectResult" = :result
                 , _modified = now(), _modifier_id = :userId
             WHERE id = :mpId
               AND ("InspectResult" IS NULL OR "InspectResult" = '')
            """, p);
	}

	/** 검사자가 자동판정을 뒤집는다. 이유를 함께 남긴다 */
	@Transactional
	public void overrideResult(Integer reportId, String finalResult, String reason, User user) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("id", reportId);
		p.addValue("result", nullIfEmpty(finalResult));
		p.addValue("reason", nullIfEmpty(reason));
		p.addValue("userId", user.getId());

		sqlRunner.execute("""
            UPDATE insp_report
               SET final_result = :result, override_reason = :reason
                 , _modified = now(), _modifier_id = :userId
             WHERE id = :id
            """, p);

		sqlRunner.execute("""
            UPDATE mat_produce mp
               SET "InspectResult" = :result
                 , _modified = now(), _modifier_id = :userId
              FROM insp_report r
             WHERE r.id = :id AND mp.id = r."MatProduce_id"
            """, p);
	}

	// =================================================================
	// 조회
	// =================================================================

	/** 검사 1건의 성적서 헤더 */
	public Map<String, Object> getReport(Integer matProduceId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mpId", matProduceId);

		return sqlRunner.getRow("""
            SELECT r.*
                 , COALESCE(r.final_result, r.auto_result) AS result
                 , TO_CHAR(r._created, 'YYYY-MM-DD HH24:MI') AS created_at
            FROM insp_report r
            WHERE r."MatProduce_id" = :mpId
            """, p);
	}

	/**
	 * 측정값 목록.
	 * @param ngOnly true 면 불합격만. 64점 × 3축 = 192행이라 전부 훑기 어렵다
	 */
	public List<Map<String, Object>> getMeasures(Integer reportId, boolean ngOnly) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("reportId", reportId);

		String ngFilter = ngOnly
				? " AND (m.mfr_judge_lh = 'fail' OR m.mfr_judge_rh = 'fail'"
				+ "   OR m.cust_judge_lh = 'fail' OR m.cust_judge_rh = 'fail') " : "";

		return sqlRunner.getRows("""
            SELECT m.seq, m.point_no, m.axis, m.datum
                 , m.mfr_lh, m.mfr_rh, m.mfr_dev_lh, m.mfr_dev_rh
                 , m.mfr_judge_lh, m.mfr_judge_rh
                 , m.cust_lh, m.cust_rh, m.cust_dev_lh, m.cust_dev_rh
                 , m.cust_judge_lh, m.cust_judge_rh
                 , m.tol_lower, m.tol_upper
            FROM insp_measure m
            WHERE m."Report_id" = :reportId
            """ + ngFilter + """
            ORDER BY m.seq
            """, p);
	}

	/** 품목의 성적서 이력. 호기별·차수별로 쌓인다 */
	public List<Map<String, Object>> getReportsBySuju(Integer sujuId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("sujuId", sujuId);

		return sqlRunner.getRows("""
            SELECT r.id, r."MatProduce_id" AS mat_produce_id
                 , r.customer_name, r.cust_value_mode
                 , r.point_cnt, r.mfr_ng_cnt, r.cust_ng_cnt, r.ng_cnt
                 , r.auto_result, r.final_result
                 , COALESCE(r.final_result, r.auto_result) AS result
                 , r.override_reason
                 , r."AttachFile_id" AS attach_file_id
                 , r.file_name
                 , TO_CHAR(mp."ProductionDate", 'YYYY-MM-DD') AS inspect_date
                 , pw."Name" AS worker
            FROM insp_report r
            LEFT JOIN mat_produce mp ON mp.id = r."MatProduce_id"
            LEFT JOIN person pw      ON pw.id = mp."Actor_id"
            WHERE r."Suju_id" = :sujuId
            ORDER BY r.id DESC
            """, p);
	}

	/** 검사 실적 id → 품목. 저장 시 sujuId 를 화면 값이 아니라 서버에서 다시 찾는다 */
	public Map<String, Object> getContext(Integer matProduceId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mpId", matProduceId);

		return sqlRunner.getRow("""
            SELECT mp.id           AS mat_produce_id
                 , mp.spjangcd     AS spjangcd
                 , j."SourceDataPk" AS suju_id
                 , s."Material_Name" AS item_name
                 , COALESCE(s."SujuQty", 0) AS jig_qty
            FROM mat_produce mp
            JOIN job_res j ON j.id = mp."JobResponse_id"
                          AND j."SourceTableName" = 'suju'
            LEFT JOIN suju s ON s.id = j."SourceDataPk"
            WHERE mp.id = :mpId
            """, p);
	}

	// =================================================================
	// helper
	// =================================================================
	private String str(Object o) { return o == null ? "" : o.toString().trim(); }

	private String nullIfEmpty(Object o) {
		String s = str(o);
		return s.isEmpty() ? null : s;
	}

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