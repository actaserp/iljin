package mes.app.balju.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * e메일 발주서 발송 이력
 *
 * [조회]  getMailList / getMailDetail  - e메일 발주서 화면
 * [적재]  saveHistory                  - 발주 등록(개별) 화면의 메일 전송에서 호출
 *
 * balju_mail 은 발송 당시 스냅샷을 통째로 들고 있으므로
 * 조회 시 balju_head / company 를 조인하지 않는다.
 * 조인하면 발주가 수정·삭제됐을 때 증빙이 깨진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalJuMailService {

	private final SqlRunner sqlRunner;

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
            FROM balju_mail m
            WHERE m.id = :mailId
              AND (CAST(:spjangcd AS varchar) IS NULL OR m.spjangcd = CAST(:spjangcd AS varchar))
            """;

		List<Map<String, Object>> rows = sqlRunner.getRows(sql, params);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// =================================================================
	// 발송 이력 적재
	//   발주 등록(개별) 화면의 sendBalJuMail 에서 호출한다.
	//   성공/실패 모두 남긴다. 실패 이력이 없으면 "왜 안 왔냐"에 답을 못 한다.
	// =================================================================
	@Transactional
	public Integer saveHistory(Integer bhId,
														 String replyTo,
														 List<String> recipients,
														 String title,
														 String content,
														 String fileName,
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
                , "FileName"
                , "JumunNumber", "CompanyName", "JumunDate", "DueDate", "BaljuTotalPrice"
                , "SendState", "ErrorMessage", "SendDate", "SendUser_id", "SendUserName"
            ) VALUES (
                  :spjangcd, :bhId
                , :replyTo, :recipients, :title, :content
                , :fileName
                , :jumunNumber, :companyName
                , CAST(:jumunDate AS date), CAST(:dueDate AS date), :baljuTotalPrice
                , :sendState, :errorMessage, now(), :userId, :userName
            )
            RETURNING id
            """;

		return sqlRunner.queryForObject(sql, params, (rs, rowNum) -> rs.getInt("id"));
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