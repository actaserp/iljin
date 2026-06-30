package mes.app.spc;

import lombok.extern.slf4j.Slf4j;
import mes.app.spc.Service.SpcCapabilityService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/spc/process_capability")
public class SpcCapabilityController { //공정 능력 분석

	//  로그 폴더
	private static final String BASE_DIR = "C:\\temp\\mes21\\Reflow\\PV";
	@Autowired
	SpcCapabilityService spcCapabilityService;

	@GetMapping("/read")
	public AjaxResult getProcessCapability(
		@RequestParam String date_from,
		@RequestParam String date_to,
		@RequestParam (value = "item_name", required = false)String item_name, //품목명
		@RequestParam (value = "process_cd") String processCode,
		@RequestParam (value = "measure_code")String measure_code,
		@RequestParam (value = "recipe", required = false) String recipe,//레시피명(기판)
		@RequestParam(required = false) String spjangcd
	) {
		log.info("getProcessCapability");
		AjaxResult ar = new AjaxResult();
		try {
			// service는 Object(Map 등) 리턴
			ar.data = spcCapabilityService.getCapabilityResult(
				BASE_DIR, spjangcd, date_from, date_to, item_name, processCode, measure_code, recipe
			);
			ar.success = true;
			ar.message = "";
		} catch (Exception e) {
			ar.success = false;
			ar.message = e.getMessage(); // 필요하면 공통 메시지로
		}
		return ar;
	}

}
