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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

	/** 도면 검색 — 키오스크. q: 도면번호 일부('01-03', '0103'), projNo: 없으면 진행 중 프로젝트 전체 */
	@GetMapping("/search")
	public AjaxResult search(@RequestParam(value = "q", defaultValue = "") String q,
							 @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult r = new AjaxResult();
		r.success = true;
		r.data = service.search(q, projNo);
		return r;
	}

	/*
	 * ===== 원본(DWG/DXF) 열기 — PC 의 도면 뷰어로 =====
	 *
	 * 브라우저로 내려받으면 '다운로드' 폴더에 복사본이 쌓인다. 그래서 키오스크·사무실 PC 에
	 * 'iljindwg:' 링크 처리기(open_dwg.ps1)를 깔아 두고, 처리기가 임시 폴더에 받아 뷰어로 연다.
	 * 처리기에는 로그인 세션이 없으므로 한 번 쓰고 버리는 표를 준다.
	 *   1) 화면(로그인됨)  GET /ticket?id=   → {token, name}
	 *   2) 처리기(비로그인) GET /file?t=token → 파일. 표는 한 번만, 2분 안에만 쓸 수 있다
	 * /file 은 SecurityConfiguration 에서 permitAll. 표 없이는 아무것도 못 받는다.
	 */
	private static final long TICKET_MS = 120_000L;
	private static final Map<String, long[]> TICKETS = new ConcurrentHashMap<>();   // token → {attachId, 만료시각}

	@GetMapping("/ticket")
	public AjaxResult ticket(@RequestParam("id") Integer id) {
		AjaxResult r = new AjaxResult();
		Map<String, Object> att = service.get(id);
		if (att == null) {
			r.success = false;
			r.message = "도면을 찾을 수 없습니다.";
			return r;
		}
		long now = System.currentTimeMillis();
		TICKETS.entrySet().removeIf(e -> e.getValue()[1] < now);   // 오래된 표 정리
		String token = UUID.randomUUID().toString().replace("-", "");
		TICKETS.put(token, new long[]{id, now + TICKET_MS});
		r.success = true;
		r.data = Map.of("token", token, "name", str(att.get("FileName")));
		return r;
	}

	@GetMapping("/file")
	public void file(@RequestParam("t") String token, HttpServletResponse res) throws Exception {
		long[] t = token == null ? null : TICKETS.remove(token);
		if (t == null || t[1] < System.currentTimeMillis()) {
			res.sendError(404, "만료된 요청입니다. 화면에서 다시 누르세요.");
			return;
		}
		view((int) t[0], true, res);
	}

	/*
	 * 처리기 설치 파일 (.reg) — 설치 프로그램 없이 '레지스트리 등록' 한 번으로 끝낸다.
	 *   [원본 열기] 가 안 열리는 PC 는 화면 안내의 [처음이면 설치] 로 이걸 받아 열고 '예' 만 누르면 된다.
	 *   관리자 권한이 필요 없는 HKCU(그 Windows 계정)에 'iljindwg:' 링크 처리기를 등록한다.
	 *   처리기 본문(PowerShell 한 줄)도 레지스트리 값(Script)에 넣어 파일을 하나도 안 남긴다.
	 *   허용 서버 = 이 파일을 받은 서버 주소. 서버 주소가 바뀌면 다시 받아 열면 된다.
	 * 처리기가 하는 일: 받은 링크가 이 서버의 /file 이고 DWG/DXF 일 때만 %TEMP%\IljinDwg 에 받아
	 *   .dwg/.dxf 에 연결된 뷰어로 연다. 하루 지난 임시 파일은 지운다.
	 */
	private static final String HANDLER_PS = "$ProgressPreference='SilentlyContinue';$l=$args[0];$h=@('__HOST__','localhost');try{$q=$l.Substring($l.IndexOf('?')+1);$kv=@{};foreach($p in $q.Split('&')){$i=$p.IndexOf('=');if($i -gt 0){$kv[$p.Substring(0,$i)]=[Uri]::UnescapeDataString($p.Substring($i+1))}};$u=[Uri]$kv['u'];if($h -notcontains $u.Host){exit};if(-not $u.AbsolutePath.EndsWith('/api/production/part_drawing/file')){exit};$n=[IO.Path]::GetFileName([string]$kv['n']) -replace '[\\\\/:*?<>|]','_';if(-not $n){$n='drawing.dwg'};$e=[IO.Path]::GetExtension($n).ToLower();if(@('.dwg','.dxf') -notcontains $e){exit};$d=Join-Path $env:TEMP 'IljinDwg';New-Item -ItemType Directory -Force -Path $d|Out-Null;Get-ChildItem $d -File|Where-Object{$_.LastWriteTime -lt (Get-Date).AddDays(-1)}|Remove-Item -Force -ErrorAction SilentlyContinue;$o=Join-Path $d $n;if(Test-Path $o){try{Remove-Item $o -Force -ErrorAction Stop}catch{$o=Join-Path $d ([IO.Path]::GetFileNameWithoutExtension($n)+'_'+(Get-Date -Format HHmmss)+$e)}};(New-Object Net.WebClient).DownloadFile($u.AbsoluteUri,$o);Start-Process -FilePath $o}catch{Add-Type -AssemblyName PresentationFramework;[System.Windows.MessageBox]::Show('원본을 열지 못했습니다. 화면에서 다시 눌러 주세요. '+$_.Exception.Message,'도면 원본 열기')|Out-Null}";

	@GetMapping("/installer")
	public void installer(javax.servlet.http.HttpServletRequest req, HttpServletResponse res) throws Exception {
		String host = req.getServerName().replaceAll("[^A-Za-z0-9.\\-]", "");
		String script = HANDLER_PS.replace("__HOST__", host);
		String cmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "
				+ "\"& ([scriptblock]::Create((Get-ItemProperty 'HKCU:\\Software\\Classes\\iljindwg').Script)) '%1'\"";
		String reg = "Windows Registry Editor Version 5.00\r\n\r\n"
				+ "[HKEY_CURRENT_USER\\Software\\Classes\\iljindwg]\r\n"
				+ "@=\"URL:Iljin MES DWG\"\r\n"
				+ "\"URL Protocol\"=\"\"\r\n"
				+ "\"Script\"=\"" + regEsc(script) + "\"\r\n\r\n"
				+ "[HKEY_CURRENT_USER\\Software\\Classes\\iljindwg\\shell\\open\\command]\r\n"
				+ "@=\"" + regEsc(cmd) + "\"\r\n";
		byte[] body = ("\uFEFF" + reg).getBytes(StandardCharsets.UTF_16LE);   // .reg 는 UTF-16LE + BOM 이어야 한글이 안 깨진다
		res.setContentType("application/octet-stream");
		res.setHeader("Content-Disposition", "attachment; filename=\"iljin_dwg_open.reg\"");
		res.setContentLength(body.length);
		res.getOutputStream().write(body);
	}

	/** .reg 문자열 값 이스케이프: \ → \\ , " → \" */
	private static String regEsc(String v) {
		return v.replace("\\", "\\\\").replace("\"", "\\\"");
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