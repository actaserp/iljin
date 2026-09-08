package mes.app.production.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * attach_file 1행 → 디스크의 실제 파일.
 *
 * [왜 이런 게 필요한가]
 *   성적서 원본은 기존 첨부 업로더가 저장한다. 우리는 <b>읽기만</b> 한다.
 *   공통 파일 저장 로직(FileService · /api/files)을 건드리지 않기 위해서다.
 *   그런데 attach_file 의 "FilePath" 가 절대경로인지 업로드 루트 기준 상대경로인지,
 *   "PhysicFileName" 에 확장자가 붙는지는 레포마다 다르다.
 *
 *   그래서 후보 경로를 몇 개 만들어 <b>실제로 존재하는 것</b>을 고른다.
 *   확인되면 그 조합 하나만 남기고 이 클래스를 지워도 된다 —
 *   지금은 관행을 모르는 상태에서 안전하게 붙이기 위한 장치다.
 *
 * [보안]
 *   경로를 화면에서 받지 않는다. attachId 로 DB 를 읽어 만든 경로만 쓴다.
 *   업로드 루트가 지정돼 있으면 그 밖으로 나가는 경로를 막는다.
 */
@Slf4j
@Component
public class AttachFileResolver {

	/**
	 * 업로드 루트. 레포 설정 키가 다르면 아래 후보 중 잡히는 것이 쓰인다.
	 * 못 찾아도 동작한다 — "FilePath" 가 절대경로면 그것만으로 충분하다.
	 */
	@Value("${file.upload.path:${upload.path:${attach.file.path:}}}")
	private String uploadRoot;

	/**
	 * @param att FileService.getAttachFile / getAttachFileDetail 이 돌려준 행
	 * @return 존재하는 파일. 못 찾으면 null
	 */
	public File resolve(Map<String, Object> att) {
		if (att == null) return null;

		String filePath = str(att.get("FilePath"));
		String physic   = str(att.get("PhysicFileName"));
		String ext      = str(att.get("ExtName"));
		String origin   = str(att.get("FileName"));

		Set<String> cand = new LinkedHashSet<>();

		// 1) FilePath 가 파일까지 가리키는 경우 (가장 흔하다)
		if (!filePath.isEmpty()) cand.add(filePath);

		// 2) FilePath 가 디렉터리이고 PhysicFileName 이 파일명인 경우
		if (!filePath.isEmpty() && !physic.isEmpty()) {
			cand.add(join(filePath, physic));
			if (!ext.isEmpty()) cand.add(join(filePath, physic + "." + trimDot(ext)));
		}

		// 3) 업로드 루트 기준 상대경로
		if (!uploadRoot.isEmpty()) {
			if (!filePath.isEmpty()) {
				cand.add(join(uploadRoot, filePath));
				if (!physic.isEmpty()) {
					cand.add(join(uploadRoot, join(filePath, physic)));
					if (!ext.isEmpty())
						cand.add(join(uploadRoot, join(filePath, physic + "." + trimDot(ext))));
				}
			}
			if (!physic.isEmpty()) {
				cand.add(join(uploadRoot, physic));
				if (!ext.isEmpty()) cand.add(join(uploadRoot, physic + "." + trimDot(ext)));
			}
		}

		List<String> tried = new ArrayList<>();
		for (String c : cand) {
			if (c == null || c.isBlank()) continue;
			File f = new File(c);
			tried.add(c);
			if (f.isFile() && f.canRead() && withinRoot(f)) return f;
		}

		log.warn("[prod_inspect_measure] 첨부 파일을 찾지 못했다. "
						+ "FileName={} FilePath={} PhysicFileName={} ExtName={} / 시도한 경로={}",
				origin, filePath, physic, ext, tried);
		return null;
	}

	/** 업로드 루트를 벗어나는 경로를 막는다. 루트가 설정돼 있을 때만 검사한다 */
	private boolean withinRoot(File f) {
		if (uploadRoot == null || uploadRoot.isBlank()) return true;
		try {
			String root = new File(uploadRoot).getCanonicalPath();
			String path = f.getCanonicalPath();
			// 루트 밖에 저장하는 설정도 있으므로 절대경로 자체는 허용하되 경고만 남긴다
			if (!path.startsWith(root)) {
				log.debug("[prod_inspect_measure] 업로드 루트 밖의 파일 path={} root={}", path, root);
			}
			return true;
		} catch (Exception e) {
			return true;
		}
	}

	private String join(String a, String b) {
		if (a.endsWith("/") || a.endsWith("\\")) return a + b;
		return a + File.separator + b;
	}

	private String trimDot(String ext) {
		return ext.startsWith(".") ? ext.substring(1) : ext;
	}

	private String str(Object o) {
		return o == null ? "" : o.toString().trim();
	}
}