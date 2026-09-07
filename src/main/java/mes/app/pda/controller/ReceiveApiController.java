package mes.app.pda.controller;

import mes.app.pda.service.ReceiveApiService;
import mes.app.util.UtilClass;
import mes.domain.entity.Balju;
import mes.domain.entity.Material;
import mes.domain.entity.MaterialInout;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.BujuRepository;
import mes.domain.repository.MatInoutRepository;
import mes.domain.repository.MaterialRepository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static javax.xml.bind.DatatypeConverter.parseDecimal;

@RestController
@RequestMapping("/pda/receive/receive_management")
public class ReceiveApiController {

    @Autowired
    private ReceiveApiService receiveApiService;

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    BujuRepository bujuRepository;

    @Autowired
    SqlRunner sqlRunner;

    @Autowired
    MatInoutRepository matInoutRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/read_balju")
    public AjaxResult getBalJuList(@RequestParam String start_date,
                                   @RequestParam String end_date,
                                   @RequestParam(value = "spjangcd", required = false) String spjangcd,
                                   // 'outsource' = 외작만 / 'balju' = 외작 제외 / 미지정 = 전체.
                                   // 웹 rp_input.html 의 발주입고·외작입고 버튼과 같은 구분이다.
                                   @RequestParam(value = "balju_type", required = false) String baljuType,
                                   HttpServletRequest request

    ){

        if(!start_date.contains("-")){
            start_date = UtilClass.toContainsHyphenDateString(start_date);
        }
        if(!end_date.contains("-")){
            end_date = UtilClass.toContainsHyphenDateString(end_date);
        }

        start_date = start_date + " 00:00:00";
        end_date = end_date + " 23:59:59";

        Timestamp start = Timestamp.valueOf(start_date);
        Timestamp end = Timestamp.valueOf(end_date);

        // spjangcd 는 앱이 세션값을 못 실어 보내던 시절의 하드코딩이 남아 있다.
        // 앱이 보내오면 그 값을 쓰고, 없으면 종전대로 ZZ 를 쓴다.
        String site = (spjangcd == null || spjangcd.isBlank()) ? "ZZ" : spjangcd.trim();

        List<Map<String, Object>> items = this.receiveApiService.getPdaBaljuList(start, end, site, baljuType);

        AjaxResult result = new AjaxResult();
        result.data = items;

        return result;
    }

    @PostMapping("/save_balju")
    @Transactional
    public AjaxResult saveBalju_inout(@RequestBody Map<String, Object> request,
                                      Authentication auth){

        User user = (User) auth.getPrincipal();
        AjaxResult result = new AjaxResult();

        List<Map<String, Object>> baljuList = (List<Map<String, Object>>) request.get("results");


        for (Map<String, Object> item : baljuList) {
            try {
                Integer bal_pk = (Integer) item.get("id");

                // 외작 발주 여부. 목록 쿼리가 b."SujuType" 을 내려준다.
                // 외작일 때만 검사여부를 mat_inout_inspect 에 남긴다 (웹 rp_input 과 같은 규칙).
                boolean isOutsource = "outsource".equals(item.get("SujuType"));

                String description = (String) item.get("Description2");
                if (description == null || description.trim().isEmpty()) {
                    description = isOutsource ? "외작 입고" : "발주 입고";
                }
                String inoutQtyStr = String.valueOf(item.get("inputQty")); // '입고 수량'
                String materialIdStr = String.valueOf(item.get("Material_id"));
                String storeHouseIdStr = String.valueOf(item.get("StoreHouse_id"));

                Integer matPk = Integer.parseInt(materialIdStr);
                BigDecimal qty = parseDecimal(inoutQtyStr);

                MaterialInout mi = new MaterialInout();
                mi.setInoutDate(LocalDate.now());
                mi.setInoutTime(LocalTime.now());
                mi.setMaterialId(matPk);
                mi.setStoreHouseId(Integer.parseInt(storeHouseIdStr));

                Material m = materialRepository.getMaterialById(matPk);
                String testYn = m.getInTestYN() != null ? m.getInTestYN() : "";

                if ("Y".equals(testYn)) {
                    mi.setPotentialInputQty(qty.floatValue());
                    mi.setState("waiting");
                    mi.set_status("t");
                } else {
                    mi.setInputQty(qty.floatValue());
                    mi.setState("confirmed");
                    mi.set_status("a");
                }

                mi.setDescription(description);
                mi.setInOut("in");
                mi.set_audit(user);
                mi.setSourceDataPk(bal_pk);
                mi.setSourceTableName("balju");
                mi.setSpjangcd((String) item.get("spjangcd"));
                mi.setCompanyId((Integer) item.get("Company_id"));

                Balju balju = this.bujuRepository.getBujuById(bal_pk);

                double sujuQty2 = jdbcTemplate.queryForObject("""
					SELECT COALESCE(SUM("InputQty"), 0)
					FROM mat_inout
					WHERE "SourceDataPk" = ? 
					  AND "SourceTableName" = 'balju'
					  AND COALESCE("_status", 'a') = 'a'
				""", Double.class, bal_pk);

                balju.setShipmentState(storeHouseIdStr);
                mi.setInputType("order_in");

                matInoutRepository.save(mi);
                bujuRepository.save(balju);

                // 외작 입고는 업체가 검사하고 보냈는지를 함께 남긴다.
                // 앱이 안 보내면 웹 화면 기본값과 같이 'Y'(검사) 로 둔다.
                if (isOutsource) {
                    Object inspectRaw = item.get("InspectYN");
                    String inspectYn = "N".equals(String.valueOf(inspectRaw)) ? "N" : "Y";
                    saveInspect(mi.getId(), bal_pk, matPk, inspectYn,
                            (String) item.get("spjangcd"), user);
                }

            } catch (Exception e) {
                result.success = false;
                result.message = "처리 중 오류 발생: " + e.getMessage();
                throw new RuntimeException("오류발생 : " + e);
            }
        }


        return result;

    }

    /**
     * 외작 입고 검사여부 저장.
     *
     * MaterialInoutController 의 같은 이름 처리와 동일한 SQL 을 쓴다 —
     * 웹에서 넣은 행과 PDA 에서 넣은 행의 모양이 갈리면
     * 대시보드(DashProjectService)가 두 경로를 다르게 집계하게 된다.
     * projno 는 발주에 연결된 수주에서 끌어온다.
     */
    private void saveInspect(Integer matInoutId, Integer baljuId, Integer materialId,
                             String inspectYn, String spjangcd, User user) {

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("matInoutId", matInoutId);
        p.addValue("baljuId", baljuId);
        p.addValue("materialId", materialId);
        p.addValue("inspectYn", inspectYn);
        p.addValue("spjangcd", spjangcd);
        p.addValue("userId", user == null ? null : user.getId());

        this.sqlRunner.execute("""
            INSERT INTO mat_inout_inspect
                   ("MatInout_id", "Balju_id", "Material_id", "InspectYN", projno,
                    spjangcd, _status, _created, _creater_id)
            SELECT :matInoutId, :baljuId, :materialId, CAST(:inspectYn AS varchar),
                   (SELECT s.project_id
                      FROM balju b
                      JOIN suju s ON s.id = b."PlanDataPk"
                     WHERE b.id = :baljuId AND b."PlanTableName" = 'suju'),
                   CAST(:spjangcd AS varchar), 'a', now(), :userId
            ON CONFLICT ("MatInout_id") DO UPDATE
               SET "InspectYN"   = EXCLUDED."InspectYN",
                   _modified     = now(),
                   _modifier_id  = :userId
            """, p);
    }
}
