package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WBS 계획 생성 / 수주 확정.
 *
 *  wbs_plan 한 테이블에 두 종류가 들어간다.
 *    suju_head_id IS NULL      프로젝트 계획 (템플릿 전개 결과, 가안)
 *    suju_head_id IS NOT NULL  수주 확정 (NQ6 / NQ7 …)
 *
 *  일정 계산 (전부 영업일, 주말·공휴일 제외)
 *    base_type = contract  tb_da003.contdate
 *                start     tb_da003.stdate
 *                end       tb_da003.eddate
 *                prev      직전 세부단계 종료 다음 영업일
 *    시작 = 기준일을 영업일로 보정 후 offset_days 만큼 이동
 *    종료 = 시작 + (duration_days - 1) 영업일
 */
@Slf4j
@Service
public class WbsPlanService {

	@Autowired SqlRunner sqlRunner;
	@Autowired WbsHolidayService holidayService;

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

	/* ================= 조회 ================= */

	/** 프로젝트 계획 (가안) */
	public List<Map<String, Object>> getPlanList(String spjangcd, String projno) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projno", projno);

		return this.sqlRunner.getRows("""
        SELECT p.id, p.step_no, p.step_name, p.seq, p.task_name,
               p.pl_stdate, p.pl_eddate, p.ac_stdate, p.ac_eddate,
               p.progress, p.charge_id, ps."Name" AS charge_name,
               p.milestone_yn, p.remark,
               CASE WHEN p.ac_eddate IS NULL
                     AND p.pl_eddate IS NOT NULL
                     AND p.pl_eddate < to_char(now(), 'YYYYMMDD')
                    THEN '1' ELSE '0' END AS delay_yn
          FROM wbs_plan p
          LEFT JOIN person ps ON ps.id = p.charge_id
         WHERE p.spjangcd = :spjangcd
           AND p.projno = :projno
           AND p.suju_head_id IS NULL
         ORDER BY p.step_no, p.seq
        """, p);
	}

	/** 수주 확정 WBS */
	public List<Map<String, Object>> getFixList(Integer sujuHeadId) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("sujuHeadId", sujuHeadId);

		return this.sqlRunner.getRows("""
        SELECT p.id, p.step_no, p.step_name, p.seq, p.task_name,
               p.suju_name,
               p.pl_stdate, p.pl_eddate, p.ac_stdate, p.ac_eddate,
               p.progress, p.charge_id, ps."Name" AS charge_name,
               p.milestone_yn, p.remark,
               CASE WHEN p.ac_eddate IS NULL
                     AND p.pl_eddate IS NOT NULL
                     AND p.pl_eddate < to_char(now(), 'YYYYMMDD')
                    THEN '1' ELSE '0' END AS delay_yn
          FROM wbs_plan p
          LEFT JOIN person ps ON ps.id = p.charge_id
         WHERE p.suju_head_id = :sujuHeadId
         ORDER BY p.step_no, p.seq
        """, p);
	}

	/* ================= 2단계 : 프로젝트 계획 생성 ================= */

	/**
	 * 템플릿을 프로젝트에 전개한다.
	 *  기존 계획이 있으면 계획일만 다시 계산하고 담당자·진척률·실적일은 보존한다.
	 */
	public Map<String, Object> generatePlan(String spjangcd, String projno, Integer formId, User user) {
		Map<String, Object> r = new HashMap<>();

		if (formId == null) { r.put("success", false); r.put("message", "템플릿이 지정되지 않았습니다."); return r; }

		MapSqlParameterSource pp = new MapSqlParameterSource();
		pp.addValue("spjangcd", spjangcd);
		pp.addValue("projno", projno);
		Map<String, Object> proj = this.sqlRunner.getRow("""
        SELECT projno, contdate, stdate, eddate
          FROM tb_da003 WHERE spjangcd = :spjangcd AND projno = :projno
        """, pp);
		if (proj == null) { r.put("success", false); r.put("message", "프로젝트를 찾을 수 없습니다."); return r; }

		LocalDate contract = parse(str(proj.get("contdate")));
		LocalDate start    = parse(str(proj.get("stdate")));
		LocalDate end      = parse(str(proj.get("eddate")));
		if (contract == null && start == null) {
			r.put("success", false);
			r.put("message", "계약일 또는 공사일자가 있어야 일정을 계산할 수 있습니다.");
			return r;
		}

		List<Map<String, Object>> tasks = this.sqlRunner.getRows("""
        SELECT s.step_no, s.step_name, t.seq, t.task_name,
               t.base_type, t.offset_days, t.duration_days, t.milestone_yn, t.remark
          FROM wbs_form_step s
          JOIN wbs_form_task t ON t.step_id = s.id
         WHERE s.form_id = :formId
         ORDER BY s.step_no, t.seq
        """, new MapSqlParameterSource().addValue("formId", formId));
		if (tasks.isEmpty()) {
			r.put("success", false); r.put("message", "템플릿에 세부단계가 없습니다."); return r;
		}

		// 기존 담당자·진척률·실적 보존용 (step_no|seq -> row)
		Map<String, Map<String, Object>> keep = new HashMap<>();
		for (Map<String, Object> row : getPlanList(spjangcd, projno)) {
			keep.put(row.get("step_no") + "|" + row.get("seq"), row);
		}

		this.sqlRunner.execute("""
        DELETE FROM wbs_plan
         WHERE spjangcd = :spjangcd AND projno = :projno AND suju_head_id IS NULL
        """, pp);

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();
		LocalDate prevEnd = null;
		int saved = 0;

		for (Map<String, Object> t : tasks) {
			int offset   = intOr(t.get("offset_days"), 0);
			int duration = intOr(t.get("duration_days"), 0);

			LocalDate base = resolveBase(str(t.get("base_type")), contract, start, end, prevEnd);
			if (base == null) {
				r.put("success", false);
				r.put("message", "기준일이 비어 있습니다: " + t.get("task_name"));
				return r;
			}

			LocalDate s = holidayService.addBusinessDays(base, offset);
			LocalDate e = (duration <= 1) ? s : holidayService.addBusinessDays(s, duration - 1);
			prevEnd = e;

			Map<String, Object> old = keep.get(t.get("step_no") + "|" + t.get("seq"));

			MapSqlParameterSource ip = new MapSqlParameterSource();
			ip.addValue("spjangcd", spjangcd);
			ip.addValue("projno", projno);
			ip.addValue("stepNo", t.get("step_no"));
			ip.addValue("stepName", t.get("step_name"));
			ip.addValue("seq", t.get("seq"));
			ip.addValue("taskName", t.get("task_name"));
			ip.addValue("plSt", s.format(YMD));
			ip.addValue("plEd", e.format(YMD));
			ip.addValue("acSt", old == null ? null : old.get("ac_stdate"));
			ip.addValue("acEd", old == null ? null : old.get("ac_eddate"));
			ip.addValue("progress", old == null ? 0d : old.get("progress"));
			ip.addValue("chargeId", old == null ? null : old.get("charge_id"));
			ip.addValue("milestoneYn", t.get("milestone_yn"));
			ip.addValue("remark", t.get("remark"));
			ip.addValue("now", now);
			ip.addValue("userId", userId);

			this.sqlRunner.execute("""
          INSERT INTO wbs_plan
                 (spjangcd, projno, suju_head_id, step_no, step_name, seq, task_name,
                  pl_stdate, pl_eddate, ac_stdate, ac_eddate,
                  progress, charge_id, milestone_yn, remark, fix_yn, _created, _creater_id)
          VALUES (:spjangcd, :projno, NULL, :stepNo, :stepName, :seq, :taskName,
                  :plSt, :plEd, :acSt, :acEd,
                  :progress, :chargeId, :milestoneYn, :remark, '0', :now, :userId)
          """, ip);
			saved++;
		}

		// 적용한 템플릿 기록
		MapSqlParameterSource fp = new MapSqlParameterSource();
		fp.addValue("spjangcd", spjangcd);
		fp.addValue("projno", projno);
		fp.addValue("formId", formId);
		this.sqlRunner.execute("""
        UPDATE tb_da003 SET wbs_form_id = :formId
         WHERE spjangcd = :spjangcd AND projno = :projno
        """, fp);

		log.info("[WBS] 계획 생성: {} / {} → {}건", projno, formId, saved);
		r.put("success", true);
		r.put("message", "WBS 계획 " + saved + "건을 생성했습니다.");
		r.put("count", saved);
		return r;
	}

	/* ================= 3단계 : 수주 확정 ================= */

	/**
	 * 수주 단위로 WBS 를 확정한다.
	 *  프로젝트 계획을 복사하고, 납기일이 있으면 종료 기준을 수주 납기에 맞춰 이동한다.
	 */
	public Map<String, Object> confirmForSuju(Integer sujuHeadId, User user) {
		Map<String, Object> r = new HashMap<>();

		MapSqlParameterSource hp = new MapSqlParameterSource().addValue("id", sujuHeadId);
		Map<String, Object> head = this.sqlRunner.getRow("""
        SELECT id, spjangcd, project_id AS projno, suju_name,
               to_char("DeliveryDate", 'YYYYMMDD') AS due_date
          FROM suju_head WHERE id = :id
        """, hp);
		if (head == null) { r.put("success", false); r.put("message", "수주를 찾을 수 없습니다."); return r; }

		String spjangcd = str(head.get("spjangcd"));
		String projno   = str(head.get("projno"));
		String sujuName = str(head.get("suju_name"));
		if (projno == null || projno.isEmpty()) {
			r.put("success", false);
			r.put("message", "이 수주에 프로젝트가 연결되어 있지 않습니다.");
			return r;
		}

		List<Map<String, Object>> plan = getPlanList(spjangcd, projno);
		if (plan.isEmpty()) {
			r.put("success", false);
			r.put("message", "프로젝트 WBS 계획이 없습니다. 프로젝트 등록에서 템플릿을 먼저 적용하세요.");
			return r;
		}

		// 기존 확정분의 담당자·진척률 보존
		Map<String, Map<String, Object>> keep = new HashMap<>();
		for (Map<String, Object> row : getFixList(sujuHeadId)) {
			keep.put(row.get("step_no") + "|" + row.get("seq"), row);
		}

		this.sqlRunner.execute("DELETE FROM wbs_plan WHERE suju_head_id = :id", hp);

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();
		int saved = 0;

		for (Map<String, Object> row : plan) {
			Map<String, Object> old = keep.get(row.get("step_no") + "|" + row.get("seq"));

			MapSqlParameterSource ip = new MapSqlParameterSource();
			ip.addValue("spjangcd", spjangcd);
			ip.addValue("projno", projno);
			ip.addValue("sujuHeadId", sujuHeadId);
			ip.addValue("sujuName", sujuName);
			ip.addValue("stepNo", row.get("step_no"));
			ip.addValue("stepName", row.get("step_name"));
			ip.addValue("seq", row.get("seq"));
			ip.addValue("taskName", row.get("task_name"));
			ip.addValue("plSt", row.get("pl_stdate"));
			ip.addValue("plEd", row.get("pl_eddate"));
			ip.addValue("acSt", old == null ? null : old.get("ac_stdate"));
			ip.addValue("acEd", old == null ? null : old.get("ac_eddate"));
			ip.addValue("progress", old == null ? 0d : old.get("progress"));
			ip.addValue("chargeId", old == null ? null : old.get("charge_id"));
			ip.addValue("milestoneYn", row.get("milestone_yn"));
			ip.addValue("remark", row.get("remark"));
			ip.addValue("now", now);
			ip.addValue("userId", userId);

			this.sqlRunner.execute("""
          INSERT INTO wbs_plan
                 (spjangcd, projno, suju_head_id, suju_name, step_no, step_name, seq, task_name,
                  pl_stdate, pl_eddate, ac_stdate, ac_eddate,
                  progress, charge_id, milestone_yn, remark, fix_yn, _created, _creater_id)
          VALUES (:spjangcd, :projno, :sujuHeadId, :sujuName, :stepNo, :stepName, :seq, :taskName,
                  :plSt, :plEd, :acSt, :acEd,
                  :progress, :chargeId, :milestoneYn, :remark, '1', :now, :userId)
          """, ip);
			saved++;
		}

		log.info("[WBS] 수주 확정: head={} ({}) → {}건", sujuHeadId, sujuName, saved);
		r.put("success", true);
		r.put("message", (sujuName == null ? "" : sujuName + " ") + "WBS " + saved + "건을 확정했습니다.");
		r.put("count", saved);
		return r;
	}

	/** 확정 행의 담당자 / 진척률 / 실적일 저장 */
	public Map<String, Object> saveRows(List<Map<String, Object>> rows, User user) {
		Map<String, Object> r = new HashMap<>();
		if (rows == null || rows.isEmpty()) {
			r.put("success", false); r.put("message", "저장할 내용이 없습니다."); return r;
		}

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();
		int cnt = 0;

		for (Map<String, Object> row : rows) {
			Integer id = intOrNull(row.get("id"));
			if (id == null) continue;

			MapSqlParameterSource p = new MapSqlParameterSource();
			p.addValue("id", id);
			p.addValue("acSt", ymd(str(row.get("ac_stdate"))));
			p.addValue("acEd", ymd(str(row.get("ac_eddate"))));
			p.addValue("progress", row.get("progress") == null ? 0d : Double.valueOf(String.valueOf(row.get("progress"))));
			p.addValue("chargeId", intOrNull(row.get("charge_id")));
			p.addValue("remark", str(row.get("remark")));
			p.addValue("now", now);
			p.addValue("userId", userId);

			this.sqlRunner.execute("""
          UPDATE wbs_plan
             SET ac_stdate = :acSt, ac_eddate = :acEd,
                 progress = :progress, charge_id = :chargeId, remark = :remark,
                 _modified = :now, _modifier_id = :userId
           WHERE id = :id
          """, p);
			cnt++;
		}

		r.put("success", true);
		r.put("message", cnt + "건을 저장했습니다.");
		return r;
	}

	/* ================= 헬퍼 ================= */

	private LocalDate resolveBase(String baseType, LocalDate contract, LocalDate start,
																LocalDate end, LocalDate prevEnd) {
		if (baseType == null) baseType = "prev";
		switch (baseType) {
			case "contract": return contract;
			case "start":    return start;
			case "end":      return end;
			case "prev":
			default:
				if (prevEnd == null) return (contract != null) ? contract : start;   // 첫 항목
				return holidayService.nextBusinessDay(prevEnd);
		}
	}

	private static LocalDate parse(String yyyymmdd) {
		if (yyyymmdd == null) return null;
		String s = yyyymmdd.replace("-", "").trim();
		if (s.length() != 8) return null;
		try { return LocalDate.parse(s, YMD); } catch (Exception e) { return null; }
	}

	/** 화면에서 yyyy-MM-dd 로 올 수 있으므로 정규화 */
	private static String ymd(String v) {
		if (v == null) return null;
		String s = v.replace("-", "").trim();
		return s.isEmpty() ? null : s;
	}

	private static String str(Object o) {
		return (o == null) ? null : String.valueOf(o).trim();
	}

	private static int intOr(Object o, int def) {
		Integer v = intOrNull(o);
		return (v == null) ? def : v;
	}

	private static Integer intOrNull(Object o) {
		if (o == null) return null;
		String s = String.valueOf(o).trim();
		if (s.isEmpty()) return null;
		try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
	}
}