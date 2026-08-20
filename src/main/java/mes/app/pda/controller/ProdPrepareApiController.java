package mes.app.pda.controller;

import mes.app.pda.service.ProdPrepareApiService;
import mes.domain.entity.JobRes;
import mes.domain.entity.MaterialProcessInputRequest;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.JobResRepository;
import mes.domain.repository.MaterialProcessInputRequestReposotory;
import mes.domain.repository.MaterialRepository;
import mes.domain.repository.StorehouseRepository;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pda/production/prod_prepare")
public class ProdPrepareApiController {

    @Autowired
    private ProdPrepareApiService prodPrepareApiService;

    @Autowired
    JobResRepository jobResRepository;

    @Autowired
    StorehouseRepository storehouseRepository;

    @Autowired
    MaterialRepository materialRepository;

    @Autowired
    MaterialProcessInputRequestReposotory materialProcessInputRequestReposotory;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    SqlRunner sqlRunner;

    /**
     * 작업지시 목록 조회
     */
    @GetMapping("/job_order_list")
    public AjaxResult jobOrderList(
            @RequestParam(value = "data_date", required = false) String dataDate,
            @RequestParam(value = "workcenter_pk", required = false) Integer workCenterPk,
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {

        List<Map<String, Object>> items = prodPrepareApiService.jobOrderList(dataDate, workCenterPk, spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    /**
     * 소요 자재 목록 조회
     */
    @GetMapping("/bom_detail_list")
    public AjaxResult bomDetailList(
            @RequestParam(value = "jr_pks", required = false) String jrPks,
            @RequestParam(value = "data_date", required = false) String dataDate) {

        List<Map<String, Object>> items = prodPrepareApiService.bomDetailList(jrPks, dataDate);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    /**
     * 자재 공정 투입 저장
     */
    @PostMapping("/save_mat_proc_input")
    @Transactional
    public AjaxResult saveMatProcInput(
            @RequestBody Map<String, Object> payload,
            Authentication auth) {

        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();

        String jobresPks = payload.get("jobres_pks").toString();
        String matPks = payload.get("mat_pks").toString();
        String inputReqQtys = payload.get("input_req_qtys").toString();
        String dataDate = payload.get("data_date").toString();

        // 공정창고 체크
        Integer procStorehouseCount = storehouseRepository.countByHouseType("process");
        if (procStorehouseCount == 0) {
            result.success = false;
            result.message = "공정창고로 지정된 창고가 없습니다.";
            return result;
        }

        // 창고 지정 체크
        String[] arrMats = matPks.split(",");
        for (String id : arrMats) {
            Integer storehouseNullCount = materialRepository.countByIdAndStoreHouseIdIsNull(Integer.parseInt(id));
            if (storehouseNullCount > 0) {
                result.success = false;
                result.message = "창고지정이 안되어 있는 품목이 있습니다.";
                return result;
            }
        }

        transactionTemplate.executeWithoutResult(status -> {
            try {
                MaterialProcessInputRequest mpReq = new MaterialProcessInputRequest();
                mpReq.setRequestDate(CommonUtil.tryTimestamp(dataDate));
                mpReq.setRequesterId(user.getId());
                mpReq.set_audit(user);
                mpReq = materialProcessInputRequestReposotory.save(mpReq);

                Integer reqPk = mpReq.getId();
                String[] arrJobresPk = jobresPks.split(",");

                for (String pk : arrJobresPk) {
                    JobRes jr = jobResRepository.getJobResById(Integer.parseInt(pk));
                    if (jr != null) {
                        jr.setMaterialProcessInputRequestId(reqPk);
                        jr.set_audit(user);
                        jobResRepository.save(jr);
                    }
                }

                MapSqlParameterSource paramMap = new MapSqlParameterSource();
                paramMap.addValue("req_pk", reqPk);
                paramMap.addValue("mat_pks", matPks);
                paramMap.addValue("req_qtys", inputReqQtys);
                paramMap.addValue("user_id", user.getId());

                String sql = """
                    insert into mat_proc_input("MaterialProcessInputRequest_id", "Material_id", "RequestQty", "MaterialStoreHouse_id", "ProcessStoreHouse_id", "State", _created, _creater_id)
                    with A as (
                        select unnest(string_to_array(:mat_pks, ','))::int as mat_pk
                        , unnest(string_to_array(:req_qtys, ','))::float as requ_qty
                    ), B as (
                        select id as proc_house_pk
                        from store_house sh
                        where "HouseType" = 'process'
                        limit 1
                    )
                    select :req_pk, A.mat_pk, A.requ_qty, m."StoreHouse_id", B.proc_house_pk, 'requested', now(), :user_id
                    from A
                    inner join material M on M.id = A.mat_pk
                    inner join B on 1 = 1
                    """;

                sqlRunner.execute(sql, paramMap);
                result.data = reqPk;

            } catch (Exception ex) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                result.success = false;
                result.message = "처리 중 오류 발생: " + ex.getMessage();
            }
        });

        return result;
    }

    /**
     * 워크센터 목록 (PDA 콤보용)
     */
    @GetMapping("/workcenter_list")
    public AjaxResult workcenterList(
            @RequestParam(value = "spjangcd", required = false) String spjangcd) {

        List<Map<String, Object>> items = prodPrepareApiService.getWorkcenterList(spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }
}
