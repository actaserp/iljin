package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.ProjectRegistrationServicr;
import mes.app.transaction.service.WbsPlanService;
import mes.domain.entity.TB_DA003;
import mes.domain.entity.TB_DA003Id;
import mes.domain.entity.User;
import mes.domain.entity.iljin.tb_da003_stage;
import mes.domain.entity.iljin.tb_da003_stage_id;
import mes.domain.model.AjaxResult;
import mes.domain.repository.ProjectRepository;
import mes.domain.repository.iljin.ProjectStageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/transaction/projectRegistration")
public class ProjectRegistrationController {  //프로젝트 관리

  @Autowired
  ProjectRegistrationServicr projectRegistrationServicr;

  @Autowired
  ProjectRepository projectRepository;

  @Autowired
  ProjectStageRepository projectStageRepository;

  @Autowired
  WbsPlanService wbsPlanService;

  /** 담당자 드롭다운용 인원 목록 */
  @GetMapping("/personOptions")
  public AjaxResult getPersonOptions(
    @RequestParam(value = "workcenter_id", required = false) Integer workcenterId) {
    AjaxResult result = new AjaxResult();
    result.data = this.wbsPlanService.getPersonOptions(workcenterId);
    result.success = true;
    return result;
  }

  /** 진행단계의 WBS 연결 드롭다운용 목록 */
  @GetMapping("/wbsStageOptions")
  public AjaxResult getWbsStageOptions(@RequestParam("projno") String projno,
                                       @RequestParam("spjangcd") String spjangcd) {
    AjaxResult result = new AjaxResult();
    result.data = this.wbsPlanService.getStageOptions(spjangcd, projno);
    result.success = true;
    return result;
  }

  @GetMapping("/read")
  public AjaxResult getProjectList(@RequestParam(value = "srchStartDt") String srchStartDt,
                                   @RequestParam(value = "srchEndDt") String srchEndDt,
                                   @RequestParam(value ="spjangcd") String spjangcd,
                                   @RequestParam(value = "cboCompany", required = false) String cboCompany,
                                   @RequestParam(value = "txtDescription") String txtDescription,
                                   HttpServletRequest request) {
    srchStartDt = formatDate8(srchStartDt);
    srchEndDt = formatDate8(srchEndDt);
    List<Map<String, Object>> items = this.projectRegistrationServicr.getProjectList(srchStartDt, srchEndDt, spjangcd, cboCompany, txtDescription);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  // 진행단계 조회
  @GetMapping("/readStages")
  public AjaxResult getStageList(@RequestParam("projno") String projno,
                                 @RequestParam("spjangcd") String spjangcd) {
    List<Map<String, Object>> items = this.projectRegistrationServicr.getStageList(spjangcd, projno);

    AjaxResult result = new AjaxResult();
    result.data = items;
    return result;
  }

  //저장
  @PostMapping("/save")
  @Transactional
  public AjaxResult ProjectListSave(@RequestBody Map<String, Object> params,
                                    Authentication auth) {
    User user = (auth == null) ? null : (User) auth.getPrincipal();

    AjaxResult result = new AjaxResult();

    // ===== 기본 필드 추출 =====
    String projno   = asStr(params.get("projno"));
    String projnm   = asStr(params.get("projnm"));
    String balcltnm = asStr(params.get("balcltnm"));
    Integer balcltcd = asInt(params.get("balcltcd"));
    String stdate   = formatDate8(asStr(params.get("stdate")));
    String eddate   = formatDate8(asStr(params.get("eddate")));
    String contdate = formatDate8(asStr(params.get("contdate")));
    String remark   = asStr(params.get("remark"));
    String spjangcd = asStr(params.get("spjangcd"));

    // ===== 진행단계 배열 추출 =====
    //  ★ stages 키가 아예 없으면 진행단계를 건드리지 않는다.
    //    진행단계는 이제 WbsPlanService.syncStagesFromWbs 가 WBS 마일스톤을 요약해
    //    자동으로 써넣는다. 화면은 읽기전용이라 stages 를 보내지 않는데,
    //    여기서 빈 배열로 치환해버리면 saveStages 의 delete-all 이
    //    <b>자동 생성된 단계를 통째로 지운다.</b>
    //    (빈 배열을 명시적으로 보내는 호출은 "전부 지워라" 로 그대로 존중한다)
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stages = params.containsKey("stages")
                                         ? (List<Map<String, Object>>) params.get("stages")
                                         : null;

    String targetProjNo; // 저장 대상 프로젝트번호 (신규/수정 공통)

    // 1. 신규 등록
    if (projno == null || projno.trim().isEmpty()) {
      targetProjNo = generateNewProjectNo();
      TB_DA003 newProject = new TB_DA003();

      newProject.setProjnm(projnm);
      newProject.setBalcltnm(balcltnm);
      newProject.setBalcltcd(balcltcd);
      newProject.setStdate(stdate);
      newProject.setEddate(eddate);
      newProject.setContdate(contdate);
      newProject.setRemark(remark);
      newProject.setId(new TB_DA003Id(spjangcd, targetProjNo));

      this.projectRepository.save(newProject);
      result.success = true;
      result.message = "신규 저장 완료";
      result.data = targetProjNo;
    }
    // 2. 기존 데이터 수정
    else {
      Optional<TB_DA003> optional = this.projectRepository.findById(new TB_DA003Id(spjangcd, projno));
      if (optional.isPresent()) {
        TB_DA003 existing = optional.get();
        existing.setProjnm(projnm);
        existing.setBalcltnm(balcltnm);
        existing.setBalcltcd(balcltcd);
        existing.setStdate(stdate);
        existing.setEddate(eddate);
        existing.setContdate(contdate);
        existing.setRemark(remark);

        this.projectRepository.save(existing);
        targetProjNo = projno;
        result.success = true;
        result.message = "수정 완료";
      } else {
        result.success = false;
        result.message = "수정 실패: 해당 프로젝트 없음";
        return result;
      }
    }

    // ===== 진행단계 저장 (delete-all + insert-all) =====
    if (stages != null) {
      saveStages(spjangcd, targetProjNo, stages);

      // ★ 진행단계의 완료 상태를 연결된 WBS 세부단계에 반영한다 (양방향).
      //   완료를 누르면 wbs_plan.ac_eddate 가 채워지고, 해제하면 비워진다.
      //   wbs_plan_id 가 없는 행은 건드리지 않는다.
      this.wbsPlanService.applyStageCompletion(spjangcd, targetProjNo, user);

      // ★ 자동 채움 단계(2D 출도)를 수주 도면출도일로 다시 맞춘다.
      //   applyStageCompletion 다음에 불러야 자동값이 이긴다 — 손으로 고친 값은
      //   도면출도일이 바뀔 때까지만 유효하다는 규칙이 여기서 지켜진다.
      //   수주 저장 때만 돌게 두면, 진행단계를 고치는 이 화면에서 저장해도
      //   2D 출도가 갱신되지 않아 영원히 빈칸으로 남는다.
      this.wbsPlanService.syncDrawDateForProject(spjangcd, targetProjNo, user);
    }

    return result;
  }

  /**
   * 진행단계 완료 즉시저장. 저장 버튼을 거치지 않는다.
   *
   *  화면의 [완료] 칩을 누르면 이 경로로 들어온다.
   *  tb_da003_stage 를 1행 고치고, 그 결과를 wbs_plan 에 반영한다.
   *
   *  ★ syncDrawDateForProject 는 <b>부르지 않는다.</b>
   *    그쪽은 auto_source='draw_date' 행의 ac_eddate 를 수주 도면출도일 MAX 로
   *    되돌리므로, 여기서 부르면 2D 출도 행은 완료를 눌러도 같은 트랜잭션 안에서
   *    원복돼 "눌렀는데 아무 일도 안 일어나는" 화면이 된다.
   *
   *    다만 /save 와 수주 저장은 여전히 자동값이 이긴다. 같은 행이 경로에 따라
   *    다르게 동작하는 비대칭이 남아 있다 — 완전히 맞추려면 wbs_plan 에
   *    "손으로 고쳤음" 플래그를 두고 자동 동기화에서 제외해야 한다.
   */
  @PostMapping("/stage_complete")
  @Transactional
  public AjaxResult stageComplete(@RequestBody Map<String, Object> params,
                                  Authentication auth) {
    User user = (auth == null) ? null : (User) auth.getPrincipal();
    AjaxResult result = new AjaxResult();

    String spjangcd = asStr(params.get("spjangcd"));
    String projno   = asStr(params.get("projno"));
    Integer seq     = asInt(params.get("seq"));

    if (projno == null || projno.isEmpty() || seq == null) {
      result.success = false;
      result.message = "저장 대상을 찾지 못했습니다. 먼저 저장한 뒤 다시 시도해 주세요.";
      return result;
    }

    // 단계명 없는 행은 저장 버튼 쪽 validateStages 와 같은 이유로 막는다
    String stagenm = asStr(params.get("stagenm"));
    if (stagenm == null || stagenm.isEmpty()) {
      result.success = false;
      result.message = "단계명이 없는 행은 완료 처리할 수 없습니다.";
      return result;
    }

    String pldate = emptyToNull(formatDate8(asStr(params.get("pldate"))));
    String cpdate = emptyToNull(formatDate8(asStr(params.get("cpdate"))));
    Integer wbsPlanId = asInt(params.get("wbs_plan_id"));
    Integer chargeId  = asInt(params.get("charge_id"));
    String  remark    = asStr(params.get("remark"));

    // 한 WBS 단계에 두 행이 물리면 applyStageCompletion 이 어느 값을 쓸지 불확정이 된다
    if (this.projectRegistrationServicr.isWbsPlanLinkedByOther(spjangcd, projno, seq, wbsPlanId)) {
      result.success = false;
      result.message = "이미 다른 진행단계 행에 연결된 WBS 단계입니다.";
      return result;
    }

    int n = this.projectRegistrationServicr.updateStageRow(
      spjangcd, projno, seq, stagenm, pldate, cpdate, wbsPlanId, chargeId, remark);

    if (n == 0) {
      // 화면에만 있고 DB 에 없는 행. 화면이 /save 를 먼저 태우므로 정상 경로에선 안 온다
      result.success = false;
      result.message = "저장된 진행단계를 찾지 못했습니다. 저장 버튼으로 먼저 저장해 주세요.";
      return result;
    }

    // tb_da003_stage → wbs_plan.ac_eddate 반영.
    //  완료를 해제했으면 cpdate 가 NULL 이라 ac_eddate 도 비워진다
    //  (delay_yn 판정이 ac_eddate IS NULL 을 보므로 지연 목록에 다시 들어온다)
    int wbsCnt = this.wbsPlanService.applyStageCompletion(spjangcd, projno, user);

    Map<String, Object> data = new HashMap<>();
    data.put("cpdate", cpdate);
    data.put("endflag", (cpdate == null) ? "0" : "1");
    data.put("wbs_updated", wbsCnt);

    result.success = true;
    result.message = (cpdate == null) ? "완료를 해제했습니다." : "완료로 저장했습니다.";
    result.data = data;
    return result;
  }

  private String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  // 진행단계 저장 로직
  private void saveStages(String spjangcd, String projno, List<Map<String, Object>> stages) {
    // 기존 단계 전체 삭제
    this.projectRegistrationServicr.deleteByProject(spjangcd, projno);

    if (stages.isEmpty()) return;   // 명시적 빈 배열 = 전부 삭제

    String today = LocalDate.now().toString().replace("-", ""); // yyyymmdd
    int seq = 1;

    for (Map<String, Object> s : stages) {
      String stagenm = asStr(s.get("stagenm"));
      String pldate  = formatDate8(asStr(s.get("pldate")));
      String cpdate  = formatDate8(asStr(s.get("cpdate")));

      tb_da003_stage stage = new tb_da003_stage();
      stage.setId(new tb_da003_stage_id(spjangcd, projno, seq));
      stage.setStagenm(stagenm);
      stage.setPldate(pldate);
      stage.setCpdate(cpdate);
      // WBS 세부단계 연결. 비어 있으면 진행단계에만 존재하는 행이다.
      stage.setWbsPlanId(asInt(s.get("wbs_plan_id")));
      stage.setChargeId(asInt(s.get("charge_id")));
      stage.setRemark(asStr(s.get("remark")));
      // 완료일 있으면 완료(1), 없으면 진행중(0)
      stage.setEndflag((cpdate != null && !cpdate.isEmpty()) ? "1" : "0");
      stage.setIndate(today);

      this.projectStageRepository.save(stage);
      seq++;
    }
  }

  private String formatDate8(String dateStr) {
    return dateStr != null ? dateStr.replace("-", "") : null;
  }

  // Object -> String 안전 변환
  private String asStr(Object o) {
    return o == null ? null : String.valueOf(o).trim();
  }

  // Object -> Integer 안전 변환
  private Integer asInt(Object o) {
    if (o == null || String.valueOf(o).trim().isEmpty()) return null;
    try {
      return Integer.parseInt(String.valueOf(o).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String generateNewProjectNo() {
    String year = String.valueOf(LocalDate.now().getYear());
    String maxProjNo = projectRepository.findMaxProjnoByYearPrefix(year + "-");

    int nextSeq = 1;
    if (maxProjNo != null && maxProjNo.length() >= 8) {
      String[] parts = maxProjNo.split("-");
      if (parts.length == 2) {
        try {
          nextSeq = Integer.parseInt(parts[1]) + 1;
        } catch (NumberFormatException ignored) {}
      }
    }
    return String.format("%s-%03d", year, nextSeq);
  }

  //삭제
  @PostMapping("/delete")
  @Transactional
  public AjaxResult deleteData(@RequestBody Map<String, Object> params) {
    AjaxResult result = new AjaxResult();

    String projno   = asStr(params.get("projno"));
    String spjangcd = asStr(params.get("spjangcd"));

    TB_DA003Id id = new TB_DA003Id(spjangcd, projno);

    if (projectRepository.existsById(id)) {
      // 자식(진행단계) 먼저 삭제 - FK 없으므로 수동
      this.projectRegistrationServicr.deleteByProject(spjangcd, projno);
      // 부모 삭제
      projectRepository.deleteById(id);
      result.success = true;
      result.message = "삭제 완료";
    } else {
      result.success = false;
      result.message = "해당 데이터 없음";
    }

    return result;
  }

}