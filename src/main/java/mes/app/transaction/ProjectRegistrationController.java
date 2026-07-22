package mes.app.transaction;

import lombok.extern.slf4j.Slf4j;
import mes.app.transaction.service.ProjectRegistrationServicr;
import mes.domain.entity.TB_DA003;
import mes.domain.entity.TB_DA003Id;
import mes.domain.entity.iljin.tb_da003_stage;
import mes.domain.entity.iljin.tb_da003_stage_id;
import mes.domain.model.AjaxResult;
import mes.domain.repository.ProjectRepository;
import mes.domain.repository.iljin.ProjectStageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
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
  public AjaxResult ProjectListSave(@RequestBody Map<String, Object> params) {

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
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stages =
      (List<Map<String, Object>>) params.getOrDefault("stages", new ArrayList<>());

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
    saveStages(spjangcd, targetProjNo, stages);

    return result;
  }

  // 진행단계 저장 로직
  private void saveStages(String spjangcd, String projno, List<Map<String, Object>> stages) {
    // 기존 단계 전체 삭제
    this.projectRegistrationServicr.deleteByProject(spjangcd, projno);

    if (stages == null || stages.isEmpty()) return;

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