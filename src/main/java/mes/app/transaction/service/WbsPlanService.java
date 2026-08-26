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
import java.util.ArrayList;
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

	/**
	 * 재직자 판정 조건. (sys_code.rtflag_type 확인 완료)
	 *  <pre>
	 *    0 = 재직   1 = 퇴직   2 = 휴직
	 *  </pre>
	 *
	 *  ★ 0 이 재직이다. 값이 뒤집혀 있으니 주의할 것.
	 *  퇴직(1)만 제외한다. 휴직(2)은 복귀하므로 목록에 남긴다 —
	 *  빼면 복귀 후 지정된 담당이 화면에서 사라져 보인다.
	 *  rtflag 가 비어 있는 인원(구 데이터)도 남긴다. 퇴직으로 단정할 근거가 없다.
	 */
	private static final String PERSON_ACTIVE_COND =
		"COALESCE(ps.rtflag, '0') <> '1'";

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
               p.milestone_yn, p.remark, p.auto_source, p.workcenter_id,
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
               p.milestone_yn, p.remark, p.auto_source, p.workcenter_id,
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
               t.base_type, t.offset_days, t.duration_days, t.milestone_yn, t.remark,
               t.auto_source, t.charge_id, t.workcenter_id
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

		// ★ 재생성 전에 진행단계의 연결을 끊어둔다.
		//   아래 DELETE + INSERT 로 wbs_plan.id 가 새로 채번되므로,
		//   tb_da003_stage.wbs_plan_id 가 없는 행을 가리키게 된다.
		//   (실제로 45~55 를 가리킨 채 wbs_plan 은 56~66 이 되어 완료 반영이 통째로 실패했다)
		//   step_no|seq 로 다시 이어붙일 수 있게 매핑을 남긴다.
		this.sqlRunner.execute("""
        UPDATE tb_da003_stage s
           SET wbs_relink = w.step_no || '|' || w.seq
          FROM wbs_plan w
         WHERE s.wbs_plan_id = w.id
           AND s.spjangcd = :spjangcd AND s.projno = :projno
        """, pp);

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
			// 기존에 지정한 담당이 있으면 유지, 없으면 템플릿의 기본 담당을 쓴다.
			Object keepCharge = (old == null) ? null : old.get("charge_id");
			ip.addValue("chargeId", keepCharge != null ? keepCharge : t.get("charge_id"));
			ip.addValue("milestoneYn", t.get("milestone_yn"));
			ip.addValue("remark", t.get("remark"));
			ip.addValue("autoSource", t.get("auto_source"));
			ip.addValue("workcenterId", t.get("workcenter_id"));
			ip.addValue("now", now);
			ip.addValue("userId", userId);

			this.sqlRunner.execute("""
          INSERT INTO wbs_plan
                 (spjangcd, projno, suju_head_id, step_no, step_name, seq, task_name,
                  pl_stdate, pl_eddate, ac_stdate, ac_eddate,
                  progress, charge_id, milestone_yn, remark, auto_source, workcenter_id,
                  fix_yn, _created, _creater_id)
          VALUES (:spjangcd, :projno, NULL, :stepNo, :stepName, :seq, :taskName,
                  :plSt, :plEd, :acSt, :acEd,
                  :progress, :chargeId, :milestoneYn, :remark, CAST(:autoSource AS varchar),
                  :workcenterId, '0', :now, :userId)
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

		// ★ 새로 채번된 id 로 진행단계 연결을 복구한다. step_no|seq 가 같으면 같은 단계다.
		//   템플릿에서 빠진 단계는 연결이 풀린 채(NULL) 진행단계에만 남는다.
		this.sqlRunner.execute("""
        UPDATE tb_da003_stage s
           SET wbs_plan_id = w.id, wbs_relink = NULL
          FROM wbs_plan w
         WHERE w.spjangcd = s.spjangcd AND w.projno = s.projno
           AND w.suju_head_id IS NULL
           AND s.wbs_relink = w.step_no || '|' || w.seq
           AND s.spjangcd = :spjangcd AND s.projno = :projno
        """, pp);

		// 이어붙이지 못한 것(템플릿에서 사라진 단계)은 연결만 끊는다. 행은 남긴다.
		this.sqlRunner.execute("""
        UPDATE tb_da003_stage
           SET wbs_plan_id = NULL, wbs_relink = NULL
         WHERE spjangcd = :spjangcd AND projno = :projno
           AND wbs_relink IS NOT NULL
        """, pp);

		// WBS 세부단계 중 진행단계에 없는 것만 추가한다 (기존 행은 건드리지 않는다).
		syncStagesFromWbs(spjangcd, projno);

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
			ip.addValue("autoSource", row.get("auto_source"));
			ip.addValue("workcenterId", row.get("workcenter_id"));
			ip.addValue("now", now);
			ip.addValue("userId", userId);

			this.sqlRunner.execute("""
          INSERT INTO wbs_plan
                 (spjangcd, projno, suju_head_id, suju_name, step_no, step_name, seq, task_name,
                  pl_stdate, pl_eddate, ac_stdate, ac_eddate,
                  progress, charge_id, milestone_yn, remark, auto_source, workcenter_id,
                  fix_yn, _created, _creater_id)
          VALUES (:spjangcd, :projno, :sujuHeadId, :sujuName, :stepNo, :stepName, :seq, :taskName,
                  :plSt, :plEd, :acSt, :acEd,
                  :progress, :chargeId, :milestoneYn, :remark, CAST(:autoSource AS varchar),
                  :workcenterId, '1', :now, :userId)
          """, ip);
			saved++;
		}

		// 확정분이 생기면 진행단계 집계 대상이 프로젝트 계획 → 수주 확정으로 바뀐다.
		syncStagesFromWbs(spjangcd, projno);

		log.info("[WBS] 수주 확정: head={} ({}) → {}건", sujuHeadId, sujuName, saved);
		r.put("success", true);
		r.put("message", (sujuName == null ? "" : sujuName + " ") + "WBS " + saved + "건을 확정했습니다.");
		r.put("count", saved);
		return r;
	}

	/* ================= 5단계 : 진행단계 <-> WBS 연결 ================= */

	/**
	 * WBS 세부단계 중 <b>진행단계에 아직 없는 것만 추가</b>한다.
	 *
	 * <p>★ 지우지 않는다. 현장이 진행단계를 자유롭게 추가·수정하므로
	 * delete-all 로 다시 만들면 손으로 넣은 행이 사라진다.
	 * (실제로 `2차검도` 처럼 템플릿에 없는 단계를 현장이 쓰고 있다)
	 *
	 * <p>연결은 이름이 아니라 {@code tb_da003_stage.wbs_plan_id} 로 건다.
	 * 이름으로 맞추면 `설계` vs `3D 설계`, `1차 검도` vs `1차검도` 처럼
	 * 표기가 갈리는 순간 조용히 어긋난다.
	 *
	 * @return 새로 추가한 진행단계 수
	 */
	public int syncStagesFromWbs(String spjangcd, String projno) {
		if (spjangcd == null || projno == null || projno.isEmpty()) return 0;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projno", projno);

		// 아직 진행단계에 연결되지 않은 WBS 세부단계 (프로젝트 계획 = 가안 기준)
		List<Map<String, Object>> missing = this.sqlRunner.getRows("""
        SELECT w.id, w.step_no, w.seq, w.step_name, w.task_name,
               w.pl_eddate, w.ac_eddate, w.charge_id, w.remark
          FROM wbs_plan w
         WHERE w.spjangcd = :spjangcd AND w.projno = :projno
           AND w.suju_head_id IS NULL
           AND NOT EXISTS (
             SELECT 1 FROM tb_da003_stage s
              WHERE s.spjangcd = w.spjangcd AND s.projno = w.projno
                AND s.wbs_plan_id = w.id)
         ORDER BY w.step_no, w.seq
        """, p);
		if (missing.isEmpty()) return 0;

		// 이어붙일 시작 순번
		Integer maxSeq = this.sqlRunner.queryForObject("""
        SELECT COALESCE(MAX(seq), 0) FROM tb_da003_stage
         WHERE spjangcd = :spjangcd AND projno = :projno
        """, p, (rs, n) -> rs.getInt(1));
		int seq = (maxSeq == null ? 0 : maxSeq) + 1;

		String today = LocalDate.now().format(YMD);

		for (Map<String, Object> w : missing) {
			String acEd = str(w.get("ac_eddate"));

			MapSqlParameterSource ip = new MapSqlParameterSource();
			ip.addValue("spjangcd", spjangcd);
			ip.addValue("projno", projno);
			ip.addValue("seq", seq);
			ip.addValue("stagenm", w.get("task_name"));
			ip.addValue("pldate", w.get("pl_eddate"));
			ip.addValue("cpdate", acEd);
			ip.addValue("endflag", (acEd != null && !acEd.isEmpty()) ? "1" : "0");
			ip.addValue("indate", today);
			ip.addValue("wbsPlanId", w.get("id"));
			ip.addValue("chargeId", w.get("charge_id"));
			ip.addValue("remark", w.get("remark"));

			this.sqlRunner.execute("""
          INSERT INTO tb_da003_stage
                 (spjangcd, projno, seq, stagenm, pldate, cpdate, endflag, indate,
                  wbs_plan_id, charge_id, remark)
          VALUES (:spjangcd, :projno, :seq, CAST(:stagenm AS varchar),
                  CAST(:pldate AS varchar), CAST(:cpdate AS varchar),
                  CAST(:endflag AS varchar), CAST(:indate AS varchar),
                  :wbsPlanId, :chargeId, CAST(:remark AS varchar))
          """, ip);
			seq++;
		}

		log.info("[WBS] 진행단계 추가: {} → {}건", projno, missing.size());
		return missing.size();
	}

	/**
	 * 진행단계의 완료 상태를 연결된 WBS 세부단계에 반영한다. <b>양방향의 반대편.</b>
	 *
	 * <p>진행단계에서 완료를 누르면 {@code wbs_plan.ac_eddate} 가 채워지고,
	 * 완료를 해제하면 다시 비워진다.
	 * {@code delay_yn} 판정이 {@code ac_eddate IS NULL} 을 보므로
	 * 완료 즉시 지연 목록에서 빠지고, 해제하면 다시 들어온다.
	 *
	 * <p>{@code wbs_plan_id} 가 없는 진행단계 행(현장이 임의로 넣은 것)은 건드리지 않는다.
	 *
	 * <p>{@code auto_source} 가 있는 행(2D 출도)도 여기서 고칠 수 있다.
	 * 다만 <b>수주 도면출도일이 바뀌면 자동값이 다시 이긴다</b>
	 * ({@link #syncDrawDate} 가 덮어쓴다). 손으로 고친 값은 그때까지만 유효하다.
	 * 두 값이 영구히 갈라지지 않게 하려는 것이고, 어느 쪽이 원본인지도 분명해진다.
	 *
	 * @return 실제로 바뀐 WBS 행 수
	 */
	public int applyStageCompletion(String spjangcd, String projno, User user) {
		if (spjangcd == null || projno == null || projno.isEmpty()) return 0;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projno", projno);
		p.addValue("now", new Timestamp(System.currentTimeMillis()));
		p.addValue("userId", user == null ? null : user.getId());

		int n = this.sqlRunner.execute("""
        WITH src AS (
          -- 한 WBS 단계에 진행단계가 둘 이상 연결되면 어느 값을 쓸지 불확정이 된다.
          --  화면이 중복 연결을 막지만 서버에서도 한 번 접는다.
          --  완료된 것이 하나라도 있으면 완료로 보고, 그중 가장 늦은 날을 쓴다.
          SELECT wbs_plan_id,
                 MAX(NULLIF(COALESCE(cpdate, ''), '')) AS cpdate,
                 MAX(charge_id)                        AS charge_id,
                 MAX(NULLIF(COALESCE(remark, ''), '')) AS remark
            FROM tb_da003_stage
           WHERE spjangcd = :spjangcd AND projno = :projno
             AND wbs_plan_id IS NOT NULL
           GROUP BY wbs_plan_id
        )
        UPDATE wbs_plan w
           SET ac_eddate = src.cpdate,
               -- 담당·비고는 진행단계에서 지웠다고 WBS 것까지 지우지는 않는다.
               --  두 화면에서 같은 값을 다루므로, 빈 값은 "입력 안 함" 으로 본다.
               charge_id = COALESCE(src.charge_id, w.charge_id),
               remark    = COALESCE(src.remark, w.remark),
               _modified = :now, _modifier_id = :userId
          FROM src
         WHERE src.wbs_plan_id = w.id
           AND (w.ac_eddate IS DISTINCT FROM src.cpdate
             OR w.charge_id IS DISTINCT FROM COALESCE(src.charge_id, w.charge_id)
             OR w.remark    IS DISTINCT FROM COALESCE(src.remark, w.remark))
        """, p);

		if (n > 0) log.info("[WBS] 진행단계 완료 반영: {} → {}건", projno, n);
		return n;
	}

	/**
	 * 담당자 드롭다운용 인원 목록.
	 *
	 * <p>SPEC 5-2 의 규칙을 그대로 따른다.
	 * <ul>
	 *   <li>{@code WorkCenter_id} 로 담당을 가른다 (부서는 조직도라 총무·영업까지 섞인다)</li>
	 *   <li>해당 워크센터 인원을 위로 올리되 <b>막지는 않는다</b> — 교대·대체 인력이 있다</li>
	 *   <li>퇴직자는 제외</li>
	 * </ul>
	 *
	 * <p>★ {@link #PERSON_ACTIVE_COND} 확인 필요.
	 * {@code person.rtflag} 의 재직/퇴직 코드값을 확인하지 못했다.
	 * 아래로 확인하고 맞춰 둘 것 — 틀리면 퇴직자가 목록에 남거나 전원이 사라진다.
	 * <pre>
	 *   SELECT rtflag, count(*) FROM person GROUP BY 1;
	 * </pre>
	 *
	 * @param workcenterId 이 워크센터 인원을 위로 올린다. null 이면 이름순.
	 */
	public List<Map<String, Object>> getPersonOptions(Integer workcenterId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("wcId", workcenterId);
		return this.sqlRunner.getRows("""
        SELECT ps.id, ps."Name" AS name,
               ps."WorkCenter_id" AS workcenter_id,
               w."Name" AS workcenter_name,
               -- ★ CAST 필수. NULL 만 들어오면 PostgreSQL 이 타입을 추론하지 못해
               --   "매개 변수의 자료형을 알 수 없습니다" 로 실패한다 (SPEC 6장).
               CASE WHEN CAST(:wcId AS integer) IS NOT NULL
                     AND ps."WorkCenter_id" = CAST(:wcId AS integer)
                    THEN 1 ELSE 0 END AS preferred
          FROM person ps
          LEFT JOIN work_center w ON w.id = ps."WorkCenter_id"
         WHERE %s
         ORDER BY preferred DESC, ps."Name"
        """.formatted(PERSON_ACTIVE_COND), p);
	}

	/**

	 * 진행단계 화면의 WBS 연결 드롭다운용 목록.
	 *  대단계까지 붙여 보여준다 (예: "설계 &gt; 2D 출도").
	 */
	public List<Map<String, Object>> getStageOptions(String spjangcd, String projno) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("projno", projno);

		return this.sqlRunner.getRows("""
        SELECT id, step_no, seq, step_name, task_name,
               step_name || ' > ' || task_name AS label,
               milestone_yn, auto_source, workcenter_id
          FROM wbs_plan
         WHERE spjangcd = :spjangcd AND projno = :projno
           AND suju_head_id IS NULL
         ORDER BY step_no, seq
        """, p);
	}

	/* ================= 4단계 : 수주 데이터에서 자동 채움 ================= */

	/**
	 * 수주의 도면출도일을 WBS 의 2D 출도 단계에 반영한다.
	 *
	 * <p>대상은 {@code auto_source = 'draw_date'} 로 표시된 행뿐이다.
	 * task_name 문자열로 찾지 않는다 — 현장이 단계명을 바꾸는 순간 조용히 멈추기 때문이다.
	 *
	 * <p><b>완료일 = 그 수주 품목들의 draw_date 중 가장 늦은 날.</b>
	 *
	 * <p>★ 알려진 한계 (현장 결정)
	 * {@code MAX()} 는 NULL 을 무시하므로, 79행 중 40행만 도면이 나왔어도
	 * 그 40행의 최댓값이 들어가고 해당 단계는 <b>완료로 표시된다.</b>
	 * ({@code delay_yn} 판정이 {@code ac_eddate IS NULL} 만 보기 때문)
	 * 전 행이 출도됐을 때만 완료로 보고 싶어지면 아래 {@code filled == total}
	 * 조건을 되살리면 된다. 지금 구조가 그것을 막지 않는다.
	 *
	 * <p>수주 저장 직후에 부른다. WBS 를 아직 확정하지 않은 수주면 대상 행이 없어
	 * 아무 일도 하지 않는다 (오류가 아니다).
	 *
	 * @return 갱신한 행 수
	 */
	public int syncDrawDate(Integer sujuHeadId, User user) {
		if (sujuHeadId == null) return 0;

		MapSqlParameterSource hp = new MapSqlParameterSource().addValue("headId", sujuHeadId);
		Map<String, Object> head = this.sqlRunner.getRow("""
        SELECT spjangcd, project_id AS projno FROM suju_head WHERE id = :headId
        """, hp);
		if (head == null) return 0;

		String spjangcd = str(head.get("spjangcd"));
		String projno   = str(head.get("projno"));

		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();
		int n = 0;

		// ── 1) 수주 확정 행 : 그 수주의 도면출도일 MAX ──
		Map<String, Object> one = this.sqlRunner.getRow("""
        SELECT to_char(MAX(s.draw_date), 'YYYYMMDD') AS last_draw,
               count(*)           AS total,
               count(s.draw_date) AS filled
          FROM suju s
         WHERE s."SujuHead_id" = :headId
        """, hp);

		if (one != null) {
			MapSqlParameterSource up = new MapSqlParameterSource();
			up.addValue("headId", sujuHeadId);
			up.addValue("acEd", str(one.get("last_draw")));
			up.addValue("now", now);
			up.addValue("userId", userId);

			n += this.sqlRunner.execute("""
          UPDATE wbs_plan
             SET ac_eddate = CAST(:acEd AS varchar),
                 _modified = :now, _modifier_id = :userId
           WHERE suju_head_id = :headId
             AND auto_source = 'draw_date'
             AND ac_eddate IS DISTINCT FROM CAST(:acEd AS varchar)
          """, up);

			log.info("[WBS] 도면출도일(수주) head={} 최종={} ({}/{}행 출도)",
				sujuHeadId, one.get("last_draw"), one.get("filled"), one.get("total"));
		}

		// ── 2) 프로젝트 계획 행 ──
		n += syncDrawDateForProject(spjangcd, projno, user);
		return n;
	}

	/**
	 * 프로젝트 계획 행의 2D 출도를 채운다.
	 *  그 프로젝트에 물린 <b>모든 수주</b>의 도면출도일 MAX 를 쓴다.
	 *
	 * <p>진행단계는 프로젝트 단위라 여기를 채우지 않으면 2D 출도가 영원히 빈칸으로 남는다.
	 * 진행단계를 고치는 곳도 프로젝트 등록 화면이므로,
	 * <b>수주 저장뿐 아니라 프로젝트 저장 때도 불러야 한다.</b>
	 * (수주 저장 때만 돌게 두면 프로젝트 화면에서 저장해도 값이 안 바뀐다)
	 *
	 * <p>★ MAX 는 NULL 을 무시한다. 79행 중 68행만 출도돼도 완료로 표시된다 (현장 결정).
	 */
	public int syncDrawDateForProject(String spjangcd, String projno, User user) {
		int n = 0;
		Timestamp now = new Timestamp(System.currentTimeMillis());
		Integer userId = (user == null) ? null : user.getId();

		if (projno != null && !projno.isEmpty()) {
			MapSqlParameterSource pp = new MapSqlParameterSource();
			pp.addValue("spjangcd", spjangcd);
			pp.addValue("projno", projno);
			pp.addValue("now", now);
			pp.addValue("userId", userId);

			Map<String, Object> all = this.sqlRunner.getRow("""
          SELECT to_char(MAX(s.draw_date), 'YYYYMMDD') AS last_draw,
                 count(*)           AS total,
                 count(s.draw_date) AS filled
            FROM suju s
            JOIN suju_head h ON h.id = s."SujuHead_id"
           WHERE h.project_id = CAST(:projno AS varchar)
          """, pp);

			String projDraw = (all == null) ? null : str(all.get("last_draw"));
			pp.addValue("acEd", projDraw);

			n += this.sqlRunner.execute("""
          UPDATE wbs_plan
             SET ac_eddate = CAST(:acEd AS varchar),
                 _modified = :now, _modifier_id = :userId
           WHERE spjangcd = :spjangcd AND projno = CAST(:projno AS varchar)
             AND suju_head_id IS NULL
             AND auto_source = 'draw_date'
             AND ac_eddate IS DISTINCT FROM CAST(:acEd AS varchar)
          """, pp);

			// 진행단계에도 같은 값을 내린다. 화면에서 완료 버튼이 잠겨 있으므로
			//  여기서 안 맞추면 사용자는 값을 고칠 방법이 없다.
			this.sqlRunner.execute("""
          UPDATE tb_da003_stage s
             SET cpdate  = w.ac_eddate,
                 endflag = CASE WHEN COALESCE(w.ac_eddate,'') = '' THEN '0' ELSE '1' END
            FROM wbs_plan w
           WHERE s.wbs_plan_id = w.id
             AND w.auto_source = 'draw_date'
             AND s.spjangcd = :spjangcd AND s.projno = CAST(:projno AS varchar)
             AND s.cpdate IS DISTINCT FROM w.ac_eddate
          """, pp);

			if (all != null) {
				log.info("[WBS] 도면출도일(프로젝트) {} 최종={} ({}/{}행 출도)",
					projno, projDraw, all.get("filled"), all.get("total"));
			}
		}

		return n;
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
		List<Integer> touched = new ArrayList<>();

		for (Map<String, Object> row : rows) {
			Integer id = intOrNull(row.get("id"));
			if (id == null) continue;
			touched.add(id);

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

		// 실적일이 바뀌면 마일스톤 완료 여부가 달라지므로 진행단계를 다시 만든다.
		//  rows 에는 id 만 오므로 어느 프로젝트인지 되짚어야 한다.
		if (touched.isEmpty()) { r.put("success", true); r.put("message", "0건을 저장했습니다."); return r; }

		// WBS 쪽에서 실적일을 고쳤으면 연결된 진행단계도 같은 값으로 맞춘다.
		//  (진행단계 → WBS 는 applyStageCompletion 이 담당한다. 여기는 그 반대 방향)
		this.sqlRunner.execute("""
        UPDATE tb_da003_stage s
           SET cpdate    = w.ac_eddate,
               endflag   = CASE WHEN COALESCE(w.ac_eddate,'') = '' THEN '0' ELSE '1' END,
               charge_id = w.charge_id,
               remark    = w.remark
          FROM wbs_plan w
         WHERE s.wbs_plan_id = w.id
           AND w.id IN (:ids)
           AND (s.cpdate IS DISTINCT FROM w.ac_eddate
             OR s.charge_id IS DISTINCT FROM w.charge_id
             OR s.remark IS DISTINCT FROM w.remark)
        """, new MapSqlParameterSource().addValue("ids", touched));

		for (Map<String, Object> pk : this.sqlRunner.getRows("""
        SELECT DISTINCT spjangcd, projno FROM wbs_plan WHERE id IN (:ids)
        """, new MapSqlParameterSource().addValue("ids", touched))) {
			syncStagesFromWbs(str(pk.get("spjangcd")), str(pk.get("projno")));
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