package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.AttachFileResolver;
import mes.app.production.service.PartDrawingService;
import mes.domain.model.AjaxResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

/**
 * 품목 도면 — 목록 · 보기 · 삭제. (업로드는 공통 /api/files/upload)
 *
 * <p>[보기]  공통 다운로드(/api/files/download)는 항상 '첨부'로 내려 보내서 브라우저가 열지 못한다.
 *   여기서는 PDF·이미지를 <b>inline</b> 으로 내려 화면 안에서 바로 보이게 한다.
 *   DWG 등은 브라우저가 못 여니 내려받기로 보낸다.
 *
 * <p>[보안]  첨부 id 를 화면에서 받으므로, 도면(AttachName='drawing')만 열어 준다.
 *   다른 업무의 첨부를 id 만 바꿔 읽지 못하게 한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/part_drawing")
@RequiredArgsConstructor
public class PartDrawingController {

	private final PartDrawingService service;
	private final AttachFileResolver resolver;

	@GetMapping("/list")
	public AjaxResult list(@RequestParam("sujuId") Integer sujuId) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = service.list(sujuId);
		return r;
	}

	@GetMapping("/view")
	public void view(@RequestParam("id") Integer id,
					 @RequestParam(value = "download", defaultValue = "false") boolean download,
					 HttpServletResponse res) throws Exception {
		Map<String, Object> att = service.get(id);
		File f = att == null ? null : resolver.resolve(att);
		if (f == null) {
			res.sendError(404, "도면 파일을 찾을 수 없습니다.");
			return;
		}

		String name = str(att.get("FileName"));
		String ext = str(att.get("ExtName")).toLowerCase(Locale.ROOT).replace(".", "");
		String type = switch (ext) {
			case "pdf" -> "application/pdf";
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "gif" -> "image/gif";
			case "bmp" -> "image/bmp";
			case "webp" -> "image/webp";
			case "svg" -> "application/octet-stream";   // 스크립트를 품을 수 있어 열지 않는다
			default -> "application/octet-stream";
		};
		boolean inline = !download && !"application/octet-stream".equals(type);

		String enc = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
		res.setContentType(type);
		res.setHeader("Content-Disposition", (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + enc);
		res.setHeader("X-Content-Type-Options", "nosniff");
		res.setContentLengthLong(f.length());
		try (InputStream in = Files.newInputStream(f.toPath()); OutputStream out = res.getOutputStream()) {
			in.transferTo(out);
		}
	}

	@PostMapping("/delete")
	@Transactional
	public AjaxResult delete(@RequestBody Map<String, Object> payload) {
		AjaxResult r = new AjaxResult();
		Integer id = toInt(payload.get("id"));
		if (id == null || service.get(id) == null) {
			r.success = false;
			r.message = "삭제할 도면을 찾을 수 없습니다.";
			return r;
		}
		service.delete(id);
		r.success = true;
		return r;
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString().trim();
	}

	private static Integer toInt(Object o) {
		if (o == null || o.toString().isBlank()) return null;
		try {
			return Integer.parseInt(o.toString().trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}