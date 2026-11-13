package mes.app.balju.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 발주서 메일 참조 · 기본 제목/내용 (2026-11-03)
 * *   - 고정 참조: 시스템관리 > 메일 설정 「발주메일 고정 참조」에 체크한 사용자 (jo_mail_cc, kind='balju') 의 이메일
 *   - 기본 회신 주소: 로그인한 사용자 이메일
 *   - 기본 제목·내용: 발주 헤더(주문번호·구매처·납기)로 만든다. 화면에서 고칠 수 있다
 */
@Service
public class BaljuMailCcService {

  public static final String KIND = "balju";

  @Autowired
  SqlRunner sqlRunner;

  /** 고정 참조 이메일 (사용 중인 사용자, 이메일이 있는 사람만) */
  public List<String> fixedCc() {
    List<Map<String, Object>> rows = sqlRunner.getRows("""
            SELECT DISTINCT u.email
              FROM mail_cc c JOIN auth_user u ON u.id = c.user_id
             WHERE c.kind = :kind AND COALESCE(u.is_active, true) AND COALESCE(u.email, '') <> ''
             ORDER BY 1
            """, new MapSqlParameterSource("kind", KIND));
    List<String> out = new ArrayList<>();
    for (Map<String, Object> r : rows) out.add(String.valueOf(r.get("email")).trim());
    return out;
  }

  public boolean isFixed(Integer userId) {
    if (userId == null) return false;
    return sqlRunner.getRow("SELECT 1 AS ok FROM mail_cc WHERE user_id = :uid AND kind = :kind",
            new MapSqlParameterSource("uid", userId).addValue("kind", KIND)) != null;
  }

  public void setFixed(Integer userId, boolean on) {
    MapSqlParameterSource p = new MapSqlParameterSource("uid", userId).addValue("kind", KIND);
    if (on) sqlRunner.execute("INSERT INTO mail_cc (user_id, kind) VALUES (:uid, :kind) ON CONFLICT DO NOTHING", p);
    else sqlRunner.execute("DELETE FROM mail_cc WHERE user_id = :uid AND kind = :kind", p);
  }

  /** 메일 설정 화면: 활성 사용자 전체 + 참조 여부 (2026-11-03, 사용자 화면에서 옮김) */
  public List<Map<String, Object>> listForSetting() {
    return sqlRunner.getRows("""
            SELECT u.id, u.username, COALESCE(NULLIF(u.first_name, ''), u.username) AS name, u.email,
                   EXISTS (SELECT 1 FROM mail_cc c WHERE c.user_id = u.id AND c.kind = :kind) AS checked
              FROM auth_user u
             WHERE COALESCE(u.is_active, true)
             ORDER BY checked DESC, name
            """, new MapSqlParameterSource("kind", KIND));
  }

  /** 체크된 사람만 남기고 나머지는 뺀다 */
  @org.springframework.transaction.annotation.Transactional
  public void saveAll(List<Integer> ids) {
    MapSqlParameterSource p = new MapSqlParameterSource("kind", KIND);
    sqlRunner.execute("DELETE FROM mail_cc WHERE kind = :kind", p);
    if (ids == null) return;
    for (Integer id : new LinkedHashSet<>(ids)) {
      if (id == null) continue;
      sqlRunner.execute("INSERT INTO mail_cc (user_id, kind) VALUES (:uid, :kind) ON CONFLICT DO NOTHING",
              new MapSqlParameterSource("uid", id).addValue("kind", KIND));
    }
  }

  /** 발주서 발송 팝업 기본값: cc · replyTo · title · content */
  public Map<String, Object> defaults(Map<String, Object> head, String loginEmail, String senderCompany) {
    String no = str(head == null ? null : head.get("JumunNumber"));
    String company = str(head == null ? null : head.get("CompanyName"));
    String due = date(head == null ? null : head.get("DeliveryDate"));
    String me = senderCompany == null || senderCompany.isBlank() ? "주아이코리아" : senderCompany;

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("cc", fixedCc());
    out.put("replyTo", loginEmail == null ? "" : loginEmail);
    out.put("title", "[" + me + "] 발주서 송부" + (no.isEmpty() ? "" : " - " + no) + (company.isEmpty() ? "" : " (" + company + ")"));
    out.put("content", (company.isEmpty() ? "안녕하세요." : "안녕하세요, " + company + " 담당자님.") + "\n"
            + me + "입니다.\n\n"
            + "발주서" + (no.isEmpty() ? "" : "(주문번호 " + no + ")") + "를 첨부하여 보내드립니다.\n"
            + (due.isEmpty() ? "" : "납기일: " + due + "\n")
            + "내용 확인 후 회신 부탁드립니다.\n\n감사합니다.");
    return out;
  }

  private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

  private static String date(Object o) {
    if (o == null) return "";
    if (o instanceof java.sql.Date d) return d.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    String s = o.toString();
    return s.length() >= 10 ? s.substring(0, 10) : s;
  }
}