package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.EquRun;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.EquRunRepository;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 설비 비가동 — 공정 키오스크에서 [비가동] 으로 시작하고 [재가동] 으로 끝낸다.
 *
 * <p>저장은 기존 표준 테이블 그대로: equ_run (RunState='X', StopCause_id) + stop_cause(비가동 유형 마스터).
 *   그래서 기존 '설비 비가동 이력' · '설비 가동 차트' 화면에 바로 나온다.
 *   나중에 PLC 가 가동/정지를 자동으로 넣어도 같은 테이블이라 섞여서 보인다.
 *
 * <p>비가동 중 = equ_run 에 RunState='X' 이고 EndDate 가 비어 있는 행. 설비당 하나만 연다.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/equ_stop")
@RequiredArgsConstructor
public class EquStopController {

	private final SqlRunner sqlRunner;
	private final EquRunRepository equRunRepository;

	/** 비가동 유형 (기준정보 > 비가동 유형). 계획 비가동이 아래로 */
	@GetMapping("/causes")
	public AjaxResult causes() {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = sqlRunner.getRows("""
            SELECT sc.id, sc."StopCauseName" AS name, sc."StopCauseCode" AS code,
                   COALESCE(sc."PlanYN", 'N') AS plan_yn
            FROM stop_cause sc
            ORDER BY COALESCE(sc."PlanYN", 'N'), sc."StopCauseCode", sc.id
            """, new MapSqlParameterSource());
		return r;
	}

	/** 그 설비의 지금 비가동 (없으면 data=null) */
	@GetMapping("/current")
	public AjaxResult current(@RequestParam("equipment") String equipCode,
							  @RequestParam(value = "spjangcd", required = false) String spjangcd) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = openStop(equipCode, spjangcd);
		return r;
	}

	/** 비가동 시작. body: {equipment(설비 코드), stopCauseId, description, worker, spjangcd} */
	@PostMapping("/start")
	@Transactional
	public AjaxResult start(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		User user = (User) auth.getPrincipal();
		String spjangcd = str(body.get("spjangcd"));
		Integer equipId = equipId(str(body.get("equipment")), spjangcd);
		Integer causeId = toInt(body.get("stopCauseId"));
		if (equipId == null) { r.success = false; r.message = "설비를 찾을 수 없습니다."; return r; }
		if (causeId == null) { r.success = false; r.message = "비가동 사유를 고르세요."; return r; }
		if (openStop(str(body.get("equipment")), spjangcd) != null) {
			r.success = false; r.message = "이미 비가동 중입니다. [재가동] 을 먼저 누르세요."; return r;
		}

		String worker = str(body.get("worker"));
		String memo = str(body.get("description"));
		EquRun er = new EquRun();
		er.setRunState("X");
		er.setEquipmentId(equipId);
		er.setStopCauseId(causeId);
		er.setStartDate(new Timestamp(System.currentTimeMillis()));
		er.setEndDate(null);
		// 누가 세웠는지 — 키오스크는 공용 계정이라 고른 작업자 이름을 남긴다
		er.setDescription((worker.isEmpty() ? "" : "[" + worker + "] ") + memo);
		if (!spjangcd.isEmpty()) er.setSpjangcd(spjangcd);
		er.set_audit(user);
		equRunRepository.save(er);

		r.success = true;
		r.data = openStop(str(body.get("equipment")), spjangcd);
		return r;
	}

	/** 재가동 = 열린 비가동을 지금 시각으로 닫는다. body: {id} */
	@PostMapping("/end")
	@Transactional
	public AjaxResult end(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		User user = (User) auth.getPrincipal();
		Integer id = toInt(body.get("id"));
		EquRun er = id == null ? null : equRunRepository.getEquRunById(id);
		if (er == null || !"X".equals(er.getRunState()) || er.getEndDate() != null) {
			r.success = false; r.message = "열린 비가동이 없습니다. 새로고침 해 주세요."; return r;
		}
		er.setEndDate(new Timestamp(System.currentTimeMillis()));
		er.set_audit(user);
		equRunRepository.save(er);
		r.success = true;
		return r;
	}

	// ─────────────────────────────────────────────

	private Map<String, Object> openStop(String equipCode, String spjangcd) {
		Integer equipId = equipId(equipCode, spjangcd);
		if (equipId == null) return null;
		List<Map<String, Object>> rows = sqlRunner.getRows("""
            SELECT er.id, er."StopCause_id" AS stop_cause_id, sc."StopCauseName" AS cause,
                   er."Description" AS description,
                   TO_CHAR(er."StartDate", 'YYYY-MM-DD HH24:MI') AS started,
                   er."StartDate" AS start_ts
            FROM equ_run er
            LEFT JOIN stop_cause sc ON sc.id = er."StopCause_id"
            WHERE er."Equipment_id" = :equipId AND er."RunState" = 'X' AND er."EndDate" IS NULL
            ORDER BY er."StartDate" DESC
            LIMIT 1
            """, new MapSqlParameterSource().addValue("equipId", equipId));
		if (rows == null || rows.isEmpty()) return null;
		// 경과 분은 Java 시각으로 낸다. StartDate 는 JVM 시각(KST)으로 저장되는데
		// DB now() 는 세션 시간대(UTC)라 DB 에서 빼면 9시간 어긋난다
		Map<String, Object> row = rows.get(0);
		Object ts = row.remove("start_ts");
		long min = 0;
		if (ts instanceof Timestamp) min = Math.max(0, (System.currentTimeMillis() - ((Timestamp) ts).getTime()) / 60000);
		row.put("minutes", min);
		return row;
	}

	private Integer equipId(String code, String spjangcd) {
		if (code == null || code.isEmpty()) return null;
		try {
			return sqlRunner.queryForObject("""
                SELECT e.id FROM equ e
                WHERE e."Code" = :code
                  AND (CAST(:spjangcd AS varchar) IS NULL OR e.spjangcd = CAST(:spjangcd AS varchar))
                ORDER BY e.id LIMIT 1
                """, new MapSqlParameterSource().addValue("code", code)
							.addValue("spjangcd", spjangcd == null || spjangcd.isEmpty() ? null : spjangcd),
					(rs, n) -> rs.getInt(1));
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

	private static Integer toInt(Object o) {
		try { return o == null || o.toString().isBlank() ? null : Integer.valueOf(o.toString().trim()); }
		catch (Exception e) { return null; }
	}
}