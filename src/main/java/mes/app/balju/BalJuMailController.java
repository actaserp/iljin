package mes.app.balju;

import lombok.extern.slf4j.Slf4j;
import mes.app.balju.service.BalJuMailService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * e메일 발주서 (조회 전용)
 *
 * 메일 작성/전송은 [발주 등록(개별)] 화면이 담당한다.
 * 이 화면은 balju_mail 에 남은 발송 이력을 조회만 한다.
 *
 * [주의]
 *  - 상세 본문은 balju_mail."MailContent" 를 그대로 내려준다.
 *    bh_id 로 balju_head 를 다시 조회해 본문을 재구성하면
 *    발주가 수정됐을 때 실제 발송 내용과 화면이 어긋난다.
 *  - 그래서 이 컨트롤러에는 등록/수정/삭제 엔드포인트가 없다.
 *    이력은 발송 시점에만 쌓이고 이후 불변이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/balju/balju_mail")
public class BalJuMailController {

	@Autowired
	BalJuMailService balJuMailService;

	/**
	 * 발송 이력 목록
	 *
	 * @param dateKind  send(발송일) / sales(발주일) / delivery(납기일)
	 * @param sendState success / fail / 공백(전체)
	 * @param keyword   제목 또는 받는사람
	 */
	@GetMapping("/read")
	public AjaxResult getMailList(
		@RequestParam(value = "date_kind", required = false, defaultValue = "send") String dateKind,
		@RequestParam(value = "start", required = false) String start,
		@RequestParam(value = "end", required = false) String end,
		@RequestParam(value = "company", required = false) String company,
		@RequestParam(value = "keyword", required = false) String keyword,
		@RequestParam(value = "send_state", required = false) String sendState,
		@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		AjaxResult result = new AjaxResult();

		List<Map<String, Object>> items =
			balJuMailService.getMailList(dateKind, start, end, company, keyword, sendState, spjangcd);

		result.data = items;
		return result;
	}

	/**
	 * 발송 이력 상세 (본문 포함)
	 */
	@GetMapping("/detail")
	public AjaxResult getMailDetail(
		@RequestParam("mail_id") Integer mailId,
		@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		AjaxResult result = new AjaxResult();

		Map<String, Object> item = balJuMailService.getMailDetail(mailId, spjangcd);

		if (item == null) {
			result.success = false;
			result.message = "발송 이력을 찾을 수 없습니다.";
			return result;
		}

		result.data = item;
		return result;
	}
}