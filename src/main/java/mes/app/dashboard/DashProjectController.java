package mes.app.production;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.production.service.DashProjectService;
import mes.domain.model.AjaxResult;
import org.springframework.web.bind.annotation.*;

/**
 * 프로젝트 현황 대시보드 (읽기 전용)
 *
 *  화면: dash_project.html / dash_project_kind.html
 *  두 화면이 <b>같은 데이터 구조</b>를 쓰므로 API 하나가 둘 다 공급한다.
 *
 * [경로] /api/production/dash_project
 *   기존 DashboardProjectService 와 이름이 비슷하지만 별개다.
 *   그쪽은 job_res 기반 표준 대시보드이고, 이쪽은 가공/조립/검사를 합친 현장 모니터링용이다.
 *
 * [읽기 전용]
 *   POST 엔드포인트가 없다. 대시보드는 벽걸이 모니터에 띄우는 화면이라
 *   조작이 들어가면 안 된다.
 *
 * [숫자의 성격]
 *   parts.kinds 의 t/d 는 참값이다.
 *   item.made 는 가공 진척을 유닛으로 <b>환산한 추정값</b>이며 실제보다 낙관적이다.
 *   자세한 이유는 DashProjectService 주석 참조.
 */
@Slf4j
@RestController
@RequestMapping("/api/production/dash_project")
@RequiredArgsConstructor
public class DashProjectController {

	private final DashProjectService dashProjectService;

	/**
	 * 대시보드 전체 데이터.
	 *
	 * 화면이 기대하는 중첩 구조(프로젝트 → 라인 → 품목)를 그대로 내려준다.
	 * projNo 를 주면 그 프로젝트만, 비우면 진행중 전체.
	 */
	@GetMapping("/summary")
	public AjaxResult summary(@RequestParam("spjangcd") String spjangcd,
							  @RequestParam(value = "projNo", required = false) String projNo) {
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = dashProjectService.getDashboard(spjangcd, projNo);
		return result;
	}
}