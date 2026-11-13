package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * 메일 발송 설정 (시스템관리 > 메일 설정, 2026-11-03)
 *
 *  - 회사 공용 SMTP 계정 1건을 DB(mail_smtp, scope='company')에 둔다. application.properties 의 spring.mail.* 는 쓰지 않는다.
 *  - 비밀번호는 AES-256-GCM 으로 암호화해 저장한다. 키 = SHA-256(비밀 키).
 *    비밀 키: mes.mail.secret 설정이 있으면 그 값, 없으면 서버 파일(mes.mail.key-file)을 쓴다.
 *    파일이 없으면 처음 쓸 때 무작위로 만들어 둔다 → 따로 설정할 것 없음, git 에도 안 올라감.
 *    ★ 이 파일을 지우거나 서버를 옮기면서 빠뜨리면 메일 설정에서 비밀번호를 다시 넣어야 한다.
 *  - 인증 실패가 연속 LOCK_AFTER 회면 LOCK_MINUTES 동안 그 계정으로 보내지 않는다.
 *    (계속 시도하다 메일 서비스가 계정을 잠그는 것을 막는다)
 */
@Slf4j
@Service
public class MailSettingService {

	public static final int LOCK_AFTER = 3;
	public static final int LOCK_MINUTES = 30;

	@Autowired
	SqlRunner sqlRunner;

	@Value("${mes.mail.secret:}")
	private String configSecret;

	/** 비밀 키 파일 (기본: 사용자 홈/.mes/mail.key) */
	@Value("${mes.mail.key-file:${user.home}/.mes/mail.key}")
	private String keyFile;

	private volatile String secret;

	/** 설정값 → 키 파일 → (없으면) 새로 만들어 파일에 저장 */
	private synchronized String secret() {
		if (secret != null) return secret;
		if (StringUtils.hasText(configSecret)) return secret = configSecret.trim();
		try {
			java.nio.file.Path f = java.nio.file.Paths.get(keyFile);
			if (java.nio.file.Files.isRegularFile(f)) {
				String v = java.nio.file.Files.readString(f, StandardCharsets.UTF_8).trim();
				if (!v.isEmpty()) return secret = v;
			}
			byte[] b = new byte[32];
			new SecureRandom().nextBytes(b);
			String v = Base64.getEncoder().encodeToString(b);
			if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
			java.nio.file.Files.writeString(f, v, StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			try {   // 리눅스면 소유자만 읽게 (윈도는 무시)
				java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignore) { }
			log.info("메일 비밀번호 암호화 키 파일을 만들었습니다: {}", f.toAbsolutePath());
			return secret = v;
		} catch (java.nio.file.FileAlreadyExistsException e) {
			secret = null;
			return secret();   // 동시에 다른 요청이 만들었다
		} catch (Exception e) {
			log.error("메일 비밀번호 암호화 키 파일을 읽거나 만들지 못했습니다: {}", keyFile, e);
			return null;
		}
	}

	/** 메일 서비스별 기본값 (화면 「메일 서비스」 선택용) */
	public static final List<Map<String, Object>> PRESETS = List.of(
			preset("hiworks", "하이웍스", "smtps.hiworks.com", 465, "ssl"),
			preset("worksmobile", "네이버웍스", "smtp.worksmobile.com", 465, "ssl"),
			preset("google", "구글 워크스페이스 / Gmail", "smtp.gmail.com", 465, "ssl"),
			preset("ms365", "Microsoft 365 / Outlook", "smtp.office365.com", 587, "starttls"),
			preset("daouoffice", "다우오피스", "smtp.daouoffice.com", 465, "ssl"),
			preset("naver", "네이버 메일", "smtp.naver.com", 465, "ssl"),
			preset("daum", "다음 메일", "smtp.daum.net", 465, "ssl"),
			preset("custom", "직접 입력", "", 465, "ssl"));

	private static Map<String, Object> preset(String code, String name, String host, int port, String sec) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("code", code); m.put("name", name); m.put("host", host); m.put("port", port); m.put("security", sec);
		return m;
	}

	// 설정이 바뀌면 비운다
	private volatile JavaMailSenderImpl cachedSender;
	private volatile Integer cachedId;

	// =================================================================
	// 조회 · 저장
	// =================================================================

	/** 화면용: 비밀번호는 내려보내지 않고 저장 여부만 준다 */
	public Map<String, Object> getCompanyForView() {
		Map<String, Object> row = companyRow();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("presets", PRESETS);
		out.put("secretReady", StringUtils.hasText(secret()));
		if (row == null) { out.put("setting", null); return out; }
		Map<String, Object> s = new LinkedHashMap<>(row);
		s.put("has_password", StringUtils.hasText((String) row.get("password_enc")));
		s.remove("password_enc");
		out.put("setting", s);
		return out;
	}

	/** password 가 비어 있으면 기존 비밀번호를 그대로 둔다 */
	public void saveCompany(Map<String, Object> in, Integer userId) {
		String host = str(in.get("host"));
		String username = str(in.get("username"));
		String fromAddr = str(in.get("from_addr"));
		if (!StringUtils.hasText(fromAddr)) fromAddr = username;
		Integer port = toInt(in.get("port"));
		String security = str(in.get("security"));
		if (!List.of("ssl", "starttls", "none").contains(security)) security = "ssl";

		if (!StringUtils.hasText(host)) throw new IllegalArgumentException("SMTP 서버 주소를 입력하세요.");
		if (port == null || port <= 0) throw new IllegalArgumentException("포트를 입력하세요.");
		if (!StringUtils.hasText(username)) throw new IllegalArgumentException("아이디(메일 주소)를 입력하세요.");
		if (!fromAddr.contains("@")) throw new IllegalArgumentException("보내는 주소가 메일 주소 형식이 아닙니다.");

		String password = (String) in.get("password");
		String enc = null;
		if (StringUtils.hasText(password)) {
			if (!StringUtils.hasText(secret()))
				throw new IllegalStateException("암호화 키 파일(" + keyFile + ")을 만들지 못해 비밀번호를 저장할 수 없습니다. 서버 폴더 쓰기 권한을 확인하세요.");
			enc = encrypt(password);
		}

		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("provider", str(in.get("provider")))
				.addValue("host", host.trim())
				.addValue("port", port)
				.addValue("security", security)
				.addValue("username", username.trim())
				.addValue("enc", enc)
				.addValue("fromAddr", fromAddr.trim())
				.addValue("fromName", str(in.get("from_name")))
				.addValue("nameWithUser", yn(in.get("name_with_user"), "Y"))
				.addValue("bccSender", yn(in.get("bcc_sender"), "Y"))
				.addValue("useYn", yn(in.get("use_yn"), "Y"))
				.addValue("uid", userId);

		Map<String, Object> row = companyRow();
		if (row == null) {
			if (enc == null) throw new IllegalArgumentException("비밀번호(앱 비밀번호)를 입력하세요.");
			sqlRunner.execute("""
					INSERT INTO mail_smtp (scope, provider, host, port, security, username, password_enc, from_addr, from_name,
					                       name_with_user, bcc_sender, use_yn, _modified, _modifier_id)
					VALUES ('company', :provider, :host, :port, :security, :username, :enc, :fromAddr, :fromName,
					        :nameWithUser, :bccSender, :useYn, now(), :uid)
					""", p);
		} else {
			p.addValue("id", row.get("id"));
			// 계정 정보를 고치면 잠금·실패 횟수를 풀어 준다
			sqlRunner.execute("""
					UPDATE mail_smtp SET provider = :provider, host = :host, port = :port, security = :security,
					       username = :username, password_enc = COALESCE(:enc, password_enc),
					       from_addr = :fromAddr, from_name = :fromName, name_with_user = :nameWithUser,
					       bcc_sender = :bccSender, use_yn = :useYn,
					       fail_count = 0, locked_until = NULL, last_error = NULL,
					       _modified = now(), _modifier_id = :uid
					 WHERE id = :id
					""", p);
		}
		clearCache();
	}

	// =================================================================
	// 발송에 쓰는 계정
	// =================================================================

	/** 발송 계정 정보 (from 주소·이름·숨은참조 여부). 설정이 없거나 쓸 수 없으면 사유와 함께 예외 */
	public Account companyAccount() {
		Map<String, Object> row = companyRow();
		if (row == null) throw new IllegalStateException("메일 설정이 없습니다. 시스템관리 > 메일 설정에서 발송 계정을 등록하세요.");
		if (!"Y".equals(row.get("use_yn"))) throw new IllegalStateException("메일 발송이 꺼져 있습니다. 시스템관리 > 메일 설정을 확인하세요.");
		Object lu = row.get("locked_until");
		if (lu instanceof java.sql.Timestamp t && t.after(new Date()))
			throw new IllegalStateException("메일 계정 인증이 연속으로 실패해 " + t.toLocalDateTime().toLocalTime().withNano(0)
					+ " 까지 발송을 멈췄습니다. 메일 설정에서 비밀번호를 확인하세요. (마지막 오류: " + row.get("last_error") + ")");
		if (!StringUtils.hasText((String) row.get("password_enc")))
			throw new IllegalStateException("메일 계정 비밀번호가 없습니다. 시스템관리 > 메일 설정에서 입력하세요.");

		Account a = new Account();
		a.id = (Integer) row.get("id");
		a.fromAddr = (String) row.get("from_addr");
		a.fromName = (String) row.get("from_name");
		a.nameWithUser = "Y".equals(row.get("name_with_user"));
		a.bccSender = "Y".equals(row.get("bcc_sender"));
		a.sender = senderFor(row);
		return a;
	}

	/** 화면 표시용 (발주서 발송 팝업 「보내는 계정」) — 예외 대신 상태로 준다 */
	public Map<String, Object> companyStatus() {
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			Account a = companyAccount();
			out.put("ok", true);
			out.put("from_addr", a.fromAddr);
			out.put("from_name", a.fromName);
			out.put("name_with_user", a.nameWithUser);
			out.put("bcc_sender", a.bccSender);
		} catch (RuntimeException e) {
			out.put("ok", false);
			out.put("message", e.getMessage());
		}
		return out;
	}

	public void markOk(Integer id) {
		if (id == null) return;
		sqlRunner.execute("UPDATE mail_smtp SET fail_count = 0, locked_until = NULL, last_error = NULL, last_ok_at = now() WHERE id = :id",
				new MapSqlParameterSource("id", id));
	}

	/** 인증 실패만 센다 (받는 주소 오류 같은 건 계정 문제가 아니다) */
	public void markFail(Integer id, Throwable e) {
		if (id == null) return;
		String msg = rootMessage(e);
		boolean auth = isAuthFailure(e);
		MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("err", cut(msg, 500))
				.addValue("after", LOCK_AFTER).addValue("min", LOCK_MINUTES);
		if (auth) {
			sqlRunner.execute("""
					UPDATE mail_smtp SET fail_count = fail_count + 1, last_error = :err,
					       locked_until = CASE WHEN fail_count + 1 >= :after THEN now() + make_interval(mins => :min) ELSE locked_until END
					 WHERE id = :id
					""", p);
			clearCache();
		} else {
			sqlRunner.execute("UPDATE mail_smtp SET last_error = :err WHERE id = :id", p);
		}
	}

	public static boolean isAuthFailure(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof javax.mail.AuthenticationFailedException) return true;
			if (t instanceof org.springframework.mail.MailAuthenticationException) return true;
			String m = t.getMessage();
			if (m != null && (m.contains("535") || m.contains("534") || m.toLowerCase().contains("authentication failed"))) return true;
		}
		return false;
	}

	/** 저장 전 시험용: 화면 값으로 바로 연결 (비밀번호가 비어 있으면 저장된 것 사용) */
	public JavaMailSenderImpl senderForTest(Map<String, Object> in) {
		Map<String, Object> row = new HashMap<>();
		row.put("host", str(in.get("host")));
		row.put("port", toInt(in.get("port")));
		row.put("security", str(in.get("security")));
		row.put("username", str(in.get("username")));
		String pw = (String) in.get("password");
		if (StringUtils.hasText(pw)) {
			row.put("plain", pw);
		} else {
			Map<String, Object> saved = companyRow();
			if (saved == null || !StringUtils.hasText((String) saved.get("password_enc")))
				throw new IllegalArgumentException("비밀번호(앱 비밀번호)를 입력하세요.");
			row.put("password_enc", saved.get("password_enc"));
		}
		return build(row);
	}

	// =================================================================
	// 내부
	// =================================================================

	private JavaMailSenderImpl senderFor(Map<String, Object> row) {
		Integer id = (Integer) row.get("id");
		JavaMailSenderImpl s = cachedSender;
		if (s != null && Objects.equals(cachedId, id)) return s;
		s = build(row);
		cachedSender = s;
		cachedId = id;
		return s;
	}

	private JavaMailSenderImpl build(Map<String, Object> row) {
		JavaMailSenderImpl s = new JavaMailSenderImpl();
		s.setHost((String) row.get("host"));
		Integer port = row.get("port") instanceof Number n ? n.intValue() : 465;
		s.setPort(port);
		s.setUsername((String) row.get("username"));
		s.setPassword(row.containsKey("plain") ? (String) row.get("plain") : decrypt((String) row.get("password_enc")));
		s.setDefaultEncoding("UTF-8");
		Properties p = s.getJavaMailProperties();
		p.put("mail.transport.protocol", "smtp");
		p.put("mail.smtp.auth", "true");
		String sec = String.valueOf(row.get("security"));
		if ("ssl".equals(sec)) {
			p.put("mail.smtp.ssl.enable", "true");
			p.put("mail.smtp.ssl.checkserveridentity", "true");
		} else if ("starttls".equals(sec)) {
			p.put("mail.smtp.starttls.enable", "true");
			p.put("mail.smtp.starttls.required", "true");
		}
		p.put("mail.smtp.connectiontimeout", "10000");
		p.put("mail.smtp.timeout", "20000");
		p.put("mail.smtp.writetimeout", "20000");
		return s;
	}

	private Map<String, Object> companyRow() {
		return sqlRunner.getRow("""
				SELECT id, provider, host, port, security, username, password_enc, from_addr, from_name,
				       name_with_user, bcc_sender, use_yn, fail_count, locked_until, last_ok_at, last_error, _modified
				  FROM mail_smtp WHERE scope = 'company'
				""", new MapSqlParameterSource());
	}

	private void clearCache() { cachedSender = null; cachedId = null; }

	private byte[] key() {
		try {
			return MessageDigest.getInstance("SHA-256").digest(secret().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) { throw new IllegalStateException(e); }
	}

	private String encrypt(String plain) {
		try {
			byte[] iv = new byte[12];
			new SecureRandom().nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
		} catch (Exception e) { throw new IllegalStateException("비밀번호 암호화 실패", e); }
	}

	private String decrypt(String enc) {
		if (!StringUtils.hasText(enc)) return null;
		if (!StringUtils.hasText(secret())) throw new IllegalStateException("암호화 키 파일(" + keyFile + ")을 읽지 못해 메일 계정 비밀번호를 풀 수 없습니다.");
		try {
			byte[] all = Base64.getDecoder().decode(enc.startsWith("v1:") ? enc.substring(3) : enc);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
			return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("메일 계정 비밀번호를 풀 수 없습니다. 암호화 키 파일이 바뀌었거나 지워졌으면 메일 설정에서 비밀번호를 다시 저장하세요.");
		}
	}

	public static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		String m = t.getMessage();
		return m == null ? t.getClass().getSimpleName() : m;
	}

	private static String cut(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
	private static String str(Object o) { return o == null ? null : o.toString().trim(); }
	private static Integer toInt(Object o) {
		if (o instanceof Number n) return n.intValue();
		try { return o == null ? null : Integer.valueOf(o.toString().trim()); } catch (NumberFormatException e) { return null; }
	}
	private static String yn(Object o, String def) {
		if (o == null) return def;
		String s = o.toString().trim();
		if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("Y")) return "Y";
		if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("N")) return "N";
		return def;
	}

	/** 발송 계정 */
	public static class Account {
		public Integer id;
		public String fromAddr;
		public String fromName;
		public boolean nameWithUser;
		public boolean bccSender;
		public JavaMailSenderImpl sender;
	}
}