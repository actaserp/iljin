package mes.app.sales;

import lombok.extern.slf4j.Slf4j;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 수주 관리 - 엑셀 양식 다운로드.
 *
 * <p>서버 폴더에 놓인 엑셀 양식을 목록으로 보여주고 그대로 내려준다.
 * 파일명을 코드에 박지 않으므로 <b>폴더에 양식을 넣기만 하면 메뉴에 뜬다</b> —
 * 양식이 늘거나 이름이 바뀌어도 코드를 고칠 필요가 없다.
 *
 * <p>★ 폴더는 <b>수주 양식 전용</b>이어야 한다. 상위 {@code 문서} 폴더를 그대로 가리키면
 * 발주서·거래명세서 양식까지 목록에 딸려 나온다 (실제로 그랬다). 화면이 폴더 내용을
 * 그대로 보여주는 구조라, 걸러내는 책임은 폴더를 나누는 쪽에 있다.
 * <pre>
 *   C:\Temp\mes21\문서\수주\제작출도리스트.xlsx
 *   C:\Temp\mes21\문서\수주\공정별_수량집계.xlsx
 * </pre>
 *
 * <p>★ 경로는 <b>WAS 가 도는 서버의 로컬 경로</b>다. 브라우저가 아니라 서버가 읽는다.
 * 개발 PC 에서 되고 서버에서 안 되면 십중팔구 서버에 그 폴더가 없는 것이다.
 * 리눅스 배포라면 기본값이 맞지 않으므로 application.yml 로 덮을 것.
 * <pre>
 *   suju:
 *     template:
 *       dir: /app/mes21/docs/suju
 * </pre>
 *
 * <p>기존 {@code SujuController} 가 {@code /api/sales/suju} 를 점유 중이라
 * 경로를 따로 뒀다. 같은 경로에 얹으면 Ambiguous mapping 으로 기동이 깨진다.
 */
@Slf4j
@RestController
@RequestMapping("/api/sales/sujuTemplate")
public class SujuTemplateController {

	@Value("${suju.template.dir:C:/Temp/mes21/문서/수주}")
	private String templateDir;

	/** 양식으로 인정할 확장자. 폴더에 섞인 다른 파일은 목록에 넣지 않는다 */
	private static final Set<String> ALLOW_EXT = Set.of("xlsx", "xls");

	private static final DateTimeFormatter YMD =
		DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

	/**
	 * 양식 목록.
	 *
	 *  폴더가 없거나 비었으면 빈 배열을 돌려준다. 화면은 "내려받을 양식이 없습니다"
	 *  로만 표시한다 — 서버 경로를 화면에 노출하지 않는다.
	 */
	@GetMapping("/list")
	public AjaxResult list() {
		AjaxResult result = new AjaxResult();
		result.data = listFiles();
		result.success = true;
		return result;
	}

	/**
	 * 양식 내려받기.
	 *
	 *  ★ name 은 반드시 <b>목록에 실제로 있는 파일명</b>이어야 한다.
	 *    받은 문자열로 경로를 조립하면 {@code ../../application.yml} 같은 값이
	 *    그대로 통한다. 폴더를 다시 읽어 대조하는 방식이라 조립 자체를 하지 않는다.
	 */
	@GetMapping("/download")
	public ResponseEntity<FileSystemResource> download(@RequestParam("name") String name) {
		if (name == null || name.trim().isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		String target = name.trim();

		Path found = null;
		for (Map<String, Object> f : listFiles()) {
			if (target.equals(f.get("name"))) {
				found = Paths.get(String.valueOf(f.get("path")));
				break;
			}
		}
		if (found == null || !Files.isReadable(found)) {
			log.warn("[양식] 요청한 파일이 폴더에 없습니다: {}", target);
			return ResponseEntity.notFound().build();
		}

		String fileName = found.getFileName().toString();

		// 한글 파일명은 그냥 넣으면 브라우저가 깨뜨린다.
		//  filename* (RFC 5987) 를 쓰고, 못 읽는 브라우저용으로 filename 도 같이 준다.
		String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
		String disposition = "attachment; filename=\"" + encoded + "\"; "
													 + "filename*=UTF-8''" + encoded;

		MediaType type = fileName.toLowerCase(Locale.ROOT).endsWith(".xls")
											 ? MediaType.parseMediaType("application/vnd.ms-excel")
											 : MediaType.parseMediaType(
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

		long len;
		try {
			len = Files.size(found);
		} catch (IOException e) {
			log.warn("[양식] 파일 크기를 읽지 못했습니다: {}", found, e);
			return ResponseEntity.notFound().build();
		}

		return ResponseEntity.ok()
						 .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
						 .contentType(type)
						 .contentLength(len)
						 .body(new FileSystemResource(found));
	}

	/* ================= 내부 ================= */

	/** 폴더의 엑셀 파일 목록. 폴더가 없으면 빈 리스트 */
	private List<Map<String, Object>> listFiles() {
		List<Map<String, Object>> rows = new ArrayList<>();

		Path dir;
		try {
			dir = Paths.get(templateDir).normalize();
		} catch (Exception e) {
			log.warn("[양식] 경로 설정이 잘못됐습니다: {}", templateDir, e);
			return rows;
		}

		if (!Files.isDirectory(dir)) {
			// 배포 환경마다 흔히 나는 상황이라 error 가 아니라 warn 으로 남긴다
			log.warn("[양식] 폴더가 없습니다: {} (suju.template.dir 확인. 수주 양식 전용 폴더여야 한다)", dir);
			return rows;
		}

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				if (!Files.isRegularFile(p)) continue;

				String fileName = p.getFileName().toString();
				int dot = fileName.lastIndexOf('.');
				if (dot < 0) continue;
				String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
				if (!ALLOW_EXT.contains(ext)) continue;
				// 엑셀이 열어둔 임시파일(~$xxx.xlsx)이 목록에 뜨는 것을 막는다
				if (fileName.startsWith("~$")) continue;

				long size = Files.size(p);
				Map<String, Object> row = new HashMap<>();
				row.put("name", fileName);
				row.put("path", p.toString());
				row.put("size", size);
				row.put("size_text", humanSize(size));
				row.put("modified", YMD.format(Files.getLastModifiedTime(p).toInstant()));
				rows.add(row);
			}
		} catch (IOException e) {
			log.warn("[양식] 폴더를 읽지 못했습니다: {}", dir, e);
			return new ArrayList<>();
		}

		rows.sort(Comparator.comparing(r -> String.valueOf(r.get("name"))));
		return rows;
	}

	private static String humanSize(long bytes) {
		if (bytes < 1024) return bytes + "B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
		return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
	}
}