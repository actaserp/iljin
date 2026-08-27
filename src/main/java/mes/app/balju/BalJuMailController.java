package mes.app.balju;

import lombok.extern.slf4j.Slf4j;
import mes.app.balju.service.BalJuMailService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *  - 첨부도 같은 이유로 재생성하지 않는다. 발송 시점에 보관해 둔 파일을
 *    그대로 내려준다. (BalJuMailService.storeMailFile 참조)
 *  - 그래서 이 컨트롤러에는 등록/수정/삭제 엔드포인트가 없다.
 *    이력은 발송 시점에만 쌓이고 이후 불변이다.
 *    /download 는 조회 계열이라 여기 둔다.
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

	/**
	 * 발송 첨부(발주서 엑셀) 다운로드
	 *
	 * 화면은 mail_id 만 넘긴다. 경로를 파라미터로 받으면 서버의 임의 파일을 읽힐 수 있다.
	 * 실제 경로는 balju_mail."FilePath" 에서 읽고, 보관 루트 밖이면 서비스가 걸러낸다.
	 *
	 * 파일 유실·구(舊) 이력(보관 이전 발송분)은 404 로 응답한다.
	 * AjaxResult 로 감싸지 않는 이유: 바이너리 응답이라 AjaxUtil 로 받을 수 없고
	 * 화면에서 location.href 로 직접 연다.
	 */
	@GetMapping("/download")
	public ResponseEntity<Resource> downloadMailFile(
		@RequestParam("mail_id") Integer mailId,
		@RequestParam(value = "spjangcd", required = false) String spjangcd) {

		Map<String, Object> info = balJuMailService.getMailFileInfo(mailId, spjangcd);
		if (info == null) {
			return ResponseEntity.notFound().build();
		}

		Path file = balJuMailService.resolveStoredFile((String) info.get("FilePath"));
		if (file == null) {
			log.warn("첨부 파일 없음. mail_id={}, path={}", mailId, info.get("FilePath"));
			return ResponseEntity.notFound().build();
		}

		// 표시용 파일명은 발송 당시 이름("FileName")을 쓴다.
		// 디스크 저장명에는 중복 방지용 타임스탬프가 붙어 있어 사용자에게 보일 이름이 아니다.
		String downloadName = (String) info.get("FileName");
		if (downloadName == null || downloadName.isBlank()) {
			downloadName = file.getFileName().toString();
		}

		long length;
		try {
			length = Files.size(file);
		} catch (IOException e) {
			log.error("첨부 파일 크기 조회 실패. mail_id={}", mailId, e);
			return ResponseEntity.notFound().build();
		}

		// 한글 파일명은 RFC 5987(filename*) 로 인코딩해야 IE/Edge 외 브라우저에서 깨지지 않는다.
		ContentDisposition disposition = ContentDisposition.builder("attachment")
																			 .filename(downloadName, StandardCharsets.UTF_8)
																			 .build();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentDisposition(disposition);
		headers.setContentType(MediaType.parseMediaType(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
		headers.setContentLength(length);
		headers.setCacheControl("no-cache, no-store, must-revalidate");

		return new ResponseEntity<>(new FileSystemResource(file), headers, org.springframework.http.HttpStatus.OK);
	}
}