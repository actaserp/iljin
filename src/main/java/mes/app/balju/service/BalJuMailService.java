package mes.app.balju.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * e메일 발주서 발송 이력
 *
 * [조회]  getMailList / getMailDetail  - e메일 발주서 화면
 * [적재]  saveHistory                  - 발주 등록(개별) 화면의 메일 전송에서 호출
 * [첨부]  storeMailFile / getMailFileInfo / resolveStoredFile
 *
 * balju_mail 은 발송 당시 스냅샷을 통째로 들고 있으므로
 * 조회 시 balju_head / company 를 조인하지 않는다.
 * 조인하면 발주가 수정·삭제됐을 때 증빙이 깨진다.
 *
 * [첨부 보관 정책]
 *  종전에는 첨부 원본을 보관하지 않고 파일명("FileName")만 남겼으나,
 *  "실제로 무엇이 나갔는지" 증빙이 되지 않아 보관하도록 변경했다.
 *  본문과 마찬가지로 발송 시점 스냅샷이므로 조회 시 재생성하지 않는다.
 *  (발주가 수정되면 재생성한 엑셀은 실제 발송본과 달라진다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalJuMailService {

	private final SqlRunner sqlRunner;

	/**
	 * 첨부 보관 루트.
	 * 운영·개발 모두 Windows 경로이며 application.yml 로 덮어쓸 수 있다.
	 * (엑셀 템플릿 경로 C:/Temp/mes21/문서/BaljuTemplate.xlsx 와는 별개)
	 */
	@Value("${mes.balju.mail.file-root:C:/Temp/mes21/iljin}")
	private String fileRoot;

	/** 루트 하위 보관 디렉터리. 다른 업무 파일과 섞이지 않게 분리한다. */
	private static final String STORE_DIR = "balju_mail";

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

	// =================================================================
	// 목록 조회
	// =================================================================
	public List<Map<String, Object>> getMailList(String dateKind,
																							 String start,
																							 String end,
																							 String company,
																							 String keyword,
																							 String sendState,
																							 String spjangcd) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("spjangcd", spjangcd);
		params.addValue("start", start);
		params.addValue("end", end);
		params.addValue("company", like(company));
		params.addValue("keyword", like(keyword));
		params.addValue("sendState", blankToNull(sendState));

		// 기간 필터 컬럼은 화이트리스트로만 결정한다 (파라미터 바인딩 불가 위치)
		//
		// [주의] 종료일 비교가 "<= :end" 가 아니라 "< :end + 1" 이다.
		//        "SendDate" 는 timestamp 라서 <= 로 비교하면 우변이 종료일 00:00:00 으로
		//        승격되고, 그날 낮에 보낸 건이 통째로 누락된다.
		//        date 컬럼("JumunDate"/"DueDate")에도 결과가 같으므로 하나로 맞춘다.
		//        CAST(컬럼 AS date) 로 감싸지 않는 이유는 인덱스를 못 타기 때문.
		String dateColumn = resolveDateColumn(dateKind);

		// [주의] "FilePath" 는 서버 내부 경로이므로 화면으로 내려보내지 않는다.
		//        존재 여부(has_file)와 표시용 파일명만 준다. 다운로드는 mail_id 로만 한다.
		String sql = """
            SELECT m.id                                             AS mail_id
                 , m.bh_id                                          AS bh_id
                 , TO_CHAR(m."SendDate", 'YYYY-MM-DD HH24:MI')      AS "SendDate"
                 , m."JumunNumber"                                  AS "JumunNumber"
                 , m."CompanyName"                                  AS "CompanyName"
                 , m."MailTitle"                                    AS "MailTitle"
                 , m."Recipients"                                   AS "Recipients"
                 , m."ReplyTo"                                      AS "ReplyTo"
                 , TO_CHAR(m."JumunDate", 'YYYY-MM-DD')             AS "JumunDate"
                 , TO_CHAR(m."DueDate", 'YYYY-MM-DD')               AS "DueDate"
                 , m."BaljuTotalPrice"                              AS "BaljuTotalPrice"
                 , m."SendUserName"                                 AS "SendUserName"
                 , m."SendState"                                    AS "SendState"
                 , CASE WHEN m."SendState" = 'fail' THEN '실패'
                        ELSE '성공' END                              AS "SendStateName"
                 , m."ErrorMessage"                                 AS "ErrorMessage"
                 , m."FileName"                                     AS "FileName"
                 , m."FileSize"                                     AS "FileSize"
                 , CASE WHEN m."FilePath" IS NULL THEN 0 ELSE 1 END AS has_file
            FROM balju_mail m
            WHERE (CAST(:spjangcd AS varchar) IS NULL OR m.spjangcd = CAST(:spjangcd AS varchar))
              AND (CAST(:start AS date) IS NULL OR %1$s >= CAST(:start AS date))
              AND (CAST(:end   AS date) IS NULL OR %1$s <  CAST(:end   AS date) + 1)
              AND (CAST(:company AS varchar) IS NULL
                   OR m."CompanyName" ILIKE CAST(:company AS varchar))
              AND (CAST(:keyword AS varchar) IS NULL
                   OR m."MailTitle"   ILIKE CAST(:keyword AS varchar)
                   OR m."Recipients"  ILIKE CAST(:keyword AS varchar)
                   OR m."JumunNumber" ILIKE CAST(:keyword AS varchar))
              AND (CAST(:sendState AS varchar) IS NULL
                   OR m."SendState" = CAST(:sendState AS varchar))
            ORDER BY m."SendDate" DESC, m.id DESC
            """.formatted(dateColumn);

		return sqlRunner.getRows(sql, params);
	}

	// =================================================================
	// 상세 조회 (본문 포함)
	// =================================================================
	public Map<String, Object> getMailDetail(Integer mailId, String spjangcd) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("mailId", mailId);
		params.addValue("spjangcd", spjangcd);

		String sql = """
            SELECT m.id                                             AS mail_id
                 , m.bh_id                                          AS bh_id
                 , TO_CHAR(m."SendDate", 'YYYY-MM-DD HH24:MI:SS')   AS "SendDate"
                 , m."JumunNumber"                                  AS "JumunNumber"
                 , m."CompanyName"                                  AS "CompanyName"
                 , m."MailTitle"                                    AS "MailTitle"
                 , m."MailContent"                                  AS "MailContent"
                 , m."Recipients"                                   AS "Recipients"
                 , m."ReplyTo"                                      AS "ReplyTo"
                 , TO_CHAR(m."JumunDate", 'YYYY-MM-DD')             AS "JumunDate"
                 , TO_CHAR(m."DueDate", 'YYYY-MM-DD')               AS "DueDate"
                 , m."BaljuTotalPrice"                              AS "BaljuTotalPrice"
                 , m."SendUserName"                                 AS "SendUserName"
                 , m."SendState"                                    AS "SendState"
                 , CASE WHEN m."SendState" = 'fail' THEN '실패'
                        ELSE '성공' END                              AS "SendStateName"
                 , m."ErrorMessage"                                 AS "ErrorMessage"
                 , m."FileName"                                     AS "FileName"
                 , m."FileSize"                                     AS "FileSize"
                 , CASE WHEN m."FilePath" IS NULL THEN 0 ELSE 1 END AS has_file
            FROM balju_mail m
            WHERE m.id = :mailId
              AND (CAST(:spjangcd AS varchar) IS NULL OR m.spjangcd = CAST(:spjangcd AS varchar))
            """;

		List<Map<String, Object>> rows = sqlRunner.getRows(sql, params);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// =================================================================
	// 첨부 다운로드용 조회
	//   화면은 mail_id 만 넘긴다. 경로를 화면에서 받으면 임의 파일을 읽힐 수 있다.
	// =================================================================
	public Map<String, Object> getMailFileInfo(Integer mailId, String spjangcd) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("mailId", mailId);
		params.addValue("spjangcd", spjangcd);

		String sql = """
            SELECT m.id            AS mail_id
                 , m."FileName"    AS "FileName"
                 , m."FilePath"    AS "FilePath"
                 , m."FileSize"    AS "FileSize"
            FROM balju_mail m
            WHERE m.id = :mailId
              AND (CAST(:spjangcd AS varchar) IS NULL OR m.spjangcd = CAST(:spjangcd AS varchar))
            """;

		return sqlRunner.getRow(sql, params);
	}

	// =================================================================
	// 발송 이력 적재
	//   발주 등록(개별) 화면의 sendBalJuMail 에서 호출한다.
	//   성공/실패 모두 남긴다. 실패 이력이 없으면 "왜 안 왔냐"에 답을 못 한다.
	//
	//   filePath 는 보관 루트 기준 상대경로다. 루트가 바뀌어도 이력이 살아 있도록
	//   절대경로를 저장하지 않는다.
	// =================================================================
	@Transactional
	public Integer saveHistory(Integer bhId,
														 String replyTo,
														 List<String> recipients,
														 String title,
														 String content,
														 String fileName,
														 String filePath,
														 Long fileSize,
														 String sendState,
														 String errorMessage,
														 String spjangcd,
														 User user) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("bhId", bhId);
		params.addValue("spjangcd", spjangcd);
		params.addValue("replyTo", replyTo);
		params.addValue("recipients", recipients == null ? "" : String.join(",", recipients));
		params.addValue("title", title);
		params.addValue("content", content);
		params.addValue("fileName", fileName);
		params.addValue("filePath", blankToNull(filePath));
		params.addValue("fileSize", fileSize);
		params.addValue("sendState", sendState == null ? "success" : sendState);
		params.addValue("errorMessage", errorMessage);
		params.addValue("userId", user == null ? null : user.getId());
		params.addValue("userName", user == null ? null : user.getUsername());

		// 구매처명·발주일·납기일·발주액은 발주 원본에서 "지금" 값을 읽어 박아둔다.
		// 이후 발주가 수정·삭제돼도 이 행은 변하지 않는다.
		//
		// INSERT ... SELECT 로 한 방에 처리하지 않는 이유:
		// bh_id 가 없으면 0행이 INSERT 되고, RETURNING 도 빈 결과가 되어
		// queryForObject 가 EmptyResultDataAccessException 을 던진다.
		// 메일은 이미 나간 뒤인데 이력 적재에서 터지면 증빙이 통째로 날아간다.
		// 조회를 분리해 두면 발주를 못 찾아도 이력은 남는다.
		Map<String, Object> head = getBaljuHeadSnapshot(bhId);

		params.addValue("companyName",     head == null ? null : head.get("company_name"));
		params.addValue("jumunNumber",     head == null ? null : head.get("jumun_number"));
		params.addValue("jumunDate",       head == null ? null : head.get("jumun_date"));
		params.addValue("dueDate",         head == null ? null : head.get("due_date"));
		params.addValue("baljuTotalPrice", head == null ? 0    : head.get("balju_total_price"));

		String sql = """
            INSERT INTO balju_mail (
                  spjangcd, bh_id
                , "ReplyTo", "Recipients", "MailTitle", "MailContent"
                , "FileName", "FilePath", "FileSize"
                , "JumunNumber", "CompanyName", "JumunDate", "DueDate", "BaljuTotalPrice"
                , "SendState", "ErrorMessage", "SendDate", "SendUser_id", "SendUserName"
            ) VALUES (
                  :spjangcd, :bhId
                , :replyTo, :recipients, :title, :content
                , :fileName, CAST(:filePath AS varchar), CAST(:fileSize AS bigint)
                , :jumunNumber, :companyName
                , CAST(:jumunDate AS date), CAST(:dueDate AS date), :baljuTotalPrice
                , :sendState, :errorMessage, now(), :userId, :userName
            )
            RETURNING id
            """;

		return sqlRunner.queryForObject(sql, params, (rs, rowNum) -> rs.getInt("id"));
	}

	// =================================================================
	// 첨부 보관
	// =================================================================

	/**
	 * 생성된 발주서 엑셀을 보관 루트로 복사한다.
	 *
	 * 저장명에 발송시각(초)을 붙여 재발송 시 이전 건을 덮어쓰지 않게 한다.
	 * (같은 발주를 두 번 보내면 이력은 2건인데 파일이 1건이면 스냅샷이 깨진다)
	 * 같은 초에 겹치면 밀리초를 덧붙인다.
	 *
	 * @return 보관된 파일의 절대경로
	 */
	public Path storeMailFile(Path source, String jumunNumber, String safeCompanyName) throws IOException {

		LocalDateTime now = LocalDateTime.now();

		Path dir = Paths.get(fileRoot, STORE_DIR, now.format(MONTH));
		Files.createDirectories(dir);

		String base = String.format("%s_%s_발주서_%s",
			blankToNull(jumunNumber) == null ? "NONUM" : jumunNumber.trim(),
			blankToNull(safeCompanyName) == null ? "NOCOMP" : safeCompanyName.trim(),
			now.format(STAMP));

		Path target = dir.resolve(base + ".xlsx");
		if (Files.exists(target)) {
			target = dir.resolve(base + "_" + (now.getNano() / 1_000_000) + ".xlsx");
		}

		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	/** 보관 루트 기준 상대경로. 구분자는 '/' 로 통일해 DB 에 넣는다. */
	public String toRelativePath(Path absolute) {
		return Paths.get(fileRoot).toAbsolutePath().normalize()
						 .relativize(absolute.toAbsolutePath().normalize())
						 .toString()
						 .replace('\\', '/');
	}

	/**
	 * DB 에 저장된 상대경로를 실제 파일로 해석한다.
	 * 보관 루트 밖으로 나가는 경로는 거부한다. (DB 값이 오염돼도 임의 파일이 열리지 않도록)
	 *
	 * @return 실제 존재하는 파일. 없거나 루트 밖이면 null
	 */
	public Path resolveStoredFile(String relativePath) {
		if (blankToNull(relativePath) == null) return null;

		Path root = Paths.get(fileRoot).toAbsolutePath().normalize();
		Path file = root.resolve(relativePath).normalize();

		if (!file.startsWith(root)) {
			log.warn("보관 루트를 벗어난 첨부 경로 요청. path={}", relativePath);
			return null;
		}
		return Files.isRegularFile(file) ? file : null;
	}

	/**
	 * 발주 헤더에서 스냅샷용 값만 읽어온다.
	 * 스키마 의존이 이 메서드 한 곳에만 있으므로, 테이블·컬럼명이 바뀌면 여기만 고치면 된다.
	 *
	 * [주의] 발주액은 balju_head 에 컬럼이 없다.
	 *        balju 명세 행의 "TotalAmount" 합계로 계산해야 한다.
	 *        (BaljuOrderService.getBaljuList / getBaljuDetail 과 동일한 산식)
	 */
	private Map<String, Object> getBaljuHeadSnapshot(Integer bhId) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("bhId", bhId);

		String sql = """
            SELECT c."Name"                             AS company_name
                 , bh."JumunNumber"                     AS jumun_number
                 , bh."JumunDate"                       AS jumun_date
                 , bh."DeliveryDate"                    AS due_date
                 , COALESCE(bt.total_amount, 0)         AS balju_total_price
            FROM balju_head bh
            LEFT JOIN company c ON c.id = bh."Company_id"
            LEFT JOIN (
                SELECT "BaljuHead_id"
                     , SUM(COALESCE("TotalAmount", 0)) AS total_amount
                FROM balju
                GROUP BY "BaljuHead_id"
            ) bt ON bt."BaljuHead_id" = bh.id
            WHERE bh.id = :bhId
            """;

		return sqlRunner.getRow(sql, params);
	}

	// =================================================================
	// helper
	// =================================================================

	/** 기간 필터 대상 컬럼. 화이트리스트 밖의 값은 발송일로 강제한다. */
	private String resolveDateColumn(String dateKind) {
		if (dateKind == null) return "m.\"SendDate\"";
		return switch (dateKind) {
			case "sales"    -> "m.\"JumunDate\"";
			case "delivery" -> "m.\"DueDate\"";
			default         -> "m.\"SendDate\"";
		};
	}

	private String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	private String like(String s) {
		String v = blankToNull(s);
		return v == null ? null : "%" + v + "%";
	}
}