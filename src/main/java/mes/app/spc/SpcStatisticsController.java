package mes.app.spc;

import lombok.extern.slf4j.Slf4j;
import mes.app.spc.Service.SpcStatisticsService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/spc/SPCStatistics")
public class SpcStatisticsController {
	@Autowired
	SpcStatisticsService spcStatisticsService;

	//  로그 폴더
	private static final String BASE_DIR = "C:\\temp\\mes21\\Reflow\\PV";

	@GetMapping("/measureCodes")
	public AjaxResult getMeasureCodes(@RequestParam String process_code) {
		AjaxResult r = new AjaxResult();
		try {
			// tb_spc_std01에서 process_code에 해당하는 measure_code/distinct
			List<Map<String, Object>> rows = spcStatisticsService.getMeasureCodes(process_code);
			r.success = true;
			r.data = rows; // 예: [{value:"O2_PPM", text:"O2농도"}, ...]
		} catch (Exception e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@GetMapping("/spcList")
	public AjaxResult getSPCList(
		@RequestParam String date_from,
		@RequestParam String date_to,
		@RequestParam(value="item_name", required=false) String item_name,
		@RequestParam(value="process_cd") String processCode,
		@RequestParam(value="measure_code") String measureCode,
		@RequestParam(value="recipe", required=false) String recipe,
		@RequestParam(required=false) String spjangcd
	) {
		AjaxResult ar = new AjaxResult();
		try {
			ar.data = spcStatisticsService.getSpcListResult(
				BASE_DIR, spjangcd, date_from, date_to, item_name, processCode, measureCode, recipe
			);
			ar.success = true;
			ar.message = "";
		} catch (Exception e) {
			ar.success = false;
			ar.message = e.getMessage();
			ar.code = "ERR_SPC_LIST";
		}
		return ar;
	}

}
