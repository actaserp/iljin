package mes.app.transaction.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공휴일 조회 + 영업일 계산.
 *
 *  공공데이터포털 특일정보(getRestDeInfo)를 연 단위로 호출해 메모리에 캐시한다.
 *  DB 에 저장하지 않으므로 서버를 재시작하면 캐시가 비고 다시 호출한다.
 *
 *  ※ 실패 시 빈 값으로 넘어가지 않고 예외를 던진다.
 *     일정 계산에서 공휴일을 누락하면 틀린 날짜가 그대로 저장되고
 *     나중에 아무도 그것이 틀렸다는 것을 알 수 없기 때문이다.
 *     (화면 달력 색칠과 달리 조용히 실패하면 안 되는 계산이다)
 */
@Slf4j
@Service
public class WbsHolidayService {

	private static final String API_URL =
		"http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";

	/** 공공데이터포털 서비스키 (URL 인코딩된 값). application.yml 로 옮길 것 */
	@Value("${holiday.api.service-key:R3P3syhq6qRP0cz8mbV2J5t%2B32WwaSN9sH8ZW4k59oKAU3Ze0WvcljMrN36OJqxs38%2F780hMD4QfhCiDZQNUyA%3D%3D}")
	private String serviceKey;

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

	/** 연도 → 공휴일(yyyyMMdd) 집합 */
	private final Map<Integer, Set<String>> cache = new ConcurrentHashMap<>();

	/* ================= 공개 API ================= */

	/** 해당 날짜가 휴일(주말 또는 공휴일)인가 */
	public boolean isHoliday(LocalDate d) {
		DayOfWeek w = d.getDayOfWeek();
		if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) return true;
		return holidaysOf(d.getYear()).contains(d.format(YMD));
	}

	/** d 가 영업일이면 그대로, 아니면 다음 영업일 */
	public LocalDate adjustToBusinessDay(LocalDate d) {
		LocalDate cur = d;
		for (int i = 0; i < 400; i++) {
			if (!isHoliday(cur)) return cur;
			cur = cur.plusDays(1);
		}
		throw new IllegalStateException("영업일을 찾지 못했습니다: " + d);
	}

	/** d 다음 영업일 (d 자신은 제외) */
	public LocalDate nextBusinessDay(LocalDate d) {
		return adjustToBusinessDay(d.plusDays(1));
	}

	/**
	 * d 로부터 영업일 n 일 뒤.
	 *  n = 0 이면 d 를 영업일로 보정만 한다.
	 */
	public LocalDate addBusinessDays(LocalDate d, int n) {
		LocalDate cur = adjustToBusinessDay(d);
		for (int i = 0; i < n; i++) cur = nextBusinessDay(cur);
		return cur;
	}

	/* ================= 내부 ================= */

	/** 연도별 공휴일 집합. 캐시에 없으면 API 호출 */
	private Set<String> holidaysOf(int year) {
		return cache.computeIfAbsent(year, this::fetchYear);
	}

	private Set<String> fetchYear(int year) {
		Set<String> result = new HashSet<>();
		try {
			// 이 API 는 solMonth 를 생략하면 응답이 불완전할 수 있어 월 단위로 조회한다.
			for (int m = 1; m <= 12; m++) {
				result.addAll(fetchMonth(year, m));
			}
		} catch (Exception e) {
			throw new IllegalStateException(
				"공휴일 정보를 가져오지 못했습니다(" + year + "). 서버에서 apis.data.go.kr 접속이 가능한지 확인하세요.", e);
		}
		log.info("[WBS] {}년 공휴일 {}건 로드", year, result.size());
		return Collections.unmodifiableSet(result);
	}

	private Set<String> fetchMonth(int year, int month) throws Exception {
		String url = API_URL
									 + "?serviceKey=" + serviceKey
									 + "&solYear=" + year
									 + "&solMonth=" + String.format("%02d", month)
									 + "&numOfRows=100";

		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(5000);

		try {
			int status = conn.getResponseCode();
			if (status != 200) {
				throw new IllegalStateException("공휴일 API 응답 코드 " + status);
			}

			Set<String> days = new HashSet<>();
			try (InputStream in = conn.getInputStream()) {
				DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
				f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
				DocumentBuilder b = f.newDocumentBuilder();
				Document doc = b.parse(in);

				NodeList items = doc.getElementsByTagName("item");
				for (int i = 0; i < items.getLength(); i++) {
					Element el = (Element) items.item(i);
					String locdate = text(el, "locdate");
					String isHoliday = text(el, "isHoliday");
					// isHoliday = 'Y' 인 것만 공휴일. (기념일 등은 근무일)
					if (locdate != null && (isHoliday == null || "Y".equalsIgnoreCase(isHoliday))) {
						days.add(locdate.trim());
					}
				}
			}
			return days;
		} finally {
			conn.disconnect();
		}
	}

	private static String text(Element el, String tag) {
		NodeList n = el.getElementsByTagName(tag);
		if (n.getLength() == 0 || n.item(0) == null) return null;
		return n.item(0).getTextContent();
	}
}