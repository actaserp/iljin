package mes.app.production;

import lombok.RequiredArgsConstructor;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 조립 전자 체크리스트 — 인수인계 · 배선/I/O 체크 · 시운전 준비.
 *
 * <p>항목(iljin_check_tpl)은 전 품목 공통, 결과(iljin_check_result)는 품목(suju 1행 = 지그)별.
 *   조립 공정(PC)과 조립 키오스크가 같은 API 를 쓴다 (iljin_checklist.js).
 *   체크한 사람은 화면에서 고른 작업자 이름으로 남긴다 — 키오스크는 공용 계정이라.
 *
 * <p>DDL: sql/조립체크리스트_DDL.sql
 */
@RestController
@RequestMapping("/api/production/asm_check")
@RequiredArgsConstructor
public class AsmCheckController {

	private final SqlRunner sqlRunner;

	/** 한 품목의 체크리스트 (항목 + 결과) */
	@GetMapping("/list")
	public AjaxResult list(@RequestParam("sujuId") Integer sujuId) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = sqlRunner.getRows("""
            SELECT t.id AS tpl_id, t.grp, t.name, t.seq,
                   COALESCE(c.checked, false) AS checked, c.note, c.checker,
                   TO_CHAR(c.checked_at, 'MM-DD HH24:MI') AS checked_at
            FROM iljin_check_tpl t
            LEFT JOIN iljin_check_result c ON c.tpl_id = t.id AND c.suju_id = :sujuId
            WHERE t.use_yn = 'Y'
            ORDER BY t.seq, t.id
            """, new MapSqlParameterSource().addValue("sujuId", sujuId));
		return r;
	}

	/** 여러 품목의 진행 (n/m) — 목록 배지용. sujuIds = '1,2,3' */
	@GetMapping("/summary")
	public AjaxResult summary(@RequestParam("sujuIds") String sujuIds) {
		AjaxResult r = new AjaxResult();
		List<Integer> ids = new ArrayList<>();
		for (String s : sujuIds.split(",")) {
			try { ids.add(Integer.valueOf(s.trim())); } catch (Exception ignore) { }
		}
		r.success = true;
		if (ids.isEmpty()) { r.data = List.of(); return r; }
		r.data = sqlRunner.getRows("""
            SELECT s.id AS suju_id,
                   (SELECT COUNT(*) FROM iljin_check_tpl t WHERE t.use_yn = 'Y') AS total,
                   (SELECT COUNT(*) FROM iljin_check_result c JOIN iljin_check_tpl t ON t.id = c.tpl_id AND t.use_yn = 'Y'
                     WHERE c.suju_id = s.id AND c.checked) AS done
            FROM suju s WHERE s.id IN (:ids)
            """, new MapSqlParameterSource().addValue("ids", ids));
		return r;
	}

	/** 체크 · 해제 · 메모. body: {sujuId, tplId, checked, note, checker, checkerId} */
	@PostMapping("/check")
	public AjaxResult check(@RequestBody Map<String, Object> b, Authentication auth) {
		AjaxResult r = new AjaxResult();
		User user = (User) auth.getPrincipal();
		Integer sujuId = toInt(b.get("sujuId")), tplId = toInt(b.get("tplId"));
		if (sujuId == null || tplId == null) { r.success = false; r.message = "품목·항목이 없습니다."; return r; }
		boolean checked = Boolean.parseBoolean(String.valueOf(b.get("checked")));
		String checker = str(b.get("checker"));
		if (checker.isEmpty()) checker = user.getFirst_name() != null ? user.getFirst_name() : user.getUsername();
		sqlRunner.execute("""
            INSERT INTO iljin_check_result (suju_id, tpl_id, checked, note, checker, checker_id, checked_at, _modified, _modifier_id)
            VALUES (:sujuId, :tplId, :checked, :note, :checker, :checkerId,
                    CASE WHEN :checked THEN now() END, now(), :userId)
            ON CONFLICT (suju_id, tpl_id) DO UPDATE
               SET checked = EXCLUDED.checked,
                   note = COALESCE(EXCLUDED.note, iljin_check_result.note),
                   checker = CASE WHEN EXCLUDED.checked THEN EXCLUDED.checker END,
                   checker_id = CASE WHEN EXCLUDED.checked THEN EXCLUDED.checker_id END,
                   checked_at = EXCLUDED.checked_at,
                   _modified = now(), _modifier_id = EXCLUDED._modifier_id
            """, new MapSqlParameterSource()
				.addValue("sujuId", sujuId).addValue("tplId", tplId).addValue("checked", checked)
				.addValue("note", b.containsKey("note") ? str(b.get("note")) : null, Types.VARCHAR)
				.addValue("checker", checker).addValue("checkerId", toInt(b.get("checkerId")), Types.INTEGER)
				.addValue("userId", user.getId()));
		r.success = true;
		return r;
	}

	/** 항목 추가 · 이름 바꾸기. body: {id?, grp, name, seq?} */
	@PostMapping("/tpl/save")
	public AjaxResult tplSave(@RequestBody Map<String, Object> b, Authentication auth) {
		AjaxResult r = new AjaxResult();
		User user = (User) auth.getPrincipal();
		String grp = str(b.get("grp")), name = str(b.get("name"));
		if (grp.isEmpty() || name.isEmpty()) { r.success = false; r.message = "구분과 항목 이름을 넣으세요."; return r; }
		Integer id = toInt(b.get("id"));
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("grp", grp).addValue("name", name)
				.addValue("id", id).addValue("userId", user.getId());
		if (id == null) {
			sqlRunner.execute("""
                INSERT INTO iljin_check_tpl (grp, name, seq, _creater_id)
                VALUES (:grp, :name,
                        COALESCE((SELECT MAX(seq) FROM iljin_check_tpl WHERE grp = :grp AND use_yn = 'Y'),
                                 (SELECT COALESCE(MAX(seq), 0) FROM iljin_check_tpl)) + 1,
                        :userId)
                """, p);
		} else {
			sqlRunner.execute("""
                UPDATE iljin_check_tpl SET name = :name, grp = :grp, _modified = now(), _modifier_id = :userId
                WHERE id = :id
                """, p);
		}
		r.success = true;
		return r;
	}

	/** 항목 빼기 — 지난 품목의 체크 기록은 남기고 목록에서만 뺀다. body: {id} */
	@PostMapping("/tpl/delete")
	public AjaxResult tplDelete(@RequestBody Map<String, Object> b, Authentication auth) {
		AjaxResult r = new AjaxResult();
		User user = (User) auth.getPrincipal();
		sqlRunner.execute("UPDATE iljin_check_tpl SET use_yn = 'N', _modified = now(), _modifier_id = :userId WHERE id = :id",
				new MapSqlParameterSource().addValue("id", toInt(b.get("id"))).addValue("userId", user.getId()));
		r.success = true;
		return r;
	}

	private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

	private static Integer toInt(Object o) {
		try { return o == null || o.toString().isBlank() ? null : Integer.valueOf(o.toString().trim()); }
		catch (Exception e) { return null; }
	}
}