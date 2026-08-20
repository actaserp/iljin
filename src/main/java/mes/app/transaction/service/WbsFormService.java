package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WBS 표준 템플릿 (개정 관리)
 *
 *  wbs_form            템플릿 마스터. (form_code, revision) 단위
 *    └ wbs_form_step   대단계   (설계 / 발주 / 가공 …)
 *        └ wbs_form_task 세부단계 + 일정 규칙
 *
 *  상태 흐름 : draft --[적용]--> active --[개정]--> (새 draft)
 *   - active 는 읽기전용. 수정하려면 개정해서 revision+1 draft 를 만든다
 *   - 같은 form_code 는 active 가 하나만 존재한다 (적용 시 이전 active 는 expired)
 *   - 이미 프로젝트에 사용된 템플릿(tb_da003.wbs_form_id)은 삭제할 수 없다
 *
 *  ※ 저장은 delete-all + insert-all. ProjectRegistrationController.saveStages 와 동일한 방식.
 */
@Slf4j
@Service
public class WbsFormService {

	@Autowired
	SqlRunner sqlRunner;

	/* ================= 조회 ================= */

	/** 템플릿 목록 (사용 건수 포함) */
	public List<Map<String, Object>> getFormList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		String sql = """
        SELECT f.id,
               f.form_code,
               f.form_name,
               f.revision,
               f.state,
               f.remark,
               COALESCE(u.used_count, 0) AS used_count
          FROM wbs_form f
          LEFT JOIN (
               SELECT wbs_form_id, COUNT(*) AS used_count
                 FROM tb_da003
                WHERE wbs_form_id IS NOT NULL
                GROUP BY wbs_form_id
          ) u ON u.wbs_form_id = f.id
         WHERE f.spjangcd = :spjangcd
           AND COALESCE(f._status, 'a') <> 'd'
         ORDER BY f.form_code ASC, f.revision DESC
        """;
		return this.sqlRunner.getRows(sql, p);
	}

	/** 적용중(active) 템플릿만 — 프로젝트 등록 화면 콤보용 */
	public List<Map<String, Object>> getActiveFormList(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		String sql = """
        SELECT id, form_code, form_name, revision,
               (form_name || ' (Rev.' || revision || ')') AS label
          FROM wbs_form
         WHERE spjangcd = :spjangcd
           AND state = 'active'
           AND COALESCE(_status, 'a') <> 'd'
         ORDER BY form_code ASC
        """;
		return this.sqlRunner.getRows(sql, p);
	}

	/** 템플릿 상세 = 마스터 + 대단계 + 세부단계 (중첩) */
	public Map<String, Object> getFormDetail(Integer formId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("formId", formId);

		Map<String, Object> form = this.sqlRunner.getRow("""
        SELECT f.id, f.form_code, f.form_name, f.revision, f.state, f.remark,
               COALESCE(u.used_count, 0) AS used_count
          FROM wbs_form f
          LEFT JOIN (
               SELECT wbs_form_id, COUNT(*) AS used_count
                 FROM tb_da003 WHERE wbs_form_id IS NOT NULL
                GROUP BY wbs_form_id
          ) u ON u.wbs_form_id = f.id
         WHERE f.id = :formId
        """, p);
		if (form == null) return null;

		List<Map<String, Object>> steps = this.sqlRunner.getRows("""
        SELECT id, step_no, step_name, remark
          FROM wbs_form_step
         WHERE form_id = :formId
         ORDER BY step_no ASC
        """, p);

		List<Map<String, Object>> tasks = this.sqlRunner.getRows("""
        SELECT t.id, t.step_id, t.seq, t.task_name,
               t.base_type, t.offset_days, t.duration_days,
               t.workcenter_id, w."Name" AS workcenter_name,
               t.milestone_yn, t.remark
          FROM wbs_form_task t
          LEFT JOIN work_center w ON w.id = t.workcenter_id
         WHERE t.step_id IN (SELECT id FROM wbs_form_step WHERE form_id = :formId)
         ORDER BY t.step_id ASC, t.seq ASC
        """, p);

		// 대단계에 세부단계를 매달아 준다
		for (Map<String, Object> st : steps) {
			Object stepId = st.get("id");
			List<Map<String, Object>> mine = new ArrayList<>();
			for (Map<String, Object> t : tasks) {
				if (stepId != null && stepId.equals(t.get("step_id"))) mine.add(t);
			}
			st.put("tasks", mine);
		}

		form.put("steps", steps);
		return form;
	}

	/* ================= 저장 ================= */

	/**
	 * 초안 저장. draft 가 아니면 거부한다.
	 * 단계/세부단계는 전체 삭제 후 재삽입 (화면 순서대로 step_no / seq 재부여).
	 */
	public Map<String, Object> saveForm(Map<String, Object> payload, String spjangcd, User user) {
		Map<String, Object> r = new HashMap<>();

		Integer formId = asInt(payload.get("id"));
		String formCode = asStr(payload.get("form_code"));
		String formName = asStr(payload.get("form_name"));

		if (isEmpty(formCode)) { r.put("success", false); r.put("message", "템플릿 코드를 입력하세요."); return r; }
		if (isEmpty(formName)) { r.put("success", false); r.put("message", "템플릿명을 입력하세요."); return r; }

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();

		if (formId == null) {
			// 신규 = Rev.1 draft
			if (existsFormCode(formCode, spjangcd)) {
				r.put("success", false);
				r.put("message", "이미 있는 템플릿 코드입니다: " + formCode);
				return r;
			}
			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("formCode", formCode);
			p.addValue("formName", formName);
			p.addValue("remark", asStr(payload.get("remark")));
			p.addValue("spjangcd", spjangcd);
			p.addValue("now", now);
			p.addValue("userId", userId);

			formId = this.sqlRunner.queryForObject("""
          INSERT INTO wbs_form
                 (form_code, form_name, revision, state, remark, spjangcd,
                  _status, _created, _creater_id)
          VALUES (:formCode, :formName, 1, 'draft', :remark, :spjangcd,
                  'a', :now, :userId)
          RETURNING id
          """, p, (rs, n) -> rs.getInt(1));
		} else {
			String state = getState(formId);
			if (!"draft".equals(state)) {
				r.put("success", false);
				r.put("message", "적용중인 템플릿은 수정할 수 없습니다. [개정] 후 편집하세요.");
				return r;
			}
			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("formId", formId);
			p.addValue("formName", formName);
			p.addValue("remark", asStr(payload.get("remark")));
			p.addValue("now", now);
			p.addValue("userId", userId);
			this.sqlRunner.execute("""
          UPDATE wbs_form
             SET form_name = :formName, remark = :remark,
                 _modified = :now, _modifier_id = :userId
           WHERE id = :formId
          """, p);
		}

		saveSteps(formId, payload.get("steps"));

		r.put("success", true);
		r.put("message", "초안을 저장했습니다.");
		r.put("id", formId);
		return r;
	}

	/** 대단계 + 세부단계 전체 교체 */
	@SuppressWarnings("unchecked")
	private void saveSteps(Integer formId, Object stepsObj) {
		MapSqlParameterSource del = new MapSqlParameterSource().addValue("formId", formId);
		this.sqlRunner.execute("""
        DELETE FROM wbs_form_task
         WHERE step_id IN (SELECT id FROM wbs_form_step WHERE form_id = :formId)
        """, del);
		this.sqlRunner.execute("DELETE FROM wbs_form_step WHERE form_id = :formId", del);

		if (!(stepsObj instanceof List)) return;
		List<Map<String, Object>> steps = (List<Map<String, Object>>) stepsObj;

		int stepNo = 1;
		for (Map<String, Object> st : steps) {
			String stepName = asStr(st.get("step_name"));
			if (isEmpty(stepName)) continue;   // 이름 없는 대단계는 저장하지 않음

			MapSqlParameterSource sp = new MapSqlParameterSource();
			sp.addValue("formId", formId);
			sp.addValue("stepNo", stepNo);
			sp.addValue("stepName", stepName);
			sp.addValue("remark", asStr(st.get("remark")));

			Integer stepId = this.sqlRunner.queryForObject("""
          INSERT INTO wbs_form_step (form_id, step_no, step_name, remark)
          VALUES (:formId, :stepNo, :stepName, :remark)
          RETURNING id
          """, sp, (rs, n) -> rs.getInt(1));

			Object tasksObj = st.get("tasks");
			if (tasksObj instanceof List) {
				int seq = 1;
				for (Map<String, Object> t : (List<Map<String, Object>>) tasksObj) {
					String taskName = asStr(t.get("task_name"));
					if (isEmpty(taskName)) continue;   // 이름 없는 세부단계는 저장하지 않음

					MapSqlParameterSource tp = new MapSqlParameterSource();
					tp.addValue("stepId", stepId);
					tp.addValue("seq", seq);
					tp.addValue("taskName", taskName);
					tp.addValue("baseType", normalizeBaseType(asStr(t.get("base_type"))));
					tp.addValue("offsetDays", asIntOr(t.get("offset_days"), 0));
					tp.addValue("durationDays", asIntOr(t.get("duration_days"), 0));
					tp.addValue("workcenterId", asInt(t.get("workcenter_id")));
					tp.addValue("milestoneYn", "1".equals(asStr(t.get("milestone_yn"))) ? "1" : "0");
					tp.addValue("remark", asStr(t.get("remark")));

					this.sqlRunner.execute("""
              INSERT INTO wbs_form_task
                     (step_id, seq, task_name, base_type, offset_days, duration_days,
                      workcenter_id, milestone_yn, remark)
              VALUES (:stepId, :seq, :taskName, :baseType, :offsetDays, :durationDays,
                      :workcenterId, :milestoneYn, :remark)
              """, tp);
					seq++;
				}
			}
			stepNo++;
		}
	}

	/* ================= 개정 / 적용 / 삭제 ================= */

	/** 개정 = active 를 복사해 revision+1 draft 생성 */
	public Map<String, Object> reviseForm(Integer baseId, String spjangcd, User user) {
		Map<String, Object> r = new HashMap<>();

		Map<String, Object> base = getFormDetail(baseId);
		if (base == null) { r.put("success", false); r.put("message", "템플릿을 찾을 수 없습니다."); return r; }
		if (!"active".equals(base.get("state"))) {
			r.put("success", false);
			r.put("message", "적용중인 템플릿만 개정할 수 있습니다.");
			return r;
		}

		String formCode = asStr(base.get("form_code"));

		// 같은 코드에 편집중인 초안이 이미 있으면 중복 생성 금지
		MapSqlParameterSource dp = new MapSqlParameterSource();
		dp.addValue("formCode", formCode);
		dp.addValue("spjangcd", spjangcd);
		Integer draftCnt = this.sqlRunner.queryForObject("""
        SELECT COUNT(*) FROM wbs_form
         WHERE form_code = :formCode AND spjangcd = :spjangcd AND state = 'draft'
        """, dp, (rs, n) -> rs.getInt(1));
		if (draftCnt != null && draftCnt > 0) {
			r.put("success", false);
			r.put("message", "이미 편집중인 초안이 있습니다. 그것을 사용하세요.");
			return r;
		}

		Integer nextRev = this.sqlRunner.queryForObject("""
        SELECT COALESCE(MAX(revision), 0) + 1 FROM wbs_form
         WHERE form_code = :formCode AND spjangcd = :spjangcd
        """, dp, (rs, n) -> rs.getInt(1));

		Timestamp now = new Timestamp(System.currentTimeMillis());
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("formCode", formCode);
		p.addValue("formName", asStr(base.get("form_name")));
		p.addValue("revision", nextRev);
		p.addValue("remark", asStr(base.get("remark")));
		p.addValue("spjangcd", spjangcd);
		p.addValue("now", now);
		p.addValue("userId", (user == null) ? null : user.getId());

		Integer newId = this.sqlRunner.queryForObject("""
        INSERT INTO wbs_form
               (form_code, form_name, revision, state, remark, spjangcd,
                _status, _created, _creater_id)
        VALUES (:formCode, :formName, :revision, 'draft', :remark, :spjangcd,
                'a', :now, :userId)
        RETURNING id
        """, p, (rs, n) -> rs.getInt(1));

		// 단계 구조 복사
		saveSteps(newId, base.get("steps"));

		r.put("success", true);
		r.put("message", "Rev." + nextRev + " 초안을 만들었습니다.");
		r.put("id", newId);
		return r;
	}

	/** 적용 = draft → active. 같은 코드의 기존 active 는 expired 로 내린다 */
	public Map<String, Object> applyForm(Integer formId, String spjangcd, User user) {
		Map<String, Object> r = new HashMap<>();

		Map<String, Object> form = this.sqlRunner.getRow(
			"SELECT id, form_code, revision, state FROM wbs_form WHERE id = :id",
			new MapSqlParameterSource().addValue("id", formId));
		if (form == null) { r.put("success", false); r.put("message", "템플릿을 찾을 수 없습니다."); return r; }
		if (!"draft".equals(form.get("state"))) {
			r.put("success", false); r.put("message", "초안만 적용할 수 있습니다."); return r;
		}

		Integer taskCnt = this.sqlRunner.queryForObject("""
        SELECT COUNT(*) FROM wbs_form_task
         WHERE step_id IN (SELECT id FROM wbs_form_step WHERE form_id = :id)
        """, new MapSqlParameterSource().addValue("id", formId), (rs, n) -> rs.getInt(1));
		if (taskCnt == null || taskCnt == 0) {
			r.put("success", false); r.put("message", "세부단계가 하나도 없습니다."); return r;
		}

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();

		MapSqlParameterSource ex = new MapSqlParameterSource();
		ex.addValue("formCode", asStr(form.get("form_code")));
		ex.addValue("spjangcd", spjangcd);
		ex.addValue("id", formId);
		ex.addValue("now", now);
		ex.addValue("userId", userId);

		this.sqlRunner.execute("""
        UPDATE wbs_form
           SET state = 'expired', _modified = :now, _modifier_id = :userId
         WHERE form_code = :formCode AND spjangcd = :spjangcd
           AND state = 'active' AND id <> :id
        """, ex);

		this.sqlRunner.execute("""
        UPDATE wbs_form
           SET state = 'active', _modified = :now, _modifier_id = :userId
         WHERE id = :id
        """, ex);

		r.put("success", true);
		r.put("message", "Rev." + form.get("revision") + " 을 적용했습니다.");
		return r;
	}

	/** 초안 삭제. 사용된 템플릿은 삭제 불가 */
	public Map<String, Object> deleteForm(Integer formId) {
		Map<String, Object> r = new HashMap<>();

		String state = getState(formId);
		if (state == null) { r.put("success", false); r.put("message", "템플릿을 찾을 수 없습니다."); return r; }
		if (!"draft".equals(state)) {
			r.put("success", false); r.put("message", "초안만 삭제할 수 있습니다."); return r;
		}

		MapSqlParameterSource p = new MapSqlParameterSource().addValue("formId", formId);
		Integer used = this.sqlRunner.queryForObject(
			"SELECT COUNT(*) FROM tb_da003 WHERE wbs_form_id = :formId", p, (rs, n) -> rs.getInt(1));
		if (used != null && used > 0) {
			r.put("success", false);
			r.put("message", "프로젝트 " + used + "건에서 사용중이라 삭제할 수 없습니다.");
			return r;
		}

		this.sqlRunner.execute("""
        DELETE FROM wbs_form_task
         WHERE step_id IN (SELECT id FROM wbs_form_step WHERE form_id = :formId)
        """, p);
		this.sqlRunner.execute("DELETE FROM wbs_form_step WHERE form_id = :formId", p);
		this.sqlRunner.execute("DELETE FROM wbs_form WHERE id = :formId", p);

		r.put("success", true);
		r.put("message", "삭제했습니다.");
		return r;
	}

	/* ================= 헬퍼 ================= */

	private String getState(Integer formId) {
		try {
			return this.sqlRunner.queryForObject(
				"SELECT state FROM wbs_form WHERE id = :id",
				new MapSqlParameterSource().addValue("id", formId), (rs, n) -> rs.getString(1));
		} catch (Exception e) {
			return null;
		}
	}

	private boolean existsFormCode(String formCode, String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("formCode", formCode);
		p.addValue("spjangcd", spjangcd);
		Integer c = this.sqlRunner.queryForObject("""
        SELECT COUNT(*) FROM wbs_form WHERE form_code = :formCode AND spjangcd = :spjangcd
        """, p, (rs, n) -> rs.getInt(1));
		return c != null && c > 0;
	}

	/** 허용된 기준만 통과. 이상한 값이 들어오면 이전단계 기준으로 떨어뜨린다 */
	private String normalizeBaseType(String v) {
		if (v == null) return "prev";
		switch (v) {
			case "contract": case "start": case "end": case "prev": return v;
			default: return "prev";
		}
	}

	private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

	private static String asStr(Object o) {
		if (o == null) return null;
		String s = String.valueOf(o).trim();
		return s.isEmpty() ? null : s;
	}

	private static Integer asInt(Object o) {
		if (o == null) return null;
		String s = String.valueOf(o).trim();
		if (s.isEmpty()) return null;
		try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
	}

	private static Integer asIntOr(Object o, int def) {
		Integer v = asInt(o);
		return (v == null) ? def : v;
	}
}