package mes.app.balju;

import lombok.extern.slf4j.Slf4j;
import mes.app.balju.service.BalJuOptimalStockService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/balju/optimal_stock")
public class BalJuOptimalStockController {

  @Autowired
  BalJuOptimalStockService optimalStockService;

  /*
   * 품목그룹 목록(/mat_grp_list) 은 제거했다.
   * 검색조건이 품목그룹 → 품목구분으로 바뀌었고, 품목구분 옵션은 화면에서
   * AjaxUtil.fillSelectOptions(..., 'system_code', ..., 'mat_type') 으로 채운다.
   */

  @GetMapping("/read")
  public AjaxResult getList(@RequestParam(value = "mat_name", required = false) String mat_name,
                            @RequestParam(value = "Inventory_status", required = false) String status,
                            @RequestParam(value = "matType", required = false) String matType,
                            @RequestParam(value = "srchStartDt") String startDt,
                            @RequestParam(value = "srchEndDt") String endDt,
                            @RequestParam(value = "spjangcd") String spjangcd) {
    AjaxResult result = new AjaxResult();
    /*log.info("자재 적정재고 현황 mat_name:{}, Inventory_status:{}, matType:{}, srchStartDt:{}, srchEndDt:{}, spjangcd:{}"
        , mat_name, status, matType, startDt, endDt, spjangcd);*/

    startDt = startDt + " 00:00:00";
    endDt = endDt + " 23:59:59";

    Timestamp start = Timestamp.valueOf(startDt);
    Timestamp end = Timestamp.valueOf(endDt);

    List<Map<String, Object>> items =
      optimalStockService.getList(mat_name, status, matType, start, end, spjangcd);
    result.data = items;
    return result;
  }

}