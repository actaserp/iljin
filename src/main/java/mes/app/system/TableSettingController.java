package mes.app.system;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;

/**
 * 공통 표(EditTable v1.8) ⚙ 표 설정 저장 (2026-11-24)
 *
 *  GET  /api/system/table_setting/page?path=화면주소   그 화면 표 설정 전부(items) · 내 계정 값(mine) · 슈퍼유저 여부(superuser)
 *  POST /api/system/table_setting/save  {key, cfg}     회사 공통 설정 저장
 *  POST /api/system/table_setting/reset {key}          처음대로
 *  POST /api/system/table_setting/mine  {key, rowHeight, fontSize}  내 계정 줄 높이 · 글자 크기 (key='*' 면 모든 표, 둘 다 비우면 지움)
 *
 * 테이블 : et_table_setting (회사 공통) · et_table_setting_user (계정별)
 * 권한 : 슈퍼유저(auth_user.is_superuser) 만 칸의 키 · 형식 · 추가 · 삭제(key · fmt · add · del)를 바꿀 수 있다.
 *        일반 계정이 보낸 key · fmt · add · del 은 버리고, 슈퍼유저가 정해 둔 값을 남긴다.
 */
@RestController
@RequestMapping("/api/system/table_setting")
public class TableSettingController {

	@Autowired
	SqlRunner sqlRunner;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_KEY = 400;
	private static final int MAX_CFG = 200_000;   // 설정 JSON 최대 글자 수

	/* ───────── 조회 ───────── */
	@GetMapping("/page")
	public AjaxResult page(@RequestParam(value = "path", required = false) String path, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String prefix = (path == null ? "" : path) + "#";

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("prefix", escapeLike(prefix) + "%");
		p.addValue("user_id", user.getId());

		Map<String, Object> items = new LinkedHashMap<>();
		List<Map<String, Object>> rows = this.sqlRunner.getRows(
				"select setting_key, cfg from et_table_setting where setting_key like :prefix", p);
		for (Map<String, Object> r : rows) {
			Map<String, Object> cfg = parse((String) r.get("cfg"));
			if (cfg != null) items.put((String) r.get("setting_key"), cfg);
		}

		Map<String, Object> mine = new LinkedHashMap<>();
		List<Map<String, Object>> my = this.sqlRunner.getRows("""
				select setting_key, row_height, font_size
				from et_table_setting_user
				where user_id = :user_id and (setting_key = '*' or setting_key like :prefix)
				""", p);
		for (Map<String, Object> r : my) {
			Map<String, Object> v = new HashMap<>();
			if (r.get("row_height") != null) v.put("rowHeight", ((Number) r.get("row_height")).intValue());
			if (r.get("font_size") != null) v.put("fontSize", ((Number) r.get("font_size")).doubleValue());
			if (!v.isEmpty()) mine.put((String) r.get("setting_key"), v);
		}

		Map<String, Object> data = new HashMap<>();
		data.put("superuser", isSuper(user));
		data.put("items", items);
		data.put("mine", mine);

		AjaxResult result = new AjaxResult();
		result.data = data;
		return result;
	}

	/* ───────── 회사 공통 저장 ───────── */
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();
		String key = key(body.get("key"));
		if (key == null) return fail(result, "표 설정 키가 올바르지 않습니다.");

		Map<String, Object> cfg = asMap(body.get("cfg"));
		if (cfg == null) cfg = new LinkedHashMap<>();
		if (!isSuper(user)) cfg = keepSuperFields(cfg, load(key));

		String json = toJson(cfg);
		if (json == null || json.length() > MAX_CFG) return fail(result, "표 설정이 너무 큽니다.");
		upsert(key, json, user.getId());
		result.data = cfg;
		return result;
	}

	/* ───────── 처음대로 ───────── */
	@PostMapping("/reset")
	public AjaxResult reset(@RequestBody Map<String, Object> body, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();
		String key = key(body.get("key"));
		if (key == null) return fail(result, "표 설정 키가 올바르지 않습니다.");

		Map<String, Object> keep = null;
		if (!isSuper(user)) {
			// 일반 계정 : 슈퍼유저가 정한 칸 구성(키 · 형식 · 추가 · 삭제)은 남긴다
			Map<String, Object> old = load(key);
			List<Map<String, Object>> cols = new ArrayList<>();
			for (Map<String, Object> c : cols(old)) {
				Map<String, Object> x = new LinkedHashMap<>();
				x.put("id", c.get("id"));
				boolean add = Boolean.TRUE.equals(c.get("add"));
				for (String f : new String[] {"key", "fmt", "add", "del"}) if (c.get(f) != null) x.put(f, c.get(f));
				if (add) for (String f : new String[] {"header", "width"}) if (c.get(f) != null) x.put(f, c.get(f));
				if (x.size() > 1) cols.add(x);
			}
			if (!cols.isEmpty()) { keep = new LinkedHashMap<>(); keep.put("cols", cols); }
		}

		MapSqlParameterSource p = new MapSqlParameterSource("key", key);
		if (keep == null) {
			this.sqlRunner.execute("delete from et_table_setting where setting_key = :key", p);
		} else {
			upsert(key, toJson(keep), user.getId());
		}
		result.data = keep;
		return result;
	}

	/* ───────── 내 계정 줄 높이 · 글자 크기 ───────── */
	@PostMapping("/mine")
	public AjaxResult mine(@RequestBody Map<String, Object> body, Authentication auth) {
		User user = (User) auth.getPrincipal();
		AjaxResult result = new AjaxResult();
		String key = "*".equals(body.get("key")) ? "*" : key(body.get("key"));
		if (key == null) return fail(result, "표 설정 키가 올바르지 않습니다.");

		Integer rh = null;
		Double fs = null;
		if (body.get("rowHeight") instanceof Number) rh = Math.max(18, Math.min(80, ((Number) body.get("rowHeight")).intValue()));
		if (body.get("fontSize") instanceof Number) fs = Math.round(Math.max(10, Math.min(20, ((Number) body.get("fontSize")).doubleValue())) * 2) / 2.0;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("key", key);
		p.addValue("user_id", user.getId());
		p.addValue("rh", rh);
		p.addValue("fs", fs);
		if (rh == null && fs == null) {
			this.sqlRunner.execute("delete from et_table_setting_user where setting_key = :key and user_id = :user_id", p);
		} else {
			this.sqlRunner.execute("""
					insert into et_table_setting_user (setting_key, user_id, row_height, font_size, updated_at)
					values (:key, :user_id, cast(:rh as int), cast(:fs as numeric), now())
					on conflict (setting_key, user_id)
					do update set row_height = excluded.row_height, font_size = excluded.font_size, updated_at = now()
					""", p);
		}
		return result;
	}

	/* ───────── 도우미 ───────── */

	/** 일반 계정이 보낸 설정에서 key · fmt · add · del 을 버리고, 저장돼 있던 슈퍼유저 값을 다시 붙인다 */
	private Map<String, Object> keepSuperFields(Map<String, Object> cfg, Map<String, Object> old) {
		Map<String, Map<String, Object>> oldById = new LinkedHashMap<>();
		for (Map<String, Object> c : cols(old)) oldById.put(String.valueOf(c.get("id")), c);

		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> c : cols(cfg)) {
			String id = String.valueOf(c.get("id"));
			Map<String, Object> o = oldById.get(id);
			boolean wasAdd = o != null && Boolean.TRUE.equals(o.get("add"));
			if (Boolean.TRUE.equals(c.get("add")) && !wasAdd) continue;   // 일반 계정은 칸을 추가할 수 없다
			Map<String, Object> x = new LinkedHashMap<>(c);
			x.remove("key"); x.remove("fmt"); x.remove("add"); x.remove("del");
			if (o != null) for (String f : new String[] {"key", "fmt", "add", "del"}) if (o.get(f) != null) x.put(f, o.get(f));
			out.add(x);
		}
		// 일반 계정 모달에는 슈퍼유저가 지운 칸이 안 보이므로, 빠진 칸은 그대로 뒤에 붙인다
		Set<String> seen = out.stream().map(x -> String.valueOf(x.get("id"))).collect(Collectors.toSet());
		for (Map<String, Object> o : oldById.values()) {
			if (seen.contains(String.valueOf(o.get("id")))) continue;
			if (o.get("key") != null || o.get("fmt") != null || o.get("add") != null || o.get("del") != null) out.add(new LinkedHashMap<>(o));
		}
		Map<String, Object> r = new LinkedHashMap<>(cfg);
		r.put("cols", out);
		return r;
	}

	private void upsert(String key, String json, Object userId) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("key", key);
		p.addValue("cfg", json);
		p.addValue("user_id", userId);
		this.sqlRunner.execute("""
				insert into et_table_setting (setting_key, cfg, updated_by, updated_at)
				values (:key, :cfg, :user_id, now())
				on conflict (setting_key)
				do update set cfg = excluded.cfg, updated_by = excluded.updated_by, updated_at = now()
				""", p);
	}

	private Map<String, Object> load(String key) {
		Map<String, Object> r = this.sqlRunner.getRow("select cfg from et_table_setting where setting_key = :key",
				new MapSqlParameterSource("key", key));
		return r == null ? null : parse((String) r.get("cfg"));
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> cols(Map<String, Object> cfg) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (cfg == null || !(cfg.get("cols") instanceof List)) return out;
		for (Object o : (List<Object>) cfg.get("cols")) if (o instanceof Map && ((Map<String, Object>) o).get("id") != null) out.add((Map<String, Object>) o);
		return out;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object o) { return o instanceof Map ? new LinkedHashMap<>((Map<String, Object>) o) : null; }

	private Map<String, Object> parse(String json) {
		if (json == null || json.isEmpty()) return null;
		try { return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return null; }
	}

	private String toJson(Object o) {
		try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return null; }
	}

	/** 설정 키 : '화면주소#표id' 모양, 400자 이하 */
	private String key(Object o) {
		if (!(o instanceof String)) return null;
		String k = ((String) o).trim();
		return k.isEmpty() || k.length() > MAX_KEY || !k.contains("#") ? null : k;
	}

	private boolean isSuper(User user) { return user != null && Boolean.TRUE.equals(user.getSuperUser()); }

	private static String escapeLike(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

	private AjaxResult fail(AjaxResult r, String msg) { r.success = false; r.message = msg; return r; }
}
