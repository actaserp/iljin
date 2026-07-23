package mes.app.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.definition.service.BomService;
import mes.app.definition.service.material.UnitPriceService;
import mes.app.sales.service.SujuService;
import mes.app.sales.service.SujuUploadService;
import mes.config.Settings;
import mes.domain.entity.*;
import mes.domain.entity.iljin.suju_detail;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.repository.iljin.SuJuDetailRepository;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import java.util.*;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static mes.domain.services.CommonUtil.tryIntNull;

@Slf4j
@RestController
@RequestMapping("/api/sales/suju")
public class SujuController {

  @Autowired
  SujuRepository SujuRepository;

  @Autowired
  SujuService sujuService;

  @Autowired
  SujuUploadService sujuUploadService;

  @Autowired
  SujuHeadRepository sujuHeadRepository;

  @Autowired
  MaterialRepository materialRepository;

  @Autowired
  CompanyRepository companyRepository;

  @Autowired
  ProjectRepository projectRepository;

  @Autowired
  Settings settings;

  @Autowired
  SqlRunner sqlRunner;

  @Autowired
  DepartRepository departRepository;

  @Autowired
  UnitPriceService unitPriceService;

  @Autowired
  UnitRepository unitRepository;

  @Autowired
  private SujuRepository sujuRepository;

  @Autowired
  SuJuDetailRepository suJuDetailRepository;

  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private ShipmentRepository shipmentRepository;

  @Autowired
  BomService bomService;

  @Autowired
  UserCodeRepository userCodeRepository;

  private static final String DEFAULT_COMPANY_TYPE = "";

  // 수주 목록 조회
  @GetMapping("/read")
  public AjaxResult getSujuList(
      @RequestParam(value = "start") String start_date,
      @RequestParam(value = "end" ) String end_date,
      @RequestParam(value = "spjangcd") String spjangcd,
      @RequestParam(value = "company",required = false) String company,
      @RequestParam(value = "projno", required = false) String projno,
      HttpServletRequest request) {

    start_date = start_date + " 00:00:00";
    end_date = end_date + " 23:59:59";

    Timestamp start = Timestamp.valueOf(start_date);
    Timestamp end = Timestamp.valueOf(end_date);

    List<Map<String, Object>> items = this.sujuService.getSujuList(start, end,spjangcd, company, projno);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  // 수주 상세정보 조회
  @GetMapping("/detail")
  public AjaxResult getSujuDetail(
      @RequestParam("id") int id,
      HttpServletRequest request) {
    Map<String, Object> item = this.sujuService.getSujuDetail(id);

    AjaxResult result = new AjaxResult();
    result.data = item;

    return result;
  }

  //규격  테이블 리스트
  @GetMapping("/detail_list")
  public AjaxResult getDetailList(@RequestParam("sujuId") Integer sujuId) {
    AjaxResult result = new AjaxResult();
    if (sujuId == null || sujuId <= 0) {
      result.success = false;
      result.message = "잘못된 수주 ID입니다.";
      result.data = List.of();
      return result;
    }

    List<Map<String, Object>> details = sujuService.getDetailList(sujuId);
    result.success = true;
    result.data = details;
    return result;
  }

  // 제품 정보 조회
  @GetMapping("/product_info")
  public AjaxResult getSujuMatInfo(
      @RequestParam("product_id") int id,
      HttpServletRequest request) {
    Map<String, Object> item = this.sujuService.getSujuMatInfo(id);

    AjaxResult result = new AjaxResult();
    result.data = item;

    return result;
  }

  // 수주 등록
  @PostMapping("/manual_save")
  @Transactional
  public AjaxResult SujuSave(@RequestBody Map<String, Object> payload, Authentication auth) {
    User user = (User) auth.getPrincipal();

    AjaxResult result = new AjaxResult();
    //log.info("수주등록 들어온 데이터: payload:{}", payload);
    String jumunDateStr = (String) payload.get("JumunDate");
    String dueDateStr = (String) payload.get("DueDate");

    Date jumunDate = CommonUtil.trySqlDate(jumunDateStr);
    Date dueDate = CommonUtil.trySqlDate(dueDateStr);

    String companyName = (String) payload.get("CompanyName");
    if (companyName == null) companyName = (String) payload.get("OrderName");
    Integer companyId = Integer.parseInt(payload.get("order_id").toString());
    String sujuType = (String) payload.get("SujuType");
    String description = (String) payload.get("Description");
    String spjangcd = (String) payload.get("spjangcd");
    Integer order_id = Integer.parseInt(payload.get("order_id").toString()); //수주처
    String SuJuOrderName = (String) payload.get("OrderName");
    String DeliveryName = (String) payload.get("DeliveryName");  //납품처
    String amountStr = payload.get("totalAmountSum").toString().replace(",", "");

    double totalAmount = 0.0;
    try {
      if (amountStr != null && !amountStr.trim().isEmpty()) {
        totalAmount = Double.parseDouble(amountStr.trim().replace(",", ""));
      }
    } catch (NumberFormatException e) {
      // 무시하고 0 유지
    }
    List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

    SujuHead head;

    // ✅ suju_head 수정 여부 확인
    if (payload.containsKey("id") && payload.get("id") != null && !payload.get("id").toString().isEmpty()) {
      Integer headId = Integer.parseInt(payload.get("id").toString());
      head = sujuHeadRepository.findById(headId).orElse(new SujuHead());
    } else {
      head = new SujuHead();
      head.setJumunNumber(generateJumunNumber(jumunDate));
    }

    head.setJumunDate(jumunDate);
    head.setDeliveryDate(dueDate);
    head.setCompany_id(companyId);
    head.setSpjangcd(spjangcd);
    head.set_audit(user);
    head.setSujuType(sujuType);
    head.setTotalPrice(totalAmount);
    head.setDescription(description);
    head.setSuJuOrderId(order_id);
    head.setSuJuOrderName(SuJuOrderName);
    head.setDeliveryName(DeliveryName);
    head.setEstimateMemo((String) payload.get("EstimateMemo"));


    head.set_status("manual");
    head = sujuHeadRepository.save(head);
    // =========================================================
    // ✅ [삭제 동기화] 시작: payload에 없는 기존 상세행 삭제
    // =========================================================
    // 1) 이번 payload에 포함된 기존 suju_id 목록 수집
    List<Integer> incomingIds = new ArrayList<>();
    if (items != null) {
      for (Map<String, Object> item : items) {
        Integer sid = toIntegerOrNull(item.get("suju_id"));
        if (sid != null && sid > 0) incomingIds.add(sid);
      }
    }

    // 2) DB에 존재하는 기존 상세행 조회
    List<Suju> existingList = SujuRepository.findBySujuHeadId(head.getId());

    // 3) 기존행 중 payload에 없는 건 = 화면에서 삭제된 행 → DB에서도 삭제
    for (Suju ex : existingList) {
      Integer exId = ex.getId();
      if (exId == null) continue;

      if (!incomingIds.contains(exId)) {

        // 🔒 출하 연동이면 삭제 차단
        boolean hasShipment = shipmentRepository
                                .existsBySourceTableNameAndSourceDataPk("rela_data", exId);

        if (hasShipment) {
          result.success = false;
          result.message = "출하계획 또는 진행중인 수주는 삭제할 수 없습니다.";
          TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
          return result;
        }

        // ✅ FK 고려: 상세 먼저 삭제 후 본문 삭제
        suJuDetailRepository.deleteBySujuId(exId);
        SujuRepository.deleteById(exId);
      }
    }
    // ✅ [삭제 동기화] 끝

    for (Map<String, Object> item : items) {
      Suju suju;
      String standard = java.util.Objects.toString(
          item.containsKey("Standard") ? item.get("Standard") : item.get("standard"),
          ""
      );


      // ✅ 수정인지 확인
      if (item.containsKey("suju_id") && item.get("suju_id") != null && !item.get("suju_id").toString().isEmpty()) {
        Integer sujuId = Integer.parseInt(item.get("suju_id").toString());
        suju = SujuRepository.findById(sujuId).orElse(new Suju());

        // 클라값 미리 파싱 (저장 전에 비교용)
        Integer mid = toIntegerOrNull(item.get("Material_id"));
        Double qty = null;
        try {
          qty = Double.valueOf(String.valueOf(item.get("quantity")));
        } catch (Exception ignore) {
        }
        Integer unitPrice = null;
        try {
          unitPrice = Integer.valueOf(String.valueOf(item.get("unitPrice")));
        } catch (Exception ignore) {
        }
        Date newDueDate = dueDate; // 이미 위에서 만든 dueDate

        boolean isAdjustmentLine = (mid == null); // 단수정리 라인

        // 기존 DB 값과 핵심 변경 비교
        boolean coreChanged =
            !java.util.Objects.equals(suju.getMaterialId(), mid) ||
                !java.util.Objects.equals(suju.getSujuQty(), qty) ||
                !java.util.Objects.equals(suju.getSujuQty2(), qty) ||
                !java.util.Objects.equals(suju.getUnitPrice(), unitPrice) ||
                !java.util.Objects.equals(suju.getCompanyId(), companyId) ||
                !java.util.Objects.equals(suju.getDueDate(), newDueDate);

        // 정확한 출하 연동 여부 확인 (SourceTableName/SourceDataPk 기준)
        boolean hasShipment = shipmentRepository
            .existsBySourceTableNameAndSourceDataPk("rela_data", sujuId);
        // ← Repository에 아래 시그니처 추가 필요:
        // boolean existsBySourceTableNameAndSourceDataPk(String sourceTableName, Integer sourceDataPk);

        // 출하 연동 + 핵심값 변경 + 단수정리 라인이 아니면 차단
        if (hasShipment && coreChanged && !isAdjustmentLine) {
          //throw new RuntimeException("출하계획 또는 진행중인 수주입니다.");
          result.success = false;
          result.message = "출하계획 또는 진행중인 수주입니다.";

          TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

          return result;
        }

        // (필요하면) 변경 없음이면 스킵
        // ✅ 규격/상세 변경도 "변경"으로 인식해야 함
        boolean standardChanged = !java.util.Objects.equals(
          java.util.Objects.toString(suju.getStandard(), ""),
          java.util.Objects.toString(standard, "")
        );

        // 표준상세 payload가 오면(빈 리스트 포함 여부는 정책에 따라 선택)
        Object sdObj = item.get("standardDetails");
        boolean hasDetailsPayload = (sdObj instanceof List) && !((List<?>) sdObj).isEmpty();

        boolean nothingChanged = !coreChanged
         && !standardChanged
         && !hasDetailsPayload
         && java.util.Objects.equals(suju.getTotalAmount(), tryIntNull(item.get("totalAmount")))
         && java.util.Objects.equals(suju.getDescription(), (String) item.get("description"));

        if (nothingChanged) continue;

      } else {
        suju = new Suju(); // 신규일 경우
        suju.setJumunNumber(head.getJumunNumber());
      }

      // 공통 필드 설정
      suju.setSujuHeadId(head.getId());
      suju.setJumunDate(jumunDate);
      suju.setDueDate(dueDate);
      suju.setCompanyId(companyId);
      suju.setCompanyName(companyName);
      suju.setSpjangcd(spjangcd);
      suju.set_status("manual");
      suju.setState("received");
      suju.set_audit(user);

      String invatyn = item.get("VatIncluded").toString();

      //suju.setMaterialId(Integer.parseInt(item.get("Material_id").toString()));
      Integer mid = toIntegerOrNull(item.get("Material_id"));
      suju.setMaterialId(mid);
      suju.setMaterial_Name((String) item.get("txtProductName"));
      suju.setSujuQty(Double.parseDouble(item.get("quantity").toString()));
      suju.setSujuQty2(Double.parseDouble(item.get("quantity").toString()));
      suju.setUnitPrice(Integer.parseInt(item.get("unitPrice").toString()));
      suju.setPrice(Integer.parseInt(item.get("supplyAmount").toString()));
      suju.setVat(Integer.parseInt(item.get("VatAmount").toString()));
      suju.setTotalAmount(Integer.parseInt(item.get("totalAmount").toString()));
      suju.setProject_id((String) payload.get("projno"));
      suju.setInVatYN(invatyn);
      suju.setDescription((String) item.get("description"));
      suju.setStandard(standard);
      suju.setConfirm("0");

      // 단가 변경 시 처리
      Boolean unitPriceChanged = (Boolean) item.get("unitPriceChanged");
      if (unitPriceChanged != null && unitPriceChanged) {
        MultiValueMap<String, Object> priceData = new LinkedMultiValueMap<>();
        priceData.add("Material_id", suju.getMaterialId());
        priceData.add("Company_id", companyId);
        priceData.add("UnitPrice", suju.getUnitPrice());
        priceData.add("ApplyStartDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        priceData.add("type", "02");
        priceData.add("ChangerName", user.getUsername());
        priceData.add("user_id", user.getId());

        unitPriceService.saveCompanyUnitPrice(priceData);
      }

      SujuRepository.save(suju);
      Integer sujuId = suju.getId();

    // 우선: items 요소에 배열 형태로 온 경우
      Object sdObj = item.get("standardDetails");
      if (sdObj instanceof List) {
//        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) sdObj;
        saveSujuDetailsList(sujuId, details);
      } else {
        // 폴백: suffix 기반 JSON 문자열
        String detailJson = resolveDetailJsonForItem(item, payload);
        saveSujuDetailsFromJson(sujuId, detailJson);
      }
    }


    result.success = true;
    return result;
  }

  private static Integer toIntegerOrNull(Object v) {
    if (v == null) return null;
    if (v instanceof Number) return ((Number) v).intValue(); // 이미 숫자면 그대로

    String s = v.toString().trim().replace(",", ""); // 공백/콤마 정리
    if (s.isEmpty() || s.equals("-") || s.equals(".")) return null; // 빈값/중간입력 방어

    try {
      return Integer.valueOf(s);
    } catch (NumberFormatException e) {
      return null; // 필요하다면 throw로 바꿔 로깅
    }
  }

  private static String numStr(Object o) {
    if (o == null) return "";
    return o.toString().replace(",", "").trim();
  }

  private static String str(Object o) {
    return (o == null) ? "" : o.toString().trim();
  }

  private static Double dnum(Object o) {
    String v = numStr(o); // 콤마 제거 + trim
    if (v.isEmpty() || v.equals("-") || v.equals(".")) return 0d;
    try { return Double.valueOf(v); } catch (Exception e) { return 0d; }
  }

  private void saveSujuDetailsList(Integer sujuId, List<Map<String, Object>> details) {
    if (sujuId == null || sujuId <= 0 || details == null) return;

    // 수정 케이스 대비: 기존 상세 먼저 삭제
    suJuDetailRepository.deleteBySujuId(sujuId);

    for (Map<String, Object> d : details) {
      // 기본 필드
      String std = str(d.get("standard"));           // "7.25"
      Double qty = dnum(d.get("qty"));               // 2.0

      // 완전 공백 레코드는 스킵(규격/수량 둘 다 비면)
      if ((std == null || std.isEmpty()) && (qty == null || qty == 0d)) continue;

      // 금액/단위 필드 (프런트 키 매핑 포함)
      String unitName   = str(d.get("UnitName"));
      if (unitName.isEmpty()) unitName = str(d.get("unit"));      // 혹시 다른 키로 올 때

      Double unitPrice  = dnum(d.get("sd_UnitPrice"));
      if (unitPrice == 0d) unitPrice = dnum(d.get("UnitPrice"));  // 백업 키

      Double price      = dnum(d.get("sd_price"));
      if (price == 0d)  price = dnum(d.get("Price"));

      Double vat        = dnum(d.get("sd_vat"));
      if (vat == 0d)    vat   = dnum(d.get("Vat"));

      Double total      = dnum(d.get("TotalAmount"));

      // 누락값 보정(프런트 계산과 동일 규칙: 소수점 반올림)
      if (price == null || price == 0d) {
        price = Math.round((qty == null ? 0d : qty) * (unitPrice == null ? 0d : unitPrice)) * 1d;
      }
      if (vat == null || vat == 0d) {
        vat = Math.round(price * 0.1) * 1d; // 부가세 10%
      }
      if (total == null || total == 0d) {
        total = price + vat;
      }

      // 엔티티 저장
      suju_detail row = new suju_detail();
      row.setSujuId(sujuId);
      row.setStandard(std);
      row.setQty(qty == null ? 0d : qty);
      row.setUnitName(unitName);
      row.setUnitPrice(unitPrice);
      row.setPrice(price);
      row.setVat(vat);
      row.setTotalAmount(total);

      suJuDetailRepository.save(row);
    }
  }

  // ----------------------------------------------------------
// 유틸: payload에 존재하는 suffix 인덱스 추출
// ----------------------------------------------------------
  private static Set<Integer> findAllSuffixIndexes(Map<String, Object> payload, String keyPrefix) {
    Set<Integer> set = new LinkedHashSet<>();
    for (String k : payload.keySet()) {
      if (k.startsWith(keyPrefix + "_")) {
        String sfx = k.substring((keyPrefix + "_").length());
        try {
          set.add(Integer.parseInt(sfx));
        } catch (Exception ignore) {
        }
      }
    }
    return set;
  }

  // ----------------------------------------------------------
// 핵심: 현재 아이템에 대응하는 standard_detail_json을 찾아 반환
// ----------------------------------------------------------
  private String resolveDetailJsonForItem(Map<String, Object> item, Map<String, Object> payload) {
    // 1) 아이템 안에 직접 들어있으면 그걸 사용
    String direct = str(item.get("standard_detail_json"));
    if (!direct.isEmpty()) return direct;

    // 2) _rowIndex / rowIndex 로 찾기
    String[] idxKeys = new String[]{"_rowIndex", "rowIndex"};
    for (String key : idxKeys) {
      String idxStr = str(item.get(key));
      if (!idxStr.isEmpty()) {
        String k1 = "standard_detail_json_" + idxStr;
        String v1 = str(item.get(k1));
        if (!v1.isEmpty()) return v1;
        String v2 = str(payload.get(k1));
        if (!v2.isEmpty()) return v2;
      }
    }

    // 3) 값 매칭 기반으로 찾기
    String materialId = numStr(item.get("Material_id"));
    String productCode = str(item.get("product_code"));
    String productName = str(item.get("txtProductName"));
    String standard = str(item.get("standard"));
    if (standard.isEmpty()) standard = str(item.get("Standard"));

    Set<Integer> idxSet = new LinkedHashSet<>();
    idxSet.addAll(findAllSuffixIndexes(payload, "standard_detail_json"));
    if (idxSet.isEmpty()) {
      idxSet.addAll(findAllSuffixIndexes(payload, "Material_id"));
      idxSet.addAll(findAllSuffixIndexes(payload, "product_code"));
      idxSet.addAll(findAllSuffixIndexes(payload, "txtProductName"));
      idxSet.addAll(findAllSuffixIndexes(payload, "standard"));
    }

    for (Integer n : idxSet) {
      boolean match = false;

      if (!materialId.isEmpty() && materialId.equals(numStr(payload.get("Material_id_" + n)))) match = true;
      if (!match && !productCode.isEmpty() && productCode.equals(str(payload.get("product_code_" + n)))) match = true;
      if (!match && !productName.isEmpty() && productName.equals(str(payload.get("txtProductName_" + n)))) match = true;

      String pStd = str(payload.get("standard_" + n));
      if (pStd.isEmpty()) pStd = str(payload.get("Standard_" + n));
      if (!match && !standard.isEmpty() && standard.equals(pStd)) match = true;

      if (match) {
        String json = str(payload.get("standard_detail_json_" + n));
        if (!json.isEmpty()) return json;
      }
    }

    // 못 찾으면 null
    return null;
  }

  // JSON 문자열을 파싱해 suju_detail에 저장(수정 시 기존행 삭제)
  private void saveSujuDetailsFromJson(Integer sujuId, String detailJson) {
    if (sujuId == null || sujuId <= 0) return;
    if (detailJson == null || detailJson.trim().isEmpty()) return;

    try {
      List<Map<String, Object>> details =
          objectMapper.readValue(detailJson,
              new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

      saveSujuDetailsList(sujuId, details);

    } catch (Exception e) {
      // 필요 시 로깅
    }
  }

  public String generateJumunNumber(Date jumunDate) {
    String dateStr = new SimpleDateFormat("yyyyMMdd").format(jumunDate);

    String sql = """
        WITH upsert AS (
        	INSERT INTO seq_maker ("Code", "BaseDate", "CurrVal", "_modified")
        	SELECT 'JumunNumber', '20250626', 1, now()
        	WHERE NOT EXISTS (
        		SELECT 1 FROM seq_maker WHERE "Code" = 'JumunNumber' AND "BaseDate" = '20250626'
        	)
        	RETURNING "CurrVal"
        ),
        updated AS (
        	UPDATE seq_maker
        	SET "CurrVal" = "CurrVal" + 1, "_modified" = now()
        	WHERE "Code" = 'JumunNumber' AND "BaseDate" = '20250626'
        	RETURNING "CurrVal"
        )
        SELECT * FROM updated
        UNION ALL
        SELECT * FROM upsert;
        """;

    MapSqlParameterSource param = new MapSqlParameterSource();
    param.addValue("date", dateStr);

    Integer nextVal = this.sqlRunner.queryForObject(sql, param, (rs, rowNum) -> rs.getInt(1));
    return dateStr + "-" + String.format("%04d", nextVal);
  }

  // 수주 삭제
  @Transactional
  @PostMapping("/delete")
  public AjaxResult deleteSuju(
      @RequestParam("id") Integer id,
      @RequestParam("State") String State,
      @RequestParam("ShipmentStateName") String ShipmentStateName) {

    AjaxResult result = new AjaxResult();

    if (State.equals("received") == false) {
      //received 아닌것만
      result.success = false;
      result.message = "수주상태만 삭제할 수 있습니다";
      return result;
    }
    if (ShipmentStateName != null && !ShipmentStateName.isEmpty()) {
      result.success = false;
      result.message = "출하된 수주는 삭제할 수 없습니다";
      return result;
    }

    SujuRepository.deleteBySujuHeadId(id);
    sujuHeadRepository.deleteById(id);

    return result;
  }

  // 단가 정보 가져오기
  @GetMapping("/readPriceSuju")
  public AjaxResult getPriceHistory(@RequestParam("mat_pk") int matPk,
                                    @RequestParam("company_id") int company_id,
                                    @RequestParam("JumunDate") String ApplyStartDate) {

    List<Map<String, Object>> items = this.sujuService.getPriceByMatAndComp(matPk, company_id, ApplyStartDate);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  /**
   * 엑셀 컬럼 순서
   * 업체명 - 사업부 - 프로젝트 - 발주번호 - 자재번호 - 품명 - 규격 - 수량 - 단가 - 금액 - 단위 - 발주일 - 요청일
   * #company_name_col = 0		# 업체명
   * #depart_name_col = 1		# 사업부 이름 - 부서로 등록함
   * #project_name_col = 2		# 프로젝트 이름
   * #jumun_number_col = 3		# 발주 받은 번호(수주 번호)
   * #prod_code_col = 4			# 자재 번호(품목 코드)
   * #prod_name_col = 5    		# 품명
   * #prod_standard1_col = 6	# 규격
   * #qty_col = 7				# 수량
   * #prod_unit_price_col = 8	# 단가
   * #total_price_col = 9		# 금액
   * #unit_name_col = 10		# 단위
   * #jumnun_date_col = 11		# 발주일
   * #due_date_col = 12			# 요청일
   **/
  // 수주 엑셀 업로드
  @Transactional
  @PostMapping("/upload_save")
  public AjaxResult saveSujuBulkData(
      @RequestParam(value = "data_date") String data_date,
      @RequestParam(value = "spjangcd") String spjangcd,
      @RequestParam(value = "upload_file") MultipartFile upload_file,
      MultipartHttpServletRequest multipartRequest,
      Authentication auth) throws FileNotFoundException, IOException {

    User user = (User) auth.getPrincipal();

//	 	int company_name_col = 0;
    int depart_name_col = 2;
    int project_name_col = 4;
    int jumun_number_col = 5;
    int prod_code_col = 7;
    int prod_name_col = 8;
    int prod_standard1_col = 9;
    int qty_col = 10;
    int prod_unit_price_col = 12;
    int total_price_col = 13;
    int unit_name_col = 14;
    int jumnun_date_col = 15;
    int due_date_col = 16;

    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    LocalDateTime now = LocalDateTime.now();
    String formattedDate = dtf.format(now);
    String upload_filename = settings.getProperty("file_temp_upload_path") + formattedDate + "_" + upload_file.getOriginalFilename();


    if (new File(upload_filename).exists()) {
      new File(upload_filename).delete();
    }

    try (FileOutputStream destination = new FileOutputStream(upload_filename)) {
      destination.write(upload_file.getBytes());
    }

    List<List<String>> suju_file = this.sujuUploadService.excel_read(upload_filename);
    List<Company> CompanyList = companyRepository.findBySpjangcd(spjangcd);
    List<TB_DA003> projectList = projectRepository.findByIdSpjangcd(spjangcd);
    List<Material> materialList = materialRepository.findBySpjangcd(spjangcd);
    List<Depart> departList = departRepository.findBySpjangcd(spjangcd);
    List<Unit> unitList = unitRepository.findAll();
    Map<String, SujuHead> sujuHeadMap = new HashMap<>();

    List<Suju> sujuList = new ArrayList<>();

    Map<String, Company> companyMap = CompanyList.stream()
        .collect(Collectors.toMap(Company::getName, Function.identity()));

    Map<String, Depart> departMap = departList.stream()
        .collect(Collectors.toMap(Depart::getName, Function.identity()));

    Map<String, TB_DA003> projectMap = projectList.stream()
        .collect(Collectors.toMap(TB_DA003::getProjnm, Function.identity()));

    Map<String, Material> materialMap = materialList.stream()
        .filter(m -> m.getCustomerBarcode() != null && !m.getCustomerBarcode().trim().isEmpty())
        .collect(Collectors.toMap(
            Material::getCustomerBarcode,
            Function.identity(),
            (existing, duplicate) -> existing
        ));

    Map<String, Unit> unitMap = unitList.stream()
        .collect(Collectors.toMap(Unit::getName, Function.identity()));


    AjaxResult result = new AjaxResult();

    for (int i = 0; i < suju_file.size(); i++) {

      List<String> row = suju_file.get(i);

//			String company_name = row.get(company_name_col).trim();
      String company_name = "대양전기공업㈜";
      String depart_name = row.get(depart_name_col).trim();
      String rawProjectName = row.get(project_name_col).trim();
      String project_name = rawProjectName.split("\\s+")[0];
      String jumun_number = row.get(jumun_number_col).trim();
      String prod_code_raw = row.get(prod_code_col);
      String prod_code;

      try {
        // Excel에서 숫자로 인식된 경우 (Double 타입)
        double doubleValue = Double.parseDouble(prod_code_raw);
        prod_code = new BigDecimal(doubleValue).toPlainString();  // 소수점 없는 문자열로 변환
      } catch (NumberFormatException e) {
        // 애초에 문자열로 잘 들어온 경우
        prod_code = prod_code_raw.trim();
      }
      String prod_name = row.get(prod_name_col).trim();
      String prod_standard = row.get(prod_standard1_col).trim();
      Float floatQty = Float.parseFloat(row.get(qty_col).trim());
      Integer quantity = floatQty.intValue();
      Float unit_price = tryFloat(row.get(prod_unit_price_col));
      Float total_price = tryFloat(row.get(total_price_col));
      String raw = row.get(total_price_col);
      String unit_name = row.get(unit_name_col).trim();

      LocalDate jumun_date = parseFlexibleDate(row.get(jumnun_date_col).trim());
      LocalDate due_date = parseFlexibleDate(row.get(due_date_col).trim());

      Company company = companyMap.get(company_name);

      if (company == null) {
        result.message = "엑셀에 기입된 업체명 '" + company_name + "'이(가) 존재하지 않습니다.";
        result.success = false;
        return result;
      }

      // 부서 확인 또는 생성
      Depart depart = departMap.get(depart_name);

      if (depart == null) {
        depart = new Depart();
        depart.setName(depart_name);
        depart.setSpjangcd(spjangcd);
        depart.set_audit(user);
        depart = departRepository.save(depart);
        departMap.put(depart_name, depart);
      }

      // 단위 확인 또는 생성
      Unit unit = unitMap.get(unit_name);

      if (unit == null) {
        unit = new Unit();
        unit.setName(depart_name);
        unit.setSpjangcd(spjangcd);
        unit = unitRepository.save(unit);
        unitMap.put(unit_name, unit);
      }

      // Project 확인 또는 생성
      TB_DA003 project = projectMap.get(project_name);

      if (project == null) {
        project = new TB_DA003();
        String newProjNo = generateNewProjectNo();
        project.setId(new TB_DA003Id(spjangcd, newProjNo));
        project.setProjnm(project_name);
        project.setBalcltcd(company.getId());
        project.setBalcltnm(company_name);
        project = projectRepository.save(project);
        projectMap.put(project_name, project);
      }

      // Material 매칭
      Material material = materialMap.get(prod_code);
      LocalDateTime jumunDateTime = LocalDateTime.of(jumun_date, LocalTime.now());
      DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      String jumunDateTimeStr = jumunDateTime.format(formatter1);

      if (material == null) {
        material = new Material();
        material.setName(prod_name);
        material.setCode(prod_code);
        material.setFactory_id(1);
        material.setCustomerBarcode(prod_code);
        material.setStandard1(prod_standard);
        material.setMtyn("1");
        material.setUseyn("0");
        material.setMaterialGroupId(45);
        material.setSpjangcd(spjangcd);
        material.set_audit(user);
        material = materialRepository.save(material);
      } else {
        List<Map<String, Object>> items = this.sujuService.getPriceByMatAndComp(material.getId(), company.getId(), jumunDateTimeStr);

        Float oldUnitPrice = items.isEmpty() ? null : ((Number) items.get(0).get("UnitPrice")).floatValue();
        boolean unitPriceChanged = (oldUnitPrice == null || !Objects.equals(oldUnitPrice.intValue(), unit_price.intValue()));

        if (unitPriceChanged) {
          String hhmmss = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
          String applyStartDate = jumun_date + "T" + hhmmss;

          MultiValueMap<String, Object> priceData = new LinkedMultiValueMap<>();
          priceData.set("Material_id", material.getId());
          priceData.set("Company_id", company.getId());
          priceData.set("UnitPrice", unit_price.intValue()); // DB 정수형이면
          priceData.set("ApplyStartDate", applyStartDate);
          priceData.set("type", "02");
          priceData.set("user_id", user.getId());

          unitPriceService.saveCompanyUnitPrice(priceData);
        }

      }

      SujuHead sujuHead = sujuHeadMap.get(jumun_number);

      if (sujuHead == null) {
        sujuHead = sujuHeadRepository.findByJumunNumberAndSpjangcd(jumun_number, spjangcd)
            .orElseGet(() -> {
              SujuHead newHead = new SujuHead();
              newHead.setCompany_id(company.getId());   // 해당 행 기준
              newHead.setJumunDate(Date.valueOf(jumun_date));
              newHead.setDeliveryDate(Date.valueOf(due_date));
              newHead.setSpjangcd(spjangcd);
              newHead.setJumunNumber(jumun_number);
              newHead.set_audit(user);
              newHead.set_audit(user);
              newHead.setSujuType("sales");
              return sujuHeadRepository.save(newHead);
            });

        sujuHeadMap.put(jumun_number, sujuHead);  // 캐싱
      }

      Suju suju = new Suju();
      suju.setState("received");

      suju.setSujuQty(Double.valueOf(quantity));
      suju.setSujuQty2(Double.valueOf(quantity));
      suju.setCompanyId(company.getId());
      suju.setCompanyName(company_name);
      suju.setDueDate(Date.valueOf(due_date));
      suju.setJumunDate(Date.valueOf(jumun_date));
      suju.setJumunNumber(jumun_number);
      suju.setMaterialId(material.getId());
      suju.setAvailableStock((float) 0); // 없으면 0으로 보내기 추가
      suju.set_status("manual");
      suju.set_audit(user);
      suju.setUnitPrice(unit_price.intValue());
      suju.setPrice(total_price.intValue());
      suju.setVat((int) (total_price.intValue() * 0.1));
      suju.setTotalAmount(total_price.intValue() + (int) (total_price.intValue() * 0.1));
      suju.setInVatYN("N");
      suju.setProject_id(project.getId().getProjno());
      suju.setSpjangcd(spjangcd);
      suju.setConfirm("0");
      suju.setSujuHeadId(sujuHead.getId());
      sujuList.add(suju);

      try {
      } catch (Exception e) {
        log.error("Insert 실패 - row {}: {}", i, e.getMessage());
        continue;
      }

    }

    SujuRepository.saveAll(sujuList);


    result.success = true;
    return result;
  }

  private String generateNewProjectNo() {
    String year = String.valueOf(LocalDate.now().getYear());

    String maxProjNo = projectRepository.findMaxProjnoByYearPrefix(year + "-"); // ex: 2025-003

    int nextSeq = 1;
    if (maxProjNo != null && maxProjNo.length() >= 8) {
      String[] parts = maxProjNo.split("-");
      if (parts.length == 2) {
        try {
          nextSeq = Integer.parseInt(parts[1]) + 1;
        } catch (NumberFormatException ignored) {
        }
      }
    }

    return String.format("%s-%03d", year, nextSeq); // ex: 2025-004
  }

  public static LocalDate parseFlexibleDate(String value) {
    try {
      if (value.matches(".*[Ee].*")) {
        double d = Double.parseDouble(value);
        int intVal = (int) d;
        return LocalDate.parse(String.valueOf(intVal), DateTimeFormatter.ofPattern("yyyyMMdd"));
      } else if (value.matches("^\\d{8}$")) {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd"));
      } else {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("날짜 형식이 올바르지 않습니다: " + value);
    }
  }

  public static float tryFloat(String data) {
    if (!StringUtils.hasText(data)) {
      return 0;
    }

    try {
      // 숫자, 점, 음수만 남기고 나머지 제거
      data = data.replaceAll("[^0-9.\\-]", "");
      return Float.parseFloat(data);
    } catch (Exception e) {
      System.out.println("tryFloat: failed to parse [" + data + "]");
      return 0;
    }
  }


  // 수주 변환 changeSujuBulkData
  @PostMapping("/change")
  public AjaxResult changeSujuBulkData(
      @RequestParam MultiValueMap<String, Object> Q,
      HttpServletRequest request,
      Authentication auth) {

    AjaxResult result = new AjaxResult();

    User user = (User) auth.getPrincipal();

    List<Map<String, Object>> error_items = new ArrayList<>();
    String sql = "";

    List<Map<String, Object>> qItems = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

    if (qItems.size() == 0) {
      result.success = false;
      return result;
    }

    for (int i = 0; i < qItems.size(); i++) {
      Integer id = Integer.parseInt(qItems.get(i).get("id").toString());
      String state = CommonUtil.tryString(qItems.get(i).get("state"));

      MapSqlParameterSource paramMap = new MapSqlParameterSource();
      paramMap.addValue("id", id);
      paramMap.addValue("user_pk", user.getId());

      //sujuUploadService.BeforeCheck();

      if (state.equals("엑셀")) {
        sql = """
            with A as (
                           select "JumunNumber", m.id as mat_pk, b."Quantity", b."JumunDate"::date, b."DueDate"::date, c.id as comp_pk, b."CompanyName"
                           , m."UnitPrice", case when m."VatExemptionYN" = 'Y' then 0 else 0.1 end as vat_pro
                           from suju_bulk b 
                           inner join material m on m."Code" = b."ProductCode"
                           --left join company c on c."Name" = b."CompanyName"
                           left join company c on c."Code"  = b."CompCode"
                           where b.id = :id
                       ), B as (
                           select A.mat_pk, A.comp_pk, mcu."UnitPrice"
                           , row_number() over (partition by A.mat_pk, A.comp_pk order by mcu."ApplyStartDate" desc) as g_idx
                           from mat_comp_uprice mcu
                           inner join A on A.mat_pk = mcu."Material_id"
                           and A.comp_pk = mcu."Company_id"
                           and A."JumunDate" between mcu."ApplyStartDate" and mcu."ApplyEndDate"
                       )
                       insert into suju("JumunNumber", "Material_id", "SujuQty", "SujuQty2", "JumunDate", "DueDate", "Company_id", "CompanyName"
                       , "UnitPrice", "Price", "Vat", "State", _status, _created, _creater_id )
                       select A."JumunNumber", A.mat_pk, A."Quantity", A."Quantity", A."JumunDate", A."DueDate", A.comp_pk, A."CompanyName"
                       , coalesce(B."UnitPrice", A."UnitPrice") as unit_price
                       , coalesce(B."UnitPrice", A."UnitPrice") * a."Quantity" as price
                       , A.vat_pro * coalesce(B."UnitPrice", A."UnitPrice") * a."Quantity" as vat
                       , 'received', 'excel', now(), :user_pk
                       from A 
                       left join B on B.mat_pk = a.mat_pk
                       and B.comp_pk = A.comp_pk 
                       and B.g_idx = 1
            """;
        this.sqlRunner.execute(sql, paramMap);

        sql = """
            update suju_bulk set _status = 'Suju' where id = :id
            """;

        this.sqlRunner.execute(sql, paramMap);

      } else {
        Map<String, Object> err_item = new HashMap<>();
        err_item.put("success", false);
        //err_item.put("message", "Excel상태만 전환할 수 있습니다.");
        err_item.put("id", id);
        error_items.add(err_item);
      }

    }

    result.success = true;

    if (error_items.size() > 0) {
      result.success = false;
      result.message = "엑셀 상태만 전환할 수 있습니다.";
    }

//		Map<String, Object> item = new HashMap<String, Object>();
//		item.put("error_items", error_items);
//
//		result.data=item;
    return result;
  }

  @Transactional
  @PostMapping("/force-complete")
  public AjaxResult forceCompleteSuju(@RequestBody Map<String, Object> payload) {
    AjaxResult result = new AjaxResult();

    List<Integer> sujuPkList = (List<Integer>) payload.get("sujuPkList");
    sujuRepository.forceCompleteSujuList(sujuPkList);
    return result;
  }

  @PostMapping("/save_Comp")
  public AjaxResult SaveComp(
      @RequestParam(value = "id", required = false) Integer id,   // ★ 신규일 땐 null 허용
      @RequestParam("name") String name,
      @RequestParam("cboCompanyType") String companyType,
      @RequestParam("TelNumber") String telNumber,
      @RequestParam("business_number") String businessNumber,
      @RequestParam("business_type") String businessType,
      @RequestParam("business_item") String businessItem,
      @RequestParam("address") String address,
      @RequestParam("fax_number") String fax_number,
      @RequestParam("sales_manager") String sales_manager,
      @RequestParam("email") String email,
      @RequestParam("spjangcd") String spjangcd,
      Authentication auth
  ) {
    AjaxResult result = new AjaxResult();
    User user = (User) auth.getPrincipal();
    try {
      Company company;

      if (id == null) {
        company = new Company();
        // 코드가 비어있으면 신규 코드 부여
        String compCode = sujuService.getNextCompCode();
        company.setCode(compCode);
      } else {
        company = this.companyRepository.getCompanyById(id);
        if (company == null) {
          result.success = false;
          result.message = "대상 회사가 존재하지 않습니다.";
          return result;
        }
        // 수정 시 코드가 없으면 보정(Optional)
        if (company.getCode() == null || company.getCode().isEmpty()) {
          company.setCode(sujuService.getNextCompCode());
        }
      }

      // 기본정보 세팅
      company.setName(name);
      company.setCompanyType(companyType);
      company.setTelNumber(telNumber);
      company.setBusinessNumber(businessNumber);
      company.setBusinessType(businessType);
      company.setBusinessItem(businessItem);
      company.setRelyn("0");
      company.setAddress(address);
      company.setFaxNumber(fax_number);
      company.setSalesManager(sales_manager);
      company.setEmail(email);
      company.setSpjangcd(spjangcd);
      company.set_audit(user);

      // 저장
      Company saved = companyRepository.save(company);

      // 프론트에서 바로 바인딩할 최소 데이터 제공
      Map<String, Object> data = new HashMap<>();
      data.put("id", saved.getId());
      data.put("name", saved.getName());

      result.success = true;
      result.message = "저장되었습니다.";
      result.data = data;
      return result;

    } catch (Exception e) {
      result.success = false;
      result.message = "저장 실패: " + e.getMessage();
      return result;
    }
  }

  @PostMapping("/save_material")
  public AjaxResult SaveMaterial(@RequestParam(value = "id", required = false) Integer id,
                                 @RequestParam("MaterialGroup_id") Integer MaterialGroup_id,
                                 @RequestParam("cboMaterialMid") Integer cboMaterialMid,
                                 @RequestParam("Name") String Name,
                                 @RequestParam("Unit_id") Integer Unit_id,
                                 @RequestParam(value = "Standard", required = false) String Standard,
                                 @RequestParam("Factory_id") Integer Factory_id,
                                 @RequestParam(value = "Thickness",required = false) Float Thickness,
                                 @RequestParam(value = "Width",  required = false) Float Width,
                                 @RequestParam(value = "Color",  required = false) String Color,
                                 @RequestParam("WorkCenter_id") Integer WorkCenter_id,
                                 @RequestParam("spjangcd") String spjangcd,
                                 Authentication auth
  ) {
    AjaxResult result = new AjaxResult();
    User user = (User) auth.getPrincipal();
    try {

      Material material;

      if (id == null) {
        material = new Material();
        // 코드가 비어있으면 신규 코드 부여
        String matCode = sujuService.getNextMatCode();
        material.setCode(matCode);
      } else {
        material = this.materialRepository.getMaterialById(id);
        if (material == null) {
          result.success = false;
          result.message = "대상 품목이 존재하지 않습니다.";
          return result;
        }
        if (material.getCode() == null || material.getCode().isEmpty()) {
          material.setCode(sujuService.getNextMatCode());
        }
      }

      material.setFactory_id(Factory_id);
      material.setName(Name);
      material.setMaterialGroupId(MaterialGroup_id);
      material.setUnitId(Unit_id);
      material.setStandard1(Standard);
      material.setSpjangcd(spjangcd);
      material.setThickness(Thickness); //폭
      material.setWidth(Width);
      material.setColor(Color);
      material.setUseyn("0");
      material.setWorkCenterId(WorkCenter_id);
//      if (Standard != null && !Standard.trim().isEmpty()) {
//        material.setRoutingId(11);
//      } else {
//        material.setRoutingId(10);
//      }
//      if (Objects.equals(WorkCenter_id, 46)) {
//        material.setRoutingId(11);
//      }
      material.setStoreHouseId(3);  // 자재창고가 기본으로
      material.setMatUserCode(cboMaterialMid);
      material.setPurchaseOrderStandard("mrp");
      material.setValidDays(1);
      material.set_audit(user);

      // 저장
      Material saved = materialRepository.save(material);

      createOrReuseDefaultBom(saved, spjangcd, user);

      String unitName = unitRepository.findById(Unit_id)
          .map(Unit::getName)
          .orElse(null);

      // 프론트에서 바로 바인딩할 최소 데이터 제공
      Map<String, Object> data = new HashMap<>();
      data.put("id", saved.getId());
      data.put("Code", saved.getCode());
      data.put("name", saved.getName());
      data.put("standard", saved.getStandard1());
      data.put("unit_name", unitName);
      data.put("GroupId", saved.getMaterialGroupId());

      result.success = true;
      result.message = "저장되었습니다.";
      result.data = data;
      return result;

    } catch (Exception e) {
      result.success = false;
      result.message = "저장 실패: " + e.getMessage();
      return result;
    }
  }

  private void createOrReuseDefaultBom(Material saved, String spjangcd, User user) {
    final String bomType = "manufacturing";
    final String version = "1.0";

    // 기간
    String startDateStr = java.time.LocalDate.now().toString() + " 00:00:00";
    String endDateStr = "2100-12-31 00:00:00";
    java.sql.Timestamp startTs = java.sql.Timestamp.valueOf(startDateStr);
    java.sql.Timestamp endTs = java.sql.Timestamp.valueOf(endDateStr);

    Integer bomId = null;

    // 1) 같은 Version 존재 여부
    boolean sameVer = bomService.checkSameVersion(null, saved.getId(), bomType, version);
    if (sameVer) {
      // 이미 있으면 끝 (필요 시 가져와서 사용)
      return;
    }

    // 2) 기간 중복 여부
    boolean dupPeriod = bomService.checkDuplicatePeriod(null, saved.getId(), bomType, startDateStr, endDateStr);
    if (dupPeriod) {
      // 기간 겹치면 StartDate만 'now'로 좁혀서 재시도
      startTs = new java.sql.Timestamp(System.currentTimeMillis());
    }

    // 3) 생성
    Bom bom = new Bom();
    bom.setName(saved.getName()); // 표시용(선택)
    bom.setMaterialId(saved.getId());
    bom.setOutputAmount(1F);
    bom.setBomType(bomType);
    bom.setVersion(version);
    bom.setStartDate(startTs);
    bom.setEndDate(endTs);
    bom.setSpjangcd(spjangcd);
    bom.set_audit(user);

    Bom savedBom = bomService.saveBom(bom);
    bomId = savedBom.getId();
    // 중복 검사
    boolean exists = bomService.checkDuplicateBomComponent(bomId, saved.getId());
    if (exists) return;

    BomComponent bc = new BomComponent();
    bc.setBomId(bomId);
    bc.setMaterialId(10853);
    bc.setAmount(1); // 필수
    bc.set_order(1);
    bc.setDescription(null);
    bc.setSpjangcd(spjangcd);
    bc.set_audit(user);

    bomService.saveBomComponent(bc);
  }

  @PostMapping("/estimate_confirm")
  @Transactional
  public AjaxResult estimateConfirm(
      @RequestParam("JumunNumber") String jumunNumber,
      Authentication auth
  ) {
    AjaxResult result = new AjaxResult();

    // 입력 검증
    if (jumunNumber == null || jumunNumber.isBlank()) {
      result.success = false;
      result.message = "주문번호가 없습니다.";
      return result;
    }

    // 1) 헤더 조회
    SujuHead head = (SujuHead) sujuHeadRepository.findByJumunNumber(jumunNumber)
        .orElse(null);
    if (head == null) {
      result.success = false;
      result.message = "수주 헤더를 찾을 수 없습니다.";
      return result;
    }

    // 2) 이미 확정된 건(= sales)이면 멱등 처리
    if ("sales".equalsIgnoreCase(head.getSujuType())

    ) {
      result.success = true;
      result.message = "이미 견적확정된 건입니다.";
      return result;
    }

    // 3) 확정 처리
    head.setSujuType("sales");


    sujuHeadRepository.save(head);

    // (선택) 디테일 일괄 동기화가 필요하면 벌크 업데이트 사용 권장
    // sujuRepository.bulkConfirmByHeadId(head.getId());

    result.success = true;
    result.message = "견적이 확정되었습니다.";
    return result;
  }

  @GetMapping("/print_list")
  public AjaxResult getPrintList( @RequestParam("id") int id){
    //log.info("견적서 인쇄 들어옴, id:{}", id);
    Map<String, Object> item = this.sujuService.getPrintList(id);

    AjaxResult result = new AjaxResult();
    result.data = item;

    return result;
  }

  @GetMapping("/workcenter_list")
  public AjaxResult getWorkcenterList( @RequestParam("factoryId") int Factory_id){

    List<Map<String, Object>> items = this.sujuService.getWorkcenterList(Factory_id);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  // ===================================================================
  // 프로젝트/수주 통합 등록 화면 전용 저장
  //  - 금액 필드 없음 (유니트→SujuQty, SET→Standard, 라인/설비타입→신규 컬럼)
  //  - Material_id 없는 행은 품목 get-or-create 후 수주 저장
  // ===================================================================
  @PostMapping("/manual_save_project")
  @Transactional
  public AjaxResult SujuSaveProject(@RequestBody Map<String, Object> payload, Authentication auth) {
    User user = (User) auth.getPrincipal();
    AjaxResult result = new AjaxResult();

    Date jumunDate = CommonUtil.trySqlDate((String) payload.get("JumunDate"));
    Date dueDate   = CommonUtil.trySqlDate((String) payload.get("DueDate"));
    String spjangcd = (String) payload.get("spjangcd");
    String sujuType = (String) payload.get("SujuType");
    String description = (String) payload.get("Description");
    Integer orderId = toIntegerOrNull(payload.get("order_id"));
    String orderName = (String) payload.get("OrderName");
    String projno = (String) payload.get("projno");

    if (orderId == null) {
      result.success = false;
      result.message = "수주처가 지정되지 않았습니다.";
      return result;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
    if (items == null) items = new ArrayList<>();

    // ---------- 헤더 (신규/수정) ----------
    SujuHead head;
    if (payload.get("id") != null && !payload.get("id").toString().isEmpty()) {
      head = sujuHeadRepository.findById(Integer.parseInt(payload.get("id").toString()))
               .orElse(new SujuHead());
    } else {
      head = new SujuHead();
      head.setJumunNumber(generateJumunNumber(jumunDate));
    }
    head.setJumunDate(jumunDate);
    head.setDeliveryDate(dueDate);
    head.setCompany_id(orderId);
    head.setSpjangcd(spjangcd);
    head.setSujuType(sujuType);
    head.setDescription(description);
    head.setSuJuOrderId(orderId);
    head.setSuJuOrderName(orderName);
    head.set_status("manual");
    head.set_audit(user);
    head = sujuHeadRepository.save(head);

    // ---------- 삭제 동기화 (payload에 없는 기존 라인 제거) ----------
    List<Integer> incomingIds = new ArrayList<>();
    for (Map<String, Object> it : items) {
      Integer sid = toIntegerOrNull(it.get("suju_id"));
      if (sid != null && sid > 0) incomingIds.add(sid);
    }
    for (Suju ex : SujuRepository.findBySujuHeadId(head.getId())) {
      if (ex.getId() != null && !incomingIds.contains(ex.getId())) {
        suJuDetailRepository.deleteBySujuId(ex.getId());
        SujuRepository.deleteById(ex.getId());
      }
    }

    // ---------- 라인 저장 ----------
    for (Map<String, Object> item : items) {
      String productName = str(item.get("txtProductName"));
      if (productName.isEmpty()) continue;

      // ★ 품목 확정 (없으면 생성)
      Integer materialId = resolveOrCreateMaterialForProject(item, spjangcd, user);

      Suju suju;
      Integer sujuId = toIntegerOrNull(item.get("suju_id"));
      if (sujuId != null && sujuId > 0) {
        suju = SujuRepository.findById(sujuId).orElse(new Suju());
      } else {
        suju = new Suju();
        suju.setJumunNumber(head.getJumunNumber());
      }

      suju.setSujuHeadId(head.getId());
      suju.setJumunDate(jumunDate);
      suju.setDueDate(dueDate);
      suju.setCompanyId(orderId);
      suju.setCompanyName(orderName);
      suju.setSpjangcd(spjangcd);
      suju.set_status("manual");
      suju.setState("received");
      suju.setConfirm("0");
      suju.set_audit(user);

      suju.setPinShiftUnit(str(item.get("pinShiftUnit")));
      suju.setLegSpec(str(item.get("legSpec")));
      suju.setLegCnt(str(item.get("legCnt")));
      suju.setMakeType(str(item.get("makeType")));
      suju.setDesignCompId(toIntegerOrNull(item.get("designCompId")));
      suju.setDesignCompName(str(item.get("designCompName")));
      suju.setDrawDate(CommonUtil.trySqlDate(str(item.get("drawDate"))));
      suju.setMakeCompId(toIntegerOrNull(item.get("makeCompId")));
      suju.setMakeCompName(str(item.get("makeCompName")));
      suju.setItemRemark(str(item.get("itemRemark")));

      suju.setMaterialId(materialId);
      suju.setMaterial_Name(productName);
      suju.setProject_id(projno);

      // 유니트 → SujuQty (NOT NULL 2개), SET → Standard
      Double unitVal = dnum(item.get("unit"));   // 빈값/문자면 0
      suju.setSujuQty(unitVal);
      suju.setSujuQty2(unitVal);
      suju.setStandard(str(item.get("setCnt")));

      // 라인 / 설비타입 → 신규 컬럼 (Suju 엔티티에 필드 있어야 함)
      suju.setLine(str(item.get("line")));
      suju.setEquipType(str(item.get("equiptype")));

      SujuRepository.save(suju);
    }

    result.success = true;
    return result;
  }

  // ---------- 신규 품목 get-or-create (BOM 생성 제외) ----------
  private Integer resolveOrCreateMaterialForProject(Map<String, Object> item, String spjangcd, User user) {
    // 1) 이미 id 있으면 그대로
    Integer mid = toIntegerOrNull(item.get("Material_id"));
    if (mid != null) return mid;

    String name = str(item.get("txtProductName"));
    if (name.isEmpty()) {
      throw new IllegalArgumentException("품목명이 비어 있습니다.");
    }

    // 2) 이름 + 사업장으로 재조회 (중복 생성 방지)
    Integer existing = findMaterialIdByName(name, spjangcd);
    if (existing != null) return existing;

    // 3) 품목그룹(proc/hanger) → MaterialGroup_id 변환
    String groupCode = str(item.get("MaterialGroupName"));
    Integer groupId = findMaterialGroupIdByCode(groupCode);
    if (groupId == null) {
      throw new IllegalStateException("품목그룹을 찾을 수 없습니다: " + groupCode);
    }

    // 4) factory_id (라인에서 추출, 없으면 1)
    Integer factoryId = toIntegerOrNull(item.get("factoryId"));
    if (factoryId == null) factoryId = 1;

    Integer workcenterId = toIntegerOrNull(item.get("lineId"));
    Integer unitId = toIntegerOrNull(item.get("unitId"));
    Integer equipId = toIntegerOrNull(item.get("equiptype"));

    // 5) 신규 INSERT (BOM 생성 안 함)
    Material material = new Material();
    material.setCode(sujuService.getNextMatCode());
    material.setName(name);
    material.setMaterialGroupId(groupId);
    material.setFactory_id(factoryId);
    material.setUnitId(unitId);
    material.setSpjangcd(spjangcd);
    material.setUseyn("0");
    material.setWorkCenterId(workcenterId);
    material.setStoreHouseId(3);
    material.setPurchaseOrderStandard("mrp");
    material.setValidDays(1);
    material.setMatUserCode(equipId);
    material.set_audit(user);

    Material saved = materialRepository.save(material);
    return saved.getId();
  }

  // 품목명 + 사업장으로 기존 품목 id 조회 (없으면 null)
  private Integer findMaterialIdByName(String name, String spjangcd) {
    String sql = "SELECT id FROM material WHERE \"Name\" = :name AND spjangcd = :spjangcd ORDER BY id DESC LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource();
    p.addValue("name", name);
    p.addValue("spjangcd", spjangcd);
    try {
      return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      return null;
    }
  }

  // ===================================================================
//  수주 엑셀 업로드 - 즉시 저장
//   파일 + 헤더(수주처/프로젝트/수주일/납기일) 받아
//   파싱 → 라인(중분류) → 공정(품목) → 수주 라인까지 한 번에 저장
// ===================================================================
  @PostMapping("/excel_save")
  @Transactional
  public AjaxResult excelSave(
    @RequestParam("upload_file") MultipartFile file,
    @RequestParam(value = "spjangcd", required = false) String spjangcd,
    @RequestParam("order_id") Integer orderId,
    @RequestParam("OrderName") String orderName,
    @RequestParam("JumunDate") String jumunDateStr,
    @RequestParam("DueDate") String dueDateStr,
    @RequestParam("projno") String projno,
    Authentication auth) {

    AjaxResult result = new AjaxResult();

    // ── 0) 검증 ──
    if (file == null || file.isEmpty()) {
      result.success = false; result.message = "파일이 없습니다."; return result;
    }
    if (orderId == null) {
      result.success = false; result.message = "수주처가 지정되지 않았습니다."; return result;
    }
    if (projno == null || projno.trim().isEmpty()) {
      result.success = false; result.message = "프로젝트가 지정되지 않았습니다."; return result;
    }

    User user = (User) auth.getPrincipal();
    Date jumunDate = CommonUtil.trySqlDate(jumunDateStr);
    Date dueDate   = CommonUtil.trySqlDate(dueDateStr);

    // ── 1) 엑셀 파싱 ──
    List<Map<String, Object>> items;
    String formName;
    try {
      Map<String, Object> parsed = parseSujuExcel(file);
      formName = (String) parsed.get("formName");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> parsedItems = (List<Map<String, Object>>) parsed.get("items");
      items = parsedItems;
    } catch (Exception e) {
      result.success = false;
      result.message = e.getMessage();
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    if (items == null || items.isEmpty()) {
      result.success = false;
      result.message = "읽어들인 데이터가 없습니다. 양식을 확인하세요.";
      return result;
    }

    // ── 2) 수주 헤더 생성 ──
    SujuHead head = new SujuHead();
    head.setJumunNumber(generateJumunNumber(jumunDate));
    head.setJumunDate(jumunDate);
    head.setDeliveryDate(dueDate);
    head.setCompany_id(orderId);
    head.setSpjangcd(spjangcd);
    head.setSujuType("sales");
    head.setSuJuOrderId(orderId);
    head.setSuJuOrderName(orderName);
    head.set_status("manual");
    head.set_audit(user);
    head = sujuHeadRepository.save(head);

    // ── 3) 라인(중분류) → 품목 → 수주 라인 저장 ──
    Map<String, Integer> lineCache = new HashMap<>();
    Map<String, Integer> matCache  = new HashMap<>();
    int saved = 0;

    for (Map<String, Object> it : items) {
      String lineName = str(it.get("line"));
      String procName = str(it.get("procName"));
      if (procName.isEmpty()) continue;

      Integer lineId = resolveOrCreateLineUserCode(lineName, "proc", user, lineCache);
      Integer matId  = resolveOrCreateProcMaterial(procName, "proc", lineId, spjangcd, user, matCache);

      Suju suju = new Suju();
      suju.setSujuHeadId(head.getId());
      suju.setJumunNumber(head.getJumunNumber());
      suju.setJumunDate(jumunDate);
      suju.setDueDate(dueDate);
      suju.setCompanyId(orderId);
      suju.setCompanyName(orderName);
      suju.setSpjangcd(spjangcd);
      suju.set_status("manual");
      suju.setState("received");
      suju.setConfirm("0");
      suju.set_audit(user);

      suju.setMaterialId(matId);
      suju.setMaterial_Name(procName);
      suju.setProject_id(projno);

      // 유니트 → SujuQty (NOT NULL 2개), JIG SET → Standard
      Double unitVal = dnum(it.get("unitQty"));
      suju.setSujuQty(unitVal);
      suju.setSujuQty2(unitVal);
      suju.setStandard(str(it.get("jigSet")));

      // 라인 / 설비타입 → 신규 컬럼
      suju.setLine(lineName);
      suju.setEquipType(str(it.get("equipType")));

      SujuRepository.save(suju);
      saved++;
    }

    if (saved == 0) {
      result.success = false;
      result.message = "저장할 공정 데이터가 없습니다.";
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    // ── 4) 결과 ──
    Map<String, Object> data = new HashMap<>();
    data.put("formName", formName);
    data.put("savedCount", saved);
    data.put("headId", head.getId());
    result.success = true;
    result.message = formName + " " + saved + "건이 저장되었습니다.";
    result.data = data;
    return result;
  }

  /* ---------- 헬퍼 ---------- */
  /*private Map<String, Object> resolveOrCreateCompany(String name, String spjangcd,
                                                     User user, Map<String, Company> cache) {
    Map<String, Object> r = new HashMap<>();
    if (name == null || name.trim().isEmpty()) {
      r.put("id", null);
      r.put("code", "");
      r.put("name", "");
      return r;
    }
    String nm = name.trim();

    // 1) 이미 존재(캐시 = 사업장 내 기존 거래처)하면 그대로 사용
    Company company = cache.get(nm);

    // 2) 없으면 신규 거래처 등록 (save_Comp 신규등록과 동일하게 세팅)
    if (company == null) {
      company = new Company();
      company.setCode(sujuService.getNextCompCode());   // 신규 거래처 코드 채번
      company.setName(nm);
      company.setCompanyType(DEFAULT_COMPANY_TYPE);      // ★ 거래처 구분 (save_Comp의 cboCompanyType 대응)
      company.setTelNumber("");
      company.setBusinessNumber("");
      company.setBusinessType("");
      company.setBusinessItem("");
      company.setRelyn("0");
      company.setAddress("");
      company.setFaxNumber("");
      company.setSalesManager("");
      company.setEmail("");
      company.setSpjangcd(spjangcd);
      if (user != null) company.set_audit(user);

      company = companyRepository.save(company);
      cache.put(nm, company);
    }

    r.put("id", company.getId());
    r.put("code", company.getCode());
    r.put("name", company.getName());
    return r;
  }*/

// ===================================================================
//  엑셀 업로드 저장용 헬퍼 (라인 중분류 / 공정 품목 get-or-create)
// ===================================================================

  // 라인(중분류) 조회 → 없으면 user_code 신규 등록, id 반환
  private Integer resolveOrCreateLineUserCode(
    String lineName, String parentGroupCode,
    User user, Map<String, Integer> lineCache) {

    if (lineName == null || lineName.trim().isEmpty()) return null;
    String nm = lineName.trim();
    if (lineCache.containsKey(nm)) return lineCache.get(nm);

    Integer parentId = findParentUserCodeId(parentGroupCode);
    if (parentId == null) {
      throw new IllegalStateException("품목그룹의 분류 부모를 찾을 수 없습니다: " + parentGroupCode);
    }

    Integer existing = findUserCodeIdByValue(parentId, nm);
    if (existing != null) { lineCache.put(nm, existing); return existing; }

    UserCode uc = new UserCode();
    uc.setParentId(parentId);
    uc.setCode(generateLineCode(parentId));
    uc.setValue(nm);
    uc.setDescription(null);
    if (user != null) uc.set_audit(user);

    UserCode saved = userCodeRepository.save(uc);
    lineCache.put(nm, saved.getId());
    return saved.getId();
  }

  // 공정(품목) 조회 → 없으면 material 신규 등록, id 반환
  private Integer resolveOrCreateProcMaterial(
    String name, String groupCode, Integer userCodeId,
    String spjangcd, User user, Map<String, Integer> cache) {

    if (name == null || name.trim().isEmpty()) return null;
    String key = (userCodeId == null ? "" : userCodeId) + "|" + name.trim();
    if (cache.containsKey(key)) return cache.get(key);

    Integer existing = findMaterialIdByName(name.trim(), spjangcd, userCodeId);
    if (existing != null) { cache.put(key, existing); return existing; }

    Integer groupId = findMaterialGroupIdByCode(groupCode);
    if (groupId == null) throw new IllegalStateException("품목그룹을 찾을 수 없습니다: " + groupCode);

    Material m = new Material();
    m.setCode(sujuService.getNextMatCode());
    m.setName(name.trim());
    m.setMaterialGroupId(groupId);
    m.setFactory_id(1);
    m.setSpjangcd(spjangcd);
    m.setUseyn("0");
    m.setStoreHouseId(3);
    m.setPurchaseOrderStandard("mrp");
    m.setValidDays(1);
    m.setMatUserCode(userCodeId);
    m.set_audit(user);

    Material saved = materialRepository.save(m);
    cache.put(key, saved.getId());
    return saved.getId();
  }

  // 품목그룹 code → 그 품목그룹에 연결된 usercode_id (= 중분류들의 parentId)
  private Integer findParentUserCodeId(String groupCode) {
    if (groupCode == null || groupCode.isEmpty()) return null;
    String sql = "SELECT usercode_id FROM mat_grp WHERE \"Code\" = :code LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("code", groupCode);
    try {
      return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      return null;
    }
  }

  // 부모 하위에서 value(중분류명)로 user_code id 조회
  private Integer findUserCodeIdByValue(Integer parentId, String value) {
    String sql = "SELECT id FROM user_code WHERE parent_id = :pid AND \"Value\" = :val LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource()
                                .addValue("pid", parentId).addValue("val", value);
    try {
      return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      return null;
    }
  }

  // 중분류 코드 채번 (부모 하위 숫자코드 max+1)
  private String generateLineCode(Integer parentId) {
    String sql = "SELECT COALESCE(MAX(CAST(\"Code\" AS INTEGER)), 0) + 1 " +
                   "FROM user_code WHERE parent_id = :pid AND \"Code\" ~ '^[0-9]+$'";
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", parentId);
    try {
      Integer next = sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
      return String.format("%03d", next == null ? 1 : next);
    } catch (Exception e) {
      return String.valueOf(System.currentTimeMillis() % 100000);
    }
  }

  // 품목그룹 code → material_group id (proc/hanger)
  private Integer findMaterialGroupIdByCode(String code) {
    if (code == null || code.isEmpty()) return null;
    String sql = "SELECT id FROM mat_grp WHERE \"Code\" = :code LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("code", code);
    try {
      return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      return null;
    }
  }

  // 품목명 + 사업장 (+중분류)로 기존 품목 id 조회
  private Integer findMaterialIdByName(String name, String spjangcd, Integer userCodeId) {
    StringBuilder sql = new StringBuilder(
      "SELECT id FROM material WHERE \"Name\" = :name AND spjangcd = :spjangcd");
    MapSqlParameterSource p = new MapSqlParameterSource()
                                .addValue("name", name).addValue("spjangcd", spjangcd);
    if (userCodeId != null) {
      sql.append(" AND \"MatUserCode\" = :uc");
      p.addValue("uc", userCodeId);
    }
    sql.append(" ORDER BY id DESC LIMIT 1");
    try {
      return sqlRunner.queryForObject(sql.toString(), p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      return null;
    }
  }

  // ===================================================================
//  엑셀 → items 파싱 (excel_save / excel_parse 공통)
//  반환: Map { "formName": String, "items": List<Map> }
// ===================================================================
  private Map<String, Object> parseSujuExcel(MultipartFile file) throws IOException {
    try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
      Sheet sheet = wb.getSheetAt(0);
      List<CellRangeAddress> merged = sheet.getMergedRegions();

      Row headerRow = sheet.getRow(1);
      if (headerRow == null) {
        throw new IllegalArgumentException("헤더(2행)를 찾을 수 없습니다.");
      }

      Map<String, Integer> col = new HashMap<>();
      for (Cell c : headerRow) {
        String h = getString(c).replaceAll("\\s+", "");
        if (!h.isEmpty()) col.put(h, c.getColumnIndex());
      }

      boolean isMakeList = col.containsKey("도면출도일") || col.containsKey("PINSHIFTUNIT수량")
                             || col.containsKey("UNIT수량");
      boolean isQtyList  = col.containsKey("유니트갯수") || col.containsKey("다리발개수")
                             || col.containsKey("설비타입");
      String formName;
      if (isMakeList) formName = "제작출도리스트";
      else if (isQtyList) formName = "공정별 수량집계";
      else throw new IllegalArgumentException("인식할 수 없는 엑셀 양식입니다. (헤더 2행 확인)");

      List<Map<String, Object>> items = new ArrayList<>();
      int last = sheet.getLastRowNum();
      for (int r = 2; r <= last; r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;

        String lineRaw  = cellByHeader(sheet, merged, row, col, "LINE");
        String procName = cellByHeader(sheet, merged, row, col, "공정명");
        if (procName.isEmpty()) continue;
        if (lineRaw.contains("합계") || procName.contains("합계")) continue;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", lineRaw);
        m.put("procName", procName);
        m.put("jigSet", cellByHeader(sheet, merged, row, col, "JIGSET"));

        if (isMakeList) {
          m.put("equipType",    cellByHeader(sheet, merged, row, col, "장비"));
          m.put("unitQty",      cellByHeader(sheet, merged, row, col, "UNIT수량"));
          m.put("pinShiftUnit", cellByHeader(sheet, merged, row, col, "PINSHIFTUNIT수량"));
          m.put("drawDate", normalizeDrawDate(cellByHeader(sheet, merged, row, col, "도면출도일")));
          m.put("itemRemark", cellByHeader(sheet, merged, row, col, "비고"));
          m.put("legSpec", "");
          m.put("legCnt", "");
        } else {
          m.put("unitQty",   cellByHeader(sheet, merged, row, col, "유니트갯수"));
          m.put("equipType", cellByHeader(sheet, merged, row, col, "설비타입"));
          m.put("legSpec",   cellByHeader(sheet, merged, row, col, "다리발사양"));
          m.put("legCnt",    cellByHeader(sheet, merged, row, col, "다리발개수"));
          m.put("pinShiftUnit", "");
          m.put("drawDate", "");
          m.put("itemRemark", "");
        }
        items.add(m);
      }

      Map<String, Object> out = new HashMap<>();
      out.put("formName", formName);
      out.put("items", items);
      return out;
    }
  }

  /* ---------- 헤더명으로 셀 값 (병합 셀이면 좌상단 값 상속) ---------- */
  private String cellByHeader(Sheet sheet, List<CellRangeAddress> merged,
                              Row row, Map<String, Integer> col, String headerNoSpace) {
    Integer idx = col.get(headerNoSpace);
    if (idx == null) return "";
    return getMergedString(sheet, merged, row.getRowNum(), idx);
  }

  /* ---------- 병합 영역을 고려해 실제 값을 가진 셀을 찾아 반환 ---------- */
  private String getMergedString(Sheet sheet, List<CellRangeAddress> merged, int rowIdx, int colIdx) {
    Cell cell = null;
    Row row = sheet.getRow(rowIdx);
    if (row != null) cell = row.getCell(colIdx);

    String v = getString(cell);
    if (!v.isEmpty()) return v;

    // 비어 있으면 이 좌표를 포함하는 병합영역의 좌상단 셀 값을 가져옴
    for (CellRangeAddress ca : merged) {
      if (ca.isInRange(rowIdx, colIdx)) {
        Row fr = sheet.getRow(ca.getFirstRow());
        if (fr != null) {
          return getString(fr.getCell(ca.getFirstColumn()));
        }
      }
    }
    return "";
  }

  private String getString(Cell c) {
    if (c == null) return "";
    switch (c.getCellType()) {
      case STRING:  return c.getStringCellValue().trim();
      case NUMERIC:
        if (DateUtil.isCellDateFormatted(c)) {
          return new java.text.SimpleDateFormat("yyyy-MM-dd").format(c.getDateCellValue());
        }
        double d = c.getNumericCellValue();
        return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
      case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
      case FORMULA:
        try { return c.getStringCellValue().trim(); }
        catch (Exception e) { return String.valueOf(c.getNumericCellValue()); }
      default: return "";
    }
  }

  /* ---------- "06월 17일", "6/17" 등 → yyyy-MM-dd ---------- */
  private String normalizeDrawDate(String raw) {
    if (raw == null) return "";
    String s = raw.trim();
    if (s.isEmpty()) return "";
    if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;

    java.util.regex.Matcher m = java.util.regex.Pattern
                                  .compile("(\\d{1,2})\\D+(\\d{1,2})").matcher(s);
    if (m.find()) {
      int mon = Integer.parseInt(m.group(1));
      int day = Integer.parseInt(m.group(2));
      if (mon >= 1 && mon <= 12 && day >= 1 && day <= 31) {
        int year = java.time.LocalDate.now().getYear();
        return String.format("%04d-%02d-%02d", year, mon, day);
      }
    }
    return "";
  }

}
