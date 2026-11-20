package mes.app.production.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.balju.service.BaljuOrderService;
import mes.app.sales.service.SujuService;
import mes.domain.entity.Balju;
import mes.domain.entity.BaljuHead;
import mes.domain.entity.Material;
import mes.domain.entity.User;
import mes.domain.repository.BalJuHeadRepository;
import mes.domain.repository.BujuRepository;
import mes.domain.repository.MaterialRepository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * 유형별 외작 (프로젝트 단위).
 *
 * <p>수주는 내작인데 그 프로젝트의 가공품 한 유형(plate 300개 등)을 외주로 보낸다.
 * 기본은 유형 필요량 통째로, 수량을 고쳐 일부만 보낼 수 있고, 한 유형을 여러 업체에 나눌 수 있다.
 *
 * <h3>발주 · 입고는 기존 흐름을 그대로 쓴다</h3>
 * <ul>
 *   <li>행 1개 = 외작 발주 라인 1개. {@code balju."SujuType"='outsource'},
 *       {@code "PlanTableName"='iljin_kind_out'}, {@code "PlanDataPk"=행 id}.
 *       그래서 [외작 입고] 화면에 그대로 뜨고, 단가는 발주 화면에서 넣는다.</li>
 *   <li>발주는 자재 품목이 있어야 걸린다. 부품마다 만들지 않고 <b>유형마다 하나</b>
 *       ('외주가공 plate')만 만든다 — 품목 마스터를 더럽히지 않는다 (SPEC 4장).</li>
 *   <li>규칙은 수주 → 외작 발주 동기화(SujuSyncService)와 같다.
 *       발주 라인은 지우지 않고 canceled 로만 내린다 / 단가는 덮어쓰지 않는다 /
 *       입고 누계 미만으로 수량을 줄이지 못한다 / 입고가 있으면 업체를 못 바꾸고 취소도 못 한다.</li>
 * </ul>
 *
 * <p>대시보드는 입고 수량을 그 유형의 완료 수량에 더한다 (DashProjectService.getKindOut).
 */
@Slf4j
@Service
public class KindOutService {

	public static final String PLAN_TABLE = "iljin_kind_out";
	private static final String BALJU_TYPE_OUTSOURCE = "outsource";
	private static final String MATERIAL_GROUP_CODE = "MAKE";   // 수주 외작 품목과 같은 그룹
	private static final String MATERIAL_PREFIX = "외주가공 ";

	@Autowired SqlRunner sqlRunner;
	@Autowired MaterialRepository materialRepository;
	@Autowired BalJuHeadRepository balJuHeadRepository;
	@Autowired BujuRepository bujuRepository;
	@Autowired BaljuOrderService baljuOrderService;
	@Autowired SujuService sujuService;

	/** 프로젝트의 유형별 외작 행 + 발주번호 · 입고 누계 */
	public List<Map<String, Object>> list(String spjangcd, String projNo) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("spjangcd", spjangcd)
				.addValue("projNo", projNo);
		return sqlRunner.getRows("""
            SELECT k.id, k.proj_no, k.kind, k.qty, k.company_id, k.company_name,
                   TO_CHAR(k.due_date, 'YYYY-MM-DD') AS due_date, k.remark,
                   k.balju_id, b."JumunNumber" AS jumun_number,
                   COALESCE(b."State", 'draft') AS balju_state,
                   COALESCE(mi.in_qty, 0) AS in_qty
            FROM iljin_kind_out k
            LEFT JOIN balju b ON b.id = k.balju_id
            LEFT JOIN (
                SELECT "SourceDataPk", SUM("InputQty") AS in_qty
                FROM mat_inout
                WHERE "SourceTableName" = 'balju' AND "InOut" = 'in' AND COALESCE("_status", 'a') = 'a'
                GROUP BY "SourceDataPk"
            ) mi ON mi."SourceDataPk" = k.balju_id
            WHERE k.proj_no = :projNo
              AND (CAST(:spjangcd AS varchar) IS NULL OR COALESCE(k.spjangcd, CAST(:spjangcd AS varchar)) = CAST(:spjangcd AS varchar))
              AND k.state = 'active'
            ORDER BY k.kind, k.id
            """, p);
	}

	/**
	 * 한 행 저장 (신규 / 수정).
	 * @return 오류 문구. null 이면 성공
	 */
	public String save(Map<String, Object> in, String spjangcd, User user) {
		Integer id = toInt(in.get("id"));
		String projNo = str(in.get("projNo"));
		String kind = str(in.get("kind"));
		double qty = toDbl(in.get("qty"));
		Integer compId = toInt(in.get("companyId"));
		String compName = str(in.get("companyName"));
		String due = str(in.get("dueDate"));
		String remark = str(in.get("remark"));

		if (projNo.isEmpty() || kind.isEmpty()) return "프로젝트와 유형이 필요합니다.";
		if (qty <= 0) return "수량을 넣으세요.";
		if (compId == null) return "외주처를 고르세요.";

		Date jumunDate = Date.valueOf(LocalDate.now());
		Date dueDate = due.isEmpty() ? projectDue(projNo, jumunDate) : Date.valueOf(due);

		if (id == null) {
			Integer matId = kindMaterial(kind, spjangcd, user);
			Integer newId = sqlRunner.queryForObject("""
                INSERT INTO iljin_kind_out
                    (spjangcd, proj_no, kind, qty, company_id, company_name, due_date, remark, state, _created, _creater_id)
                VALUES (CAST(:spjangcd AS varchar), :projNo, :kind, :qty, :compId, :compName, :due, :remark, 'active', now(), :userId)
                RETURNING id
                """, new MapSqlParameterSource()
							.addValue("spjangcd", spjangcd).addValue("projNo", projNo).addValue("kind", kind)
							.addValue("qty", qty).addValue("compId", compId).addValue("compName", compName)
							.addValue("due", dueDate).addValue("remark", remark)
							.addValue("userId", user.getId()),
					(rs, n) -> rs.getInt(1));
			Integer headId = findHead(projNo, compId);
			if (headId == null) headId = createHead(compId, jumunDate, dueDate, spjangcd, user, projNo);
			Integer baljuId = insertBalju(headId, newId, matId, compId, compName, qty, jumunDate, dueDate,
					kind, remark, spjangcd, user);
			setBalju(newId, baljuId);
			recalcHead(headId, user);
			log.info("[유형외작] 신규 proj={} kind={} qty={} 업체={} balju={}", projNo, kind, qty, compId, baljuId);
			return null;
		}

		Map<String, Object> cur = row(id);
		if (cur == null) return "외작 행을 찾을 수 없습니다.";
		double inQty = toDbl(cur.get("in_qty"));
		Integer oldBalju = toInt(cur.get("balju_id"));
		Integer oldComp = toInt(cur.get("company_id"));

		if (qty < inQty) return "이미 입고된 수량이 " + fmt(inQty) + " 이라 그 미만으로 줄일 수 없습니다.";

		boolean compChanged = !Objects.equals(oldComp, compId);
		if (compChanged && inQty > 0) return "입고가 있어 외주처를 바꿀 수 없습니다. 새 행으로 나눠 보내세요.";

		sqlRunner.execute("""
            UPDATE iljin_kind_out
               SET qty = :qty, company_id = :compId, company_name = :compName,
                   due_date = :due, remark = :remark, _modified = now(), _modifier_id = :userId
             WHERE id = :id
            """, new MapSqlParameterSource()
				.addValue("id", id).addValue("qty", qty).addValue("compId", compId).addValue("compName", compName)
				.addValue("due", dueDate).addValue("remark", remark).addValue("userId", user.getId()));

		if (compChanged) {
			// 업체가 바뀜 → 옛 발주 라인 취소, 새 업체로 새 라인
			Integer oldHead = headOf(oldBalju);
			if (oldBalju != null) cancelBalju(oldBalju, user, "외주처 변경으로 취소됨 (→ " + compName + ")");
			if (oldHead != null) recalcHead(oldHead, user);
			Integer matId = kindMaterial(str(cur.get("kind")), spjangcd, user);
			Integer headId = findHead(projNo, compId);
			if (headId == null) headId = createHead(compId, jumunDate, dueDate, spjangcd, user, projNo);
			Integer baljuId = insertBalju(headId, id, matId, compId, compName, qty, jumunDate, dueDate,
					str(cur.get("kind")), remark, spjangcd, user);
			setBalju(id, baljuId);
			recalcHead(headId, user);
			return null;
		}

		if (oldBalju != null) {
			updateBaljuQty(oldBalju, qty, inQty, dueDate, remark, user);
			recalcHead(headOf(oldBalju), user);
		}
		return null;
	}

	/** 행 취소. 입고가 있으면 막는다 */
	public String cancel(Integer id, User user) {
		Map<String, Object> cur = row(id);
		if (cur == null) return "외작 행을 찾을 수 없습니다.";
		double inQty = toDbl(cur.get("in_qty"));
		if (inQty > 0) return "입고 " + fmt(inQty) + " 가 있어 취소할 수 없습니다. 수량을 입고 수량으로 줄이세요.";
		Integer b = toInt(cur.get("balju_id"));
		Integer head = headOf(b);
		if (b != null) cancelBalju(b, user, "유형 외작 취소");
		if (head != null) recalcHead(head, user);
		sqlRunner.execute("""
            UPDATE iljin_kind_out SET state = 'canceled', _modified = now(), _modifier_id = :userId WHERE id = :id
            """, new MapSqlParameterSource().addValue("id", id).addValue("userId", user.getId()));
		return null;
	}

	// ===================================================================

	private Map<String, Object> row(Integer id) {
		List<Map<String, Object>> rows = sqlRunner.getRows("""
            SELECT k.*, COALESCE(mi.in_qty, 0) AS in_qty
            FROM iljin_kind_out k
            LEFT JOIN (
                SELECT "SourceDataPk", SUM("InputQty") AS in_qty
                FROM mat_inout
                WHERE "SourceTableName" = 'balju' AND "InOut" = 'in' AND COALESCE("_status", 'a') = 'a'
                GROUP BY "SourceDataPk"
            ) mi ON mi."SourceDataPk" = k.balju_id
            WHERE k.id = :id AND k.state = 'active'
            """, new MapSqlParameterSource().addValue("id", id));
		return rows == null || rows.isEmpty() ? null : rows.get(0);   // SqlRunner 는 오류 시 null
	}

	/** 납기 미입력 → 프로젝트 납기(tb_da003.eddate 'YYYYMMDD'), 그것도 없으면 오늘 */
	private Date projectDue(String projNo, Date fallback) {
		try {
			String ed = sqlRunner.queryForObject(
					"SELECT eddate FROM tb_da003 WHERE projno = :p LIMIT 1",
					new MapSqlParameterSource().addValue("p", projNo), (rs, n) -> rs.getString(1));
			if (ed != null && ed.matches("\\d{8}")) {
				return Date.valueOf(ed.substring(0, 4) + "-" + ed.substring(4, 6) + "-" + ed.substring(6, 8));
			}
		} catch (Exception ignore) { /* 없음 */ }
		return fallback;
	}

	/** 유형 자재 품목 '외주가공 plate' — 있으면 쓰고 없으면 만든다 */
	private Integer kindMaterial(String kind, String spjangcd, User user) {
		String name = MATERIAL_PREFIX + kind;
		try {
			Integer id = sqlRunner.queryForObject("""
                SELECT m.id FROM material m
                JOIN mat_grp g ON g.id = m."MaterialGroup_id" AND g."Code" = :grp
                WHERE m."Name" = :name
                ORDER BY m.id LIMIT 1
                """, new MapSqlParameterSource().addValue("name", name).addValue("grp", MATERIAL_GROUP_CODE),
					(rs, n) -> rs.getInt(1));
			if (id != null) return id;
		} catch (Exception ignore) { /* 없음 → 만든다 */ }

		Integer groupId;
		try {
			groupId = sqlRunner.queryForObject("SELECT id FROM mat_grp WHERE \"Code\" = :c LIMIT 1",
					new MapSqlParameterSource().addValue("c", MATERIAL_GROUP_CODE), (rs, n) -> rs.getInt(1));
		} catch (Exception e) {
			throw new IllegalStateException("품목그룹을 찾을 수 없습니다. mat_grp 에 Code='" + MATERIAL_GROUP_CODE + "' 가 필요합니다.");
		}
		Material m = new Material();
		m.setCode(sujuService.getNextMatCode());
		m.setName(name);
		m.setMaterialGroupId(groupId);
		m.setFactory_id(1);
		m.setSpjangcd(spjangcd);
		m.setUseyn("0");
		m.setStoreHouseId(3);
		m.setPurchaseOrderStandard("mrp");
		m.setValidDays(1);
		m.setDescription("유형별 외작 발주용 (생산 지시)");
		m.set_audit(user);
		Integer id = materialRepository.save(m).getId();
		log.info("[유형외작] 자재 품목 생성: {} id={}", name, id);
		return id;
	}

	/**
	 * 같은 프로젝트·같은 업체로 이미 만든 발주 헤더 중 '아직 손대지 않은 것' (발주 한 장에 유형 여러 줄).
	 *   손댄 헤더 = 입고가 하나라도 들어왔거나, 업체에 발주서 메일을 보냈거나, 취소된 것.
	 *   그런 헤더에 나중 발주를 붙이면 발주일이 첫 발주일로 남고, 끝난 발주가 다시 '부분입고'로 열리며,
	 *   업체가 받은 발주서와 내용이 달라진다 → 그때는 새 헤더(그날 발주일)를 만든다.
	 */
	private Integer findHead(String projNo, Integer compId) {
		try {
			return sqlRunner.queryForObject("""
                SELECT h.id
                FROM iljin_kind_out k
                JOIN balju b ON b.id = k.balju_id
                JOIN balju_head h ON h.id = b."BaljuHead_id"
                WHERE k.proj_no = :projNo AND b."Company_id" = :compId
                  AND COALESCE(h."State", 'draft') <> 'canceled'
                  -- 입고가 들어온 발주가 하나도 없고
                  AND NOT EXISTS (
                      SELECT 1 FROM balju b2
                      JOIN mat_inout mi ON mi."SourceTableName" = 'balju' AND mi."SourceDataPk" = b2.id
                                       AND mi."InOut" = 'in' AND COALESCE(mi."_status", 'a') = 'a'
                      WHERE b2."BaljuHead_id" = h.id)
                  -- 발주서 메일도 안 보낸 헤더
                  AND NOT EXISTS (
                      SELECT 1 FROM balju_mail m
                      WHERE m.bh_id = h.id AND COALESCE(m."SendState", 'success') = 'success')
                ORDER BY h.id DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("projNo", projNo).addValue("compId", compId),
					(rs, n) -> rs.getInt(1));
		} catch (Exception e) {
			return null;
		}
	}

	private Integer createHead(Integer compId, Date jumunDate, Date dueDate, String spjangcd, User user, String projNo) {
		BaljuHead h = new BaljuHead();
		h.setCreated(new Timestamp(System.currentTimeMillis()));
		h.setCreaterId(user.getId());
		h.set_status("manual");
		h.setJumunNumber(baljuOrderService.makeJumunNumber(jumunDate));
		h.setJumunDate(jumunDate);
		h.setDeliveryDate(dueDate);
		h.setCompanyId(compId);
		h.setSpjangcd(spjangcd);
		h.setSujuType(BALJU_TYPE_OUTSOURCE);
		h.setTotalPrice(0d);
		h.setDescription("유형별 외작 · " + projNo);
		balJuHeadRepository.save(h);
		return h.getId();
	}

	private Integer insertBalju(Integer headId, Integer rowId, Integer matId, Integer compId, String compName,
								double qty, Date jumunDate, Date dueDate, String kind, String remark,
								String spjangcd, User user) {
		String jumunNumber = sqlRunner.queryForObject(
				"SELECT \"JumunNumber\" FROM balju_head WHERE id = :id",
				new MapSqlParameterSource().addValue("id", headId), (rs, n) -> rs.getString(1));
		Balju d = new Balju();
		d._created = new Timestamp(System.currentTimeMillis());
		d._creater_id = user.getId();
		d.setBaljuHeadId(headId);
		d.setJumunNumber(jumunNumber);
		d.setMaterialId(matId);
		d.setCompanyId(compId);
		d.setCompanyName(compName);
		d.setSujuQty(qty);
		d.setSujuQty2(0d);
		d.setUnitPrice(0d);          // 단가 미정 → 발주 화면에서 입력
		d.setPrice(0d);
		d.setVat(0d);
		d.setTotalAmount(0d);
		d.setStandard(kind);
		d.setDescription(remark.isEmpty() ? null : remark);
		d.setJumunDate(jumunDate);
		d.setDueDate(dueDate);
		d.setSpjangcd(spjangcd);
		d.setInVatYN("N");
		d.setSujuType(BALJU_TYPE_OUTSOURCE);
		d.setState("draft");
		d.set_status("manual");
		Balju saved = bujuRepository.save(d);
		sqlRunner.execute("""
            UPDATE balju SET "PlanTableName" = :t, "PlanDataPk" = :rowId WHERE id = :id
            """, new MapSqlParameterSource().addValue("t", PLAN_TABLE).addValue("rowId", rowId).addValue("id", saved.getId()));
		return saved.getId();
	}

	private void setBalju(Integer rowId, Integer baljuId) {
		sqlRunner.execute("UPDATE iljin_kind_out SET balju_id = :b WHERE id = :id",
				new MapSqlParameterSource().addValue("b", baljuId).addValue("id", rowId));
	}

	private Integer headOf(Integer baljuId) {
		if (baljuId == null) return null;
		try {
			return sqlRunner.queryForObject("SELECT \"BaljuHead_id\" FROM balju WHERE id = :id",
					new MapSqlParameterSource().addValue("id", baljuId), (rs, n) -> rs.getInt(1));
		} catch (Exception e) {
			return null;
		}
	}

	/** 수량 변경. 단가는 발주 화면 소유 — 기존 단가로 금액만 다시 계산 (SujuSyncService 와 같은 식) */
	private void updateBaljuQty(Integer baljuId, double qty, double inQty, Date dueDate, String remark, User user) {
		Map<String, Object> b = sqlRunner.getRow(
				"SELECT \"UnitPrice\", \"InVatYN\" FROM balju WHERE id = :id",
				new MapSqlParameterSource().addValue("id", baljuId));
		double unit = b == null ? 0d : toDbl(b.get("UnitPrice"));
		String inVat = b == null ? "N" : str(b.get("InVatYN"));
		double supply, vat, total;
		if ("Y".equalsIgnoreCase(inVat)) {
			total = unit * qty;
			supply = Math.round(total * 10d / 11d);
			vat = total - supply;
		} else {
			supply = unit * qty;
			vat = Math.round(supply * 0.1d);
			total = supply + vat;
		}
		String state = inQty <= 0 ? "draft" : (inQty >= qty ? "received" : "partial");
		sqlRunner.execute("""
            UPDATE balju
               SET "SujuQty" = :qty, "State" = CAST(:state AS varchar),
                   "Price" = :price, "Vat" = :vat, "TotalAmount" = :total,
                   "DueDate" = :due, "Description" = CAST(:desc AS varchar),
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :id
            """, new MapSqlParameterSource()
				.addValue("id", baljuId).addValue("qty", qty).addValue("state", state)
				.addValue("price", supply).addValue("vat", vat).addValue("total", total)
				.addValue("due", dueDate).addValue("desc", remark.isEmpty() ? null : remark)
				.addValue("userId", user.getId()));
	}

	private void cancelBalju(Integer baljuId, User user, String reason) {
		sqlRunner.execute("""
            UPDATE balju SET "State" = 'canceled', "Description" = CAST(:reason AS varchar),
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE id = :id
            """, new MapSqlParameterSource().addValue("id", baljuId).addValue("reason", reason)
				.addValue("userId", user.getId()));
	}

	/** 헤더 합계 · 상태 (SujuSyncService.recalcBaljuHead 와 같은 규칙) */
	private void recalcHead(Integer headId, User user) {
		if (headId == null) return;
		sqlRunner.execute("""
            UPDATE balju_head h
               SET "TotalPrice" = COALESCE((
                     SELECT SUM(b."TotalAmount") FROM balju b
                      WHERE b."BaljuHead_id" = h.id AND COALESCE(b."State", 'draft') <> 'canceled'), 0),
                   "State" = CASE
                       WHEN NOT EXISTS (SELECT 1 FROM balju b
                                         WHERE b."BaljuHead_id" = h.id AND COALESCE(b."State", 'draft') <> 'canceled')
                       THEN 'canceled'
                       WHEN COALESCE(h."State", '') = 'canceled' THEN 'draft'
                       ELSE h."State" END,
                   "_modified" = now(), "_modifier_id" = :userId
             WHERE h.id = :headId
            """, new MapSqlParameterSource().addValue("headId", headId).addValue("userId", user.getId()));
	}

	private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

	private static Integer toInt(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		try { return (int) Double.parseDouble(o.toString().trim()); } catch (NumberFormatException e) { return null; }
	}

	private static double toDbl(Object o) {
		if (o == null || o.toString().isBlank()) return 0d;
		try { return Double.parseDouble(o.toString().trim()); } catch (NumberFormatException e) { return 0d; }
	}

	private static String fmt(double v) {
		return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
	}
}